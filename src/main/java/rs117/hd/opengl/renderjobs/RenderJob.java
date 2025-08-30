package rs117.hd.opengl.renderjobs;

import rs117.hd.HdPlugin;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.AWTContextWrapper;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.Job;

public abstract class RenderJob extends Job {

	private static SceneDrawContext DRAW_CONTEXT;
	private static SceneContext SCENE_CONTEXT;
	private static FrameTimer FRAME_TIMER;
	private static AWTContextWrapper AWT_CONTEXT_WRAPPER;

	private final String jobName = getClass().getSimpleName();
	private final JobPool<?> jobPool;
	private final Timer timer;

	public RenderJob(JobPool<?> jobPool) {
		this.jobPool = jobPool;
		this.timer = null;
	}

	public RenderJob(JobPool<?> jobPool, Timer timer) {
		this.jobPool = jobPool;
		this.timer = timer;
	}

	@Override
	protected void doWork() {
		long time = FRAME_TIMER != null && timer != null ? System.nanoTime() : 0;
		try {
			doRenderWork(AWT_CONTEXT_WRAPPER, DRAW_CONTEXT, SCENE_CONTEXT);
		} finally {
			jobPool.push(this);

			if(AWT_CONTEXT_WRAPPER.isRenderThreadOwner()){
				HdPlugin.checkGLErrors(jobName);
			}

			if(FRAME_TIMER != null && timer != null) {
				time = System.nanoTime() - time;
				FRAME_TIMER.add(timer, time);
			}
		}
	}

	protected abstract void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext);

	public static void setAwtContextWrapper(AWTContextWrapper awtContextWrapper) {
		AWT_CONTEXT_WRAPPER = awtContextWrapper;
	}

	public static void setRenderDrawContext(SceneDrawContext newDrawContext) {
		DRAW_CONTEXT = newDrawContext;
	}

	public static void setSceneContext(SceneContext newSceneContext) {
		SCENE_CONTEXT = newSceneContext;
	}

	public static void setFrameTimer(FrameTimer frameTimer) {
		FRAME_TIMER = frameTimer;
	}

	protected static SceneDrawContext getSceneDrawContext() { return DRAW_CONTEXT; }

	protected static SceneContext getSceneContext() { return SCENE_CONTEXT; }
}
