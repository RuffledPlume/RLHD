package rs117.hd.renderer.zone;

import com.google.common.base.Stopwatch;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.FishingSpotReplacer;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.NpcDisplacementCache;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static net.runelite.api.Perspective.SCENE_SIZE;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static rs117.hd.HdPlugin.THREAD_POOL;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.renderer.zone.ZoneRenderer.eboAlpha;
import static rs117.hd.utils.MathUtils.*;

@Singleton
@Slf4j
public class SceneManager {
	private static final int ZONE_DEFER_DIST_START = 50 * LOCAL_TILE_SIZE;
	private static final int ZONE_DEFER_BLEND_RANGE = 250 * LOCAL_TILE_SIZE;
	private static final float ZONE_DEFER_DELAY = 2.0f;

	private static final int REUSE_STATE_NONE = -1;
	private static final int REUSE_STATE_PARTIAL = 0;
	private static final int REUSE_STATE_FULLY = 1;

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
	private UBOWorldViews uboWorldViews;

	private final WorldViewContext root = new WorldViewContext(null, null, null);
	private final WorldViewContext[] subs = new WorldViewContext[MAX_WORLDVIEWS];
	private ZoneSceneContext nextSceneContext;
	private Zone[][] nextZones;
	private Map<Integer, Integer> nextRoofChanges;

	class AsyncSceneUploaderGroup {
		private final SceneUploader[] sceneUploaders = new SceneUploader[HdPlugin.THREAD_COUNT];
		private int nextSceneUploaderIndex;

		public int getUploaderCount() {
			return sceneUploaders.length;
		}

		void init() {
			for (int i = 0; i < sceneUploaders.length; i++) {
				sceneUploaders[i] = plugin.getInjector().getInstance(SceneUploader.class);
			}
		}

		int calculateWorkPerUploader(int workLoad) {
			return (workLoad + sceneUploaders.length - 1) / sceneUploaders.length;
		}

		boolean isNextUploaderBusy() {
			return sceneUploaders[nextSceneUploaderIndex].isBusy();
		}

		SceneUploader nextUploader() {
			SceneUploader uploader = sceneUploaders[nextSceneUploaderIndex];
			nextSceneUploaderIndex = (nextSceneUploaderIndex + 1) % sceneUploaders.length;
			return uploader;
		}

		void completeAll() {
			boolean isClientThread = client.isClientThread();
			for (SceneUploader uploader : sceneUploaders) {
				uploader.completeTask(isClientThread);
			}
		}
	}

	private final AsyncSceneUploaderGroup rootAsyncSceneUploader = new AsyncSceneUploaderGroup();
	private final AsyncSceneUploaderGroup subSceneAsyncSceneUploader = new AsyncSceneUploaderGroup();
	private final AsyncSceneUploaderGroup rebuildAsyncSceneUploader = new AsyncSceneUploaderGroup();

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

	public void initialize() {
		rootAsyncSceneUploader.init();
		subSceneAsyncSceneUploader.init();
		rebuildAsyncSceneUploader.init();
	}

	public void shutdown() {
		rootAsyncSceneUploader.completeAll();
		subSceneAsyncSceneUploader.completeAll();
		rebuildAsyncSceneUploader.completeAll();

		root.free();

		for (int i = 0; i < subs.length; i++) {
			if (subs[i] != null)
				subs[i].free();
			subs[i] = null;
		}

		Zone.freeZones(nextZones);
		nextZones = null;
		nextRoofChanges = null;
		if (nextSceneContext != null)
			nextSceneContext.destroy();
		nextSceneContext = null;
	}

	public void update(WorldView wv) {
		updateAreaHiding();

		rebuild(wv);
		for (WorldEntity we : wv.worldEntities())
			rebuild(we.getWorldView());
	}

