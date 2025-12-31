package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public final class VertexWriteCache {
	private static final int INT_BYTES   = Integer.BYTES;
	private static final int FLOAT_BYTES = Float.BYTES;

	// Logical layout (must match shaders)
	private static final int FACE_INTS   = 9;   // 9 ints = 36 bytes
	private static final int FACE_BYTES  = FACE_INTS * INT_BYTES;

	private static final int VERTEX_INTS  = 7;   // 7 ints = 28 bytes
	private static final int VERTEX_BYTES = VERTEX_INTS * INT_BYTES;

	private final boolean isFaceCache;

	private IntBuffer outputBuffer;
	private long outputBaseAddr;

	private long stagingCapacityBytes;

	private long stagingBaseAddr;
	private long stagingPtr;

	private int writtenInts; // exact number of ints written

	public VertexWriteCache(int initialCapacityInts, boolean isFaceCache) {
		this.stagingCapacityBytes = (long)initialCapacityInts * INT_BYTES;
		this.isFaceCache = isFaceCache;

		this.stagingBaseAddr =
			MemoryUtil.nmemAlloc(stagingCapacityBytes);
		this.stagingPtr = stagingBaseAddr;
	}

	public void free() {
		if (stagingBaseAddr != MemoryUtil.NULL) {
			MemoryUtil.nmemFree(stagingBaseAddr);
			stagingBaseAddr = MemoryUtil.NULL;
		}
	}

	public void setOutputBuffer(IntBuffer outputBuffer, int faceCount) {
		if (outputBuffer != null && !outputBuffer.isDirect())
			throw new IllegalArgumentException("outputBuffer must be direct");

		this.outputBuffer = outputBuffer;
		this.outputBaseAddr =
			outputBuffer != null ? MemoryUtil.memAddress(outputBuffer, 0) : 0L;

		if(outputBuffer != null) {
			if (isFaceCache) {
				ensureCapacity((long)faceCount * FACE_BYTES);
			} else {
				ensureCapacity((long)faceCount * 3 * VERTEX_BYTES);
			}
		}

		stagingPtr = stagingBaseAddr;
		writtenInts = 0;
	}

	private void ensureCapacity(long requiredBytes) {
		if(requiredBytes < stagingCapacityBytes)
			return;

		stagingCapacityBytes = HDUtils.ceilPow2(requiredBytes * 2);

		stagingBaseAddr = MemoryUtil.nmemRealloc(
			stagingBaseAddr,
			stagingCapacityBytes
		);
		stagingPtr = stagingBaseAddr;
	}

	public int putFace(
		int alphaBiasHslA, int alphaBiasHslB, int alphaBiasHslC,
		int materialDataA, int materialDataB, int materialDataC,
		int terrainDataA, int terrainDataB, int terrainDataC
	) {
		assert isFaceCache;
		final int textureFaceIdx =
			(outputBuffer.position() + writtenInts) / 3;

		long p = stagingPtr;

		p = putLong2Ints(p, alphaBiasHslA, alphaBiasHslB);
		p = putLong2Ints(p, alphaBiasHslC, materialDataA);
		p = putLong2Ints(p, materialDataB, materialDataC);
		p = putLong2Ints(p, terrainDataA, terrainDataB);
		p = putInt(p, terrainDataC);

		stagingPtr = p;
		writtenInts += FACE_INTS;
		return textureFaceIdx;
	}

	public void putVertex(
		float x, float y, float z,
		int u, int v, int w,
		int nx, int ny, int nz,
		int textureFaceIdx
	) {
		assert !isFaceCache;
		long p = stagingPtr;

		p = putFloat(p, x);
		p = putFloat(p, y);
		p = putFloat(p, z);

		stagingPtr = putAdditionalVertexData(p, u, v, w, nx, ny, nz, textureFaceIdx);
		writtenInts += VERTEX_INTS;
	}

	public void putVertex(
		int x, int y, int z,
		int u, int v, int w,
		int nx, int ny, int nz,
		int textureFaceIdx
	) {
		assert !isFaceCache;
		long p = stagingPtr;

		p = putLong2Ints(p, x, y);
		p = putInt(p, z);

		stagingPtr = putAdditionalVertexData(p, u, v, w, nx, ny, nz, textureFaceIdx);
		writtenInts += VERTEX_INTS;
	}

	private static long putAdditionalVertexData(
		long p,
		int u, int v, int w,
		int nx, int ny, int nz,
		int textureFaceIdx
	) {
		// Vertex u,v,w,nx packed in one long (4 shorts)
		p = putLong4Shorts(p, (short)u, (short)v, (short)w, (short)nx);

		// ny,nz and face index packed in one long (2 ints)
		p = putLong2Ints(p, ((ny & 0xFFFF) << 16 | (nz & 0xFFFF)), textureFaceIdx);

		return p;
	}

	public void flush() {
		if (outputBuffer == null || stagingPtr == stagingBaseAddr)
			return;

		final long byteCount = stagingPtr - stagingBaseAddr;

		long dst =
			outputBaseAddr +
			(long) outputBuffer.position() * INT_BYTES;

		MemoryUtil.memCopy(stagingBaseAddr, dst, byteCount);

		outputBuffer.position(outputBuffer.position() + writtenInts);

		stagingPtr = stagingBaseAddr;
		writtenInts = 0;
	}

	private static long putFloat(long p, float a) {
		MemoryUtil.memPutFloat(p, a);
		return p + FLOAT_BYTES;
	}

	private static long putInt(long p, int a) {
		MemoryUtil.memPutInt(p, a);
		return p + INT_BYTES;
	}

	private static long putLong4Shorts(long p, short a, short b, short c, short d) {
		// Packs four 16-bit shorts into a 64-bit long
		long packed =
			((long)(d & 0xFFFF) << 48) |
			((long)(c & 0xFFFF) << 32) |
			((long)(b & 0xFFFF) << 16) |
			((long)(a & 0xFFFF));
		MemoryUtil.memPutLong(p, packed);
		return p + Long.BYTES;
	}

	private static long putLong2Ints(long p, int a, int b) {
		// Packs two 32-bit ints into a 64-bit long
		long packed = ((long)b << 32) | (a & 0xFFFFFFFFL);
		MemoryUtil.memPutLong(p, packed);
		return p + Long.BYTES;
	}

	public static class Collection {
		private static final int INITIAL_CAPACITY = (int) (32 * KiB / Integer.BYTES);

		public final VertexWriteCache opaque = new VertexWriteCache(INITIAL_CAPACITY, false);
		public final VertexWriteCache alpha = new VertexWriteCache(INITIAL_CAPACITY, false);
		public final VertexWriteCache opaqueTex = new VertexWriteCache(INITIAL_CAPACITY, true);
		public final VertexWriteCache alphaTex = new VertexWriteCache(INITIAL_CAPACITY, true);
		public boolean useAlphaBuffer;

		public void setOutputBuffers(IntBuffer opaque, IntBuffer alpha, IntBuffer opaqueTex, IntBuffer alphaTex, int faceCount) {
			this.opaque.setOutputBuffer(opaque, faceCount);
			this.opaqueTex.setOutputBuffer(opaqueTex, faceCount);
			useAlphaBuffer = alpha != opaque && alphaTex != opaqueTex;
			if (useAlphaBuffer) {
				this.alpha.setOutputBuffer(alpha, faceCount);
				this.alphaTex.setOutputBuffer(alphaTex, faceCount);
			} else {
				this.alpha.setOutputBuffer(null, faceCount);
				this.alphaTex.setOutputBuffer(null, faceCount);
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
