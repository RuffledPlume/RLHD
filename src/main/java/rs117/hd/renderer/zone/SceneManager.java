package rs117.hd.renderer.zone;

import com.google.common.base.Stopwatch;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.WorldViewContext.SwapZone;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.FishingSpotReplacer;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.NpcDisplacementCache;
import rs117.hd.utils.jobs.JobGenericTask;
import rs117.hd.utils.jobs.JobSystem;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.SCENE_SIZE;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;

@Singleton
@Slf4j
public class SceneManager {
	private static final int ZONE_DEFER_DIST_START = 3;

	public static final int MAX_WORLDVIEWS = 4096;

	public static final int NUM_ZONES = EXTENDED_SCENE_SIZE >> 3;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	@Inject
	private AreaManager areaManager;

	@Inject
	private LightManager lightManager;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	@Inject
	private JobSystem jobSystem;

	@Inject
	private FrameTimer frameTimer;

	private UBOWorldViews uboWorldViews;

	private final Map<Integer, Integer> nextRoofChanges = new HashMap<>();
	private final WorldViewContext root = new WorldViewContext(null, null, null);
	private final WorldViewContext[] subs = new WorldViewContext[MAX_WORLDVIEWS];
	private final List<SortedZone> sortedZones = new ArrayList<>();
	private ZoneSceneContext nextSceneContext;
	private Zone[][] nextZones;
	private boolean reloadRequested;

	@Getter
	public long sceneLoadTime;
	@Getter
	public long sceneUploadTime;
	@Getter
	public long sceneSwapTime;
	@Getter
	public final ReentrantLock loadingLock = new ReentrantLock();

	public boolean isTopLevelValid() {
		return root != null && root.sceneContext != null;
	}

	@Nullable
	public ZoneSceneContext getSceneContext() {
		return root.sceneContext;
	}

	@Nonnull
	public WorldViewContext getRoot() { return root; }

	public boolean isRoot(WorldViewContext context) { return root == context; }

	public WorldViewContext context(Scene scene) {
		return context(scene.getWorldViewId());
	}

	public WorldViewContext context(WorldView wv) {
		return context(wv.getId());
	}

	public WorldViewContext context(int worldViewId) {
		if (worldViewId != -1)
			return subs[worldViewId];
		if (root.sceneContext == null)
			return null;
		return root;
	}

	public void initialize(UBOWorldViews uboWorldViews) {
		this.uboWorldViews = uboWorldViews;

		root.sceneLoadGroup = jobSystem.getWorkGroup(true);
		root.streamingGroup = jobSystem.getWorkGroup(false);
	}

	public void shutdown() {
		root.free(false);

		for (int i = 0; i < subs.length; i++) {
			if (subs[i] != null)
				subs[i].free(false);
			subs[i] = null;
		}

		Zone.freeZones(nextZones);
		nextZones = null;
		if (nextSceneContext != null)
			nextSceneContext.destroy();
		nextSceneContext = null;

		uboWorldViews = null;
	}

	public void update() {
		frameTimer.begin(Timer.UPDATE_AREA_HIDING);
		updateAreaHiding();
		frameTimer.end(Timer.UPDATE_AREA_HIDING);

		if(reloadRequested && loadingLock.getHoldCount() == 0) {
			reloadRequested = false;

			completeAllStreaming();
			try {
				loadingLock.lock();

				WorldView wv = client.getTopLevelWorldView();
				if(wv != null) {
					despawnWorldView(wv);
					loadScene(wv, wv.getScene());
					swapScene(wv.getScene());

					for (WorldEntity we : wv.worldEntities()) {
						WorldView ewv = we.getWorldView();

						despawnWorldView(ewv);
						loadScene(ewv, ewv.getScene());
						swapScene(ewv.getScene());
					}
				}
			} finally {
				loadingLock.unlock();
			}
			return;
		}

		boolean anyScenesLoading = root.isLoading;
		boolean queuedWork = root.update();

		WorldView wv = client.getTopLevelWorldView();
		if(wv != null) {
			for (WorldEntity we : wv.worldEntities()) {
				WorldViewContext ctx = context(we.getWorldView());
				if (ctx != null) {
					anyScenesLoading = anyScenesLoading || ctx.isLoading;
					queuedWork = ctx.update() || queuedWork;
				}
			}
		}

		if(queuedWork)
			jobSystem.wakeWorkers();

		if(plugin.isInHouse && queuedWork)
			root.streamingGroup.complete();
	}

