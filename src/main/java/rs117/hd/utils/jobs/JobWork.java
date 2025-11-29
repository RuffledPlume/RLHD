package rs117.hd.utils.jobs;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class JobWork {
	protected final AtomicBoolean wasCancelled = new AtomicBoolean();
	protected final AtomicBoolean ranToCompletion = new AtomicBoolean();
	protected JobHandle handle;
	protected JobGroup group;
	protected boolean isReleased;

	public void waitForCompletion() {
		if(handle != null) {
			handle.await();
			handle.release();

			if(group != null) {
				group.pending.remove(this);
				group = null;
			}
		}
	}

	public boolean wasCancelled() {
		return wasCancelled.get();
	}

	public boolean ranToCompletion() {
		return ranToCompletion.get();
	}

	public void cancel() {
		if(handle != null) {
			handle.cancel(true);
			handle.release();
		}
	}

	public void release() {
		if(isReleased)
			return;
		isReleased = true;
		waitForCompletion();
		onReleased();
	}

	public boolean isCompleted() {
		return handle == null || handle.isCompleted();
	}

	public boolean isHighPriority() {
		return (group != null && group.highPriority) || (handle != null && handle.highPriority);
	}

	// TODO: Move this to JobSystem Class ?
	public final void queueClientCallback(boolean highPriority, boolean immediate, Runnable callback) throws InterruptedException {
		JobSystem.INSTANCE.queueClientCallback(highPriority, immediate, callback);
	}

	// TODO: Move this to JobSystem Class ?
	public final void workerHandleCancel() throws InterruptedException {
		if(handle == null)
			return;

		final JobWorker worker = handle.worker;
		if(handle.worker == null)
			return;

		worker.workerHandleCancel();
	}

	public final <T extends JobWork> T queue(JobGroup group, JobWork... dependencies) {
		assert group != null;
		JobSystem.INSTANCE.queue(this, group.highPriority, dependencies);
		group.pending.add(this);
		return (T) this;
	}

	public final <T extends JobWork> T queue(boolean highPriority, JobWork... dependencies) {
		JobSystem.INSTANCE.queue(this, highPriority, dependencies);
		return (T) this;
	}

	public final <T extends JobWork> T queue(JobWork... dependencies) {
		JobSystem.INSTANCE.queue(this, true, dependencies);
		return (T) this;
	}

	protected abstract void onRun() throws InterruptedException;
	protected abstract void onCancel();
	protected abstract void onReleased();

	public String toString() {
		return "[" + hashCode() + "|" + getClass().getSimpleName() + "]";
	}
}
