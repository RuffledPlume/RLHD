package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import lombok.extern.slf4j.Slf4j;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public final class VertexWriteCache {
	private IntBuffer outputBuffer;

	private final int maxCapacity;
	private int[] stagingBuffer;
	private int stagingPosition;

	public VertexWriteCache(int initialCapacity, int maxCapacity) {
		this.maxCapacity = maxCapacity;
		stagingBuffer = new int[initialCapacity];
	}

	public void setOutputBuffer(IntBuffer outputBuffer) {
		this.outputBuffer = outputBuffer;
		stagingPosition = 0;
	}

	private void flushAndGrow() {
		// Flush buffer and then resize to avoid flushing mid put
		flush();

		if (stagingBuffer.length < maxCapacity)
			stagingBuffer = new int[min(stagingBuffer.length * 2, maxCapacity)];
	}

	public void ensureFace(int faceCount) {
		if (stagingPosition + (9 * faceCount) > stagingBuffer.length)
			flushAndGrow();
	}

	private void put(int value) {
		stagingBuffer[stagingPosition++] = value;
	}

	public int putFace(
		int alphaBiasHslA, int alphaBiasHslB, int alphaBiasHslC,
		int materialDataA, int materialDataB, int materialDataC,
		int terrainDataA, int terrainDataB, int terrainDataC
	) {
		final int textureFaceIdx = (outputBuffer.position() + stagingPosition) / 3;

		put(alphaBiasHslA);
		put(alphaBiasHslB);
		put(alphaBiasHslC);

		put(materialDataA);
		put(materialDataB);
		put(materialDataC);

		put(terrainDataA);
		put(terrainDataB);
		put(terrainDataC);

		return textureFaceIdx;
	}

	public void ensureVertex(int vertexCount) {
		if (stagingPosition + (7 * vertexCount) > stagingBuffer.length)
			flushAndGrow();
	}

	public void putVertex(
		float x, float y, float z,
		int u, int v, int w,
		int nx, int ny, int nz,
		int textureFaceIdx
	) {
		putVertex(
			Float.floatToRawIntBits(x), Float.floatToRawIntBits(y), Float.floatToRawIntBits(z),
			u, v, w,
			nx, ny, nz,
			textureFaceIdx);
	}

	public void putVertex(
		int x, int y, int z,
		int u, int v, int w,
		int nx, int ny, int nz,
		int textureFaceIdx
	) {
		put( x);
		put( y);
		put( z);
		put( v << 16 | u);
		put( (nx & 0xFFFF) << 16 | w);
		put( (nz & 0xFFFF) << 16 | ny & 0xFFFF);
		put( textureFaceIdx);
	}

	public void flush() {
		if (stagingPosition == 0 || outputBuffer == null)
			return;

		outputBuffer.put(stagingBuffer, 0, stagingPosition);
		stagingPosition = 0;
	}

	public static class Collection {
		private static final int MAX_CAPACITY = (int) (MiB / Integer.BYTES);
		private static final int INITIAL_CAPACITY = (int) (32 * KiB / Integer.BYTES);

		public final VertexWriteCache opaque = new VertexWriteCache(INITIAL_CAPACITY, MAX_CAPACITY);
		public final VertexWriteCache alpha = new VertexWriteCache(INITIAL_CAPACITY, MAX_CAPACITY);
		public final VertexWriteCache opaqueTex = new VertexWriteCache(INITIAL_CAPACITY, MAX_CAPACITY);
		public final VertexWriteCache alphaTex = new VertexWriteCache(INITIAL_CAPACITY, MAX_CAPACITY);
		public boolean useAlphaBuffer;

		public void setOutputBuffers(IntBuffer opaque, IntBuffer alpha, IntBuffer opaqueTex, IntBuffer alphaTex) {
			this.opaque.setOutputBuffer(opaque);
			this.opaqueTex.setOutputBuffer(opaqueTex);
			useAlphaBuffer = alpha != opaque && alphaTex != opaqueTex;
			if (useAlphaBuffer) {
				this.alpha.setOutputBuffer(alpha);
				this.alphaTex.setOutputBuffer(alphaTex);
			} else {
				this.alpha.setOutputBuffer(null);
				this.alphaTex.setOutputBuffer(null);
			}
		}

		public void flush() {
			opaque.flush();
			alpha.flush();
			opaqueTex.flush();
			alphaTex.flush();
		}
	}
}
