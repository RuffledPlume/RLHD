package rs117.hd.utils;

import java.util.ArrayDeque;
import java.util.function.Supplier;

public final class ObjectPool<T> {
	private final Supplier<T> supplier;
	private final ArrayDeque<T> pool = new ArrayDeque<>();

	public ObjectPool(Supplier<T> supplier, int initialCapacity) {
		this.supplier = supplier;
		for (int i = 0; i < initialCapacity; i++) {
			pool.push(supplier.get());
		}
	}

	public T obtain() {
		return pool.isEmpty() ? supplier.get() : pool.pop();
	}

	public void release(T obj) {
		pool.push(obj);
	}
}
