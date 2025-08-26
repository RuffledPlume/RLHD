package rs117.hd.opengl.renderjobs;

import net.runelite.rlawt.AWTContext;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.scene.SceneContext;

import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBlitFramebuffer;

public class BlitSceneFBOToBackBuffer extends RenderJob {
	private static final JobPool<BlitSceneFBOToBackBuffer> POOL = new JobPool<>(BlitSceneFBOToBackBuffer::new);

	private AWTContext awtContext;
	private int fboScene;
	private int fboSceneResolve;
	private int[] sceneResolution;
	private int[] sceneViewport;
	private int sceneScalingModeFilter;


	@Override
	protected void doRenderWork(SceneDrawContext drawContext, SceneContext sceneContext) {
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboScene);
		if (fboSceneResolve != 0) {
			// Blit from the scene FBO to the multisample resolve FBO
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboSceneResolve);
			glBlitFramebuffer(
				0, 0, sceneResolution[0], sceneResolution[1],
				0, 0, sceneResolution[0], sceneResolution[1],
				GL_COLOR_BUFFER_BIT, GL_NEAREST
			);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, fboSceneResolve);
		}

		// Blit from the resolved FBO to the default FBO
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
		glBlitFramebuffer(
			0, 0, sceneResolution[0], sceneResolution[1],
			sceneViewport[0], sceneViewport[1], sceneViewport[0] + sceneViewport[2], sceneViewport[1] + sceneViewport[3],
			GL_COLOR_BUFFER_BIT, sceneScalingModeFilter
		);

		POOL.push(this);
	}

	public static void addToQueue(AWTContext awtContext, int fboScene, int fboSceneResolve, int[] sceneResolution, int[] sceneViewport, int sceneScalingModeFilter) {
		BlitSceneFBOToBackBuffer job = POOL.pop();
		job.awtContext = awtContext;
		job.fboScene = fboScene;
		job.fboSceneResolve = fboSceneResolve;
		job.sceneResolution = sceneResolution;
		job.sceneViewport = sceneViewport;
		job.sceneScalingModeFilter = sceneScalingModeFilter;
	}
}
