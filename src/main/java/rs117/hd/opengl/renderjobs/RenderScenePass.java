package rs117.hd.opengl.renderjobs;

import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.ModelDrawBuffer;
import rs117.hd.opengl.shader.ShaderProgram;
import rs117.hd.scene.SceneContext;

import static org.lwjgl.opengl.GL11C.GL_BACK;
import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_ZERO;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearColor;
import static org.lwjgl.opengl.GL11C.glClearDepth;
import static org.lwjgl.opengl.GL11C.glCullFace;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL13C.GL_MULTISAMPLE;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

public class RenderScenePass extends RenderJob {
	private static final JobPool<RenderScenePass> POOL = new JobPool<>(RenderScenePass::new);

	private ShaderProgram sceneProgram;
	private int fboScene;
	private int msaaSamples;
	private int[] sceneResolution;
	private float[] gammaCorrectedFogColor;
	private int vaoScene;
	private ModelDrawBuffer drawBuffer;

	@Override
	protected void doRenderWork(SceneDrawContext drawContext, SceneContext sceneContext) {
		sceneProgram.use();

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboScene);
		//gltoggle(GL_MULTISAMPLE, msaaSamples > 1); TODO: FIXME
		glViewport(0, 0, sceneResolution[0], sceneResolution[1]);

		glClearColor(
			gammaCorrectedFogColor[0],
			gammaCorrectedFogColor[1],
			gammaCorrectedFogColor[2],
			1f
		);
		glClearDepth(0);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		glEnable(GL_CULL_FACE);
		glCullFace(GL_BACK);

		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);

		glBindVertexArray(vaoScene);

		drawBuffer.draw();

		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glDisable(GL_MULTISAMPLE);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(true);
		glUseProgram(0);
	}

	public static void addToQueue(ShaderProgram sceneProgram, int fboScene, int msaaSamples, int[] sceneResolution, float[] gammaCorrectedFogColor, int vaoScene, ModelDrawBuffer drawBuffer) {
		RenderScenePass job = POOL.pop();
		job.sceneProgram = sceneProgram;
		job.fboScene = fboScene;
		job.msaaSamples = msaaSamples;
		job.sceneResolution = sceneResolution;
		job.gammaCorrectedFogColor = gammaCorrectedFogColor;
		job.vaoScene = vaoScene;
		job.drawBuffer = drawBuffer;
		job.submit(SUBMIT_SERIAL);
	}
}
