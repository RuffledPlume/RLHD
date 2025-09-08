package rs117.hd.opengl.renderjobs;

import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.AWTContextWrapper;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.ObjectPool;

public class DrawFence extends RenderJob {
	private static ObjectPool<DrawFence> POOL = new ObjectPool<>(DrawFence::new);

	public DrawFence() {super(POOL);}

	@Override
	protected void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext) {
		// STUB
	}

	public static void addToQueue() {
		POOL.pop().submit(SUBMIT_SERIAL).complete();
	}
}