	private void updateAreaHiding() {
		Player localPlayer = client.getLocalPlayer();
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

	private void rebuild(WorldView wv) {
		assert client.isClientThread();
		WorldViewContext ctx = context(wv);
		if (ctx == null || ctx.isLoading)
			return;

		if(!ctx.rebuildZones.isEmpty() || rebuildAsyncSceneUploader.isNextUploaderBusy())
			return;

		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				if (!ctx.zones[x][z].invalidate && ctx.zones[x][z].uploaded)
					continue;

				if(ctx.zones[x][z].defferDelay > 0) {
					ctx.zones[x][z].defferDelay -= plugin.deltaTime;
					if(ctx.zones[x][z].defferDelay > 0) {
						continue;
					}
					ctx.zones[x][z].defferDelay = -1.0f;
				}

				if(ctx.zones[x][z].uploaded)
					ctx.zones[x][z].isRebuilding = true;

				ctx.rebuildZones.add(x * ctx.sizeZ + z);
			}
		}

		if(ctx.rebuildZones.isEmpty())
			return;

		ctx.newZones.clear();

		final SceneUploader uploader = rebuildAsyncSceneUploader.nextUploader();
		uploader.queueTask(() -> {
			uploader.setScene(ctx.sceneContext.scene);
			for (int i = 0; i < ctx.rebuildZones.size(); i++) {
				int zoneIdx = ctx.rebuildZones.get(i);
				int x = zoneIdx / NUM_ZONES;
				int z = zoneIdx % NUM_ZONES;

				Zone newZone = new Zone();
				ctx.newZones.add(newZone);

				if(ctx.zones[x][z] != null && ctx.zones[x][z].needsTerrainGen) {
					ctx.zones[x][z].needsTerrainGen = false;
					proceduralGenerator.generateTerrainDataForZone(ctx.sceneContext, x, z);
				}
				uploader.estimateZoneSize(ctx.sceneContext, newZone, x, z);
			}

			// Initialise buffers for new zone
			plugin.queueClientCallbackBlock(() -> {
				for (int i = 0; i < ctx.rebuildZones.size(); i++) {
					int zoneIdx = ctx.rebuildZones.get(i);
					int x = zoneIdx / NUM_ZONES;
					int z = zoneIdx % NUM_ZONES;

					Zone newZone = ctx.newZones.get(i);

					VBO o = null, a = null;
					int sz = newZone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0) {
						o = new VBO(sz);
						o.initialize(GL_STATIC_DRAW);
						o.map();
					}

					sz = newZone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0) {
						a = new VBO(sz);
						a.initialize(GL_STATIC_DRAW);
						a.map();
					}

					newZone.initialize(o, a, eboAlpha);
					newZone.setMetadata(ctx, x, z);
				}
			});

			for(int i = 0; i < ctx.newZones.size(); i++) {
				int zoneIdx = ctx.rebuildZones.get(i);
				int x = zoneIdx / NUM_ZONES;
				int z = zoneIdx % NUM_ZONES;

				Zone newZone = ctx.newZones.get(i);

				uploader.uploadZone(ctx.sceneContext, newZone, x, z);
			}

