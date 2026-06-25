package rs117.hd.utils;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.concurrent.LinkedTransferQueue;
import javax.inject.Inject;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.collections.PrimitiveIntArray;
import rs117.hd.utils.jobs.Job;

public final class UITileCopyJob extends Job {

	public static final int UI_TILE_SIZE = 64;

	@Inject
	private FrameTimer frameTimer;

	private UITileHashJob[][] tileJobs;
	private int[] pixels;
	private int[] uiTileHashes;
	private int uiWidth, uiHeight;
	private int tileCountX, tileCountY;
	private IntBuffer mapped;

	private final LinkedTransferQueue<UITileHashJob> completionQueue = new LinkedTransferQueue<>();

	public final PrimitiveIntArray dirtyTiles = new PrimitiveIntArray();

	@Override
	protected void onRun() {
		final long start = System.nanoTime();

		completionQueue.clear();
		dirtyTiles.reset();
		dirtyTiles.ensureCapacity(tileCountX * tileCountY * 2);

		// Queue all tile hash jobs
		for (int ty = 0; ty < tileCountY; ty++)
			for (int tx = 0; tx < tileCountX; tx++)
				tileJobs[ty][tx].queue();

		// Poll completion queue until all tiles are done
		final int totalTiles = tileCountX * tileCountY;
		int completed = 0;
		while (completed < totalTiles) {
			final UITileHashJob job;
			try {
				job = completionQueue.take();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}

			if (job.isDirty) {
				final int startX = job.tx * UI_TILE_SIZE;
				final int startY = job.ty * UI_TILE_SIZE;
				final int endX = Math.min(startX + UI_TILE_SIZE, uiWidth);
				final int endY = Math.min(startY + UI_TILE_SIZE, uiHeight);
				final int tileW = endX - startX;
				final int tileH = endY - startY;

				for (int y = startY; y < endY; y++) {
					mapped.put(pixels, y * uiWidth + startX, tileW);
					if (tileW < UI_TILE_SIZE)
						mapped.position(mapped.position() + (UI_TILE_SIZE - tileW));
				}

				if (tileH < UI_TILE_SIZE)
					mapped.position(mapped.position() + UI_TILE_SIZE * (UI_TILE_SIZE - tileH));

				dirtyTiles.put(job.tx);
				dirtyTiles.put(job.ty);
			}

			completed++;
		}

		frameTimer.add(Timer.COPY_UI_ASYNC, System.nanoTime() - start);
	}

	@RequiredArgsConstructor
	private class UITileHashJob extends Job {
		public final int tx, ty;
		public boolean isDirty;

		@Override
		protected void onRun() {
			final int[] pixels = UITileCopyJob.this.pixels;
			final int startX = tx * UI_TILE_SIZE;
			final int startY = ty * UI_TILE_SIZE;
			final int endX = Math.min(startX + UI_TILE_SIZE, uiWidth);
			final int endY = Math.min(startY + UI_TILE_SIZE, uiHeight);
			final int tileW = endX - startX;

			int hash = 0x811C9DC5;
			for (int y = startY; y < endY; y++) {
				final int rowBase = y * uiWidth + startX;
				final int rowEnd = rowBase + tileW;
				for (int i = rowBase; i < rowEnd; i++) {
					hash += pixels[i];
					hash ^= (hash << 13);
					hash ^= (hash >>> 7);
				}
			}

			final int idx = ty * tileCountX + tx;
			isDirty = hash != uiTileHashes[idx];

			completionQueue.add(this);

			if (isDirty)
				uiTileHashes[idx] = hash;
		}
	}

	public static long getPBOSize(int width, int height) {
		final int tileCountX = (width + UI_TILE_SIZE - 1) / UI_TILE_SIZE;
		final int tileCountY = (height + UI_TILE_SIZE - 1) / UI_TILE_SIZE;
		return (long) tileCountX * tileCountY * UI_TILE_SIZE * UI_TILE_SIZE * 4;
	}

	public void queue(BufferProvider bufferProvider, GLBuffer pbo, boolean resized) {
		uiWidth = bufferProvider.getWidth();
		uiHeight = bufferProvider.getHeight();
		pixels = bufferProvider.getPixels();
		mapped = pbo.mapped().intView();
		tileCountX = (uiWidth + UI_TILE_SIZE - 1) / UI_TILE_SIZE;
		tileCountY = (uiHeight + UI_TILE_SIZE - 1) / UI_TILE_SIZE;

		final int newTileCount = tileCountX * tileCountY;
		if (uiTileHashes == null || uiTileHashes.length != newTileCount) {
			uiTileHashes = new int[newTileCount];
			tileJobs = new UITileHashJob[tileCountY][tileCountX];
			for (int ty = 0; ty < tileCountY; ty++)
				for (int tx = 0; tx < tileCountX; tx++)
					tileJobs[ty][tx] = new UITileHashJob(tx, ty);
			resized = true;
		}

		completionQueue.clear();

		if (resized)
			Arrays.fill(uiTileHashes, 0xDEADBEEF);

		queue();
	}
}