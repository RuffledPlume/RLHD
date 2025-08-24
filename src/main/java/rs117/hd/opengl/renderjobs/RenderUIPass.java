package rs117.hd.opengl.renderjobs;

import rs117.hd.data.SceneDrawContext;
import rs117.hd.scene.SceneContext;

public class RenderUIPass extends RenderJob {
	private static final JobPool<RenderUIPass> POOL = new JobPool<>(RenderUIPass::new);

	@Override
	protected void doRenderWork(SceneDrawContext drawContext, SceneContext sceneContext) {

	}

	public static void addToQueue() {

	}
}
