package rs117.hd.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.Getter;

public class SliceAllocator<SLICE extends SliceAllocator.Slice> {
	private static final Comparator<Slice> OFFSET_COMPARATOR = Comparator.comparingInt(Slice::getOffset);

	private final List<SLICE> active = new ArrayList<>();
	private final Supplier<SLICE> supplier;
	private final boolean validate;

	private int totalSize;

	public SliceAllocator(
		Supplier<SLICE> supplier,
		int initialCapacity,
		boolean validate
	) {
		this.supplier = Objects.requireNonNull(supplier);
		this.totalSize = Math.max(0, initialCapacity);
		this.validate = validate;
	}

	public SLICE allocate(int size) {
		if (size <= 0)
			throw new IllegalArgumentException("size must be > 0");

		int offset = findGap(size);
		if (offset < 0) {
			defrag();
			offset = findGap(size);
		}

		if (offset < 0) {
			if ((long) totalSize + size > Integer.MAX_VALUE)
				throw new IllegalStateException("Allocator overflow");

			offset = totalSize;
			totalSize += size;
		}

		SLICE slice = supplier.createSlice(offset, size);
		slice.owner = this;
		slice.allocate();

		insertSorted(slice);

		validate();
		return slice;
	}

	private void insertSorted(SLICE slice) {
		int index = Collections.binarySearch(active, slice, OFFSET_COMPARATOR);
		if (index < 0)
			index = -index - 1;

		active.add(index, slice);
	}

	private int findGap(int size) {
		int cursor = 0;

		for (Slice slice : active) {
			if (slice.offset - cursor >= size)
				return cursor;

			cursor = slice.offset + slice.size;
		}

		if (totalSize - cursor >= size)
			return cursor;

		return -1;
	}

	private void free(Slice slice) {
		//noinspection SuspiciousMethodCalls
		active.remove(slice);

		// shrink tail capacity if possible
		int max = 0;
		for (Slice s : active)
			max = Math.max(max, s.offset + s.size);

		totalSize = max;

		validate();
	}

	public void defrag() {
		int cursor = 0;

		for (Slice slice : active) {
			if (!slice.canMove) {
				cursor = slice.offset + slice.size;
				continue;
			}

			if (slice.offset > cursor) {
				int old = slice.offset;

				slice.onMove(old, cursor);
				slice.offset = cursor;
			}

			cursor = slice.offset + slice.size;
		}

		validate();
	}

	private void validate() {
		if (!validate)
			return;

		for (int i = 1; i < active.size(); i++) {
			Slice a = active.get(i - 1);
			Slice b = active.get(i);

			if (a.offset + a.size > b.offset) {
				throw new IllegalStateException(
					"Overlap: " + a + " vs " + b
				);
			}
		}
	}

	@FunctionalInterface
	public interface Supplier<T extends Slice> {
		T createSlice(int offset, int size);
	}

	public abstract static class Slice {
		@Getter
		protected final int size;
		protected SliceAllocator<?> owner;
		@Getter
		protected int offset;
		protected boolean canMove;
		private boolean freed = false;

		protected Slice(int offset, int size) {
			this.offset = offset;
			this.size = size;
		}

		protected abstract void allocate();
		protected abstract void onFreed();

		protected void onMove(int oldOffset, int newOffset) {}

		public void free() {
			if (freed)
				return;

			freed = true;

			if (owner == null)
				throw new IllegalStateException("Slice has no owner");

			onFreed();
			owner.free(this);
		}

		@Override
		public String toString() {
			return "[" + offset + ", " + (offset + size) + "]";
		}
	}
}