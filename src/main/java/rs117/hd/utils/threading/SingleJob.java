package rs117.hd.utils.threading;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class SingleJob extends BaseJob {
	@Override
	public void run() {
		try {
			execute();
		} catch (Exception ex) {
			log.error("Job: {} Encountered an error while executing", getClass().getName(), ex);
		} finally {
			sema.release();
		}
	}

	protected abstract void execute();
}
