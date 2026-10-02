package rs117.hd.renderer.zone;

import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.jobs.Job;

@Slf4j
public final class ZoneUploadJob extends Job {
	private static final ConcurrentPool<ZoneUploadJob> POOL = new ConcurrentPool<>(ZoneUploadJob::new);

	private WorldViewContext viewContext;
	private ZoneSceneContext sceneContext;

	Zone zoneToBeReplaced;
	Zone zoneBeingUploaded;
	int x, z;

	@Override
	protected void onRun() throws InterruptedException {
		SceneUploader sceneUploader = SceneUploader.POOL.acquire();
		try (sceneUploader) {
			try {
				workerHandleCancel();

				sceneUploader.setScene(sceneContext.scene);
				sceneUploader.estimateZoneSize(sceneContext, zoneBeingUploaded, x, z);

				if (zoneBeingUploaded.sizeO > 0 || zoneBeingUploaded.sizeA > 0) {
					workerHandleCancel();

					allocateZoneBlocks();
					workerHandleCancel();

					sceneUploader.uploadZone(sceneContext, zoneBeingUploaded, x, z);
					workerHandleCancel();
				}
				zoneBeingUploaded.uploadReady = true;
			} catch (InterruptedException ex) {
				zoneBeingUploaded.destroyStagingBuffers();
				throw ex;
			} catch (RuntimeException | Error ex) {
				int[] written = getStagingLengths();
				zoneBeingUploaded.destroyStagingBuffers();
				log.error(
					"Zone upload failed: worldViewId={} zone=({}, {}) id={} estimatedFaces=[opaque={}, alpha={}, faces={}, models={}] writtenInts=[opaque={}, alpha={}, faces={}, models={}] bases=[opaque={}, alpha={}, faces={}, models={}]",
					viewContext.worldViewId,
					x,
					z,
					zoneBeingUploaded.zoneId,
					zoneBeingUploaded.sizeO,
					zoneBeingUploaded.sizeA,
					zoneBeingUploaded.sizeF,
					zoneBeingUploaded.sizeM,
					written[SharedZoneBuffers.KIND_OPAQUE],
					written[SharedZoneBuffers.KIND_ALPHA],
					written[SharedZoneBuffers.KIND_FACES],
					written[SharedZoneBuffers.KIND_MODELS],
					zoneBeingUploaded.vertexBaseO,
					zoneBeingUploaded.vertexBaseA,
					zoneBeingUploaded.faceBase,
					zoneBeingUploaded.modelBase,
					ex
				);
				throw ex;
			}
		}
	}

	private int[] getStagingLengths() {
		int[] lengths = new int[SharedZoneBuffers.KIND_COUNT];
		for (int kind = 0; kind < lengths.length; kind++) {
			GpuIntBuffer staging = zoneBeingUploaded.staging[kind];
			lengths[kind] = staging != null ? staging.position() : 0;
		}
		return lengths;
	}

	private void allocateZoneBlocks() {
		zoneBeingUploaded.viewContext = viewContext;
		viewContext.sharedBuffers.allocate(zoneBeingUploaded);
		viewContext.setZoneData(zoneBeingUploaded, sceneContext, x, z);
	}

	@Override
	protected void onCancel() {
		if (viewContext.zones[x][z] != zoneBeingUploaded)
			DestructibleHandler.queueDestruction(zoneBeingUploaded);

		// Avoid holding a reference to the context after the job is done
		viewContext = null;
		sceneContext = null;
	}

	@Override
	protected void onReleased() {
		viewContext = null;
		sceneContext = null;
		if (zoneToBeReplaced != null && zoneToBeReplaced.uploadJob == this)
			zoneToBeReplaced.uploadJob = null;
		zoneToBeReplaced = null;
		if (zoneBeingUploaded != null && zoneBeingUploaded.uploadJob == this)
			zoneBeingUploaded.uploadJob = null;
		zoneBeingUploaded = null;
		POOL.recycle(this);
	}

	public static ZoneUploadJob build(
		WorldViewContext viewContext,
		ZoneSceneContext sceneContext,
		Zone uploadZone,
		int x,
		int z
	) {
		assert viewContext != null : "WorldViewContext cant be null";
		assert sceneContext != null : "ZoneSceneContext cant be null";
		assert uploadZone != null : "Zone cant be null";
		assert !uploadZone.initialized : "Zone is already initialized";

		ZoneUploadJob newTask = POOL.acquire();
		newTask.viewContext = viewContext;
		newTask.sceneContext = sceneContext;
		newTask.zoneBeingUploaded = uploadZone;
		newTask.x = x;
		newTask.z = z;
		newTask.zoneToBeReplaced = null;
		uploadZone.uploadReady = false;
		newTask.resetReleased();

		return newTask;
	}

	@Override
	public String toString() {
		return String.format(
			"%s: worldViewId=%s, x=%d, z=%d",
			super.toString(),
			viewContext != null ? viewContext.worldViewId : "null",
			x, z
		);
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() {
		log.trace("ZoneUploadJob finalized, it should have been pooled? - {}", this);
	}
}
