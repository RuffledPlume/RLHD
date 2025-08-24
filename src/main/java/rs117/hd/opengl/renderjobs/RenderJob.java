package rs117.hd.opengl.renderjobs;

import rs117.hd.data.SceneDrawContext;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.Job;

public abstract class RenderJob extends Job {

	private static SceneDrawContext DRAW_CONTEXT;
	private static SceneContext SCENE_CONTEXT;

	@Override
	protected void doWork() {
		doRenderWork(DRAW_CONTEXT, SCENE_CONTEXT);
	}

	protected abstract void doRenderWork(SceneDrawContext drawContext, SceneContext sceneContext);

	public static void setRenderDrawContext(SceneDrawContext newDrawContext) {
		DRAW_CONTEXT = newDrawContext;
	}

	public static void setSceneContext(SceneContext newSceneContext) {
		SCENE_CONTEXT = newSceneContext;
	}

	protected static SceneDrawContext getSceneDrawContext() { return DRAW_CONTEXT; }

	protected static SceneContext getSceneContext() { return SCENE_CONTEXT; }
}
