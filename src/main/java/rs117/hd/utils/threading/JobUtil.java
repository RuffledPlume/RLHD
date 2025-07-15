package rs117.hd.utils.threading;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JobUtil {
	public static final int MINIMAL_WORK_PER_THREAD = 8;
	public static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors() - 1;
	public static final ExecutorService THREAD_POOL = Executors.newFixedThreadPool(THREAD_POOL_SIZE);


	public static class JobBatch<T extends BaseJob> {
		public interface OnCompleteFunction {
			void onComplete();
		}

		protected final ArrayList<T> jobs = new ArrayList<>();
		protected boolean isCompleted = true;
		protected OnCompleteFunction completeFunction;

		public void setOnCompleteFunction(OnCompleteFunction completeFunction) {
			this.completeFunction = completeFunction;
		}

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
			completeFunction = null;
			jobs.clear();
		}

		public void complete() {
			if(isCompleted) {
				return;
			}

			for(T job : jobs) {
				job.complete();
			}

			if(completeFunction != null) {
				completeFunction.onComplete();
				completeFunction = null;
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

	public static <T extends TwoDJob> void submit(int offsetX, int offsetY, int countX, int countY,
		CreateJobFunction<T> createFunction, JobBatch<T> batch) {
		if (createFunction == null) return;

		if (batch != null) {
			batch.complete();
			batch.reset();
		}

		int totalWork = countX * countY;
		int jobCount = Math.max(1, (totalWork / MINIMAL_WORK_PER_THREAD) / THREAD_POOL_SIZE);

		int cols = (int) Math.ceil(Math.sqrt((double) jobCount * countX / countY));
		int rows = (int) Math.ceil((double) jobCount / cols);

		int tileWidth  = (int) Math.ceil((double) countX / cols);
		int tileHeight = (int) Math.ceil((double) countY / rows);

		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < cols; col++) {
				int jobOffsetX = offsetX + col * tileWidth;
				int jobOffsetY = offsetY + row * tileHeight;

				int jobLimitX = Math.min(jobOffsetX + tileWidth, offsetX + countX);
				int jobLimitY = Math.min(jobOffsetY + tileHeight, offsetY + countY);

				if (jobOffsetX >= jobLimitX || jobOffsetY >= jobLimitY)
					continue; // Skip empty tiles

				T newJob = createFunction.createJob();

				newJob.offsetX = jobOffsetX;
				newJob.offsetY = jobOffsetY;
				newJob.limitX  = jobLimitX;
				newJob.limitY  = jobLimitY;

				THREAD_POOL.submit(newJob);
				if (batch != null) {
					batch.jobs.add(newJob);
				}
			}
		}
	}

	public static <T extends TwoDJob> void submit(int offsetX, int offsetY, int countX, int countY, CreateJobFunction<T> createFunction) {
		submit(offsetX, offsetY, countX, countY, createFunction, null);
	}

	public static <T extends ThreeDJob> void submit(int offsetX, int offsetY, int offsetZ, int countX, int countY, int countZ, CreateJobFunction<T> createFunction, JobBatch<T> batch) {
		if (createFunction == null) return;

		if (batch != null) {
			batch.complete();
			batch.reset();
		}

		int totalWork = countX * countY * countZ;
		int jobCount = Math.max(1, (totalWork / MINIMAL_WORK_PER_THREAD) / THREAD_POOL_SIZE);

		// Estimate tile counts in 3D
		int tilesX = (int) Math.ceil(Math.cbrt(jobCount * (double) countX * countX / (countY * countZ)));
		int tilesY = (int) Math.ceil(Math.cbrt(jobCount * (double) countY * countY / (countX * countZ)));
		int tilesZ = (int) Math.ceil((double) jobCount / (tilesX * tilesY));

		int tileWidth  = (int) Math.ceil((double) countX / tilesX);
		int tileHeight = (int) Math.ceil((double) countY / tilesY);
		int tileDepth  = (int) Math.ceil((double) countZ / tilesZ);

		for (int z = 0; z < tilesZ; z++) {
			for (int y = 0; y < tilesY; y++) {
				for (int x = 0; x < tilesX; x++) {
					int jobOffsetX = offsetX + x * tileWidth;
					int jobOffsetY = offsetY + y * tileHeight;
					int jobOffsetZ = offsetZ + z * tileDepth;

					int jobLimitX = Math.min(jobOffsetX + tileWidth, offsetX + countX);
					int jobLimitY = Math.min(jobOffsetY + tileHeight, offsetY + countY);
					int jobLimitZ = Math.min(jobOffsetZ + tileDepth, offsetZ + countZ);

					if (jobOffsetX >= jobLimitX || jobOffsetY >= jobLimitY || jobOffsetZ >= jobLimitZ)
						continue; // Skip empty tiles

					T newJob = createFunction.createJob();

					newJob.offsetX = jobOffsetX;
					newJob.offsetY = jobOffsetY;
					newJob.offsetZ = jobOffsetZ;
					newJob.limitX  = jobLimitX;
					newJob.limitY  = jobLimitY;
					newJob.limitZ  = jobLimitZ;

					THREAD_POOL.submit(newJob);
					if (batch != null) {
						batch.jobs.add(newJob);
					}
				}
			}
		}
	}

	public static <T extends ThreeDJob> void submit(int offsetX, int offsetY, int offsetZ, int countX, int countY, int countZ, CreateJobFunction<T> createFunction) {
		submit(offsetX, offsetY, offsetZ, countX, countY, countZ, createFunction, null);
	}
}
