package rs117.hd.utils.threading;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class JobUtil {
	public static final int MINIMAL_WORK_PER_THREAD = 8;
	public static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors() - 1;
	public static final ExecutorService THREAD_POOL = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

	public static class JobBatch<T extends BaseJob> {
		protected final ArrayList<T> jobs = new ArrayList<>();
		protected boolean isCompleted = true;

		public int getJobCount() {
			assert isCompleted;
			return jobs.size();
		}

		public T getJob(int idx) {
			assert isCompleted;
			return jobs.get(idx);
		}

		private void reset (){
			assert isCompleted;
			isCompleted = false;
			jobs.clear();
		}

		public void complete() {
			if(isCompleted) {
				return;
			}

			for(T job : jobs) {
				job.complete();
			}
			isCompleted = true;
		}
	}

	public interface CreateJobFunction<T extends BaseJob> {
		T createJob();
	}

	public static <T extends SingleJob> T submit(CreateJobFunction<T> createFunction) {
		T newJob = createFunction.createJob();
		THREAD_POOL.submit(newJob);
		return newJob;
	}

	public static void submit(BaseJob job) {
		job.onPreSubmit();
		THREAD_POOL.submit(job);
	}

	public static <T extends OneDJob> void submit(int offset, int count, CreateJobFunction<T> createFunction, JobBatch<T> batch) {
		if (createFunction == null) return;

		if(batch != null) {
			batch.complete();
			batch.reset();
		}

		int jobCount = Math.max(1, (count / MINIMAL_WORK_PER_THREAD) / THREAD_POOL_SIZE);
		int workPerJob = count / jobCount;

		for (int i = 0; i < jobCount; i++) {
			T newJob = createFunction.createJob();
			newJob.offset = offset + (i * workPerJob);
			newJob.limit = (i == jobCount - 1) ? offset + count : newJob.offset + workPerJob;

			THREAD_POOL.submit(newJob);
			if (batch != null) {
				batch.jobs.add(newJob);
			}
		}
	}

	public static <T extends OneDJob> void submit(int offset, int count, CreateJobFunction<T> createFunction) {
		submit(offset, count, createFunction, null);
	}

	public static <T extends TwoDJob> void submit(int offsetX, int offsetY, int countX, int countY, CreateJobFunction<T> createFunction, JobBatch<T> batch) {
		if (createFunction == null) return;

		if(batch != null) {
			batch.complete();
			batch.reset();
		}

		int totalWork = countX * countY;
		int jobCount = Math.max(1, (totalWork / MINIMAL_WORK_PER_THREAD) / THREAD_POOL_SIZE);
		int rowsPerJob = countY / jobCount;

		for (int i = 0; i < jobCount; i++) {
			T newJob = createFunction.createJob();

			newJob.offsetX = offsetX;
			newJob.offsetY = offsetY + (i * rowsPerJob);
			newJob.limitX = offsetX + countX;
			newJob.limitY = (i == jobCount - 1) ? offsetY + countY : newJob.offsetY + rowsPerJob;

			THREAD_POOL.submit(newJob);
			if (batch != null) {
				batch.jobs.add(newJob);
			}
		}
	}

	public static <T extends TwoDJob> void submit(int offsetX, int offsetY, int countX, int countY, CreateJobFunction<T> createFunction) {
		submit(offsetX, offsetY, countX, countY, createFunction, null);
	}

	public static <T extends ThreeDJob> void submit(int offsetX, int offsetY, int offsetZ, int countX, int countY, int countZ, CreateJobFunction<T> createFunction, JobBatch<T> batch) {
		if (createFunction == null) return;

		if(batch != null) {
			batch.complete();
			batch.reset();
		}

		int totalWork = countX * countY * countZ;
		int jobCount = Math.max(1, (totalWork / MINIMAL_WORK_PER_THREAD) / THREAD_POOL_SIZE);
		int slicesPerJob = countZ / jobCount;

		for (int i = 0; i < jobCount; i++) {
			T newJob = createFunction.createJob();

			newJob.offsetX = offsetX;
			newJob.offsetY = offsetY;
			newJob.offsetZ = offsetZ + (i * slicesPerJob);

			newJob.limitX = offsetX + countX;
			newJob.limitY = offsetY + countY;
			newJob.limitZ = (i == jobCount - 1) ? offsetZ + countZ : newJob.offsetZ + slicesPerJob;

			THREAD_POOL.submit(newJob);
			if (batch != null) {
				batch.jobs.add(newJob);
			}
		}
	}

	public static <T extends ThreeDJob> void submit(int offsetX, int offsetY, int offsetZ, int countX, int countY, int countZ, CreateJobFunction<T> createFunction) {
		submit(offsetX, offsetY, offsetZ, countX, countY, countZ, createFunction, null);
	}
}
