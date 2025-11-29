package rs117.hd.utils.jobs;

public abstract class JobWork {
	protected JobHandle handle;

	public final void queueClientCallback(boolean highPriority, boolean immediate, Runnable callback) throws InterruptedException {
		JobSystem.INSTANCE.queueClientCallback(highPriority, immediate, callback);
	}

	public final void workerHandleCancel() throws InterruptedException {
		if(handle == null)
			return;

		final JobWorker worker = handle.worker;
		if(handle.worker == null)
			return;

		worker.workerHandleCancel();
	}

	public final JobHandle queue(JobGroup group, JobHandle... dependencies) {
		return JobSystem.INSTANCE.queue(group, this, group.highPriority, dependencies);
	}

	public final JobHandle queue(boolean highPriority, JobHandle... dependencies) {
		return JobSystem.INSTANCE.queue(null, this, highPriority, dependencies);
	}

	public final JobHandle queue(JobHandle... dependencies) {
		return queue(true, dependencies);
	}

	protected abstract void run() throws InterruptedException;
	protected abstract void cancelled();
	protected abstract void release();
	protected abstract String debugInfo();

	public String toString() {
		return getClass().getSimpleName() + " [" + debugInfo() + "]";
	}
}
