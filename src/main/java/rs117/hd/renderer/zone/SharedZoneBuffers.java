package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLShaderStorage;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.renderer.zone.Zone.MAX_FACE_OFFSET;
import static rs117.hd.renderer.zone.Zone.MAX_ZONE_ID;
import static rs117.hd.renderer.zone.Zone.MODEL_DATA_NUM_INTS;
import static rs117.hd.renderer.zone.Zone.STATIC_FACE_NUM_INTS;
import static rs117.hd.renderer.zone.Zone.ZONE_DATA_NUM_BYTES;
import static rs117.hd.renderer.zone.Zone.ZONE_DATA_NUM_INTS;
import static rs117.hd.renderer.zone.Zone.ZONE_VERTEX_NUM_BYTES;
import static rs117.hd.renderer.zone.Zone.ZONE_VERTEX_NUM_INTS;
import static rs117.hd.renderer.zone.ZoneRenderer.FRAMES_IN_FLIGHT;
import static rs117.hd.renderer.zone.ZoneRenderer.SHADER_STORAGE_BUFFER_MODEL_DATA;
import static rs117.hd.renderer.zone.ZoneRenderer.SHADER_STORAGE_BUFFER_TEXTURED_FACES;
import static rs117.hd.renderer.zone.ZoneRenderer.SHADER_STORAGE_BUFFER_ZONE_DATA;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_MODEL_DATA;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_ZONE_DATA;
import static rs117.hd.utils.MathUtils.*;

/** Shared geometry buffers and zone metadata for one world view. */
@Slf4j
public class SharedZoneBuffers {
	private static final long FENCE_TIMEOUT_NANOS = 100_000_000L;
	private static final int MIN_WASTE_INTS = 1 << 20;

	static final int KIND_OPAQUE = 0;
	static final int KIND_ALPHA = 1;
	static final int KIND_FACES = 2;
	static final int KIND_MODELS = 3;
	static final int KIND_COUNT = 4;

	// The uploader's estimate can miss geometry emitted by repeated roof-level passes. Reserve enough room
	// for the maximum four passes, plus a minimum cushion for small zones, so a zone cannot write into its
	// neighbor's block.
	private static final int MIN_BLOCK_HEADROOM = 1 << 12;
	private static final int BLOCK_HEADROOM_MULTIPLIER = 3;

	// Id 0 is reserved for the dynamic model buffers, which address their faces absolutely and so need a
	// table entry with zero bases.
	static final int DYNAMIC_ZONE_ID = 0;

	private final BlockAllocator[] allocators = new BlockAllocator[KIND_COUNT];

	final GLBuffer vboO;
	final GLBuffer vboA;
	final GLShaderStorage tboF;
	final GLShaderStorage tboM;
	final GLShaderStorage tboZ;

	int glVao, glVaoA;

	// CPU mirror of the shader-visible zone table, with the dirty range [zoneDataLo, zoneDataHi) in zone ids.
	private final IntBuffer zoneData;
	private int zoneDataLo = Integer.MAX_VALUE;
	private int zoneDataHi = Integer.MIN_VALUE;

	// Zone ids are handed out from here and returned once no in-flight draw can reference them anymore, so a
	// stale face index can never resolve to a different zone's data.
	private int[] freeZoneIds = new int[32];
	private int numFreeZoneIds;
	private final List<int[]> deferredZoneIds = new ArrayList<>();
	private int nextZoneId = DYNAMIC_ZONE_ID + 1;

	// Scratch state for compact()
	private final List<Zone> live = new ArrayList<>();
	private long[] srcOffsets = new long[64];
	private long[] dstOffsets = new long[64];
	private long[] numBytes = new long[64];