	private void updateAreaHiding() {
		Player localPlayer = client.getLocalPlayer();
		if(!isTopLevelValid() || localPlayer == null || root.isLoading)
			return;

		var lp = localPlayer.getLocalLocation();
		if (root.sceneContext.enableAreaHiding) {
			var base = root.sceneContext.sceneBase;
			assert base != null;
			int[] worldPos = {
				base[0] + lp.getSceneX(),
				base[1] + lp.getSceneY(),
				base[2] + client.getTopLevelWorldView().getPlane()
			};

			// We need to check all areas contained in the scene in the order they appear in the list,
			// in order to ensure lower floors can take precedence over higher floors which include tiny
			// portions of the floor beneath around stairs and ladders
			Area newArea = null;
			for (var area : root.sceneContext.possibleAreas) {
				if (area.containsPoint(false, worldPos)) {
					newArea = area;
					break;
				}
			}

			// Force a scene reload if the player is no longer in the same area
			if (newArea != root.sceneContext.currentArea) {
				if (plugin.justChangedArea) {
					// Disable area hiding if it somehow gets stuck in a loop switching areas
					root.sceneContext.enableAreaHiding = false;
					log.error(
						"Disabling area hiding after moving from {} to {} at {}",
						root.sceneContext.currentArea,
						newArea,
						worldPos
					);
					newArea = null;
				} else {
					plugin.justChangedArea = true;
					// This should happen very rarely, so we invalidate all zones for simplicity
					root.invalidate();
				}
				root.sceneContext.currentArea = newArea;
			} else {
				plugin.justChangedArea = false;
			}
		} else {
			plugin.justChangedArea = false;
		}
	}

	public void despawnWorldView(WorldView worldView) {
		int worldViewId = worldView.getId();
		if (worldViewId > -1) {
			log.debug("WorldView despawn: {}", worldViewId);
			if (subs[worldViewId] == null) {
				log.debug("Attempted to despawn unloaded worldview: {}", worldView);
			} else {
				subs[worldViewId].free(true);
				subs[worldViewId] = null;
			}
		} else if(worldViewId == root.worldViewId) {
			root.free(true);

			root.sceneLoadGroup = jobSystem.getWorkGroup(true);
			root.streamingGroup = jobSystem.getWorkGroup(false);
		}
	}

	public void reloadScene() {
		if(!plugin.isActive() || reloadRequested)
			return;

		reloadRequested = true;
		log.debug("Reload scene requested");
	}

	public boolean isLoadingScene() { return nextSceneContext != null; }

	public void completeAllStreaming() {
		if (root.sceneLoadGroup != null)
			root.sceneLoadGroup.complete();

		if (root.streamingGroup != null)
			root.streamingGroup.complete();

		WorldView wv = client.getTopLevelWorldView();
		if(wv != null) {
			for (WorldEntity we : wv.worldEntities()) {
				WorldViewContext ctx = context(we.getWorldView());
				if (ctx != null) {
					if (ctx.sceneLoadGroup != null)
						ctx.sceneLoadGroup.complete();

					if (ctx.streamingGroup != null)
						ctx.streamingGroup.complete();
				}
			}
		}
	}

	public void invalidateZone(Scene scene, int zx, int zz) {
		try {
			loadingLock.lock();
			WorldViewContext ctx = context(scene);
			if (ctx == null)
				return;

			if(ctx.zones[zx][zz].zoneUploadHandle != null)
				ctx.zones[zx][zz].zoneUploadHandle.cancel(true);

			if(ctx.zones[zx][zz].rebuild)
				return;

			ctx.zones[zx][zz].rebuild = true;
			log.debug("Zone invalidated: wx={} x={} z={}", scene.getWorldViewId(), zx, zz);
		} finally {
			loadingLock.unlock();
		}
	}

	private static boolean isEdgeTile(Zone[][] zones, int zx, int zz) {
		for (int x = zx - 2; x <= zx + 2; ++x) {
			if (x < 0 || x >= NUM_ZONES)
				return true;
			for (int z = zz - 2; z <= zz + 2; ++z) {
				if (z < 0 || z >= NUM_ZONES)
					return true;
				Zone zone = zones[x][z];
				if (!zone.initialized)
					return true;
				if (zone.sizeO == 0 && zone.sizeA == 0)
					return true;
			}
		}
		return false;
	}

