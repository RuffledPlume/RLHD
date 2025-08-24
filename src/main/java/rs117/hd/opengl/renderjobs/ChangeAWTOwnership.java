package rs117.hd.opengl.renderjobs;

import lombok.extern.slf4j.Slf4j;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.scene.SceneContext;

@Slf4j
public class ChangeAWTOwnership extends RenderJob {
	private static final JobPool<ChangeAWTOwnership> POOL = new JobPool<>(ChangeAWTOwnership::new);

	private AWTContext awtContext;
	private SceneDrawContext.AWTContextOwner newOwner;

	@Override
	protected void doRenderWork(SceneDrawContext drawContext, SceneContext sceneContext) {
		if (newOwner == SceneDrawContext.AWTContextOwner.RenderThread) {
			awtContext.makeCurrent();
			drawContext.currentAWTContextOwner = SceneDrawContext.AWTContextOwner.RenderThread;

			if(HdPlugin.GL_SERIAL_THREAD_CAPS == null) {
				HdPlugin.GL_SERIAL_THREAD_CAPS = GL.createCapabilities();
			}
		} else {
			awtContext.detachCurrent();
		}

		POOL.push(this);
	}

	public static void addToQueue(AWTContext awtContext, SceneDrawContext.AWTContextOwner newOwner) {
		if(getSceneDrawContext().currentAWTContextOwner == SceneDrawContext.AWTContextOwner.Client &&
		   newOwner == SceneDrawContext.AWTContextOwner.RenderThread) {
			awtContext.detachCurrent(); // Detach Context from Client
		}

		ChangeAWTOwnership job = POOL.pop();
		job.awtContext = awtContext;
		job.newOwner = newOwner;
		job.submit(SUBMIT_SERIAL);

		if(getSceneDrawContext().currentAWTContextOwner == SceneDrawContext.AWTContextOwner.RenderThread &&
		   newOwner == SceneDrawContext.AWTContextOwner.Client) {
			job.complete();

			awtContext.makeCurrent();
			getSceneDrawContext().currentAWTContextOwner = SceneDrawContext.AWTContextOwner.Client;
		}
	}
}
