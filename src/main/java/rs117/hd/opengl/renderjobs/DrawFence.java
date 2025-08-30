package rs117.hd.opengl.renderjobs;

import net.runelite.rlawt.AWTContext;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.AWTContextWrapper;
import rs117.hd.scene.SceneContext;

public class DrawFence extends RenderJob {
	private static JobPool<DrawFence> POOL = new JobPool<>(DrawFence::new);

	public DrawFence() {super(POOL);}

	@Override
	protected void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext) {
		// STUB
	}

	public static void addToQueue() {
		POOL.pop().submit(SUBMIT_SERIAL).complete();
	}
}
