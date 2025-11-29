package rs117.hd.utils.jobs;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

import static rs117.hd.utils.HDUtils.printStacktrace;
import static rs117.hd.utils.jobs.JobSystem.VALIDATE;

@Slf4j
public final class JobWorker {
	String name, pausedName;
	Thread thread;
	JobHandle handle;

	final BlockingDeque<JobHandle> localWorkQueue = new LinkedBlockingDeque<>();
	final AtomicBoolean inflight = new AtomicBoolean();

	void run() {
		name = thread.getName();
		pausedName = name + " [Paused]";
		ThreadLocalRandom random = ThreadLocalRandom.current();
		while (JobSystem.INSTANCE.active) {
			try {
				// Check local work queue
				handle = localWorkQueue.poll();

				while(handle == null) {
					// Randomly attempt to steal another threads work
					final int stealTargetIdx = random.nextInt(0, JobSystem.INSTANCE.workers.length);
					final JobWorker stealTarget = JobSystem.INSTANCE.workers[stealTargetIdx];
					if (stealTarget != this) {
						handle = stealTarget.localWorkQueue.poll();
					}

					if (handle == null) {
						// Still no work, wait longer on main work Queue
						handle = JobSystem.INSTANCE.workQueue.poll(10, TimeUnit.MILLISECONDS);
					}

					if(handle == null)
						inflight.lazySet(false);

					if(!JobSystem.INSTANCE.active) {
						log.debug("Shutdown");
						return;
					}
				}
			} catch (InterruptedException e) {
				if(handle != null) {
					log.debug("Interrupt Received before processing: {}", handle.hashCode());
					handle = null;
				}
				continue;
			}

			try {
				workerHandleCancel();

				if(handle.item != null && handle.setRunning(this)) {
					inflight.lazySet(true);
					handle.item.onRun();
					handle.item.ranToCompletion.set(true);
				}
			}
			catch (InterruptedException e) {
				log.debug("Interrupt Received whilst processing: {}", handle.hashCode());
			}
			catch (Throwable ex) {
				log.warn("Encountered an error whilst processing: {}", handle.hashCode(), ex);
				handle.cancel(false);
			} finally {
				handle.setCompleted();
				handle.worker = null;
				handle = null;
			}
		}
		log.debug("Shutdown - {}", JobSystem.INSTANCE.active);
	}

	void workerHandleCancel() throws InterruptedException {
		if (handle.isCancelled()) {
			if(VALIDATE) log.debug("Handle {} has been cancelled, interrupting to exit execution", handle);
			if(handle.item != null)
				handle.item.onCancel();
			throw new InterruptedException();
		}
	}

	void printState() {
		if(handle == null) {
			log.debug("Worker [{}] Idle", thread.getName());
			return;
		}

		printStacktrace(false, thread.getStackTrace());
	}
}
