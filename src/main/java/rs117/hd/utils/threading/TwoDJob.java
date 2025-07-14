package rs117.hd.utils.threading;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class TwoDJob extends BaseJob {
	protected int offsetX, offsetY;
	protected int limitX, limitY;

	@Override
	public void run() {
		try {
			for(int x = offsetX; x < limitX; x++) {
				for(int y = offsetY; y < limitY; y++) {
					execute(x, y);
				}
			}
		} catch (Exception ex) {
			log.error("Job: {} Encountered an error while executing", getClass().getName(), ex);
		} finally {
			sema.release();
		}
	}

	protected abstract void execute(int x, int y);
}