package rs117.hd.utils.threading;

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
			ex.printStackTrace();
		} finally {
			sema.release();
		}
	}

	protected abstract void execute(int x, int y);
}