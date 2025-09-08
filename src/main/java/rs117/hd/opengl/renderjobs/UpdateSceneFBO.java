package rs117.hd.opengl.renderjobs;

import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.AWTContextWrapper;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.ObjectPool;

public class UpdateSceneFBO extends RenderJob {
	private static final ObjectPool<UpdateSceneFBO> POOL = new ObjectPool<>(UpdateSceneFBO::new);

	public UpdateSceneFBO() {super(POOL);}

	@Override
	protected void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext) {
		// TODO... This would require managing the creation/destruction of resources
		// TODO... We'd need some form of ThreadSafe resource tracking
	}

	public static void addToQueue() {
		UpdateSceneFBO job = POOL.pop();

		job.submit(SUBMIT_SERIAL);
	}
}
