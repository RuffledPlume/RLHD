package rs117.hd.utils;

import java.util.ArrayDeque;

public class ObjectPool <T> {
	private final ArrayDeque<T> pool = new ArrayDeque<>();
	private final CreateFunc<T> createFunc;

	public interface CreateFunc<T> { T create(); }

	public ObjectPool(CreateFunc<T> createFunc) {
		this.createFunc = createFunc;
	}

	public void pushRaw(Object obj) {
		T castedObj = (T) obj;
		if(castedObj != null) {
			push(castedObj);
		}
	}

	public void push(T obj) {
		synchronized (pool) {
			pool.add(obj);
		}
	}

	public T pop() {
		final T obj;
		synchronized (pool) {
			obj = pool.isEmpty() ? null : pool.pop();
		}
		return obj != null ? obj : createFunc.create();
	}
}