	SharedZoneBuffers() {
		vboO = new GLBuffer("Zone::VBO::Opaque", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
		vboA = new GLBuffer("Zone::VBO::Alpha", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
		tboF = new GLShaderStorage("Zone::TexturedFaces", GL_STATIC_DRAW);
		tboM = new GLShaderStorage("Zone::ModelData", GL_STATIC_DRAW);
		tboZ = new GLShaderStorage("Zone::ZoneData", GL_DYNAMIC_DRAW);

		zoneData = MemoryUtil.memAllocInt(MAX_ZONE_ID * ZONE_DATA_NUM_INTS);

		allocators[KIND_OPAQUE] = new BlockAllocator("Opaque", vboO, ZONE_VERTEX_NUM_INTS);
		allocators[KIND_ALPHA] = new BlockAllocator("Alpha", vboA, ZONE_VERTEX_NUM_INTS);
		allocators[KIND_FACES] = new BlockAllocator("Faces", tboF, STATIC_FACE_NUM_INTS);
		allocators[KIND_MODELS] = new BlockAllocator("Models", tboM, MODEL_DATA_NUM_INTS);
	}

	void initialize() {
		vboO.initialize();
		vboA.initialize();
		tboF.initialize(0, SHADER_STORAGE_BUFFER_TEXTURED_FACES, TEXTURE_UNIT_TEXTURED_FACES);
		tboM.initialize(0, SHADER_STORAGE_BUFFER_MODEL_DATA, TEXTURE_UNIT_MODEL_DATA);

		// Keep the table allocation stable while draws are in flight.
		tboZ.initialize((long) MAX_ZONE_ID * ZONE_DATA_NUM_BYTES, SHADER_STORAGE_BUFFER_ZONE_DATA, TEXTURE_UNIT_ZONE_DATA);

		for (BlockAllocator alloc : allocators)
			alloc.initialize();

		glVao = glGenVertexArrays();
		setupVao(glVao, vboO.id);
		glVaoA = glGenVertexArrays();
		setupVao(glVaoA, vboA.id);

		checkGLErrors();
	}

	synchronized void destroy() {
		if (glVao != 0) {
			glDeleteVertexArrays(glVao);
			glVao = 0;
		}
		if (glVaoA != 0) {
			glDeleteVertexArrays(glVaoA);
			glVaoA = 0;
		}

		vboO.destroy();
		vboA.destroy();
		tboF.destroy();
		tboM.destroy();
		tboZ.destroy();

		for (BlockAllocator alloc : allocators)
			alloc.reset();

		live.clear();
		zoneDataLo = Integer.MAX_VALUE;
		zoneDataHi = Integer.MIN_VALUE;
		deferredZoneIds.clear();
		numFreeZoneIds = 0;
		nextZoneId = DYNAMIC_ZONE_ID + 1;
		MemoryUtil.memFree(zoneData);
	}

	private void setupVao(int vao, int buffer) {
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, buffer);

		// Position
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 4, GL_SHORT, false, ZONE_VERTEX_NUM_BYTES, 0);

		// UVs (u, v, w)
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 4, GL_HALF_FLOAT, false, ZONE_VERTEX_NUM_BYTES, 14);

