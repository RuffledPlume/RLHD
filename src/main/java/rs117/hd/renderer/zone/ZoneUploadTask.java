package rs117.hd.renderer.zone;

import java.util.concurrent.ConcurrentLinkedDeque;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.utils.jobs.JobSystem;
import rs117.hd.utils.jobs.JobWork;

import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static rs117.hd.renderer.zone.ZoneRenderer.eboAlpha;

@Slf4j
public final class ZoneUploadTask extends JobWork {
	private static final ConcurrentLinkedDeque<ZoneUploadTask> POOL = new ConcurrentLinkedDeque<>();

	private static final ThreadLocal<SceneUploader> tlSceneUploader = new ThreadLocal<>();
	private static ProceduralGenerator proceduralGenerator;

	WorldViewContext viewContext;
	ZoneSceneContext sceneContext;
	Zone zone;
	int x, z;

	ZoneUploadTask() {
		if(proceduralGenerator == null) {
			proceduralGenerator = JobSystem.INSTANCE.injector.getInstance(ProceduralGenerator.class);
		}
	}

	@Override
	protected void onRun() throws InterruptedException {
		SceneUploader sceneUploader = tlSceneUploader.get();
		if(sceneUploader == null)
			tlSceneUploader.set(sceneUploader = JobSystem.INSTANCE.injector.getInstance(SceneUploader.class));

		workerHandleCancel();

		sceneUploader.setScene(sceneContext.scene);
		sceneUploader.setCurrentWork(this);
		sceneUploader.estimateZoneSize(sceneContext, zone, x, z);

		workerHandleCancel();

		queueClientCallback(isHighPriority(), true, () -> {
				try {
					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0) {
						o = new VBO(sz);
						o.initialize(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0) {
						a = new VBO(sz);
						a.initialize(GL_STATIC_DRAW);
						a.map();
					}

					zone.initialize(o, a, eboAlpha);
					zone.setMetadata(viewContext, sceneContext, x, z);
				} catch (Throwable ex) {
					log.warn("Caught exception whilst processing zone [{}, {}] worldId [{}] group priority [{}] cancelling...\n", x, z, viewContext.worldViewId, isHighPriority(), ex);
					cancel();
				}
			}
		);
		workerHandleCancel();

		if(zone.vboO != null || zone.vboA != null) {
			sceneUploader.uploadZone(sceneContext, zone, x, z);
			workerHandleCancel();

			queueClientCallback(isHighPriority(), isHighPriority(), () -> {
					zone.unmap();
					zone.initialized = true;
				}
			);
		}

		sceneUploader.clear();
	}

	@Override
	protected void onCancel() {
		SceneUploader sceneUploader = tlSceneUploader.get();
		if(sceneUploader != null)
			sceneUploader.clear();

		if(viewContext.zones[x][z] != zone)
			viewContext.pendingCull.add(zone);
	}

	@Override
	protected void onReleased() {
		viewContext = null;
		sceneContext = null;
		zone = null;
		assert !POOL.contains(this) : "Task is already in pool";
		POOL.add(this);
	}

	public static ZoneUploadTask build(WorldViewContext viewContext, ZoneSceneContext sceneContext, Zone zone, int x, int z) {
		assert viewContext != null : "WorldViewContext cant be null";
		assert sceneContext != null : "ZoneSceneContext cant be null";
		assert zone != null : "Zone cant be null";
		assert !zone.initialized : "Zone is already initialized";

		ZoneUploadTask newTask = POOL.poll();
		if (newTask == null)
			newTask = new ZoneUploadTask();
		newTask.viewContext = viewContext;
		newTask.sceneContext = sceneContext;
		newTask.zone = zone;
		newTask.x = x;
		newTask.z = z;
		newTask.isReleased = false;

		return newTask;
	}

	@Override
	public String toString() {
		return super.toString() + " worldViewId: [" + (viewContext != null ? viewContext.worldViewId : "null") + "] X: [" + x + "] Z: [" + z + "]";
	}
}
