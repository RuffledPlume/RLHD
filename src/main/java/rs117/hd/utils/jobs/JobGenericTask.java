package rs117.hd.utils.jobs;

import java.util.concurrent.ConcurrentLinkedDeque;
import lombok.SneakyThrows;

public final class JobGenericTask extends JobWork {
	private static final ConcurrentLinkedDeque<JobGenericTask> POOL = new ConcurrentLinkedDeque<>();

	public interface TaskRunnable {
		void run(JobGenericTask Task) throws InterruptedException;
	}

	@SneakyThrows
	public static JobGenericTask build(String context, TaskRunnable runnable) {
		JobGenericTask newTask = POOL.poll();
		if (newTask == null)
			newTask = new JobGenericTask();
		newTask.context = context;
		newTask.runnable = runnable;

		return newTask;
	}

	public String context;
	public TaskRunnable runnable;

	@Override
	public void run() throws InterruptedException {
		runnable.run(this);
	}

	@Override
	protected void cancelled() {}

	@Override
	public void release() {
		runnable = null;
		POOL.add(this);
	}

	@Override
	protected String debugInfo() {
		return "runnable: " + context;
	}
}