		// Normals + modelIdx (nx, ny, nz, modelIdx)
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 4, GL_SHORT, false, ZONE_VERTEX_NUM_BYTES, 6);

		// TextureFaceIdx, which also carries the zone id the per-zone data is looked up by
		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 1, GL_INT, ZONE_VERTEX_NUM_BYTES, 20);

		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	// ----------------------------------------------------------------------------------------------------
	// Allocation
	// ----------------------------------------------------------------------------------------------------

	/**
	 * Reserves blocks for the zone using CPU-side allocator state only. GL storage is grown on the client
	 * thread when the staged zone is committed.
	 */
	synchronized void allocate(Zone zone) {
		zone.zoneId = acquireZoneId();

		for (int kind = 0; kind < KIND_COUNT; ++kind) {
			BlockAllocator alloc = allocators[kind];
			int estimate = zoneSize(zone, kind);
			int size = kind == KIND_FACES ? faceBlockSize(estimate) : alloc.alignUp(blockSize(estimate));
			setZoneOffset(zone, kind, alloc.alloc(size));
			zone.blockSizes[kind] = size;
			if (size > 0)
				alloc.add(zone);
		}
	}

	/** Grows the zone's GL storage and uploads whatever it staged. */
	synchronized void commit(Zone zone) {
		for (int kind = 0; kind < KIND_COUNT; ++kind) {
			GLBuffer buffer = allocators[kind].buffer;
			int offset = zoneOffset(zone, kind);
			int blockSize = zone.blockSizes[kind];

			if (blockSize > 0) {
				int prevId = buffer.id;
				buffer.ensureCapacity((long) offset * Integer.BYTES, (long) blockSize * Integer.BYTES);
				if (buffer.id != prevId)
					rebindVao(kind);
			}

			GpuIntBuffer view = zone.staging[kind];
			if (view == null)
				continue;

			IntBuffer staged = view.getBuffer();
			int written = staged.position();
			if (written <= 0)
				continue;

			if (written > blockSize)
				throw new IllegalStateException(
					"Zone " + zone.zoneId + " wrote " + written + " ints to its " + allocators[kind].name
					+ " block of " + blockSize + " ints"
				);

			int estimate = zoneSize(zone, kind);
			if (written > estimate)
				log.debug("Zone {} overran its {} block estimate, {} of {} ints", zone.zoneId, allocators[kind].name, written, estimate);

			IntBuffer block = staged.duplicate();
			block.position(0);
			block.limit(written);
			buffer.upload(block, (long) offset * Integer.BYTES);
		}
	}

	/** Rebinds a shared VAO after its vertex buffer grows and receives a new GL id. */
	private void rebindVao(int kind) {
		int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
		if (kind == KIND_OPAQUE)
			setupVao(glVao, vboO.id);
		else if (kind == KIND_ALPHA)
			setupVao(glVaoA, vboA.id);
		glBindVertexArray(prevVao);
	}

	/** Returns the zone's blocks to the pools, once no in-flight draw can reference them anymore. */
	synchronized void free(Zone zone, int frame) {
		if (zone.zoneId < 0)
			return;

		for (int kind = 0; kind < KIND_COUNT; ++kind)
			allocators[kind].free(zone, zoneOffset(zone, kind), zone.blockSizes[kind], frame);

		releaseZoneId(zone.zoneId, frame);
		zone.zoneId = -1;
		zone.vertexBaseO = 0;
		zone.vertexBaseA = 0;
		zone.faceBase = 0;
		zone.modelBase = 0;
		Arrays.fill(zone.blockSizes, 0);
	}

	/** Block sizes in ints, matching what the uploader writes per unit. */
	static int zoneSize(Zone zone, int kind) {
		long size;
		switch (kind) {
			case KIND_OPAQUE: size = (long) zone.sizeO * 3 * ZONE_VERTEX_NUM_INTS; break;
			case KIND_ALPHA: size = (long) zone.sizeA * 3 * ZONE_VERTEX_NUM_INTS; break;
			case KIND_FACES: size = (long) zone.sizeF * STATIC_FACE_NUM_INTS; break;
			case KIND_MODELS: size = (long) zone.sizeM * MODEL_DATA_NUM_INTS; break;
			default: throw new IllegalArgumentException("Unknown zone buffer kind: " + kind);
		}

		if (size < 0 || size > Integer.MAX_VALUE)
			throw new IllegalStateException("Zone " + zone.zoneId + " estimated buffer size exceeds integer range: " + size);
		return (int) size;
	}

	/** Adds headroom to the estimate so an upload cannot spill into the next zone's block. */
	static int blockSize(int size) {
		if (size <= 0)
			return 0;
		long headroom = max(MIN_BLOCK_HEADROOM, (long) size * BLOCK_HEADROOM_MULTIPLIER);
		long blockSize = size + headroom;
		if (blockSize > Integer.MAX_VALUE)
			throw new IllegalStateException("Zone block size exceeds the maximum addressable buffer size: " + blockSize);
		return (int) blockSize;
	}

	static int faceBlockSize(int estimatedSize) {
		int maxAlignedFaceSize = MAX_FACE_OFFSET / STATIC_FACE_NUM_INTS * STATIC_FACE_NUM_INTS;
		if (estimatedSize > maxAlignedFaceSize)
			throw new IllegalStateException(
				"Zone face data estimate requires " + estimatedSize + " ints, past the " + maxAlignedFaceSize
				+ " a face index can address."
			);

		return min(blockSize(estimatedSize), maxAlignedFaceSize);
	}

	static int zoneOffset(Zone zone, int kind) {
		switch (kind) {
			case KIND_OPAQUE: return zone.vertexBaseO;
			case KIND_ALPHA: return zone.vertexBaseA;
			case KIND_FACES: return zone.faceBase;
			default: return zone.modelBase;
		}
	}

	static void setZoneOffset(Zone zone, int kind, int offset) {
		switch (kind) {
			case KIND_OPAQUE: zone.vertexBaseO = offset; break;
			case KIND_ALPHA: zone.vertexBaseA = offset; break;
			case KIND_FACES: zone.faceBase = offset; break;
			default: zone.modelBase = offset; break;
		}
	}

	/**
	 * Returns the model block's zero-based record offset. Static vertex model indices are one-based local
	 * indices, so adding this offset preserves the one-based index expected by the shader reader.
	 */
	static int modelBase(Zone zone) {
		return zone.modelBase / MODEL_DATA_NUM_INTS;
	}

	// ----------------------------------------------------------------------------------------------------
	// Zone table
	// ----------------------------------------------------------------------------------------------------

	/** Hands out the id the zone's vertices address it by. */
	private int acquireZoneId() {
		if (numFreeZoneIds > 0)
			return freeZoneIds[--numFreeZoneIds];
		if (nextZoneId >= MAX_ZONE_ID)
			throw new IllegalStateException("Out of zone ids, " + MAX_ZONE_ID + " zones are already live");
		return nextZoneId++;
	}

	/** Delays reuse until prior draws can no longer reference this id. */
	private void releaseZoneId(int zoneId, int frame) {
		deferredZoneIds.add(new int[] { zoneId, frame });
	}

	private void ageZoneIds(int frame) {
		for (Iterator<int[]> it = deferredZoneIds.iterator(); it.hasNext();) {
			int[] entry = it.next();
			if (frame - entry[1] < FRAMES_IN_FLIGHT)
				continue;

			it.remove();
			int zoneId = entry[0];

			// Clear stale metadata before reusing the id.
			for (int i = 0; i < ZONE_DATA_NUM_INTS; ++i)
				zoneData.put(zoneId * ZONE_DATA_NUM_INTS + i, 0);
			markZoneDirty(zoneId);

			if (numFreeZoneIds == freeZoneIds.length)
				freeZoneIds = Arrays.copyOf(freeZoneIds, freeZoneIds.length * 2);
			freeZoneIds[numFreeZoneIds++] = zoneId;
		}
	}

	private void markZoneDirty(int zoneId) {
		zoneDataLo = min(zoneDataLo, zoneId);
		zoneDataHi = max(zoneDataHi, zoneId + 1);
	}

	synchronized void setDynamicZoneData(WorldViewContext ctx, int baseX, int baseZ) {
		zoneData
			.position(DYNAMIC_ZONE_ID * ZONE_DATA_NUM_INTS)
			.put(ctx.uboWorldViewStruct != null ? ctx.uboWorldViewStruct.worldViewIdx + 1 : 0)
			.put(baseX)
			.put(baseZ)
			.put(Float.floatToIntBits(-1f))
			.put(0)
			.put(0);
		markZoneDirty(DYNAMIC_ZONE_ID);
	}

	synchronized void setZoneData(Zone zone, int worldViewIdx, int baseX, int baseZ, float fade) {
		if (zone.zoneId < 0)
			return;

		zoneData
			.position(zone.zoneId * ZONE_DATA_NUM_INTS)
			.put(worldViewIdx)
			.put(baseX)
			.put(baseZ)
			.put(Float.floatToIntBits(fade))
			.put(zone.faceBase)
			.put(modelBase(zone));
		markZoneDirty(zone.zoneId);
	}

	/** Re-publishes the face/model bases after compaction moved a zone's blocks. */
	private void refreshZoneData(Zone zone) {
		if (zone.zoneId < 0)
			return;

		zoneData
			.position(zone.zoneId * ZONE_DATA_NUM_INTS + 4)
			.put(zone.faceBase)
			.put(modelBase(zone));
		markZoneDirty(zone.zoneId);
	}

	/** Uploads any zone data that changed since the last flush. */
	synchronized void flushZoneData() {
		if (zoneDataHi <= zoneDataLo)
			return;

		final int lo = zoneDataLo * ZONE_DATA_NUM_INTS;
		final int hi = zoneDataHi * ZONE_DATA_NUM_INTS;
		IntBuffer block = zoneData.duplicate();
		block.position(lo);
		block.limit(hi);
		tboZ.upload(block, (long) lo * Integer.BYTES);

		zoneDataLo = Integer.MAX_VALUE;
		zoneDataHi = Integer.MIN_VALUE;
	}

	// ----------------------------------------------------------------------------------------------------
	// Defragmentation
	// ----------------------------------------------------------------------------------------------------

	/** Whether enough of the shared buffers is stranded in holes to be worth compacting. */
	synchronized boolean isFragmented() {
		for (BlockAllocator alloc : allocators)
			if (alloc.waste() >= MIN_WASTE_INTS)
				return true;
		return false;
	}

	/** Returns the number of bytes that could be reclaimed by compacting. */
	synchronized long wastedBytes() {
		long ints = 0;
		for (BlockAllocator alloc : allocators)
			ints += alloc.waste();
		return ints * Integer.BYTES;
	}

	/** Compacts shared buffers after uploads finish and prior GPU draws complete. */
	synchronized boolean defragment() {
		// Wait for any in-flight draws to finish reading the buffers before overwriting them
		long fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		if (fence == 0) {
			log.warn("Skipping zone buffer defragmentation, failed to create GPU fence");
			return false;
		}

		int status = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_TIMEOUT_NANOS);
		glDeleteSync(fence);
		if (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED) {
			log.debug("Skipping zone buffer defragmentation, GPU not ready (status {})", status);
			return false;
		}

		final long reclaimed = wastedBytes();

		boolean moved = false;
		for (int kind = 0; kind < KIND_COUNT; ++kind)
			moved |= compact(kind);

		if (moved) {
			log.debug("Defragmented zone buffers, reclaiming {} KiB", reclaimed / KiB);

			// The zone ids are unaffected, but the bases the table hands out to the shaders are not
			flushZoneData();
		} else if (reclaimed > 0) {
			log.debug("Reclaimed {} KiB of trailing zone buffer space", reclaimed / KiB);
		}

		live.clear();
		checkGLErrors();
		return moved;
	}

	/** Packs the live blocks toward the start of a buffer. */
	private boolean compact(int kind) {
		BlockAllocator alloc = allocators[kind];

		live.clear();
		for (Zone zone : alloc.zones)
			if (zone.zoneId >= 0)
				live.add(zone);

		final int count = live.size();
		if (count == 0) {
			// Nothing is left to draw, so the whole buffer is free again
			alloc.rewind(0);
			return false;
		}

		live.sort(Comparator.comparingInt(zone -> zoneOffset(zone, kind)));
		ensureMoveCapacity(count);

		int end = 0;
		boolean moved = false;
		for (int i = 0; i < count; ++i) {
			Zone zone = live.get(i);
			int oldOffset = zoneOffset(zone, kind);
			int size = zone.blockSizes[kind];
			int newOffset = end;
			end += alloc.alignUp(size);

			if (newOffset != oldOffset) {
				moved = true;
				setZoneOffset(zone, kind, newOffset);
				if (kind == KIND_OPAQUE)
					zone.shiftOpaqueRanges(oldOffset, newOffset);
				else if (kind == KIND_ALPHA)
					zone.shiftAlphaRanges(oldOffset, newOffset);
				else
					refreshZoneData(zone);
			}

			srcOffsets[i] = (long) oldOffset * Integer.BYTES;
			dstOffsets[i] = (long) newOffset * Integer.BYTES;
			numBytes[i] = (long) size * Integer.BYTES;
		}

		if (moved) {
			// Blocks can overlap their own destinations, and glCopyBufferSubData forbids overlapping ranges
			// within one buffer, so stage the whole used range in a scratch buffer and copy back out of it.
			GLBuffer scratch = alloc.scratch;
			long used = (long) alloc.end() * Integer.BYTES;
			scratch.ensureCapacity(used);
			alloc.buffer.copyMultiTo(scratch, new long[] { 0 }, new long[] { 0 }, new long[] { used }, 1);
			scratch.copyMultiTo(alloc.buffer, srcOffsets, dstOffsets, numBytes, count);
		}

		alloc.rewind(end);
		return moved;
	}

	private void ensureMoveCapacity(int count) {
		if (srcOffsets.length < count) {
			int capacity = max(count, srcOffsets.length * 2);
			srcOffsets = new long[capacity];
			dstOffsets = new long[capacity];
			numBytes = new long[capacity];
		}
	}

	/** Ages the deferred block and zone id frees, returning anything that's been in flight long enough to the pool. */
	synchronized void age(int frame) {
		for (BlockAllocator alloc : allocators)
			alloc.age(frame);
		ageZoneIds(frame);
	}

	@Override
	public synchronized String toString() {
		StringBuilder sb = new StringBuilder("Zone buffers:\n");
		for (BlockAllocator alloc : allocators)
			sb.append("  ").append(alloc).append('\n');
		return sb.toString();
	}

	/**
	 * Sub allocator for a single shared buffer. Blocks are handed out from a bump pointer and a free list,
	 * with adjacent free blocks merged on release.
	 */
	@Slf4j
	static final class BlockAllocator {
		final String name;
		final GLBuffer buffer;
		final GLBuffer scratch;
		final Set<Zone> zones = new LinkedHashSet<>();

		private final int align;
		private final TreeMap<Integer, Integer> free = new TreeMap<>(); // offset -> size
		private final List<int[]> deferred = new ArrayList<>(); // { offset, size, frame }
		private int end, live;

		BlockAllocator(String name, GLBuffer buffer, int align) {
			this.name = name;
			this.buffer = buffer;
			this.align = max(1, align);
			scratch = new GLBuffer(name + "::Compact", buffer.target, GL_STATIC_DRAW);
		}

		void initialize() {
			scratch.initialize();
		}

		int alloc(int size) {
			if (size <= 0)
				return 0;

			size = alignUp(size);

			// First fit from the free list
			for (Iterator<Map.Entry<Integer, Integer>> it = free.entrySet().iterator(); it.hasNext();) {
				Map.Entry<Integer, Integer> entry = it.next();
				int offset = entry.getKey();
				int freeSize = entry.getValue();
				if (freeSize < size)
					continue;

				it.remove();
				if (freeSize > size)
					free.put(offset + size, freeSize - size);

				live += size;
				return offset;
			}

			// Otherwise bump the end pointer
			if ((long) end + size > Integer.MAX_VALUE)
				throw new IllegalStateException(name + " buffer exceeds the maximum addressable size");
			int offset = end;
			end += size;
			live += size;
			return offset;
		}

		void add(Zone zone) {
			zones.add(zone);
		}

		void free(Zone zone, int offset, int size, int frame) {
			if (!zones.remove(zone) || size <= 0)
				return;

			size = alignUp(size);
			live -= size;
			deferred.add(new int[] { offset, size, frame });
		}

		void age(int frame) {
			for (Iterator<int[]> it = deferred.iterator(); it.hasNext();) {
				int[] block = it.next();
				if (frame - block[2] < FRAMES_IN_FLIGHT)
					continue;

				it.remove();
				release(block[0], block[1]);
			}
		}

		/** Returns a block to the pool, merging it with free neighbors, or pulling the end pointer back if it's the tail. */
		private void release(int offset, int size) {
			Map.Entry<Integer, Integer> prev = free.lowerEntry(offset);
			if (prev != null && prev.getKey() + prev.getValue() == offset) {
				offset = prev.getKey();
				size += prev.getValue();
				free.remove(offset);
			}

			Integer next = free.remove(offset + size);
			if (next != null)
				size += next;

			if (offset + size == end)
				end = offset;
			else
				free.put(offset, size);
		}

		void rewind(int newEnd) {
			end = newEnd;
			live = newEnd;
			free.clear();
			deferred.clear();
		}

		int alignUp(int size) {
			long aligned = ((long) size + align - 1) / align * align;
			if (aligned > Integer.MAX_VALUE)
				throw new IllegalStateException(name + " block exceeds the maximum addressable size");
			return (int) aligned;
		}

		int end() {
			return end;
		}

		/** Allocated ints that are no longer in use, but still occupy the buffer. */
		int waste() {
			return end - live;
		}

		void reset() {
			free.clear();
			deferred.clear();
			zones.clear();
			scratch.destroy();
			end = live = 0;
		}

		@Override
		public String toString() {
			return String.format("%-8s %5.1f MiB live, %5.1f MiB free, %5.1f MiB total",
				name, live * 4 / 1048576.0, (end - live) * 4 / 1048576.0, end * 4 / 1048576.0);
		}
	}
}