	public synchronized void loadScene(WorldView worldView, Scene scene) {
		try {
			loadingLock.lock();
			if (worldView.getId() > -1) {
				loadSubScene(worldView, scene);
				return;
			}

			assert worldView.getId() == -1;
			if (nextZones != null)
				throw new RuntimeException("Double zone load!"); // does this happen?

			Stopwatch sw = Stopwatch.createStarted();
			root.isLoading = true;
			root.sceneLoadGroup.cancel();
			root.streamingGroup.cancel();

			if (nextSceneContext != null)
				nextSceneContext.destroy();
			nextSceneContext = null;

			SwapZone swapZone;
			while ((swapZone = root.pendingSwap.poll()) != null)
				root.pendingCull.add(swapZone.zone);

			nextZones = new Zone[NUM_ZONES][NUM_ZONES];
			nextSceneContext = new ZoneSceneContext(
				client,
				worldView,
				scene,
				plugin.getExpandedMapLoadingChunks(),
				root.sceneContext
			);

			WorldViewContext ctx = root;
			Scene prev = client.getTopLevelWorldView().getScene();

			nextSceneContext.enableAreaHiding = nextSceneContext.sceneBase != null && config.hideUnrelatedAreas();

			environmentManager.loadSceneEnvironments(nextSceneContext);

			root.procGenHandle = JobGenericTask.build(
					"ProceduralGenerator::generateSceneData",
					(task) -> {
						proceduralGenerator.generateSceneData(nextSceneContext);
						root.procGenHandle = null;
					}
				)
				.queue(true);

			root.lightJobHandle = JobGenericTask.build(
					"lightManager::loadSceneLights",
					(task) -> lightManager.loadSceneLights(nextSceneContext, root.sceneContext)
				)
				.queue(true);

			final int dx = scene.getBaseX() - prev.getBaseX() >> 3;
			final int dy = scene.getBaseY() - prev.getBaseY() >> 3;

			// Calculate roof ids for the zone
			final int[][][] prids = prev.getRoofs();
			final int[][][] nrids = scene.getRoofs();

			nextRoofChanges.clear();
			var roofJobHandle = JobGenericTask.build(
				"CalculateRoofChanges",
				(task) -> {
					for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
						for (int z = 0; z < EXTENDED_SCENE_SIZE; ++z) {
							int ox = x + (dx << 3);
							int oz = z + (dy << 3);

							for (int level = 0; level < 4; ++level) {
								task.workerHandleCancel();
								// old zone still in scene?
								if (ox >= 0 && oz >= 0 && ox < EXTENDED_SCENE_SIZE && oz < EXTENDED_SCENE_SIZE) {
									int prid = prids[level][ox][oz];
									int nrid = nrids[level][x][z];
									if (prid > 0 && nrid > 0 && prid != nrid) {
										Integer old = nextRoofChanges.putIfAbsent(prid, nrid);
										if (old == null) {
											log.trace("Roof change: {} -> {}", prid, nrid);
										} else if (old != nrid) {
											log.debug("Roof change mismatch: {} -> {} vs {}", prid, nrid, old);
										}
									}
								}
							}
						}
					}
				}
			).queue(true);

			if (nextSceneContext.enableAreaHiding) {
				assert nextSceneContext.sceneBase != null;
				int centerOffset = SCENE_SIZE / 2 & ~7;
				int centerX = nextSceneContext.sceneBase[0] + centerOffset;
				int centerY = nextSceneContext.sceneBase[1] + centerOffset;

				nextSceneContext.possibleAreas = Arrays
					.stream(areaManager.areasWithAreaHiding)
					.filter(area -> nextSceneContext.sceneBounds.intersects(area.aabbs))
					.toArray(Area[]::new);

				if (log.isDebugEnabled() && nextSceneContext.possibleAreas.length > 0) {
					log.debug(
						"Area hiding areas: {}",
						Arrays.stream(nextSceneContext.possibleAreas)
							.distinct()
							.map(Area::toString)
							.collect(Collectors.joining(", "))
					);
				}

				// If area hiding can be decided based on the central chunk, apply it early
				rs117.hd.scene.areas.AABB centerChunk = new AABB(centerX, centerY, centerX + 7, centerY + 7);
				for (Area possibleArea : nextSceneContext.possibleAreas) {
					if (!possibleArea.intersects(centerChunk))
						continue;

					if (nextSceneContext.currentArea != null) {
						// Multiple possible areas, so let's defer this until swapScene
						nextSceneContext.currentArea = null;
						break;
					}
					nextSceneContext.currentArea = possibleArea;
				}
			}

			for (int x = 0; x < NUM_ZONES; ++x)
				for (int z = 0; z < NUM_ZONES; ++z)
					ctx.zones[x][z].cull = true;

			if (ctx.sceneContext != null &&
				prev.isInstance() == scene.isInstance() &&
				ctx.sceneContext.expandedMapLoadingChunks == nextSceneContext.expandedMapLoadingChunks &&
				ctx.sceneContext.currentArea == nextSceneContext.currentArea) {
				for (int x = 0; x < NUM_ZONES; ++x) {
					for (int z = 0; z < NUM_ZONES; ++z) {
						int ox = x + dx;
						int oz = z + dy;
						if (ox < 0 || ox >= NUM_ZONES || oz < 0 || oz >= NUM_ZONES)
							continue;

						Zone old = ctx.zones[ox][oz];
						if (!old.initialized || (old.sizeO == 0 && old.sizeA == 0))
							continue;

						old.cull = false;

						if (old.hasWater || old.dirty || isEdgeTile(ctx.zones, ox, oz)) {
							float dist = distance(vec(x, z), vec(NUM_ZONES / 2, NUM_ZONES / 2));
							sortedZones.add(SortedZone.getZone(old, x, z, dist));
							nextSceneContext.totalDeferred++;
						}

						JobGenericTask.build(
								"updateRoof",
								(task) -> old.updateRoofs(nextRoofChanges)
							)
							.queue(true, roofJobHandle);

						nextZones[x][z] = old;
						nextSceneContext.totalReused++;
					}
				}
			}

			for (int x = 0; x < NUM_ZONES; ++x) {
				for (int z = 0; z < NUM_ZONES; ++z) {
					Zone zone = nextZones[x][z];
					if (zone == null)
						zone = nextZones[x][z] = new Zone();

					if (!zone.initialized) {
						float dist = distance(vec(x, z), vec(NUM_ZONES / 2, NUM_ZONES / 2));
						if (root.sceneContext == null || dist < ZONE_DEFER_DIST_START) {
							zone.zoneUploadHandle = ZoneUploadTask
								.build(ctx, nextSceneContext, zone, x, z, false)
								.queue(ctx.sceneLoadGroup, root.procGenHandle);
							nextSceneContext.totalMapZones++;
						} else {
							sortedZones.add(SortedZone.getZone(zone, x, z, dist));
							nextSceneContext.totalDeferred++;
						}
					}
				}
			}

			sortedZones.sort(SortedZone::compareTo);
			for (SortedZone sorted : sortedZones) {
				Zone newZone = new Zone();
				newZone.dirty = sorted.zone.dirty;

				sorted.zone.zoneUploadHandle = ZoneUploadTask
						.build(ctx, nextSceneContext, newZone, sorted.x, sorted.z, true)
						.queue(ctx.streamingGroup, root.procGenHandle);
				sorted.free();
			}
			sortedZones.clear();

			jobSystem.wakeWorkers();

			sceneLoadTime = sw.elapsed(TimeUnit.NANOSECONDS);
			log.debug("loadScene time: {}", sw);
		} finally {
			loadingLock.unlock();
		}
	}

	@SneakyThrows
	public void swapScene(Scene scene) {
		if (!plugin.isActive() || plugin.skipScene == scene) {
			plugin.redrawPreviousFrame = true;
			return;
		}

		if (scene.getWorldViewId() > -1) {
			swapSubScene(scene);
			return;
		}

		if (nextSceneContext == null)
			return; // Return early if scene loading failed

		Stopwatch sw = Stopwatch.createStarted();

		// Handle object spawns that must be processed on the client thread
		root.lightJobHandle.await(true);

		for (var tileObject : nextSceneContext.lightSpawnsToHandleOnClientThread)
			lightManager.handleObjectSpawn(nextSceneContext, tileObject);
		nextSceneContext.lightSpawnsToHandleOnClientThread.clear();

		fishingSpotReplacer.despawnRuneLiteObjects();

		npcDisplacementCache.clear();

		if (nextSceneContext.intersects(areaManager.getArea("PLAYER_OWNED_HOUSE"))) {
			plugin.isInHouse = true;
			plugin.isInChambersOfXeric = false;
		} else {
			plugin.isInHouse = false;
			plugin.isInChambersOfXeric = nextSceneContext.intersects(areaManager.getArea("CHAMBERS_OF_XERIC"));
		}

		boolean isFirst = root.sceneContext == null;
		if (!isFirst)
			root.sceneContext.destroy(); // Destroy the old context before replacing it

		long sceneUploadTimeStart = sw.elapsed(TimeUnit.NANOSECONDS);
		int blockingCount = root.sceneLoadGroup.getPendingCount();
		root.sceneLoadGroup.complete();
		if(plugin.isInHouse)
			root.streamingGroup.complete();

		int totalOpaque = 0;
		int totalAlpha = 0;
		for(int x = 0; x < NUM_ZONES; ++x) {
			for(int z = 0; z < NUM_ZONES; ++z) {
				totalOpaque += nextZones[x][z].bufLen;
				totalAlpha += nextZones[x][z].bufLenA;
			}
		}

		sceneUploadTime = sw.elapsed(TimeUnit.NANOSECONDS) - sceneUploadTimeStart;
		log.debug(
			"upload time {} reused {} deferred {} map {} sceneLoad {} len opaque {} size opaque {} KiB len alpha {} size alpha {} KiB",
			TimeUnit.MILLISECONDS.convert(sceneUploadTime, TimeUnit.NANOSECONDS), nextSceneContext.totalReused, nextSceneContext.totalDeferred, nextSceneContext.totalMapZones, blockingCount,
			totalOpaque, ((long) totalOpaque * Zone.VERT_SIZE * 3) / KiB,
			totalAlpha, ((long) totalAlpha * Zone.VERT_SIZE * 3) / KiB
		);

		WorldViewContext ctx = root;
		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				Zone zone = ctx.zones[x][z];

				if (zone.cull)
					root.pendingCull.add(zone);

				nextZones[x][z].setMetadata(ctx, nextSceneContext, x, z);
			}
		}

		ctx.zones = nextZones;
		root.sceneContext = nextSceneContext;
		root.isLoading = false;

		nextZones = null;
		nextSceneContext = null;
		root.lightJobHandle = null;

		if (isFirst) {
			// Load all pre-existing sub scenes on the first scene load
			for (WorldEntity subEntity : client.getTopLevelWorldView().worldEntities()) {
				WorldView sub = subEntity.getWorldView();
				Scene subScene = sub.getScene();
				log.debug(
					"Loading worldview: id={}, sizeX={}, sizeZ={}",
					sub.getId(),
					sub.getSizeX(),
					sub.getSizeY()
				);
				loadSubScene(sub, subScene);
				swapSubScene(subScene);
			}
		}

		checkGLErrors();
		sceneSwapTime = sw.elapsed(TimeUnit.NANOSECONDS);
		log.debug("swapScene time: {}", sw);
	}

	private void loadSubScene(WorldView worldView, Scene scene) {
		int worldViewId = worldView.getId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);

		final WorldViewContext prevCtx = subs[worldViewId];
		if (prevCtx != null) {
			log.error("Reload of an already loaded sub scene?");
			prevCtx.sceneLoadGroup.cancel();
			prevCtx.streamingGroup.cancel();
			clientThread.invoke(() -> prevCtx.free(false));
		}

		var sceneContext = new ZoneSceneContext(client, worldView, scene, plugin.getExpandedMapLoadingChunks(), null);
		proceduralGenerator.generateSceneData(sceneContext);

		final WorldViewContext ctx = new WorldViewContext(worldView, sceneContext, uboWorldViews);
		ctx.sceneLoadGroup = jobSystem.getWorkGroup(true);
		ctx.streamingGroup = jobSystem.getWorkGroup(false);
		subs[worldViewId] = ctx;

		for(int x = 0; x <  ctx.sizeX; ++x) {
			for(int z = 0; z < ctx.sizeZ; ++z) {
				ctx.zones[x][z].zoneUploadHandle = ZoneUploadTask.build(ctx, sceneContext, ctx.zones[x][z], x, z, false).queue(ctx.sceneLoadGroup);

				if(root.sceneContext == null) {
					// Wait on each zone load to prevent boats from erroring on first load. (Calling LoadSubScene during initial load might be the root cause)
					ctx.zones[x][z].zoneUploadHandle.await();
				}
			}
		}

		jobSystem.wakeWorkers();
	}

	private void swapSubScene(Scene scene) {
		WorldViewContext ctx = context(scene);
		if (ctx == null)
			return;

		Stopwatch sw = Stopwatch.createStarted();
		ctx.isLoading = false;
		ctx.initMetadata();

		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				ctx.zones[x][z].setMetadata(ctx, ctx.sceneContext, x, z);
			}
		}

		log.debug("swapSubScene time {} WorldView ready: {}", sw, scene.getWorldViewId());
	}

	static class SortedZone implements Comparable<SortedZone> {
		private static final ArrayDeque<SortedZone> POOL = new ArrayDeque<>();

		public Zone zone;
		public int x, z;
		public float dist;

		public static SortedZone getZone(Zone zone, int x, int z, float dist) {
			SortedZone sorted = POOL.poll();
			if(sorted == null)
				sorted = new SortedZone();
			sorted.zone = zone;
			sorted.x = x;
			sorted.z = z;
			sorted.dist = dist;
			return sorted;
		}

		public void free() { POOL.add(this); }

		@Override
		public int compareTo(SortedZone o) {
			return Float.compare(dist, o.dist);
		}
	}
}
