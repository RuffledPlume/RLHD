package rs117.hd.utils.collections;

import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;
import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import javax.annotation.Nonnull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.Props;

import static java.lang.Integer.numberOfLeadingZeros;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public enum PooledArrayType {
	BOOL(boolean[]::new, 1),
	BYTE(byte[]::new, 1),
	CHAR(char[]::new, 2),
	SHORT(short[]::new, 2),
	INT(int[]::new, 4),
	FLOAT(float[]::new, 4),
	OBJECT(Object[]::new, 4);

	public static final PooledArrayType[] VALUES = values();

	private static final double MAX_HEAP_FRACTION = 0.05; // 768 MB * 0.05 = 38.4 MB
	private static final long MAX_POOL_BYTES = max((long) (Runtime.getRuntime().maxMemory() * MAX_HEAP_FRACTION), 10 * MB);

	private static final int MAX_BUCKET = 30;
	private static final int STRIPES = 8;
	private static final int STRIPES_MASK = STRIPES - 1;
	private static final int CLEANUP_INTERVAL = 64;
	private static final long SHRINK_DELAY_MS = 60_000;
	private static final double ALPHA = 0.1;

	private static final AtomicLong CURRENT_POOL_BYTES = new AtomicLong();
	private static final AtomicLong TOTAL_POOL_BYTES = new AtomicLong();

	private static final Cleaner CLEANER = Cleaner.create();

	public final ArraySupplier<?> supplier;
	public final int stride;

	private final Bucket[][] buckets = new Bucket[MAX_BUCKET + 1][STRIPES];

	PooledArrayType(ArraySupplier<?> supplier, int stride) {
		this.supplier = supplier;
		this.stride = stride;

		for (int i = 0; i < buckets.length; i++) {
			int size = 1 << i;
			for (int s = 0; s < STRIPES; s++)
				buckets[i][s] = new Bucket(size);
		}
	}

	private static int bucket(int size) {
		if (size <= 1) return 0;
		int b = 32 - numberOfLeadingZeros(size - 1);
		return min(b, MAX_BUCKET);
	}

	private static int stripeIndex() {
		final int hash = Thread.currentThread().hashCode();
		return (hash ^ (hash >>> 16)) & STRIPES_MASK;
	}

	private static boolean isPoolFull(long additionalBytes) {
		return CURRENT_POOL_BYTES.get() + additionalBytes > MAX_POOL_BYTES;
	}

	public static void forceCleanup(boolean full) {
		for (int v = 0; v < VALUES.length; v++) {
			final PooledArrayType type = VALUES[v];
			for (int b = 0; b < type.buckets.length; b++) {
				for (int s = 0; s < STRIPES; s++) {
					final Bucket bucket = type.buckets[b][s];
					final long stamp = bucket.lock.writeLock();
					try {
						bucket.opCounter = 0;
						if (full) {
							bucket.inUse = 0;
							bucket.isEmpty = true;
							for (PooledArray<Object> wrapper : bucket.stack) {
								wrapper.pooled.set(false);
								// We know for certain this array is being discarded - account for
								// it immediately instead of waiting on GC to run the cleaner.
								wrapper.cleanable.clean();
							}
							bucket.stack.clear();
						} else {
							type.maybeCleanup(b, s, bucket, true);
						}
					} finally {
						bucket.lock.unlockWrite(stamp);
					}
				}
			}
		}
		if (full)
			CURRENT_POOL_BYTES.set(0);
	}

	public static void shutdown() {
		CURRENT_POOL_BYTES.set(0);

		for (int v = 0; v < VALUES.length; v++) {
			final PooledArrayType type = VALUES[v];
			for (int b = 0; b < VALUES[v].buckets.length; b++) {
				for (int s = 0; s < STRIPES; s++) {
					final Bucket bucket = type.buckets[b][s];

					for (PooledArray<Object> wrapper : bucket.stack) {
						wrapper.pooled.set(false);
						wrapper.cleanable.clean();
					}
					bucket.stack.clear();
					bucket.isEmpty = true;

					bucket.inUse = 0;
					bucket.peakInUse = 0;
					bucket.avgDemand = 0;
					bucket.lastOverTargetTime = 0;

					// Recreate the bucket to clear the stack inner arrays
					type.buckets[b][s] = new Bucket(bucket.size);
				}
			}
		}
	}

	public static long getCurrentTotalCacheSize() { return CURRENT_POOL_BYTES.get(); }

	public static long getTotalPoolBytes() { return TOTAL_POOL_BYTES.get(); }

	private void maybeCleanup(int b, int s, Bucket bucket) {
		maybeCleanup(b, s, bucket, false);
	}

	private void maybeCleanup(int b, int s, Bucket bucket, boolean forced) {
		if (!forced && (++bucket.opCounter & (CLEANUP_INTERVAL - 1)) != 0)
			return;

		bucket.avgDemand = (float) (ALPHA * bucket.peakInUse + (1 - ALPHA) * bucket.avgDemand);
		bucket.peakInUse = bucket.inUse;

		if (bucket.stack.size() <= bucket.avgDemand) {
			bucket.lastOverTargetTime = 0;
			return;
		}

		final long now = System.currentTimeMillis();
		if (bucket.lastOverTargetTime == 0) {
			bucket.lastOverTargetTime = now;
			return;
		}

		if (!forced && now - bucket.lastOverTargetTime <= SHRINK_DELAY_MS)
			return;

		final int target = max((int) (bucket.avgDemand * 0.5f), 1);
		int excess = bucket.stack.size() - target;

		while (excess-- > 0) {
			PooledArray<Object> wrapper = bucket.poll(bytesFor(bucket.size));
			if (wrapper == null)
				break;

			spill(b, s, wrapper);
		}

		bucket.lastOverTargetTime = now;
	}

	private boolean spill(int b, int fromStripe, PooledArray<Object> wrapper) {
		final Bucket[] stripes = buckets[b];

		final long bytes = bytesFor(Array.getLength(wrapper.array));

		for (int i = 1; i < STRIPES; i++) {
			final int s = (fromStripe + i) & STRIPES_MASK;
			final Bucket other = stripes[s];

			if (other.stack.size() > other.avgDemand)
				continue;

			final long stamp = other.lock.tryWriteLock();
			if (stamp == 0)
				continue;

			try {
				if (other.stack.size() <= other.avgDemand) {
					other.add(wrapper, bytes);
					return true;
				}
			} finally {
				other.lock.unlockWrite(stamp);
			}
		}
		return false;
	}

	private long bytesFor(int len) {
		return (long) len * stride;
	}

	@SuppressWarnings("unchecked")
	public <T> PooledArray<T> create(int requestedSize) {
		final int cap = ceilPow2(requestedSize);
		final long bytes = bytesFor(cap);
		final Object array = supplier.get(cap);

		final PooledArray<Object> wrapper = new PooledArray<>(this, array, bytes);
		TOTAL_POOL_BYTES.addAndGet(bytes);

		return (PooledArray<T>) wrapper;
	}

	public <T> PooledArrayRef<T> ref(String context) {
		return new PooledArrayRef<>(this, context);
	}

	@SuppressWarnings("SuspiciousSystemArraycopy")
	public <T> PooledArray<T> cache(String context, @Nonnull Object array, int offset, int size) {
		final PooledArray<T> cached = borrow(context, size);
		System.arraycopy(array, offset, cached.array, 0, size);
		return cached;
	}

	public <T> PooledArray<T> borrow(String context, int requestedSize) {
		return borrow(context, requestedSize, true);
	}

	public <T> PooledArray<T> ensureCapacity(PooledArray<T> current, int requestedSize) {
		if (current != null && current.array != null && Array.getLength(current.array) >= requestedSize)
			return current;

		final PooledArray<T> grown = borrow(current != null ? current.borrowInfo.context : null, requestedSize);
		release(current);
		return grown;
	}

	@SuppressWarnings("SuspiciousSystemArraycopy")
	public <T> PooledArray<T> ensureCapacity(PooledArray<T> current, int requestedSize, int offset, int count) {
		if (current != null && current.array != null && Array.getLength(current.array) >= requestedSize)
			return current;

		final PooledArray<T> grown = borrow(current != null ? current.borrowInfo.context : null, requestedSize);
		if (current != null && current.array != null && count > 0)
			System.arraycopy(current.array, offset, grown.array, 0, count);
		release(current);
		return grown;
	}

	@SuppressWarnings("unchecked")
	public <T> PooledArray<T> borrow(String context, int requestedSize, boolean createIfMissing) {
		final int roundedSize = ceilPow2(requestedSize);
		final int b = bucket(roundedSize);

		final long bytes = bytesFor(roundedSize);
		final Bucket[] bucketStripes = buckets[b];
		final int startStripe = stripeIndex();

		for (int i = 0; i < STRIPES * 2; i++) {
			final int s = (startStripe + i) & STRIPES_MASK;
			final Bucket bucket = bucketStripes[s];
			if (bucket.isEmpty)
				continue;

			final long stamp = i < STRIPES ? bucket.lock.tryWriteLock() : bucket.lock.writeLock();
			if (stamp == 0)
				continue;

			try {
				final PooledArray<Object> wrapper = bucket.poll(bytes);
				if (wrapper != null) {
					wrapper.borrowInfo.context = context;
					bucket.inUse++;
					bucket.peakInUse = Math.max(bucket.peakInUse, bucket.inUse);
					maybeCleanup(b, s, bucket);
					return (PooledArray<T>) wrapper;
				}
			} finally {
				bucket.lock.unlockWrite(stamp);
			}
		}

		if (!createIfMissing)
			return null;

		return create(roundedSize);
	}

	@SuppressWarnings("unchecked")
	public void release(PooledArray<?> wrapper) {
		if (wrapper == null || wrapper.array == null)
			return;

		if (wrapper.pooled.get()) {
			if (Props.DEVELOPMENT)
				log.warn("Attempted to release a PooledArray that's already back in the pool: {}", wrapper.array);
			return;
		}

		final int len = Array.getLength(wrapper.array);
		if (len != ceilPow2(len))
			return;

		final int b = bucket(len);

		final long bytes = bytesFor(len);
		if (isPoolFull(bytes))
			return;

		final PooledArray<Object> objWrapper = (PooledArray<Object>) wrapper;

		final int startStripe = stripeIndex();

		final Bucket[] bucketStripes = buckets[b];

		for (int i = 0; i < STRIPES * 2; i++) {
			final int s = (startStripe + i) & STRIPES_MASK;
			final Bucket bucket = bucketStripes[s];

			final long stamp = i < STRIPES
				? bucket.lock.tryWriteLock()
				: bucket.lock.writeLock();
			if (stamp == 0)
				continue;

			try {
				if (isPoolFull(bytes))
					return;

				bucket.inUse = max(0, bucket.inUse - 1);
				bucket.add(objWrapper, bytes);
				maybeCleanup(b, s, bucket);
				return;
			} finally {
				bucket.lock.unlockWrite(stamp);
			}
		}
	}

	@FunctionalInterface
	public interface ArraySupplier<T> {
		T get(int capacity);
	}

	@RequiredArgsConstructor
	private static final class Bucket {
		private final ArrayDeque<PooledArray<Object>> stack = new ArrayDeque<>();
		private final StampedLock lock = new StampedLock();

		private final int size;
		private int opCounter;
		private int inUse;
		private int peakInUse;
		private float avgDemand;
		private long lastOverTargetTime;

		private volatile boolean isEmpty = true;

		public void add(PooledArray<Object> wrapper, long bytes) {
			if (!wrapper.pooled.compareAndSet(false, true)) {
				log.warn("Duplicate release of pooled array: " + wrapper.array);
				return;
			}

			wrapper.borrowInfo.context = null;
			stack.add(wrapper);
			CURRENT_POOL_BYTES.addAndGet(bytes);
			isEmpty = false;
		}

		public PooledArray<Object> poll(long bytes) {
			PooledArray<Object> wrapper = stack.poll();
			if (wrapper != null) {
				wrapper.pooled.set(false);
				CURRENT_POOL_BYTES.addAndGet(-bytes);
			}
			isEmpty = stack.isEmpty();
			return wrapper;
		}
	}

	public static class PooledArrayRef<T> implements AutoCloseable {
		private final PooledArrayType arrayType;
		private final String context;

		@Getter
		private PooledArray<T> pooledArray;

		private PooledArrayRef(PooledArrayType arrayType, String context) {
			this.arrayType = arrayType;
	 		this.context = context;
		}

		public int length() { return pooledArray != null ? pooledArray.length : 0; }

		public T getArray() { return pooledArray != null ? pooledArray.getArray() : null; }

		public T ensureCapacity(int requestedSize) {
			if(pooledArray == null) {
				pooledArray = arrayType.borrow(context, requestedSize);
				return pooledArray.getArray();
			}

			pooledArray = arrayType.ensureCapacity(pooledArray, requestedSize);
			return pooledArray.getArray();
		}

		public T ensureCapacity(int requestedSize, int offset, int count) {
			if(pooledArray == null) {
				pooledArray = arrayType.borrow(context, requestedSize);
				return pooledArray.getArray();
			}

			pooledArray = pooledArray.ensureCapacity(requestedSize, offset, count);
			return pooledArray.getArray();
		}

		@Override
		public void close() {
			if(pooledArray != null)
				pooledArray.close();
			pooledArray = null;
		}
	}

	public static class PooledArray<T> implements AutoCloseable {
		private final PooledArrayType arrayType;
		private final T array;
		@Getter private final int length;
		private final Cleanable cleanable;
		private final AtomicBoolean pooled = new AtomicBoolean(false);

		private final BorrowInfo borrowInfo = new BorrowInfo();

		private PooledArray(PooledArrayType arrayType, T array, long bytes) {
			this.arrayType = arrayType;
			this.array = array;
			this.length = Array.getLength(array);

			final BorrowInfo borrowInfo = this.borrowInfo;
			final AtomicBoolean pooledFlag = pooled;
			final String typeName = arrayType.name();

			this.cleanable = CLEANER.register(this, () -> {
				TOTAL_POOL_BYTES.addAndGet(-bytes);

				final String context = borrowInfo.context;
				if (!pooledFlag.get() && context != null) {
					log.warn(
						"A {} PooledArray ({} bytes) was garbage collected while still borrowed by {}. " +
						"This usually means close()/release() was never called - check for a leak.",
						typeName, bytes, context
					);
				}
			});
		}

		public T getArray() {
			if (pooled.get()) {
				log.warn("Attempted to use a PooledArray after it was released back to the pool");
				return null;
			}

			return array;
		}

		public PooledArray<T> ensureCapacity(int requestedSize) {
			return arrayType.ensureCapacity(this, requestedSize);
		}

		public PooledArray<T> ensureCapacity(int requestedSize, int offset, int count) {
			return arrayType.ensureCapacity(this, requestedSize, offset, count);
		}

		@Override
		public void close() {
			arrayType.release(this);
		}
	}

	private static class BorrowInfo {
		public String context;
	}
}