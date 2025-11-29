package rs117.hd.utils.jobs;

import java.util.concurrent.LinkedBlockingDeque;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class JobGroup {
	@Getter
	protected final LinkedBlockingDeque<JobHandle> pending = new LinkedBlockingDeque<>();

	@Getter
	protected boolean highPriority;

	public int getPendingCount() { return pending.size(); }

	@SneakyThrows
	public void complete() {
		JobHandle handle;
		while ((handle = pending.poll()) != null) {
			handle.await();
		}
	}

	public void cancel(boolean block) {
		for(JobHandle handle : pending) {
			handle.cancel(block);
		}
		pending.clear();
	}

	public void cancel() {
		cancel(true);
	}
}
