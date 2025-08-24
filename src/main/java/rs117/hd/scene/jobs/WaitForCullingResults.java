package rs117.hd.scene.jobs;

import rs117.hd.scene.SceneCullingManager;
import rs117.hd.utils.Job;

public class WaitForCullingResults extends Job {
	private static final JobPool<WaitForCullingResults> POOL = new JobPool<>(WaitForCullingResults::new);

	private SceneCullingManager sceneCullingManager;

	@Override
	protected void doWork() {
		sceneCullingManager.waitOnCulling();
		POOL.push(this);
	}

	public static void addToQueue(SceneCullingManager sceneCullingManager) {
		WaitForCullingResults job = POOL.pop();
		job.sceneCullingManager = sceneCullingManager;
		job.submit(SUBMIT_SERIAL);
	}
}
