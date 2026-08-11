package rs117.hd.utils.collections;

import rs117.hd.utils.collections.PooledArrayType.PooledArray;

public final class PooledObjectArray<T> {
	public PooledArray<Object[]> pooledArray;

	@SuppressWarnings("unchecked")
	public T get(int idx) {
		return (T) pooledArray.getArray()[idx];
	}

	public void set(int idx, T value) {
		pooledArray.getArray()[idx] = value;
	}

	public void ensureCapacity(int size) {
		pooledArray = PooledArrayType.OBJECT.ensureCapacity(pooledArray, size);
	}

	public void release() {
		if (pooledArray != null)
			PooledArrayType.OBJECT.release(pooledArray);
		pooledArray = null;
	}
}
