package rs117.hd.opengl.renderjobs;

import java.util.List;
import rs117.hd.config.DynamicLights;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.shader.TiledLightingShaderProgram;
import rs117.hd.scene.SceneContext;

import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.glClear;
import static org.lwjgl.opengl.GL11C.glClearColor;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glFramebufferTextureLayer;
import static org.lwjgl.opengl.GL32C.glFramebufferTexture;
import static rs117.hd.HdPlugin.checkGLErrors;

public class TiledLightingPass extends RenderJob {
	private static final JobPool<TiledLightingPass> POOL = new JobPool<>(TiledLightingPass::new);

	private int[] tiledLightingResolution;
	private int fboTiledLighting;
	private int texTiledLighting;
	private int vaoTri;
	private DynamicLights configDynamicLights;
	private TiledLightingShaderProgram tiledLightingImageStoreProgram;
	private List<TiledLightingShaderProgram> tiledLightingShaderPrograms;

	@Override
	protected void doRenderWork(SceneDrawContext drawContext, SceneContext sceneContext) {
		glViewport(0, 0, tiledLightingResolution[0], tiledLightingResolution[1]);
		glBindFramebuffer(GL_FRAMEBUFFER, fboTiledLighting);

		glFramebufferTexture(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texTiledLighting, 0);

		glClearColor(0, 0, 0, 0);
		glClear(GL_COLOR_BUFFER_BIT);

		glBindVertexArray(vaoTri);
		glDisable(GL_BLEND);

		if (tiledLightingImageStoreProgram.isValid()) {
			tiledLightingImageStoreProgram.use();
			glDrawArrays(GL_TRIANGLES, 0, 3);
		} else {
			int layerCount = configDynamicLights.getLightsPerTile() / 4;
			for (int layer = 0; layer < layerCount; layer++) {
				tiledLightingShaderPrograms.get(layer).use();
				glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texTiledLighting, 0, layer);
				glDrawArrays(GL_TRIANGLES, 0, 3);
			}
		}

		checkGLErrors();

		POOL.push(this);
	}

	public static void addToQueue(
			int[] tiledLightingResolution,
			int fboTiledLighting,
			int texTiledLighting,
			int vaoTri,
			DynamicLights configDynamicLights,
			TiledLightingShaderProgram tiledLightingImageStoreProgram,
			List<TiledLightingShaderProgram> tiledLightingShaderPrograms) {
		TiledLightingPass job = POOL.pop();
		job.tiledLightingResolution = tiledLightingResolution;
		job.fboTiledLighting = fboTiledLighting;
		job.texTiledLighting = texTiledLighting;
		job.vaoTri = vaoTri;
		job.configDynamicLights = configDynamicLights;
		job.tiledLightingImageStoreProgram = tiledLightingImageStoreProgram;
		job.tiledLightingShaderPrograms = tiledLightingShaderPrograms;
		job.submit(SUBMIT_SERIAL);
	}
}
