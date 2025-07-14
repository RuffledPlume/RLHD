package rs117.hd.utils.threading;

public abstract class ThreeDJob extends BaseJob {
	protected int offsetX, offsetY, offsetZ;
	protected int limitX, limitY, limitZ;

	@Override
	public void run() {
		try {
			for(int x = offsetX; x < limitX; x++) {
				for(int y = offsetY; y < limitY; y++) {
					for(int z = offsetZ; z < limitZ; z++) {
						execute(x, y, z);
					}
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		} finally {
			sema.release();
		}
	}

	protected abstract void execute(int x, int y, int z);
}
