package rs117.hd.renderer.zone;

import com.google.inject.Injector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.opengl.uniforms.UBOWorldViews.WorldViewStruct;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.collections.IntHashSet;
import rs117.hd.utils.jobs.JobGroup;

import static rs117.hd.renderer.zone.FrameContext.VAO_COUNT;
import static rs117.hd.renderer.zone.SceneManager.NUM_ZONES;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.collections.Util.quickSort;

@Slf4j
public class WorldViewContext {
	@Inject
	private Injector injector;

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ZoneRenderer zoneRenderer;

	@Inject
	private SceneManager sceneManager;

	SharedZoneBuffers sharedBuffers;

	final int worldViewId;
	final int sizeX, sizeZ;
	@Nullable
	WorldViewStruct uboWorldViewStruct;
	public ZoneSceneContext sceneContext;
	Zone[][] zones;
	boolean isLoading = true;

	int minLevel, level, maxLevel;
	final IntHashSet hideRoofIds = new IntHashSet();

	private final Comparator<Zone> alphaSortComparator = Comparator.comparingInt((Zone z) -> z.dist).reversed();
	private final List<Zone> alphaZones = new ArrayList<>();

	private final int[] dynamicDrawRanges = new int[VAO_COUNT * 2];

	CommandBuffer vaoSceneCmd;
	CommandBuffer vaoDirectionalCmd;
	ZoneDrawContext zoneDrawContext;

	public long loadTime;
	public long uploadTime;
	public long sceneSwapTime;

	final JobGroup<ZoneUploadJob> sceneLoadGroup = new JobGroup<>(true, true);
	final JobGroup<ZoneUploadJob> streamingGroup = new JobGroup<>(false, false);
	final JobGroup<ZoneUploadJob> invalidationGroup = new JobGroup<>(true, false);

	WorldViewContext(
		@Nullable WorldView worldView,
		@Nullable ZoneSceneContext sceneContext,
		UBOWorldViews uboWorldViews
	) {
		this.worldViewId = worldView == null ? WorldView.TOPLEVEL : worldView.getId();
		this.sceneContext = sceneContext;
		this.sizeX = worldView == null ? NUM_ZONES : worldView.getSizeX() >> 3;
		this.sizeZ = worldView == null ? NUM_ZONES : worldView.getSizeY() >> 3;
		if (worldView != null)
			uboWorldViewStruct = uboWorldViews.acquire(worldView);
		zones = new Zone[sizeX][sizeZ];
	}

