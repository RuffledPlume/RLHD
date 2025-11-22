package rs117.hd.renderer.zone;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.scene.ProceduralGenerator;

import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static rs117.hd.renderer.zone.ZoneRenderer.eboAlpha;

@Singleton
@Slf4j
public class ZoneStreamingManager {
	private static final WorkItem EMPTY = new WorkItem();
	private static final LinkedBlockingDeque<WorkItem> WORK_ITEM_POOL = new LinkedBlockingDeque<>();
	private static final LinkedBlockingDeque<WorkHandle> WORK_HANDLE_POOL = new LinkedBlockingDeque<>();

	static class WorkItem {
		public WorldViewContext viewContext;
		public ZoneSceneContext sceneContext;
		public int x, z;
		public Zone zone;
		public boolean highPriority;
		public WorkHandle handle;
	}

	public class WorkHandle {
		private boolean canceled;
		@Getter
		private boolean isComplete;
		private final Semaphore semaphore = new Semaphore(1);

		public void complete() throws InterruptedException {
			if(isComplete) return;
			if(client.isClientThread()) {
				while(semaphore.tryAcquire()) {
					HdPlugin.processPendingClientCallbacks(true);
				}
			} else {
				semaphore.acquire();
			}
		}

		public void cancel() {
			canceled = true;
		}

		public void release() {
			if(canceled) return;
			WORK_HANDLE_POOL.push(this);
		}
	}

	@Inject
	private HdPlugin plugin;

	@Inject
	private Client client;

	@Inject
	private ProceduralGenerator  proceduralGenerator;

	private final LinkedBlockingDeque<WorkItem> workQueue = new LinkedBlockingDeque<>();

	private Thread[] workers;
	private final Semaphore pauseSema = new Semaphore(0);
	private final Semaphore resumeSema = new Semaphore(0);
	private transient boolean paused;
	private transient boolean active;

	public void initialize() {
		workers = new Thread[HdPlugin.THREAD_COUNT];
		paused = false;
		active = true;

		for(int i = 0; i < HdPlugin.THREAD_COUNT; i++) {
			Thread newWorker = workers[i] = new Thread(this::workerRun);
			newWorker.setPriority(Thread.MAX_PRIORITY);
			newWorker.setName("117HD - Streaming Worker  " + i);
			newWorker.start();
		}
	}

	@SneakyThrows
	public WorkHandle queueZone(WorldViewContext viewContext, ZoneSceneContext sceneContext, Zone zone, int x, int z, boolean highPriority){
		assert viewContext != null : "WorldViewContext cant be null";
		WorkItem newItem = WORK_ITEM_POOL.peek() != null ? WORK_ITEM_POOL.poll() : new WorkItem();
		newItem.viewContext = viewContext;
		newItem.sceneContext = sceneContext;
		newItem.zone = zone;
		newItem.x = x;
		newItem.z = z;
		newItem.highPriority = highPriority;

		newItem.handle = WORK_HANDLE_POOL.peek() != null ? WORK_HANDLE_POOL.poll() : new WorkHandle();
		newItem.handle.canceled = false;
		newItem.handle.isComplete = false;
		newItem.handle.semaphore.drainPermits();

		if(highPriority) {
			workQueue.putFirst(newItem);
		} else {
			workQueue.putLast(newItem);
		}

		return newItem.handle;
	}

	public int getZoneStreamingCount() {
		return workQueue.size();
	}

	public void resumeStreaming() {
		if(!paused) return;
		log.debug("---- resumeStreaming ----");
		// Signal that the workers can reuse processing zones
		paused = false;
		resumeSema.release(workers.length);
		resumeSema.drainPermits();
	}

	public boolean isPaused() { return paused; }

	@SneakyThrows
	public void pauseStreaming() {
		if(paused) return;
		log.debug("---- pauseStreaming ----");
		// Signal that we should pause, wait for all workers to do so
		pauseSema.drainPermits();
		paused = true;
		// Ensure workers have woken up, since they might be blocked waiting for zone
		for(int  i = 0; i < HdPlugin.THREAD_COUNT; i++)
			workQueue.push(EMPTY);
		pauseSema.acquire(workers.length);
	}

	@SneakyThrows
	public void shutdown() {
		active = false;
		for(int i = 0; i < HdPlugin.THREAD_COUNT; i++) {
			workers[i].interrupt();
			workers[i].join();
		}
	}

	private void workerHandlePaused() throws InterruptedException {
		if(!paused) return;
		// Signal that where paused, then wait for the resume signal
		log.debug("---- " + Thread.currentThread().getName() + ": paused ----");
		pauseSema.release();
		resumeSema.acquire();
		log.debug("---- " + Thread.currentThread().getName() + ": resumed ----");
	}

	private void workerRun() {
		final SceneUploader uploader = plugin.getInjector().getInstance(SceneUploader.class);
		while (active) {
			try {
				if (workQueue.isEmpty())
					uploader.clear();
				workerHandlePaused();

				final WorkItem work = workQueue.take();
				if (work == EMPTY)
					continue;

				if(work.handle.canceled) {
					WORK_ITEM_POOL.put(work);
					WORK_HANDLE_POOL.put(work.handle);
					continue;
				}

				try {
					if (work.zone.needsTerrainGen) {
						proceduralGenerator.asyncProcGenTask.get(); // TODO: replace this with something like proceduralGenerator.waitForAsyncProcGen();
						proceduralGenerator.generateTerrainDataForZone(work.sceneContext, work.x, work.z);
						work.zone.needsTerrainGen = false;
					}

					final boolean isRebuild = work.zone.initialized || work.zone.invalidate || work.zone.cull;
					final Zone zone = isRebuild ? new Zone() : work.zone;

					uploader.setScene(work.sceneContext.scene);
					uploader.estimateZoneSize(work.sceneContext, zone, work.x, work.z);
					workerHandlePaused();

					plugin.queueClientCallbackBlock(
						work.highPriority, () -> {
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
							zone.setMetadata(work.viewContext, work.sceneContext, work.x, work.z);
						}
					);
					workerHandlePaused();

					uploader.uploadZone(work.sceneContext, zone, work.x, work.z);
					workerHandlePaused();

					plugin.queueClientCallbackBlock(
						work.highPriority, () -> {
							zone.unmap();
							zone.initialized = true;
							zone.dirty = isRebuild;

							if(isRebuild) {
								work.viewContext.zones[work.x][work.z] = zone;
							}

							work.handle.semaphore.release();
						}
					);
					workerHandlePaused();

					WORK_ITEM_POOL.put(work);
				} catch (Exception ex) {
					log.warn("Caught exception whilst processing zone [{}, {}] worldId [{}]", work.x, work.z, work.viewContext.worldViewId, ex);
				}
			} catch (Exception ex) {
				log.warn("Caught exception whilst waiting for work", ex);
			}
		}
	}
}
