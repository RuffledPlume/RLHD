package rs117.hd.scene.jobs;

import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.AWTContextWrapper;
import rs117.hd.opengl.renderjobs.RenderJob;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.SceneCullingManager;
import rs117.hd.utils.ObjectPool;

public class WaitForCullingResults extends RenderJob {
	private static final ObjectPool<WaitForCullingResults> POOL = new ObjectPool<>(WaitForCullingResults::new);

	private SceneCullingManager sceneCullingManager;

	public WaitForCullingResults() {super(POOL);}

	@Override
	protected void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext) {
		sceneCullingManager.waitOnCulling();
	}

	public static void addToQueue(SceneCullingManager sceneCullingManager) {
		WaitForCullingResults job = POOL.pop();
		job.sceneCullingManager = sceneCullingManager;
		job.submit(SUBMIT_SERIAL);
	}
}
