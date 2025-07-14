package rs117.hd.utils.threading;

public abstract class SingleJob extends BaseJob {
	@Override
	public void run() {
		try {
			execute();
		} catch (Exception ex) {
			ex.printStackTrace();
		} finally {
			sema.release();
		}
	}

	protected abstract void execute();
}
