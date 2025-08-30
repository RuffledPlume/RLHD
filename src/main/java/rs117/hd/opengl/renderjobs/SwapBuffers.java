package rs117.hd.opengl.renderjobs;

import net.runelite.rlawt.AWTContext;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.AWTContextWrapper;
import rs117.hd.scene.SceneContext;

public class SwapBuffers extends RenderJob {
	private static final JobPool<SwapBuffers> POOL = new JobPool<>(SwapBuffers::new);

	public SwapBuffers() {super(POOL);}

	@Override
	protected void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext) {
		awtContextWrapper.getContext().swapBuffers();
	}

	public static void addToQueue() {
		POOL.pop().submit(SUBMIT_SERIAL);
	}
}
