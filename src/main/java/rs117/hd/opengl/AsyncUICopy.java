package rs117.hd.opengl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.threading.JobUtil;
import rs117.hd.utils.threading.SingleJob;

import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL21C.*;

@Slf4j
public class AsyncUICopy extends SingleJob {
	@Inject
	private Client client;

	@Inject
	private FrameTimer timer;

	private IntBuffer mappedBuffer;
	private int[] pixels;
	private int interfacePbo;
	private int interfaceTexture;
	private int width;
	private int height;

	@Override
	protected void execute() {
		long time = System.nanoTime();
		mappedBuffer.put(pixels, 0, width * height);
		time = System.nanoTime() - time;
		timer.add(Timer.COPY_UI, time);
	}

	public void prepare(int interfacePbo, int interfaceTex) {
		// Ensure there isn't already another UI copy in progress
		if (mappedBuffer != null)
			return;

		timer.begin(Timer.MAP_UI_BUFFER);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		ByteBuffer buffer = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		timer.end(Timer.MAP_UI_BUFFER);
		if (buffer == null)
			return;

		this.interfacePbo = interfacePbo;
		this.interfaceTexture = interfaceTex;
		this.mappedBuffer = buffer.asIntBuffer();

		var provider = client.getBufferProvider();
		this.pixels = provider.getPixels();
		this.width = provider.getWidth();
		this.height = provider.getHeight();

		JobUtil.submit(this);
	}

	@Override
	protected void onComplete() {
		if (mappedBuffer == null)
			return;

		timer.begin(Timer.UPLOAD_UI);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		timer.end(Timer.UPLOAD_UI);

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, 0);

		mappedBuffer = null;
		pixels = null;
	}

	@Override
	protected boolean shouldCache() { return false; }
}