	public void initialize(Injector injector) {
		injector.injectMembers(this);

		vaoSceneCmd = new CommandBuffer("WorldViewScene");
		vaoDirectionalCmd = new CommandBuffer("WorldViewDirectional");
		zoneDrawContext = new ZoneDrawContext(this);

		if (sharedBuffers == null) {
			sharedBuffers = new SharedZoneBuffers();
			sharedBuffers.initialize();
		}

		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z] = injector.getInstance(Zone.class);
	}

	void sortStaticAlphaModels(Camera camera) {
		alphaZones.clear();

		final int offset = sceneContext.sceneOffset >> 3;
		final int camPosX = (int) camera.getPositionX();
		final int camPosZ = (int) camera.getPositionZ();
		for (int zx = 0; zx < sizeX; zx++) {
			for (int zz = 0; zz < sizeZ; zz++) {
				final Zone z = zones[zx][zz];
				if (z.alphaModels.isEmpty() || (worldViewId == WorldView.TOPLEVEL && !z.inSceneFrustum))
					continue;

				final int dx = camPosX - ((zx - offset) << 10);
				final int dz = camPosZ - ((zz - offset) << 10);
				z.dist = dx * dx + dz * dz;
				alphaZones.add(z);
			}
		}

		if (!alphaZones.isEmpty()) {
			quickSort(alphaZones, alphaSortComparator);
			for (int i = 0; i < alphaZones.size(); i++)
				alphaZones.get(i).alphaStaticModelSort(camera);
		}
	}

	void resetDrawRanges() {
		for (int i = 0; i < VAO_COUNT; i++)
			dynamicDrawRanges[i * 2] = dynamicDrawRanges[i * 2 + 1] = 0;
	}

	synchronized void trackDrawRange(DynamicModelVAO.View view, int type) {
		dynamicDrawRanges[type * 2] = min(dynamicDrawRanges[type * 2], view.getDrawIdx());
		dynamicDrawRanges[type * 2 + 1] = max(dynamicDrawRanges[type * 2 + 1], view.getDrawIdx() + 1);
	}

	DynamicModelVAO.View beginDraw(int type, int faces) {
		FrameContext ctx = zoneRenderer.frameContext();
		DynamicModelVAO.View view = ctx.dynamicModelVaos[type].beginDraw(faces);
		trackDrawRange(view, type);
		return view;
	}

	DynamicModelVAO.View beginDraw(int type, int playerDrawIndex, int faces) {
		FrameContext ctx = zoneRenderer.frameContext();
		DynamicModelVAO.View view = ctx.dynamicModelVaos[type].beginDraw(playerDrawIndex, faces);
		trackDrawRange(view, type);
		return view;
	}

	void drawAll(int type, CommandBuffer cmd) {
		FrameContext ctx = zoneRenderer.frameContext();
		ctx.dynamicModelVaos[type].draw(cmd, dynamicDrawRanges[type * 2], dynamicDrawRanges[type * 2 + 1], sharedBuffers.tboZ);
	}

	void handleZoneSwap(int zx, int zz) {
		if (zones == null || zx < 0 || zx >= zones.length || zones[zx] == null ||
			zz < 0 || zz >= zones[zx].length || zones[zx][zz] == null)
			return;

		Zone curZone = zones[zx][zz];
		ZoneUploadJob uploadTask = curZone.uploadJob;
		if (uploadTask == null)
			return;

		if (uploadTask.isDone()) {
			curZone.uploadJob = null;
			if (uploadTask.ranToCompletion() && !uploadTask.wasCancelled()) {
				Zone uploadedZone = uploadTask.zoneBeingUploaded;
				if (uploadedZone == null) {
					log.error(
						"Completed zone upload job [{}] has already been released: [{}-{},{}]",
						uploadTask.hashCode(),
						worldViewId,
						zx,
						zz
					);
					curZone.rebuild = true;
					return;
				}
				if (!commitZoneUpload(uploadedZone, zx, zz)) {
					curZone.rebuild = true;
					uploadTask.release();
					return;
				}
				log.trace("swapping zone({}): [{}-{},{}]", uploadedZone.hashCode(), worldViewId, zx, zz);

				Zone prevZone = curZone;
				// Swap the zone out with the one we just uploaded
				zones[zx][zz] = curZone = uploadedZone;

				if (prevZone != curZone) {
					curZone.inSceneFrustum = prevZone.inSceneFrustum;
					curZone.inShadowFrustum = prevZone.inShadowFrustum;
					DestructibleHandler.queueDestruction(prevZone);
				}

				sceneContext.animatedDynamicObjectIds.addAll(curZone.animatedDynamicObjectIds);
			} else if (uploadTask.wasCancelled() && !curZone.cull) {
				boolean shouldRetry = uploadTask.encounteredError() && curZone.isFirstLoadingAttempt;
				if (shouldRetry) {
					// Cache if the previous upload task encountered an error,
					// if it encounters another one the zone will be dropped to avoid constantly rebuilding
					curZone.rebuild = true;
					curZone.isFirstLoadingAttempt = false;
				} else if (uploadTask.encounteredError()) {
					log.debug(
						"Zone({}) [{}-{},{}] was cancelled due to an error, dropping to avoid constantly rebuilding",
						curZone.hashCode(),
						worldViewId,
						zx,
						zz
					);
				}
			}
			uploadTask.release();
		}
	}

	void processZoneSwaps() {
		if (zones == null || sceneContext == null)
			return;

		for (int x = 0; x < sizeX; x++) {
			for (int z = 0; z < sizeZ; z++) {
				if (zones[x] == null || zones[x][z] == null)
					continue;

				handleZoneSwap(x, z);

				final Zone zone = zones[x][z];
				if (zone.fadingAlpha <= 0)
					continue;

				zone.fadingAlpha = max(0.0f, zone.fadingAlpha - plugin.deltaTime);
				setZoneData(zone, sceneContext, x, z);
			}

		}
	}

	void commitReadyZoneUploads(Zone[][] zones) {
		if (zones == null)
			return;

		for (int x = 0; x < zones.length; ++x) {
			if (zones[x] == null)
				continue;

			for (int z = 0; z < zones[x].length; ++z) {
				Zone zone = zones[x][z];
				if (zone != null && zone.uploadReady && !commitZoneUpload(zone, x, z))
					zone.rebuild = true;
			}
		}
	}

	private boolean commitZoneUpload(Zone zone, int zx, int zz) {
		assert client.isClientThread();

		try {
			if (!zone.uploadReady) {
				if (zone.initialized)
					return true;

				log.error("Completed zone upload has no staged data to commit: [{}-{},{}]", worldViewId, zx, zz);
			} else {
				GpuIntBuffer opaque = zone.staging[SharedZoneBuffers.KIND_OPAQUE];
				GpuIntBuffer alpha = zone.staging[SharedZoneBuffers.KIND_ALPHA];
				zone.bufLen = opaque != null ? opaque.position() : 0;
				zone.bufLenA = alpha != null ? alpha.position() : 0;

				sharedBuffers.commit(zone);
				zone.initialized = true;
				zone.uploadReady = false;
				return true;
			}
		} catch (RuntimeException | Error ex) {
			log.error("Failed to commit zone upload: worldViewId={} zone=({}, {}) id={}", worldViewId, zx, zz, zone.zoneId, ex);
		} finally {
			zone.destroyStagingBuffers();
		}

		zone.destroy();
		return false;
	}

	void processZoneRebuilds() {
		if (zones == null)
			return;

		for (int x = 0; x < sizeX; x++) {
			for (int z = 0; z < sizeZ; z++) {
				if (zones[x] != null && zones[x][z] != null && zones[x][z].rebuild) {
					zones[x][z].rebuild = false;
					invalidateZone(x, z);
				}
			}
		}
	}

	void completeInvalidation() {
		if (invalidationGroup.getPendingCount() <= 0)
			return;

		invalidationGroup.complete();

		if (zones == null)
			return;

		for (int x = 0; x < sizeX; x++) {
			if (zones[x] == null)
				continue;

			for (int z = 0; z < sizeZ; z++)
				handleZoneSwap(x, z);
		}
	}

	void free() {
		isLoading = true;
		sceneLoadGroup.cancel();
		streamingGroup.cancel();
		invalidationGroup.cancel();

		if (sceneContext != null)
			sceneContext.destroy();
		sceneContext = null;

		if (uboWorldViewStruct != null)
			uboWorldViewStruct.free();
		uboWorldViewStruct = null;

		if (zones != null) {
			for (int x = 0; x < sizeX; ++x)
				for (int z = 0; z < sizeZ; ++z)
					if (zones[x] != null && zones[x][z] != null)
						zones[x][z].destroy();
			zones = null;
		}

		if (sharedBuffers != null) {
			sharedBuffers.destroy();
			sharedBuffers = null;
		}
	}

	void freeZoneBlocks(Zone zone) {
		if (sharedBuffers != null)
			sharedBuffers.free(zone, plugin.frame);
	}

	void setZoneData(Zone zone, SceneContext sceneContext, int x, int z) {
		if (sharedBuffers == null)
			return;

		int baseX = (x - (sceneContext.sceneOffset >> 3)) << 10;
		int baseZ = (z - (sceneContext.sceneOffset >> 3)) << 10;
		int worldViewIdx = uboWorldViewStruct != null ? uboWorldViewStruct.worldViewIdx + 1 : 0;
		sharedBuffers.setZoneData(zone, worldViewIdx, baseX, baseZ, saturate(zone.fadingAlpha));
	}

	/** Flushes zone metadata and compacts buffers when uploads are idle and fragmentation warrants it. */
	void flushSharedBuffers() {
		if (sharedBuffers == null)
			return;

		sharedBuffers.flushZoneData();
		sharedBuffers.age(plugin.frame);

		if (!sharedBuffers.isFragmented())
			return;

		if (sceneLoadGroup.getPendingCount() > 0 ||
			streamingGroup.getPendingCount() > 0 ||
			invalidationGroup.getPendingCount() > 0)
			return;

		sharedBuffers.defragment();
	}

	void updateDynamicZoneData(SceneContext sceneContext) {
		int sceneBase = -(sceneContext.sceneOffset >> 3) << 10;
		sharedBuffers.setDynamicZoneData(
			this,
			sceneBase,
			sceneBase
		);
		sharedBuffers.flushZoneData();
	}

	void invalidate() {
		log.debug("invalidate all zones for worldViewId: [{}]", worldViewId);
		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				invalidateZone(x, z);
	}

	// Synchronize creation of delayed upload jobs with zone swaps and invalidation
	synchronized void invalidateZone(int zx, int zz) {
		Zone curZone = zones[zx][zz];
		ZoneUploadJob previousUpload = curZone.uploadJob;
		if (previousUpload != null) {
			curZone.uploadJob = null;
			Zone pendingZone = previousUpload.zoneBeingUploaded;
			log.trace(
				"Invalidate Zone({}) - Cancelled upload task: [{}-{},{}] task zone({})",
				curZone.hashCode(),
				worldViewId,
				zx,
				zz,
				pendingZone.hashCode()
			);

			previousUpload.cancel();
			previousUpload.release();

			if (pendingZone != curZone)
				DestructibleHandler.destroy(pendingZone);
		}

		Zone newZone = injector.getInstance(Zone.class);
		newZone.dirty = zones[zx][zz].dirty;

		curZone.setUploadJob(ZoneUploadJob.build(this, sceneContext, newZone, zx, zz));
		curZone.uploadJob.queue(invalidationGroup, sceneManager.getGenerateSceneDataTask());
	}
}
