package rs117.hd.utils.threading;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class OneDJob extends BaseJob {
	protected int offset;
	protected int limit;

	@Override
	public void run() {
		try {
			for(int i = offset; i < limit; i++) {
				execute(i);
			}
		} catch (Exception ex) {
			log.error("Job: {} Encountered an error while executing", getClass().getName(), ex);
			ex.printStackTrace();
		} finally {
			sema.release();
		}
	}

	protected abstract void execute(int idx);
}
