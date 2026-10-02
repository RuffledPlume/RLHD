package rs117.hd.renderer.zone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import org.lwjgl.system.MemoryStack;
import rs117.hd.HdPlugin;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.Destructible;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLShaderStorage;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.Int2IntHashMap;
import rs117.hd.utils.collections.IntHashSet;
import rs117.hd.utils.collections.PooledArrayType;

import static net.runelite.api.Constants.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.SUPPORTS_INDIRECT_DRAW;
import static rs117.hd.HdPlugin.SUPPORTS_MULTI_INDIRECT_DRAW;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.renderer.zone.ZoneRenderer.eboAlpha;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.collections.Util.quickSort;

@Slf4j
public class Zone implements Destructible {
	static final IntHashSet EMPTY_ROOF = new IntHashSet();
	private static final ConcurrentPool<AlphaModel> ALPHA_MODEL_POOL = new ConcurrentPool<>(AlphaModel::new);

	public static final int DRAW_FADING_ONLY = 1;
	public static final int DRAW_INCLUDE_ROOFS = 2;
	public static final int DRAW_DEPTH_ONLY = 4;

	@Nullable
	WorldViewContext viewContext;

	// ZONE_VERTEX_FORMAT
	// position: int16 vec3
	// normal: int16 vec3
	// modelIdx: int16
	// uvw: float16 vec3
	// texturedFaceIdx: int32 (modelFace << 31 | zoneId << 20 | faceOffset)
	// Winding reversal is stored in the sign bit of modelIdx, whose vertex attribute is already signed.
	public static final int ZONE_VERTEX_NUM_BYTES = 24;
	public static final int ZONE_VERTEX_NUM_INTS = ZONE_VERTEX_NUM_BYTES / Integer.BYTES;
	public static final int TEXTURE_FACE_IS_MODEL = 1 << 31;
	public static final int MODEL_INDEX_WINDING_REVERSED = 1 << 15;
	public static final int MODEL_INDEX_MASK = MODEL_INDEX_WINDING_REVERSED - 1;

	// Packed into each face index to look up the zone table entry.
	public static final int TEXTURE_FACE_ZONE_ID_SHIFT = 20;
	public static final int TEXTURE_FACE_ZONE_ID_BITS = 11;
	public static final int TEXTURE_FACE_ZONE_ID_MASK = ((1 << TEXTURE_FACE_ZONE_ID_BITS) - 1) << TEXTURE_FACE_ZONE_ID_SHIFT;
	public static final int TEXTURE_FACE_OFFSET_MASK = (1 << TEXTURE_FACE_ZONE_ID_SHIFT) - 1;
	public static final int MAX_ZONE_ID = 1 << TEXTURE_FACE_ZONE_ID_BITS;
	public static final int MAX_FACE_OFFSET = TEXTURE_FACE_OFFSET_MASK + 1;

	// STATIC_FACE_FORMAT
	// alphaBiasHsl: int32 vec3
	// materialData: int32 vec3
	// terrainData: int32 vec3
	public static final int STATIC_FACE_NUM_BYTES = 36;
	public static final int STATIC_FACE_NUM_INTS = STATIC_FACE_NUM_BYTES / Integer.BYTES;

	// MODEL_FACE_FORMAT
	// packedHslAB: int32
	// packedHslCDepthAlpha: int32
	// materialData: int32
	public static final int MODEL_FACE_NUM_BYTES = 12;
	public static final int MODEL_FACE_NUM_INTS = MODEL_FACE_NUM_BYTES / Integer.BYTES;

	// MODEL_DATA_FORMAT
	// worldViewIdx: int32
	// flags: int32
	// position: float32 vec3
	// height: int32
	// fade: float32
	public static final int MODEL_DATA_NUM_BYTES = 28;
	public static final int MODEL_DATA_NUM_INTS = MODEL_DATA_NUM_BYTES / Integer.BYTES;
	public static final int MODEL_DATA_IS_STATIC = 1;

