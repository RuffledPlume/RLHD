package rs117.hd.opengl.renderjobs;

import java.nio.ByteBuffer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.scene.SceneContext;

import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glTexImage2D;
import static org.lwjgl.opengl.GL11C.glTexSubImage2D;
import static org.lwjgl.opengl.GL12C.GL_BGRA;
import static org.lwjgl.opengl.GL12C.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;
import static org.lwjgl.opengl.GL15C.GL_WRITE_ONLY;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glMapBuffer;
import static org.lwjgl.opengl.GL15C.glUnmapBuffer;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;

@Slf4j
public class UploadUITexture extends RenderJob {
	private static final JobPool<UploadUITexture> POOL = new JobPool<>(UploadUITexture::new);

	private int pboUi;
	private int texUi;

	private int[] uiResolution;
	private int[] pixels;
	private int width;
	private int height;
	private boolean resize;

	@Override
	protected void doRenderWork(SceneDrawContext drawContext, SceneContext sceneContext) {
		if(resize) {
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboUi);
			glBufferData(GL_PIXEL_UNPACK_BUFFER, uiResolution[0] * uiResolution[1] * 4L, GL_STREAM_DRAW);
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

			glActiveTexture(TEXTURE_UNIT_UI);
			glBindTexture(GL_TEXTURE_2D, texUi);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, uiResolution[0], uiResolution[1], 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
		}

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboUi);
		ByteBuffer mappedBuffer = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);

		if (mappedBuffer == null) {
			log.error("Unable to map interface PBO. Skipping UI...");
		} else if (width > uiResolution[0] || height > uiResolution[1]) {
			log.error("UI texture resolution mismatch ({}x{} > {}). Skipping UI...", width, height, uiResolution);
		} else {
			mappedBuffer.asIntBuffer().put(pixels, 0, width * height);

			glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
			glActiveTexture(TEXTURE_UNIT_UI);
			glBindTexture(GL_TEXTURE_2D, texUi);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		}

		POOL.push(this);
	}

	public static void addToQueue(BufferProvider bufferProvider, int[] uiResolution, int pboUi, int texUi, boolean resize) {
		UploadUITexture job = POOL.pop();

		job.pixels = bufferProvider.getPixels();
		job.width = bufferProvider.getWidth();
		job.height = bufferProvider.getHeight();

		job.uiResolution = uiResolution;
		job.pboUi = pboUi;
		job.texUi = texUi;
		job.resize = resize;
		job.submit(SUBMIT_SERIAL);
	}
}
