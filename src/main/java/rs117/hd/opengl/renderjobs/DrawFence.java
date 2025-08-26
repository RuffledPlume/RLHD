package rs117.hd.opengl.renderjobs;

import rs117.hd.data.SceneDrawContext;
import rs117.hd.scene.SceneContext;

public class DrawFence extends RenderJob {
	private static JobPool<DrawFence> POOL = new JobPool<>(DrawFence::new);

	@Override
	protected void doRenderWork(SceneDrawContext drawContext, SceneContext sceneContext) {
		POOL.push(this);
	}

	public static void addToQueue() {
		POOL.pop().submit(SUBMIT_SERIAL).complete();
	}
}
