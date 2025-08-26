package rs117.hd.opengl.renderjobs;

import net.runelite.rlawt.AWTContext;
import rs117.hd.config.UIScalingMode;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.shader.ShaderProgram;
import rs117.hd.opengl.uniforms.UBOUI;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.ColorUtils;

import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.GL_ZERO;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;

public class RenderUIPass extends RenderJob {
	private static final JobPool<RenderUIPass> POOL = new JobPool<>(RenderUIPass::new);

	private AWTContext awtContext;
	private ShaderProgram uiProgram;
	private UBOUI uboUI;
	private int[] actualUiResolution;
	private int scalingFunction;
	private int vaoTri;
	private int texUi;
	private int overlayColor;
	private int[] uiResolution;

	@Override
	protected void doRenderWork(SceneDrawContext drawContext, SceneContext sceneContext) {
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		// Disable alpha writes, just in case the default FBO has an alpha channel
		glColorMask(true, true, true, false);

		glViewport(0, 0, actualUiResolution[0], actualUiResolution[1]);

		//tiledLightingOverlay.render(); TODO: Fix me

		uiProgram.use();
		uboUI.sourceDimensions.set(uiResolution);
		uboUI.targetDimensions.set(actualUiResolution);
		uboUI.alphaOverlay.set(ColorUtils.srgba(overlayColor));
		uboUI.upload();

		glActiveTexture(TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_2D, texUi);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, scalingFunction);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, scalingFunction);

		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		glBindVertexArray(vaoTri);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		//shadowMapOverlay.render(); TODO: FIX ME
		//gammaCalibrationOverlay.render(); TODO: FIX ME

		// Reset
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		glDisable(GL_BLEND);
		glColorMask(true, true, true, true);

		POOL.push(this);
	}

	public static void addToQueue(AWTContext awtContext, ShaderProgram uiProgram, UBOUI uboUI, int[] actualUiResolution, int scalingFunction, int vaoTri, int texUi, int overlayColor, int[] uiResolution) {
		RenderUIPass job = POOL.pop();
		job.awtContext = awtContext;
		job.uiProgram = uiProgram;
		job.uboUI = uboUI;
		job.actualUiResolution = actualUiResolution;
		job.scalingFunction = scalingFunction;
		job.vaoTri = vaoTri;
		job.texUi = texUi;
		job.overlayColor = overlayColor;
		job.uiResolution = uiResolution;
		job.submit(SUBMIT_SERIAL);
	}
}
