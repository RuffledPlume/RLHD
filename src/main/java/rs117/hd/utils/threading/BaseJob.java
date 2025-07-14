package rs117.hd.utils.threading;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public abstract class BaseJob implements Runnable {
	private static final Map<Class<? extends BaseJob>, ArrayDeque<BaseJob>> cache = new HashMap<>();

	protected final Semaphore sema = new Semaphore(1);

	protected static <T extends BaseJob> T tryGetFromCacheOrCreate(Class<T> jobClass, JobUtil.CreateJobFunction<T> createFunction) {
		if(cache.containsKey(jobClass)) {
			ArrayDeque<BaseJob> jobCache = cache.get(jobClass);
			if(!jobCache.isEmpty()){
				return (T) jobCache.pop();
			}
		}
		return createFunction.createJob();
	}

	public void onPreSubmit() {
		try {
			sema.acquire();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	public void complete() {
		try {
			long timeout = getTimeout();
			if(timeout > 0) {
				if (sema.tryAcquire(1, getTimeout(), TimeUnit.SECONDS)) {
					sema.release();
				}
			} else {
				sema.acquire();
				sema.release();
			}
			onComplete();

			if(shouldCache()) {
				if (!cache.containsKey(getClass())) {
					cache.put(getClass(), new ArrayDeque<>());
				}
				cache.get(getClass()).add(this);
			}

		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	protected long getTimeout() {return 1;}
	protected boolean shouldCache() { return true;}
	protected void onComplete() {}
}