	// ZONE_DATA_FORMAT
	// worldViewIndex: int32
	// sceneOffset: int32 vec2
	// fade: float32
	// faceBase: int32
	// modelBase: int32
	public static final int ZONE_DATA_NUM_BYTES = 24;
	public static final int ZONE_DATA_NUM_INTS = ZONE_DATA_NUM_BYTES / Integer.BYTES;

	public static int LEVEL_COUNT = MAX_Z;
	public static final int LEVEL_WATER_SURFACE = LEVEL_COUNT++;
	public static final int LEVEL_GAP_FILLER = LEVEL_COUNT++;

	public int dist;
	int bufLen;

	public int bufLenA;

	public int sizeO, sizeA, sizeF, sizeM;
	volatile boolean uploadReady;

	int vertexBaseO, vertexBaseA, faceBase, modelBase;
	int zoneId = -1;

	final int[] blockSizes = new int[SharedZoneBuffers.KIND_COUNT];

	final GpuIntBuffer[] staging = new GpuIntBuffer[SharedZoneBuffers.KIND_COUNT];

	public boolean initialized;
	public boolean cull; // whether the zone is queued for deletion
	public boolean needsRoofUpdate; // whether the zone needs to have its roofs updated during scene swap
	public boolean rebuild; // whether the zone is queued for rebuild
	public boolean dirty; // whether the zone has temporary modifications
	public boolean hasWater; // whether the zone has any water tiles
	public boolean onlyWater; // whether the zone only contains water tiles
	public boolean hasGapFiller; // whether the zone has any gap filler geometry
	public boolean inSceneFrustum = true; // whether the zone is visible to the scene camera
	public boolean inShadowFrustum; // whether the zone casts shadows into the visible scene
	public boolean sceneCameraIntersects; // whether the scene camera intersects the zone with a radius of 2 tiles
	public boolean isFirstLoadingAttempt = true;

	public IntHashSet animatedDynamicObjectIds = new IntHashSet();

	final StaticAlphaSortingJob alphaSortingJob = new StaticAlphaSortingJob();
	ZoneUploadJob uploadJob;
	float fadingAlpha;
	ZoneRoofFades.State fade; // owned by ZoneRoofFades while the zone is being drawn

	void setUploadJob(ZoneUploadJob uploadJob) {
		this.uploadJob = uploadJob;
		if (uploadJob != null)
			uploadJob.zoneToBeReplaced = this;
	}

	int[] levelOffsets = new int[LEVEL_COUNT]; // buffer pos in ints for the end of the level
	int[] alphaLevelOffsets = new int[MAX_Z]; // buffer pos in ints for the end of the alpha level

	int[][] rids;
	int[][] roofStart;
	int[][] roofEnd;
	int[][] alphaRoofStart;
	int[][] alphaRoofEnd;

	final List<AlphaModel> alphaModels = new ArrayList<>(0);
	final ConcurrentLinkedQueue<AsyncCachedModel> pendingModelJobs = new ConcurrentLinkedQueue<>();

	GpuIntBuffer prepareStagingBuffer(int kind) {
		int blockSize = blockSizes[kind];
		if (blockSize == 0)
			return null;

		GpuIntBuffer buffer = staging[kind];
		if (buffer == null)
			staging[kind] = buffer = new GpuIntBuffer(blockSize);
		else
			buffer.clear().ensureCapacity(blockSize);
		buffer.getBuffer().limit(blockSize);
		return buffer;
	}

	void destroyStagingBuffers() {
		for (int i = 0; i < staging.length; ++i) {
			if (staging[i] != null) {
				staging[i].destroy();
				staging[i] = null;
			}
		}
	}

