package rs117.hd.opengl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import net.runelite.api.*;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.ThreadPool;

import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL21C.*;

public class AsyncInterfaceCopy {

	private FrameTimer timer;
	private ThreadPool threadPool;

	private final Semaphore completionSemaphore = new Semaphore(0);

	private IntBuffer mappedBuffer;
	private int[] pixels;
	private int interfacePho;
	private int interfaceTexture;
	private int width;
	private int height;
	private int inflightTasks;

	public AsyncInterfaceCopy(FrameTimer timer, ThreadPool threadPool) {
		this.timer = timer;
		this.threadPool = threadPool;
	}

	// Class used over lambda to reduce garbage generated each frame
	public class SlicedPutTask implements Runnable {
		public int pixelMin;
		public int pixelMax;

		public SlicedPutTask() {};

		@Override
		public void run() {
			long address = MemoryUtil.memAddress(mappedBuffer) + ((long) pixelMin * Integer.BYTES);
			for (int pixelIdx = pixelMin; pixelIdx < pixelMax; pixelIdx++) {
				MemoryUtil.memPutInt(address, pixels[pixelIdx]);
				address += Integer.BYTES;
			}
			completionSemaphore.release();
		}
	}

	public void prepare(BufferProvider provider, int interfacePho, int interfaceTex) {
		if (inflightTasks > 0) {
			return;
		}

		timer.begin(Timer.COPY_UI);
		this.interfacePho = interfacePho;
		this.interfaceTexture = interfaceTex;

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, this.interfacePho);
		ByteBuffer mappedBuffer = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

		if (mappedBuffer == null) {
			timer.end(Timer.COPY_UI);
			return;
		}

		int maxWorkerCount = threadPool.getFixedThreadCount();
		this.mappedBuffer = mappedBuffer.asIntBuffer();
		this.pixels = provider.getPixels();
		this.width = provider.getWidth();
		this.height = provider.getHeight();
		this.inflightTasks = ((width * height) / maxWorkerCount) == 0 ? 1 : maxWorkerCount;

		final int workerPixelCount = (width * height) / inflightTasks;
		for (int i = 0, offset = 0; i < inflightTasks; i++, offset += workerPixelCount) {
			SlicedPutTask work = threadPool.obtainTask(SlicedPutTask.class, this);
			work.pixelMin = offset;
			work.pixelMax = offset + workerPixelCount;
			threadPool.submitWork(work);
		}
		timer.end(Timer.COPY_UI);
	}

	public void complete() {
		// Check if there are any workers doing anything
		if (inflightTasks <= 0) {
			return;
		}

		timer.begin(Timer.COPY_UI);
		try {
			// Timeout after couple ms, shouldn't take more than a millisecond in the worst case
			completionSemaphore.tryAcquire(inflightTasks, 12, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		timer.end(Timer.COPY_UI);

		timer.begin(Timer.UPLOAD_UI);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePho);
		glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, 0);
		timer.end(Timer.UPLOAD_UI);

		mappedBuffer = null;
		pixels = null;
		inflightTasks = -1;
	}
}