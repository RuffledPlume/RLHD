package rs117.hd.opengl.renderjobs;

import net.runelite.rlawt.AWTContext;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.scene.SceneContext;

public class SwapBuffers extends RenderJob {
	private static final JobPool<SwapBuffers> POOL = new JobPool<>(SwapBuffers::new);

	private AWTContext awtContext;

	@Override
	protected void doRenderWork(SceneDrawContext drawContext, SceneContext sceneContext) {
		awtContext.swapBuffers();
		POOL.push(this);
	}

	public static void addToQueue(AWTContext awtContext) {
		SwapBuffers job = POOL.pop();
		job.awtContext = awtContext;
		job.submit(SUBMIT_SERIAL);
	}
}