			CountDownLatch latch = new CountDownLatch(1);
			clientThread.invoke(() -> {
				for(int i = 0; i < ctx.newZones.size(); i++) {
					int zoneIdx = ctx.rebuildZones.get(i);
					int x = zoneIdx / NUM_ZONES;
					int z = zoneIdx % NUM_ZONES;

					Zone newZone = ctx.newZones.get(i);

					newZone.unmap();
					newZone.initialized = true;

					if(ctx.zones[x][z] != null) {
						newZone.dirty = ctx.zones[x][z].isRebuilding;
						ctx.zones[x][z].free();
					}
					ctx.zones[x][z] = newZone;
					log.trace("Rebuilt zone wv={} x={} z={}", wv.getId(), x, z);
				}
				latch.countDown();
			});
			try {
				latch.await();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			ctx.rebuildZones.clear();
			ctx.newZones.clear();
			uploader.clear();
		});
	}

	public void despawnWorldView(WorldView worldView) {
		int worldViewId = worldView.getId();
		if (worldViewId > -1) {
			log.debug("WorldView despawn: {}", worldViewId);
			if (subs[worldViewId] == null) {
				log.debug("Attempted to despawn unloaded worldview: {}", worldView);
			} else {
				subs[worldViewId].free();
				subs[worldViewId] = null;
			}
		}
	}

	public void reloadScene() {
		root.invalidate();
		for (var sub : subs)
			if (sub != null)
				sub.invalidate();
	}

	public boolean isLoadingScene() { return nextSceneContext != null; }

	private static int canReuse(Zone[][] zones, int zx, int zz) {
		if(zx < 0 || zx >= NUM_ZONES || zz < 0 || zz >= NUM_ZONES)
			return REUSE_STATE_NONE;
		Zone zone = zones[zx][zz];

		if (!zone.initialized)
			return REUSE_STATE_NONE;
		if (zone.sizeO == 0 && zone.sizeA == 0)
			return REUSE_STATE_NONE;

		for (int x = zx - 1; x <= zx + 1; ++x) {
			if (x < 0 || x >= NUM_ZONES)
				return REUSE_STATE_PARTIAL;
			for (int z = zz - 1; z <= zz + 1; ++z) {
				if (z < 0 || z >= NUM_ZONES)
					return REUSE_STATE_PARTIAL;

				if(x == zx && z == zz)
					continue;

				Zone neighbourZone = zones[zx][zz];
				if (!neighbourZone.initialized)
					return REUSE_STATE_PARTIAL;
				if (neighbourZone.sizeO == 0 && zone.sizeA == 0)
					return REUSE_STATE_PARTIAL;
			}
		}

		if(zone.dirty)
			return REUSE_STATE_PARTIAL;
		if(zone.hasWater)
			return REUSE_STATE_PARTIAL;

		return REUSE_STATE_FULLY;
	}

	private void calculateDeferDelay(ZoneSceneContext ctx, Zone zone, int x, int z, int[] playerPos) {
		int baseX = (x - (ctx.sceneOffset >> 3)) << 10;
		int baseZ = (z - (ctx.sceneOffset >> 3)) << 10;
		float dist = distance(vec(baseX, baseZ), vec(playerPos[0], playerPos[1]));

		float frac = clamp(((dist - ZONE_DEFER_DIST_START) / (float)ZONE_DEFER_BLEND_RANGE), 0.0f, 1.0f);
		zone.defferDelay = 1.0f + frac * ZONE_DEFER_DELAY;
	}

	private boolean shouldDeferZone(ZoneSceneContext ctx, Zone zone, int x, int z, int[] playerPos) {
		if(root.sceneContext == null || ctx.sceneBase == null || playerPos == null) {
			return false; // First load, no point deferring
		}

		int baseX = (x - (ctx.sceneOffset >> 3)) << 10;
		int baseZ = (z - (ctx.sceneOffset >> 3)) << 10;
		return distance(vec(baseX, baseZ), vec(playerPos[0], playerPos[1])) > ZONE_DEFER_DIST_START;
	}

	public void invalidateZone(Scene scene, int zx, int zz) {
		WorldViewContext ctx = context(scene);
		if(ctx == null) return;
		Zone z = ctx.zones[zx][zz];
		if (!z.invalidate) {
			z.invalidate = true;
			log.debug("Zone invalidated: wx={} x={} z={}", scene.getWorldViewId(), zx, zz);
		}
	}

	public void loadScene(WorldView worldView, Scene scene) {
		if (scene.getWorldViewId() > -1) {
			loadSubScene(worldView, scene);
			return;
		}

		assert scene.getWorldViewId() == -1;
		if (nextZones != null)
			throw new RuntimeException("Double zone load!"); // does this happen?

		rootAsyncSceneUploader.completeAll();
		rebuildAsyncSceneUploader.completeAll();

		if (nextSceneContext != null)
			nextSceneContext.destroy();
		nextSceneContext = null;

		nextSceneContext = new ZoneSceneContext(
			client,
			worldView,
			scene,
			plugin.getExpandedMapLoadingChunks(),
			root.sceneContext
		);
		nextSceneContext.enableAreaHiding = nextSceneContext.sceneBase != null && config.hideUnrelatedAreas();

		Stopwatch sw = Stopwatch.createStarted();
		environmentManager.loadSceneEnvironments(nextSceneContext);
		log.debug("Loaded scene environments in {}", sw);

		LocalPoint lp = client.getLocalPlayer().getLocalLocation();
		final int[] nextPlayerPos;
		if(nextSceneContext.sceneBase != null) {
			nextPlayerPos = new int[] {
				((lp.getX()) + nextSceneContext.sceneBase[0]) + (NUM_ZONES * LOCAL_TILE_SIZE) ,
				((lp.getY()) + nextSceneContext.sceneBase[1]) + (NUM_ZONES * LOCAL_TILE_SIZE)
			};
		} else {
			nextPlayerPos = null;
		}


		Future<?> procGenTask = THREAD_POOL.submit(() -> proceduralGenerator.generateSceneData(nextSceneContext));

		sw.reset().start();

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
		log.debug("area hiding time: {}", sw);

		sw.reset().start();
		WorldViewContext ctx = root;
		Scene prev = client.getTopLevelWorldView().getScene();

		int dx = scene.getBaseX() - prev.getBaseX() >> 3;
		int dy = scene.getBaseY() - prev.getBaseY() >> 3;

		// Initially mark every zone as being no longer in use
		for (int x = 0; x < NUM_ZONES; ++x)
			for (int z = 0; z < NUM_ZONES; ++z)
				ctx.zones[x][z].cull = true;

		nextZones = new Zone[NUM_ZONES][NUM_ZONES];
		if (ctx.sceneContext != null && ctx.sceneContext.currentArea == nextSceneContext.currentArea) {
			// Find zones which overlap, and reuse them
			if (prev.isInstance() == scene.isInstance() && prev.getRoofRemovalMode() == scene.getRoofRemovalMode()) {
				int[][][] prevTemplates = prev.getInstanceTemplateChunks();
				int[][][] curTemplates = scene.getInstanceTemplateChunks();

				for (int x = 0; x < NUM_ZONES; ++x) {
					next:
					for (int z = 0; z < NUM_ZONES; ++z) {
						int ox = x + dx;
						int oz = z + dy;

						int reuseState = canReuse(ctx.zones, ox, oz);
						if (reuseState == REUSE_STATE_NONE)
							continue;

						if (scene.isInstance()) {
							// Convert from modified chunk coordinates to Jagex chunk coordinates
							int jx = x - nextSceneContext.sceneOffset / 8;
							int jz = z - nextSceneContext.sceneOffset / 8;
							int jox = ox - nextSceneContext.sceneOffset / 8;
							int joz = oz - nextSceneContext.sceneOffset / 8;
							// Check Jagex chunk coordinates are within the Jagex scene
							if (jx >= 0 && jx < SCENE_SIZE / 8 && jz >= 0 && jz < SCENE_SIZE / 8) {
								if (jox >= 0 && jox < SCENE_SIZE / 8 && joz >= 0 && joz < SCENE_SIZE / 8) {
									for (int level = 0; level < 4; ++level) {
										int prevTemplate = prevTemplates[level][jox][joz];
										int curTemplate = curTemplates[level][jx][jz];
										if (prevTemplate != curTemplate) {
											// Does this ever happen?
											log.warn("Instance template reuse mismatch! prev={} cur={}", prevTemplate, curTemplate);
											continue next;
										}
									}
								}
							}
						}

						Zone old = ctx.zones[ox][oz];
						assert old.initialized;
						assert old.sizeO > 0 || old.sizeA > 0;
						assert old.cull;

						if (reuseState == REUSE_STATE_PARTIAL) {
							if(nextPlayerPos == null)
								continue;
							calculateDeferDelay(nextSceneContext, old, x, z, nextPlayerPos);
							old.invalidate = true;
						}

						old.cull = false;
						old.metadataDirty = true;

						nextZones[x][z] = old;
					}
				}
			} else {
				log.debug("Couldn't reuse anything! \nprev.isInstance()={} cur.isInstance={}\nprev.roofRemovalMode={} cur.roofRemovalMode={}",
					prev.isInstance(), scene.isInstance(),
					prev.getRoofRemovalMode(), scene.getRoofRemovalMode());
			}
		}
		log.debug("zone reuse time: {}", sw);

		nextRoofChanges = new HashMap<>();
		final int[][][] prids = prev.getRoofs();
		final int[][][] nrids = scene.getRoofs();

		int totalZones = NUM_ZONES * NUM_ZONES;
		int chunk = rootAsyncSceneUploader.calculateWorkPerUploader(totalZones);
		for (int i = 0; i < rootAsyncSceneUploader.getUploaderCount(); i++) {
			int start = i * chunk;
			int end = Math.min(start + chunk, totalZones);

			if (start >= end)
				break;

			final SceneUploader uploader = rootAsyncSceneUploader.nextUploader();
			uploader.queueTask(() -> {
				uploader.setScene(scene);

				// Ensure proc gen has finished before we start uploading
				try {
					procGenTask.get();
				} catch (InterruptedException | ExecutionException e) {
					throw new RuntimeException(e);
				}

				for (int idx = start; idx < end; idx++) {
					int x = idx / NUM_ZONES;
					int z = idx % NUM_ZONES;

					Zone zone = nextZones[x][z];

					if(zone == null)
						zone = nextZones[x][z] = new Zone();

					if (!zone.initialized || zone.defferDelay >= 0) {
						if(!shouldDeferZone(nextSceneContext, zone, x, z, nextPlayerPos)) {
							proceduralGenerator.generateTerrainDataForZone(nextSceneContext, x, z);
							uploader.estimateZoneSize(nextSceneContext, zone, x, z);
							nextSceneContext.totalOpaque += zone.sizeO;
							nextSceneContext.totalAlpha += zone.sizeA;
							nextSceneContext.totalNewZones++;
						} else {
							calculateDeferDelay(nextSceneContext, zone, x, z, nextPlayerPos);
							zone.needsTerrainGen = true;
							nextSceneContext.totalDeferred++;
						}
					} else {
						nextSceneContext.totalReused++;
					}
				}

				// allocate buffers for zones which require upload
				plugin.queueClientCallbackBlock(() -> {
					for (int idx = start; idx < end; idx++) {
						int x = idx / NUM_ZONES;
						int z = idx % NUM_ZONES;

						Zone zone = nextZones[x][z];
						if (zone.initialized || zone.defferDelay >= 0)
							continue;

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
					}
				});

				// Upload new zones
				for (int idx = start; idx < end; idx++) {
					int x = idx / NUM_ZONES;
					int z = idx % NUM_ZONES;

					Zone zone = nextZones[x][z];
					if (!zone.initialized && zone.defferDelay < 0) {
						uploader.uploadZone(nextSceneContext, zone, x, z);
					}

					// Calculate roof ids for the zone
					for (int level = 0; level < 4; ++level) {
						int ox = x + (dx << 3);
						int oz = z + (dy << 3);

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
				uploader.clear();
			});
		}
	}

	public void swapScene(Scene scene) {
		if (!plugin.isActive() || plugin.skipScene == scene) {
			plugin.redrawPreviousFrame = true;
			return;
		}

		if (scene.getWorldViewId() > -1) {
			swapSubScene(scene);
			return;
		}

		// If the scene wasn't loaded by a call to loadScene, load it synchronously instead
		// TODO: Low memory mode
		if (nextSceneContext == null) {
//			loadSceneInternal(scene);
//			if (nextSceneContext == null)
			return; // Return early if scene loading failed
		}
		Stopwatch sw = Stopwatch.createStarted();

		lightManager.loadSceneLights(nextSceneContext, root.sceneContext);
		fishingSpotReplacer.despawnRuneLiteObjects();
		npcDisplacementCache.clear();

		boolean isFirst = root.sceneContext == null;
		if (!isFirst)
			root.sceneContext.destroy(); // Destroy the old context before replacing it

		log.debug("preSwapScene time: {}", sw);
		sw.reset().start();

		rootAsyncSceneUploader.completeAll();

		log.debug(
			"upload time {} reused {} deferred {} new {} len opaque {} size opaque {} KiB len alpha {} size alpha {} KiB",
			sw, nextSceneContext.totalReused, nextSceneContext.totalNewZones, nextSceneContext.totalDeferred,
			nextSceneContext.totalOpaque, ((long) nextSceneContext.totalOpaque * Zone.VERT_SIZE * 3) / KiB,
			nextSceneContext.totalAlpha, ((long) nextSceneContext.totalAlpha * Zone.VERT_SIZE * 3) / KiB
		);
		sw.reset().start();

		root.sceneContext = nextSceneContext;
		nextSceneContext = null;

		updateAreaHiding();

		if (root.sceneContext.intersects(areaManager.getArea("PLAYER_OWNED_HOUSE"))) {
			plugin.isInHouse = true;
			plugin.isInChambersOfXeric = false;
		} else {
			plugin.isInHouse = false;
			plugin.isInChambersOfXeric = root.sceneContext.intersects(areaManager.getArea("CHAMBERS_OF_XERIC"));
		}

		WorldViewContext ctx = root;
		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				Zone zone = ctx.zones[x][z];

				if (zone.cull) {
					zone.free();
				} else {
					// reused zone
					zone.updateRoofs(nextRoofChanges);
				}
			}
		}
		nextRoofChanges = null;

		ctx.zones = nextZones;
		nextZones = null;

		// setup vaos
		for (int x = 0; x < ctx.zones.length; ++x) {
			for (int z = 0; z < ctx.zones[0].length; ++z) {
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized && zone.uploaded) {
					zone.unmap();
					zone.initialized = true;
				}

				zone.setMetadata(ctx, x, z);
			}
		}

		root.isLoading = false;

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

		log.debug("swapScene time: {}", sw);

		checkGLErrors();
	}

	private void loadSubScene(WorldView worldView, Scene scene) {
		int worldViewId = worldView.getId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);

		WorldViewContext prevCtx = subs[worldViewId];
		if (prevCtx != null) {
			log.error("Reload of an already loaded sub scene?");
			prevCtx.free();
		}
		assert prevCtx == null;

		var sceneContext = new ZoneSceneContext(client, worldView, scene, plugin.getExpandedMapLoadingChunks(), null);
		proceduralGenerator.generateSceneData(sceneContext);

		final WorldViewContext ctx = new WorldViewContext(worldView, sceneContext, uboWorldViews);
		subs[worldViewId] = ctx;

		int totalZones = ctx.sizeX * ctx.sizeZ;
		int chunk = subSceneAsyncSceneUploader.calculateWorkPerUploader(totalZones);
		for (int i = 0; i < subSceneAsyncSceneUploader.getUploaderCount(); i++) {
			int start = i * chunk;
			int end = Math.min(start + chunk, totalZones);

			if (start >= end)
				break;

			final SceneUploader uploader = subSceneAsyncSceneUploader.nextUploader();
			uploader.queueTask(() -> {
				uploader.setScene(scene);
				for (int idx = start; idx < end; idx++) {
					int x = idx % ctx.sizeX;
					int z = idx / ctx.sizeX;

					proceduralGenerator.generateTerrainDataForZone(sceneContext, x, z);
					uploader.estimateZoneSize(sceneContext, ctx.zones[x][z], x, z);
				}

				// allocate buffers for zones which require upload
				plugin.queueClientCallbackBlock(() -> {
					ctx.initMetadata();

					for (int idx = start; idx < end; idx++) {
						int x = idx % ctx.sizeX;
						int z = idx / ctx.sizeX;

						Zone zone = ctx.zones[x][z];
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
						zone.setMetadata(ctx, x, z);
					}
				});

				// Upload new zones
				for (int idx = start; idx < end; idx++) {
					int x = idx % ctx.sizeX;
					int z = idx / ctx.sizeX;

					uploader.uploadZone(sceneContext, ctx.zones[x][z], x, z);
				}
				uploader.clear();
			});
		}
	}

	private void swapSubScene(Scene scene) {
		WorldViewContext ctx = context(scene);
		if (ctx == null)
			return;

		Stopwatch sw = Stopwatch.createStarted();
		subSceneAsyncSceneUploader.completeAll();

		// setup vaos
		for (int x = 0; x < ctx.sizeX; ++x) {
			for (int z = 0; z < ctx.sizeZ; ++z) {
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized) {
					zone.unmap();
					zone.initialized = true;
				}

				zone.setMetadata(ctx, x, z);
			}
		}
		ctx.isLoading = false;
		log.debug("swapSubScene time {} WorldView ready: {}", sw, scene.getWorldViewId());
	}
}