	void shiftOpaqueRanges(int from, int to) {
		final int delta = to - from;
		for (int i = 0; i < levelOffsets.length; ++i)
			levelOffsets[i] += delta;

		if (roofStart != null)
			for (int level = 0; level < 4; ++level)
				for (int i = 0; i < roofStart[level].length; ++i) {
					if (rids[level][i] > 0) {
						roofStart[level][i] += delta;
						roofEnd[level][i] += delta;
					}
				}
	}

	synchronized void shiftAlphaRanges(int from, int to) {
		final int delta = to - from;
		for (int i = 0; i < alphaLevelOffsets.length; ++i)
			alphaLevelOffsets[i] += delta;

		if (alphaRoofStart != null)
			for (int level = 0; level < alphaRoofStart.length; ++level)
				for (int i = 0; i < alphaRoofStart[level].length; ++i) {
					if (rids[level][i] > 0) {
						alphaRoofStart[level][i] += delta;
						alphaRoofEnd[level][i] += delta;
					}
				}

		for (int i = 0; i < alphaModels.size(); ++i) {
			AlphaModel m = alphaModels.get(i);
			// Temporary models live in the dynamic model buffers, which are never compacted
			if (m.isTemp() || m.packedFaces == null)
				continue;

			m.startpos += delta;
			m.endpos += delta;
		}
	}

