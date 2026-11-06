package rs117.hd.utils;

import java.awt.Dimension;
import java.io.IOException;
import java.util.Arrays;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.shader.UIShaderProgram;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.jobs.GenericJob;

import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexSubImage2D;
import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL12C.GL_BGRA;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12C.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.buffer.GLBuffer.MAP_WRITE;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_IMMUTABLE;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_PERSISTENT;
import static rs117.hd.utils.buffer.GLBuffer.STORAGE_WRITE;

@Singleton
@Slf4j
public class TiledInterface {

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private FrameTimer frameTimer;

	@Inject
	private UIShaderProgram uiProgram;

	@Getter
	@Nullable
	private int[] uiResolution;

	@Getter
	private final int[] actualUiResolution = { 0, 0 }; // Includes stretched mode and DPI scaling
	private final GLBuffer[] pboUi = new GLBuffer[3];

	private int texUi;
	private int uiWidth;
	private int uiHeight;
	
	private GenericJob uiCopyJob;

	public void startup() {
		for (int i = 0; i < 3; i++) {
			pboUi[i] = new GLBuffer(
				"PBO::UI",
				GL_PIXEL_UNPACK_BUFFER,
				GL_STREAM_DRAW,
				STORAGE_PERSISTENT | STORAGE_IMMUTABLE | STORAGE_WRITE
			);
			pboUi[i].initialize();
		}

		texUi = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_2D, texUi);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

		checkGLErrors();
	}

	public void shutdown() {
		uiResolution = null;

		for (int i = 0; i < 3; i++) {
			if (pboUi[i] != null)
				pboUi[i].destroy();
			pboUi[i] = null;
		}

		if (texUi != 0)
			glDeleteTextures(texUi);
		texUi = 0;
	}

	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		uiProgram.compile(includes);
	}

	public void destroyShaders() {
		uiProgram.destroy();
	}

	public boolean prepare() {
		try {
			if (uiCopyJob != null)
				uiCopyJob.waitForCompletion(true);
			uiCopyJob = null;

			int[] resolution = {
				max(1, client.getCanvasWidth()),
				max(1, client.getCanvasHeight())
			};
			boolean resize = !Arrays.equals(uiResolution, resolution);
			if (resize) {
				uiResolution = resolution;

				glActiveTexture(TEXTURE_UNIT_UI);
				glBindTexture(GL_TEXTURE_2D, texUi);
				glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, uiResolution[0], uiResolution[1], 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
			}

			if (client.isStretchedEnabled()) {
				Dimension dim = client.getStretchedDimensions();
				actualUiResolution[0] = dim.width;
				actualUiResolution[1] = dim.height;
			} else {
				copyTo(actualUiResolution, uiResolution);
			}
			round(actualUiResolution, multiply(vec(actualUiResolution), plugin.getDpiScaling()));

			final BufferProvider bufferProvider = client.getBufferProvider();
			final int[] pixels = bufferProvider.getPixels();
			uiWidth = bufferProvider.getWidth();
			uiHeight = bufferProvider.getHeight();

			frameTimer.begin(Timer.MAP_UI_BUFFER);
			final GLBuffer pbo = pboUi[plugin.frame % 3];
			pbo.map(MAP_WRITE, 0, uiWidth * uiHeight * 4L);
			frameTimer.end(Timer.MAP_UI_BUFFER);
			if (!pbo.isMapped()) {
				log.error("Unable to map interface PBO. Skipping UI...");
			} else if (uiWidth > uiResolution[0] || uiHeight > uiResolution[1]) {
				log.error("UI texture resolution mismatch ({}x{} > {}). Skipping UI...", uiWidth, uiHeight, uiResolution);
			} else {
				uiCopyJob = GenericJob
					.build(
						"AsyncUICopy",
						t -> {
							long start = System.nanoTime();
							pbo.mapped().intView().put(pixels, 0, uiWidth * uiHeight);
							frameTimer.add(Timer.COPY_UI_ASYNC, System.nanoTime() - start);
						}
					)
					.setExecuteAsync(!plugin.isPowerSaving)
					.queue();
			}
			pbo.unbind();
		} catch (Exception ex) {
			// Fixes: https://github.com/runelite/runelite/issues/12930
			// Gracefully Handle loss of opengl buffers and context
			log.warn("prepareInterfaceTexture exception", ex);
			plugin.restartPlugin();
			return false;
		}

		return true;
	}

	public void upload() {
		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear/pixel isn't
		final int function = config.uiScalingMode().glSamplingFunction;
		glActiveTexture(TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_2D, texUi);

		if (uiCopyJob != null) {
			frameTimer.begin(Timer.COPY_UI);
			uiCopyJob.waitForCompletion(true);
			uiCopyJob = null;
			frameTimer.end(Timer.COPY_UI);

			frameTimer.begin(Timer.UPLOAD_UI);
			final GLBuffer pbo = pboUi[plugin.frame % 3];
			pbo.unmap();
			pbo.bind();

			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, uiWidth, uiHeight, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
			pbo.unbind();
			frameTimer.end(Timer.UPLOAD_UI);
		}

		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, function);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, function);
	}
}
