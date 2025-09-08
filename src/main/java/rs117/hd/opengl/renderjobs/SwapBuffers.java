package rs117.hd.opengl.renderjobs;

import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.AWTContextWrapper;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.ObjectPool;

public class SwapBuffers extends RenderJob {
	private static final ObjectPool<SwapBuffers> POOL = new ObjectPool<>(SwapBuffers::new);

	public SwapBuffers() {super(POOL);}

	@Override
	protected void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext) {
		awtContextWrapper.getContext().swapBuffers();
	}

	public static void addToQueue() {
		POOL.pop().submit(SUBMIT_SERIAL);
	}
}
