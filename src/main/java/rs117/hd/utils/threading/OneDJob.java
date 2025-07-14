package rs117.hd.utils.threading;

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
			ex.printStackTrace();
		} finally {
			sema.release();
		}
	}

	protected abstract void execute(int idx);
}