	public static void freeZones(@Nullable Zone[][] zones) {
		if (zones == null)
			return;

		for (Zone[] column : zones)
			for (Zone zone : column)
				if (zone != null)
					zone.destroy();
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() {
		if (zoneId >= 0)
			DestructibleHandler.queueLeakedDestruction(this);
	}

	@Override
	public void destroy() {
		if (uploadJob != null) {
			uploadJob.cancel();
			Zone replaced = uploadJob.zoneBeingUploaded;
			uploadJob = null;
			if (replaced != null && replaced != this)
				DestructibleHandler.destroy(replaced);
		}

		destroyStagingBuffers();
		if (viewContext != null)
			viewContext.freeZoneBlocks(this);
		viewContext = null;

		sortedAlphaFacesUpload.release();

		sizeO = 0;
		sizeA = 0;
		sizeF = 0;
		sizeM = 0;
		bufLen = 0;
		bufLenA = 0;
		Arrays.fill(blockSizes, 0);

		initialized = false;
		uploadReady = false;
		cull = false;
		hasWater = false;
		onlyWater = false;
		hasGapFiller = false;
		inSceneFrustum = false;
		inShadowFrustum = false;

		Arrays.fill(levelOffsets, 0);
		Arrays.fill(alphaLevelOffsets, 0);
		rids = null;
		roofStart = null;
		roofEnd = null;
		alphaRoofStart = null;
		alphaRoofEnd = null;

		// don't add permanent alphamodels to the cache as permanent alphamodels are always allocated
		// to avoid having to synchronize the cache
		alphaModels.clear();
	}

	@Override
	public String toString() {
		return String.format(
			"Zone Initialized: %b, culled: %b hasUploadJob: %b opaqueSize: %d alphaSize: %d",
			initialized, cull, uploadJob != null, sizeO, sizeA
		);
	}

	void updateRoofs(Int2IntHashMap updates) {
		for (int level = 0; level < 4; ++level) {
			for (int i = 0; i < rids[level].length; ++i) {
				rids[level][i] = updates.getOrDefault(rids[level][i], rids[level][i]);
			}
		}

		for (AlphaModel m : alphaModels)
			m.rid = (short) updates.getOrDefault(m.rid, m.rid);
	}

	int levelStart(int level) {
		return level == 0 ? vertexBaseO : levelOffsets[level - 1];
	}

	int alphaLevelStart(int level) {
		return level == 0 ? vertexBaseA : alphaLevelOffsets[level - 1];
	}

	public static final class AlphaModel {
		int id;
		ModelOverride modelOverride;
		int startpos, endpos;
		short x, y, z; // local position
		short rid;
		int vao;
		int zoneId = -1;
		@Nullable
		GLShaderStorage tboF, tboM;
		byte level;
		byte lx, lz, ux, uz; // lower/upper zone coords
		byte zofx, zofz; // for temp alpha models, offset of source zone from target zone
		byte flags;

		// only set for static geometry as they require sorting
		int radius;
		int[] packedFaces;
		int[] doubleSidedBitSet;
		char doubleSidedCount;

		int dist;
		int asyncSortIdx = -1;
		int sortedFacesLen;
		int[] tempSortedFaces;

		static final int SKIP = 1; // temporary model is in a closer zone
		static final int TEMP = 2; // temporary model added to a closer zone
		static final int SORT_COMPLETED = 4;

		void setSorted() {
			flags |= SORT_COMPLETED;
		}

		boolean needsSorting() {
			return (flags & SORT_COMPLETED) == 0;
		}

		boolean isTemp() {
			return packedFaces == null;
		}

		int calculateDepth(int cx, int cy, int cz, int zx, int zz) {
			final int mx = (x + ((zx - zofx) << 10));
			final int mz = (z + ((zz - zofz) << 10));
			return (mx - cx) * (mx - cx) + (y - cy) * (y - cy) + (mz - cz) * (mz - cz);
		}

		void setView(DynamicModelVAO.View view) {
			vao = view.vao;
			tboF = view.tboFBuffer;
			tboM = view.tboMBuffer;
			startpos = view.getStartOffset();
			endpos = view.getEndOffset();
			zoneId = SharedZoneBuffers.DYNAMIC_ZONE_ID;
		}
	}

	void addAlphaModel(
		HdPlugin plugin,
		MaterialManager materialManager,
		int vao,
		int zoneId,
		GLShaderStorage tboF,
		GLShaderStorage tboM,
		Model model,
		ModelOverride modelOverride,
		int startpos,
		int endpos,
		int x,
		int y,
		int z,
		int lx,
		int lz,
		int ux,
		int uz,
		int rid,
		int level,
		int id
	) {
		AlphaModel m = new AlphaModel();
		m.id = id;
		m.modelOverride = modelOverride;
		m.startpos = startpos;
		m.endpos = endpos;
		m.x = (short) x;
		m.y = (short) y;
		m.z = (short) z;
		m.vao = vao;
		m.zoneId = zoneId;
		m.tboF = tboF;
		m.tboM = tboM;
		m.rid = (short) rid;
		m.level = (byte) level;
		if (lx > -1) {
			m.lx = (byte) lx;
			m.lz = (byte) lz;
			m.ux = (byte) ux;
			m.uz = (byte) uz;
		} else {
			m.lx = m.lz = m.ux = m.uz = -1;
		}

		int faceCount = model.getFaceCount();
		short[] unlitColor = plugin.configUnlitFaceColors ? model.getUnlitFaceColors() : null;
		int[] color1 = model.getFaceColors1();
		int[] color3 = model.getFaceColors3();
		byte[] transparencies = model.getFaceTransparencies();
		short[] faceTextures = model.getFaceTextures();
		float[] vertexX = model.getVerticesX();
		float[] vertexY = model.getVerticesY();
		float[] vertexZ = model.getVerticesZ();
		int[] indices1 = model.getFaceIndices1();
		int[] indices2 = model.getFaceIndices2();
		int[] indices3 = model.getFaceIndices3();

		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

		for (int f = 0; f < faceCount; ++f) {
			if (color3[f] == -2)
				continue;

			boolean hasAlpha = modelOverride.mightHaveTransparency || transparencies != null && transparencies[f] != 0;
			if (!hasAlpha)
				continue;

			int fx = (int) (vertexX[indices1[f]] + vertexX[indices2[f]] + vertexX[indices3[f]]);
			int fy = (int) (vertexY[indices1[f]] + vertexY[indices2[f]] + vertexY[indices3[f]]);
			int fz = (int) (vertexZ[indices1[f]] + vertexZ[indices2[f]] + vertexZ[indices3[f]]);

			minX = Math.min(minX, fx);
			maxX = Math.max(maxX, fx);
			minY = Math.min(minY, fy);
			maxY = Math.max(maxY, fy);
			minZ = Math.min(minZ, fz);
			maxZ = Math.max(maxZ, fz);
		}

		int cx = (minX + maxX) / 6;
		int cy = (minY + maxY) / 6;
		int cz = (minZ + maxZ) / 6;

		int size = Math.max(
			Math.max(
				Math.max(maxX / 3 - cx, minX / -3 - cx),
				Math.max(maxY / 3 - cy, minY / -3 - cy) * 2
			),
			Math.max(maxZ / 3 - cz, minZ / -3 - cz)
		);

		int shift = 0;
		// 10 bits because we need a sign bit
		for (int v = size >> 10; v > 0; v >>= 1) {
			shift++;
		}

		final int intsPerVertex = ZONE_VERTEX_NUM_BYTES / Integer.BYTES;
		final int writtenAlphaFaceCount = (endpos - startpos) / (3 * intsPerVertex);
		final int bucketCapacity = ceil(writtenAlphaFaceCount / 32.0f);

		final int[] packedFaces = PooledArrayType.INT.borrow(writtenAlphaFaceCount);
		final int[] doubleSidedBitSet = PooledArrayType.INT.borrow(bucketCapacity);

		Arrays.fill(doubleSidedBitSet, 0, bucketCapacity, 0);

		final Material baseMaterial = modelOverride.baseMaterial;
		final Material textureMaterial = modelOverride.textureMaterial;

		int radius = 0;
		char bufferIdx = 0;
		char doubleSidedCount = 0;
		for (int f = 0; f < faceCount; ++f) {
			if (color3[f] == -2)
				continue;

			// Hide fake shadows or lighting that is often baked into models by making the fake shadow transparent
			if (plugin.configHideFakeShadows && modelOverride.hideVanillaShadows && HDUtils.isBakedGroundShading(model, f))
				continue;

			Material material = baseMaterial;
			ModelOverride faceOverride = modelOverride;

			int transparency = SceneUploader.readFaceTransparency(model.getTransparency(), transparencies, f);
			if (transparency == 255)
				continue;

			int textureId = faceTextures != null ? faceTextures[f] : -1;
			if (textureId != -1) {
				if (modelOverride.textureMaterial != Material.NONE) {
					material = textureMaterial;
				} else {
					material = materialManager.fromVanillaTexture(textureId);
					if (modelOverride.materialOverrides != null) {
						var override = modelOverride.materialOverrides.get(material);
						if (override != null) {
							faceOverride = override;
							material = override.textureMaterial;
						}
					}
				}
			} else if (modelOverride.colorOverrides != null) {
				final int ahsl = (0xFF - transparency) << 16 | (unlitColor != null ? unlitColor[f] & 0xFFFF : color1[f]);
				final var override = modelOverride.testColorOverrides(ahsl);
				if (override != null) {
					faceOverride = override;
					material = override.baseMaterial;
				}
			}

			if (faceOverride.hide)
				continue;

			if (faceOverride.modifiesAlpha)
				transparency = 255 - faceOverride.modifyAlpha(255 - transparency);

			boolean hasAlpha = material.hasTransparency || transparency != 0;
			if (!hasAlpha)
				continue;

			final int fx = (((int) (vertexX[indices1[f]] + vertexX[indices2[f]] + vertexX[indices3[f]]) / 3) - cx) >> shift;
			final int fy = (((int) (vertexY[indices1[f]] + vertexY[indices2[f]] + vertexY[indices3[f]]) / 3) - cy) >> shift;
			final int fz = (((int) (vertexZ[indices1[f]] + vertexZ[indices2[f]] + vertexZ[indices3[f]]) / 3) - cz) >> shift;

			radius = Math.max(radius, fx * fx + fy * fy + fz * fz);
			packedFaces[bufferIdx] = ((fx & ((1 << 11) - 1)) << 21)
			                         | ((fy & ((1 << 10) - 1)) << 11)
			                         | (fz & ((1 << 11) - 1));

			if (faceOverride.doubleSidedFaces || material.doubleSidedFaces) {
				doubleSidedBitSet[bufferIdx >> 5] |= 1 << (bufferIdx & 31);
				doubleSidedCount++;
			}

			bufferIdx++;
		}

		assert packedFaces.length > 0;
		// Normally these will be equal, but transparency is used to hide faces in the TzHaar reskin
		assert bufferIdx <= packedFaces.length : String.format("%d > %d", (int) bufferIdx, packedFaces.length);

		m.radius = 2 + (int) Math.sqrt(radius);
		m.packedFaces = Arrays.copyOf(packedFaces, bufferIdx);
		m.doubleSidedBitSet = doubleSidedCount > 0 ? Arrays.copyOf(doubleSidedBitSet, ceil(bufferIdx / 32.0f)) : null;
		m.doubleSidedCount = doubleSidedCount;

		alphaModels.add(m);

		PooledArrayType.INT.release(packedFaces);
		PooledArrayType.INT.release(doubleSidedBitSet);
	}

	synchronized AlphaModel requestTempAlphaModel(ModelOverride modelOverride, int level, int x, int y, int z) {
		AlphaModel m = ALPHA_MODEL_POOL.acquire();
		m.id = -1;
		m.modelOverride = modelOverride;
		m.x = (short) x;
		m.y = (short) y;
		m.z = (short) z;
		m.level = (byte) level;
		m.vao = m.rid = m.lx = m.lz = m.ux = m.uz = -1;
		m.zoneId = -1;
		m.tboF = m.tboM = null;
		m.flags = 0;
		m.zofx = m.zofz = 0;
		alphaModels.add(m);
		return m;
	}

	synchronized void postAlphaPass() {
		sortedAlphaFacesUpload.waitForCompletion();
		alphaSortingJob.waitForCompletion();

		for (int i = alphaModels.size() - 1; i >= 0; --i) {
			AlphaModel m = alphaModels.get(i);
			m.asyncSortIdx = -1;
			m.flags &= ~(AlphaModel.SKIP | AlphaModel.SORT_COMPLETED);

			if (m.isTemp() || (m.flags & AlphaModel.TEMP) != 0) {
				alphaModels.remove(i);
				m.packedFaces = null;
				m.doubleSidedBitSet = null;
				m.tboF = m.tboM = null;
				ALPHA_MODEL_POOL.recycle(m);
			}

			if (m.tempSortedFaces != null)
				PooledArrayType.INT.release(m.tempSortedFaces);
			m.tempSortedFaces = null;
		}
	}

	static class AlphaModelComparator implements Comparator<AlphaModel> {
		int zx, zz;
		int cx, cy, cz;

		@Override
		public int compare(AlphaModel modelA, AlphaModel modelB) {
			return Integer.compare(
				modelB.calculateDepth(cx, cy, cz, zx, zz),
				modelA.calculateDepth(cx, cy, cz, zx, zz)
			);
		}
	}

	private static final AlphaModelComparator alphaModelComparator = new AlphaModelComparator();
	final EboAlphaWriterJob sortedAlphaFacesUpload = new EboAlphaWriterJob();

	synchronized void alphaSort(int zx, int zz, Camera camera) {
		final int alphaModelCount = alphaModels.size();
		if (alphaModelCount <= 1)
			return;

		alphaModelComparator.cx = (int) camera.getPositionX();
		alphaModelComparator.cy = (int) camera.getPositionY();
		alphaModelComparator.cz = (int) camera.getPositionZ();
		alphaModelComparator.zx = zx;
		alphaModelComparator.zz = zz;

		quickSort(alphaModels, alphaModelComparator);
	}

	void alphaStaticModelSort(Camera camera) {
		alphaSortingJob.reset();
		for (AlphaModel m : alphaModels) {
			if ((m.flags & AlphaModel.SKIP) != 0 || m.isTemp())
				continue;

			m.dist = dist;
			m.tempSortedFaces = PooledArrayType.INT.borrow((m.packedFaces.length + m.doubleSidedCount) * 3);
			alphaSortingJob.addAlphaModel(m);
		}
		alphaSortingJob.queue(camera);
	}

	synchronized void multizoneLocs(SceneContext ctx, int zx, int zz, Camera camera, Zone[][] zones) {
		int offset = ctx.sceneOffset >> 3;
		int cx = (int) camera.getPositionX();
		int cz = (int) camera.getPositionZ();
		for (int i = 0; i < alphaModels.size(); i++) {
			final AlphaModel m = alphaModels.get(i);
			if (m.lx == -1)
				continue;

			// calculate which zone this model should be drawn from
			// TODO fix for boats
			int max = Integer.MAX_VALUE;
			int closestZoneX = -50, closestZoneZ = -50;
			for (int x = m.lx >> 3; x <= m.ux >> 3; ++x) {
				for (int z = m.lz >> 3; z <= m.uz >> 3; ++z) {
					int centerX = (zx - m.zofx + x) * 8 + 4 << 7;
					int centerZ = (zz - m.zofz + z) * 8 + 4 << 7;
					int distance = (centerX - cx) * (centerX - cx) +
								   (centerZ - cz) * (centerZ - cz);
					if (distance < max) {
						int zx2 = (centerX >> 10) + offset;
						int zz2 = (centerZ >> 10) + offset;
						if (zx2 >= 0 && zx2 < zones.length && zz2 >= 0 && zz2 < zones[0].length) {
							if (zones[zx2][zz2].inSceneFrustum && zones[zx2][zz2].initialized) {
								max = distance;
								closestZoneX = centerX >> 10;
								closestZoneZ = centerZ >> 10;
							}
						}
					}
				}
			}
			if (closestZoneX != -50 && (closestZoneX != zx || closestZoneZ != zz)) {
				assert closestZoneX + offset >= 0 : closestZoneX;
				assert closestZoneX + offset < zones.length : closestZoneX;
				assert closestZoneZ + offset >= 0 : closestZoneZ;
				assert closestZoneZ + offset < zones[0].length : closestZoneZ;

				Zone z = zones[closestZoneX + offset][closestZoneZ + offset];
				assert z != null;
				assert z != this;

				AlphaModel m2 = ALPHA_MODEL_POOL.acquire();
				m2.id = m.id;
				m2.modelOverride = m.modelOverride;
				m2.startpos = m.startpos;
				m2.endpos = m.endpos;
				m2.x = m.x;
				m2.y = m.y;
				m2.z = m.z;
				m2.vao = m.vao;
				m2.zoneId = m.zoneId;
				m2.tboF = m.tboF;
				m2.tboM = m.tboM;
				m2.rid = m.rid;
				m2.level = m.level;
				m2.lx = m.lx;
				m2.lz = m.lz;
				m2.ux = m.ux;
				m2.uz = m.uz;
				m2.zofx = (byte) (closestZoneX - zx);
				m2.zofz = (byte) (closestZoneZ - zz);

				m2.packedFaces = m.packedFaces;
				m2.doubleSidedBitSet = m.doubleSidedBitSet;
				m2.radius = m.radius;
				m2.doubleSidedCount = m.doubleSidedCount;
				m2.asyncSortIdx = m.asyncSortIdx;
				m2.tempSortedFaces = m.tempSortedFaces;
				m2.sortedFacesLen = m.sortedFacesLen;

				m2.flags = AlphaModel.TEMP;
				m.flags |= AlphaModel.SKIP;

				z.alphaModels.add(m2);
			}
		}
	}
}
