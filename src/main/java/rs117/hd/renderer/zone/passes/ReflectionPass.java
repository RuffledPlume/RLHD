package rs117.hd.renderer.zone.passes;

import java.awt.Color;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.hooks.*;
import rs117.hd.HdPlugin;
import rs117.hd.config.ReflectionMode;
import rs117.hd.opengl.shader.SceneShaderProgram;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.uniforms.UBOReflectionPlanes;
import rs117.hd.opengl.uniforms.UBOReflectionPlanes.WaterPlaneStruct;
import rs117.hd.overlays.ReflectionMapOverlay;
import rs117.hd.renderer.zone.ModelStreamingManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.Camera;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.DebugDraw;
import rs117.hd.utils.DeveloperTools;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.RectAtlasPacker;
import rs117.hd.utils.RenderState;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_WATER_REFLECTION_MAP;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.renderer.zone.ZoneRenderer.UNIFORM_BLOCK_REFLECTION_PLANES;
import static rs117.hd.utils.MathUtils.*;

@Singleton
@Slf4j
public final class ReflectionPass implements RenderPass {
	public static final int MAX_REFLECTION_RENDERS = 4;
	public static final int WATER_HEIGHT_THRESHOLD = LOCAL_TILE_SIZE;

	private static final int MIN_REFLECTION_RESOLUTION = 32;
	private static final Color[] DEBUG_PLANE_COLORS = {
		Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
		Color.CYAN, Color.MAGENTA, Color.ORANGE, Color.PINK
	};

	public static Color getDebugPlaneColor(int index) {
		return DEBUG_PLANE_COLORS[index % DEBUG_PLANE_COLORS.length];
	}

	@Inject
	private HdPlugin plugin;

	@Inject
	private ZoneRenderer zoneRenderer;

	@Inject
	private DeveloperTools developerTools;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private ModelStreamingManager streamingManager;

	@Inject
	private UBOReflectionPlanes uboReflectionPlanes;

	@Inject
	private ReflectionMapOverlay reflectionMapOverlay;

	@Inject
	public SceneShaderProgram.ZoneReflection sceneReflectionProgram;

	private final WaterPlane[] planes = new WaterPlane[MAX_REFLECTION_RENDERS];
	private final RectAtlasPacker.Rect[] packRects = new RectAtlasPacker.Rect[MAX_REFLECTION_RENDERS];
	private final int[] packSizes = new int[MAX_REFLECTION_RENDERS];
	private final int[] maxPackSizes = new int[MAX_REFLECTION_RENDERS];
	private final int[] packOrder = new int[MAX_REFLECTION_RENDERS];

	// Scratch buffers
	private final float[] viewProj = new float[16];
	private final float[] point = new float[4];
	private final float[] clip = new float[4];
	private int[] levelKeys = new int[MAX_REFLECTION_RENDERS * 8];
	private int[] levelCounts = new int[MAX_REFLECTION_RENDERS * 8];

	private int planeCount;

	private int reflectionAtlasSize;
	private int reflectionViewportWidth;
	private int reflectionViewportHeight;
	private int reflectionFramebuffer;
	private int reflectionColorTexture;
	private int reflectionDepthTexture;

	@Override
	public void initialize() {
		for (int i = 0; i < MAX_REFLECTION_RENDERS; i++) {
			if (planes[i] == null)
				planes[i] = new WaterPlane(uboReflectionPlanes.planes[i], i);
			if (packRects[i] == null)
				packRects[i] = new RectAtlasPacker.Rect();
		}
		uboReflectionPlanes.initialize(UNIFORM_BLOCK_REFLECTION_PLANES);
	}

	@Override
	public void addShaderIncludes(ShaderIncludes includes) {
		includes
			.define("MAX_REFLECTION_RENDERS", MAX_REFLECTION_RENDERS)
			.define("WATER_HEIGHT_THRESHOLD", WATER_HEIGHT_THRESHOLD)
			.addUniformBuffer(uboReflectionPlanes);
	}

	@Override
	public void processConfigChanges(Set<String> keys) {
		if (keys.contains(KEY_PLANAR_REFLECTIONS))
			updateReflectionFramebuffer();
	}

	@Override
	public void initializeShaders(ShaderIncludes includes) throws ShaderException, IOException {
		sceneReflectionProgram.compile(includes);
	}

	@Override
	public void destroyShaders() {
		sceneReflectionProgram.destroy();
	}

	@Override
	public void destroy() {
		destroyReflectionFramebuffer();
		uboReflectionPlanes.destroy();
	}

	@Override
	public RenderPassType getType() { return RenderPassType.REFLECTION; }

	private void updateReflectionFramebuffer() {
		if (plugin.configPlanarReflections == ReflectionMode.DISABLED || plugin.sceneViewport == null)
			return;

		final float scale = plugin.configPlanarReflections.resolutionFrac;
		final int width = Math.max(1, Math.round(plugin.sceneViewport[2] * scale));
		final int height = Math.max(1, Math.round(plugin.sceneViewport[3] * scale));
		if (reflectionFramebuffer != 0 && reflectionViewportWidth == width && reflectionViewportHeight == height)
			return;

		destroyReflectionFramebuffer();
		reflectionViewportWidth = width;
		reflectionViewportHeight = height;

		final int maxAtlasSize = Integer.highestOneBit(glGetInteger(GL_MAX_TEXTURE_SIZE));
		final int desiredSize = Math.max(MIN_REFLECTION_RESOLUTION * 4, Math.max(width, height) * 2);
		reflectionAtlasSize = Math.min(ceilPow2(desiredSize), maxAtlasSize);

		reflectionFramebuffer = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, reflectionFramebuffer);

		// Both of these are required color-renderable texture formats
		reflectionColorTexture = createAtlasTexture(plugin.configLinearAlphaBlending ? GL_SRGB8 : GL_RGB8, GL_RGB, GL_UNSIGNED_BYTE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, reflectionColorTexture, 0);
		glReadBuffer(GL_NONE);

		reflectionDepthTexture = createAtlasTexture(GL_DEPTH_COMPONENT16, GL_DEPTH_COMPONENT, GL_UNSIGNED_SHORT);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, reflectionDepthTexture, 0);
		checkGLErrors();
	}

	private int createAtlasTexture(int internalFormat, int format, int type) {
		final int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, tex);
		glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, reflectionAtlasSize, reflectionAtlasSize, 0, format, type, 0);
		checkGLErrors();
		return tex;
	}

	private void destroyReflectionFramebuffer() {
		reflectionViewportWidth = 0;
		reflectionViewportHeight = 0;

		if (reflectionColorTexture != 0)
			glDeleteTextures(reflectionColorTexture);
		if (reflectionDepthTexture != 0)
			glDeleteTextures(reflectionDepthTexture);
		if (reflectionFramebuffer != 0)
			glDeleteFramebuffers(reflectionFramebuffer);
		reflectionColorTexture = reflectionDepthTexture = reflectionFramebuffer = 0;
	}

	@Override
	public boolean zoneInFrustum(Zone z, int zx, int zz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		boolean anyVisible = false;
		for (int i = 0; i < planeCount; i++)
			anyVisible |= z.setVisibility(planes[i].camera, planes[i].testZoneReflectionVisibility(minX, minY, minZ, maxX, maxY, maxZ));
		return anyVisible;
	}

	@Override
	public void drawZoneOpaque(WorldViewContext ctx, Zone z, int zx, int zz) {
		if (z.onlyWater && z.modelCount == 0)
			return;

		for (int i = 0; i < planeCount; i++) {
			final WaterPlane plane = planes[i];
			if (z.isVisible(plane.camera))
				z.renderOpaque(plane.cmd, ctx, null, plugin.configRoofReflections);
		}
	}

	@Override
	public void drawZoneAlpha(WorldViewContext ctx, Zone z, int level, int zx, int zz) {
		if (z.onlyWater && z.modelCount == 0)
			return;

		final int offset = ctx.sceneContext.sceneOffset >> 3;
		for (int i = 0; i < planeCount; i++) {
			final WaterPlane plane = planes[i];
			if (z.isVisible(plane.camera))
				z.renderAlpha(plane.cmd, zx - offset, zz - offset, level, ctx, null, false, plugin.configRoofReflections);
		}
	}

	@Override
	public void drawPass(WorldViewContext ctx, int pass) {
		if (pass != DrawCallbacks.PASS_OPAQUE)
			return;

		for (int i = 0; i < planeCount; i++) {
			final WaterPlane plane = planes[i];
			if (plane.zoneCount > 0 && !plane.cmd.isEmpty())
				plane.cmd.ExecuteSubCommandBuffer(ctx.vaoSceneCmd);
		}
	}

	@Override
	public void preSceneDraw(WorldViewContext ctx, boolean isTopLevel) {
		if (!isTopLevel)
			return;

		for (WaterPlane plane : planes)
			if (plane != null)
				plane.reset();

		if (!ctx.sceneContext.hasWater || plugin.configPlanarReflections == ReflectionMode.DISABLED) {
			setPlaneCount(0);
			return;
		}

		updateReflectionFramebuffer();
		final int levelCapacity = ctx.sizeX * ctx.sizeZ;
		if (levelKeys.length < levelCapacity) {
			final int capacity = Math.max(levelCapacity, levelKeys.length * 2);
			levelKeys = Arrays.copyOf(levelKeys, capacity);
			levelCounts = Arrays.copyOf(levelCounts, capacity);
		}

		// Reset before selecting planes, since already-selected levels are excluded.
		planeCount = 0;
		while (planeCount < MAX_REFLECTION_RENDERS) {
			final int level = findMostCommonWaterLevel(ctx);
			if (level == Integer.MIN_VALUE)
				break;

			final Camera sceneCamera = zoneRenderer.sceneCamera;
			final WaterPlane plane = planes[planeCount];
			plane.camera.copyFrom(sceneCamera);
			plane.camera.setPositionY(-level * 2 - sceneCamera.getPositionY());
			plane.camera.setPitch(-sceneCamera.getPitch());
			plane.waterHeight = level;
			for (int zx = 0; zx < ctx.sizeX; zx++) {
				for (int zz = 0; zz < ctx.sizeZ; zz++) {
					final Zone zone = ctx.zones[zx][zz];
					if (zone.hasWater && zone.isVisible(sceneCamera) && isSameWaterPlane(zone, level))
						plane.expandWaterBounds(ctx.sceneContext, zx, zz);
				}
			}
			plane.screenArea = calculateScreenArea(plane, sceneCamera);
			streamingManager.addModelCullingFrustums(plane.camera);
			planeCount++;
		}

		if (!packReflectionAtlas()) {
			log.warn("Failed to pack water reflection atlas");
			planeCount = 0;
		}
		setPlaneCount(planeCount);
	}

	private void setPlaneCount(int count) {
		planeCount = count;
		uboReflectionPlanes.activePlanes.set(count);
		reflectionMapOverlay.activePlanes = count;
		reflectionMapOverlay.setAtlasRects(packRects, count, reflectionAtlasSize);
	}

	/**
	 * @return The water level covering the most eligible zones, or Integer.MIN_VALUE if there are none.
	 */
	private int findMostCommonWaterLevel(WorldViewContext ctx) {
		int numLevels = 0;
		for (int zx = 0; zx < ctx.sizeX; zx++) {
			for (int zz = 0; zz < ctx.sizeZ; zz++) {
				final Zone zone = ctx.zones[zx][zz];
				if (!zone.hasWater || !zone.isVisible(zoneRenderer.sceneCamera))
					continue;

				final int level = zone.mostPrevalentWaterLevel;
				boolean alreadyCovered = false;
				for (int i = 0; i < planeCount; i++) {
					if (isSameWaterPlane(zone, planes[i].waterHeight)) {
						alreadyCovered = true;
						break;
					}
				}
				if (alreadyCovered)
					continue;

				int slot = 0;
				while (slot < numLevels && levelKeys[slot] != level)
					slot++;
				if (slot == numLevels) {
					levelKeys[numLevels++] = level;
					levelCounts[slot] = 0;
				}
				levelCounts[slot]++;
			}
		}

		if (numLevels == 0)
			return Integer.MIN_VALUE;

		int best = 0;
		for (int i = 1; i < numLevels; i++)
			if (levelCounts[i] > levelCounts[best])
				best = i;
		return levelKeys[best];
	}

	private static boolean isSameWaterPlane(Zone zone, float waterHeight) {
		return abs(zone.mostPrevalentWaterLevel - waterHeight) <= WATER_HEIGHT_THRESHOLD;
	}

	/**
	 * Approximates the on-screen area of a plane's water bounds by projecting its corners.
	 */
	private double calculateScreenArea(WaterPlane plane, Camera camera) {
		final int vpWidth = camera.getViewportWidth();
		final int vpHeight = camera.getViewportHeight();
		if (vpWidth <= 0 || vpHeight <= 0)
			return 0;

		camera.getViewProjMatrix(viewProj);
		float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
		boolean anyProjected = false;

		for (int corner = 0; corner < 4; corner++) {
			point[0] = (corner & 1) == 0 ? plane.waterMinX : plane.waterMaxX;
			point[1] = -plane.waterHeight;
			point[2] = (corner & 2) == 0 ? plane.waterMinZ : plane.waterMaxZ;
			point[3] = 1;
			Mat4.mulVec(clip, viewProj, point);

			final float w = clip[3];
			if (w <= 1e-5f)
				continue;

			final float sx = (clip[0] / w * 0.5f + 0.5f) * vpWidth;
			final float sy = (clip[1] / w * 0.5f + 0.5f) * vpHeight;
			minX = Math.min(minX, sx);
			minY = Math.min(minY, sy);
			maxX = Math.max(maxX, sx);
			maxY = Math.max(maxY, sy);
			anyProjected = true;
		}

		if (!anyProjected || maxX <= 0 || maxY <= 0 || minX >= vpWidth || minY >= vpHeight)
			return 0;

		final float visibleWidth = clamp(maxX, 0, vpWidth) - clamp(minX, 0, vpWidth);
		final float visibleHeight = clamp(maxY, 0, vpHeight) - clamp(minY, 0, vpHeight);
		return (double) visibleWidth * visibleHeight;
	}

	private boolean packReflectionAtlas() {
		if (planeCount == 0)
			return true;

		final int minResolution = Math.min(MIN_REFLECTION_RESOLUTION, reflectionAtlasSize);
		final double viewportArea = (double) reflectionViewportWidth * reflectionViewportHeight;

		float nearestDistance = Float.POSITIVE_INFINITY;
		for (int i = 0; i < planeCount; i++) {
			final WaterPlane plane = planes[i];
			packOrder[i] = i;
			plane.distanceToCamera = zoneRenderer.sceneCamera.distanceTo(
				(plane.waterMinX + plane.waterMaxX) * 0.5f,
				-plane.waterHeight,
				(plane.waterMinZ + plane.waterMaxZ) * 0.5f
			);
			nearestDistance = Math.min(nearestDistance, Math.max(1, plane.distanceToCamera));
		}

		// Determine each plane's priority and ideal resolution
		for (int i = 0; i < planeCount; i++) {
			final WaterPlane plane = planes[i];
			final float distance = Math.max(1, plane.distanceToCamera);
			plane.priorityWeight = plane.screenArea * nearestDistance / distance;

			double scale = 1;
			if (planeCount > 1) {
				final double screenScale = viewportArea > 0 ? Math.sqrt(Math.max(0, plane.screenArea) / viewportArea) * 2 : 0;
				scale = Math.min(1, screenScale) * Math.sqrt(nearestDistance / distance);
			}
			final int desired = Math.max(minResolution, (int) (reflectionAtlasSize * scale));
			maxPackSizes[i] = packSizes[i] = Math.max(minResolution, Integer.highestOneBit(desired));
		}

		for (int i = 1; i < planeCount; i++) {
			final int planeIndex = packOrder[i];
			int j = i;
			while (j > 0) {
				final WaterPlane previous = planes[packOrder[j - 1]];
				final WaterPlane current = planes[planeIndex];
				if (previous.priorityWeight > current.priorityWeight ||
					(previous.priorityWeight == current.priorityWeight &&
					 previous.distanceToCamera <= current.distanceToCamera))
					break;
				packOrder[j] = packOrder[j - 1];
				j--;
			}
			packOrder[j] = planeIndex;
		}

		// Shrink the lowest priority planes until everything fits
		while (!RectAtlasPacker.pack(reflectionAtlasSize, planeCount, packSizes, packRects)) {
			int toShrink = -1;
			for (int i = planeCount - 1; i >= 0 && toShrink < 0; i--)
				if (packSizes[packOrder[i]] > minResolution)
					toShrink = packOrder[i];
			if (toShrink < 0)
				return false;
			packSizes[toShrink] >>= 1;
		}

		// Grow planes back up in priority order while there's still room
		for (int i = 0; i < planeCount; i++) {
			final int idx = packOrder[i];
			while (packSizes[idx] < maxPackSizes[idx]) {
				packSizes[idx] <<= 1;
				if (!RectAtlasPacker.pack(reflectionAtlasSize, planeCount, packSizes, packRects)) {
					packSizes[idx] >>= 1;
					break;
				}
			}
		}

		// The last successful pack may have been a failed growth attempt, so make sure the rects match the final sizes
		// (a failed pack leaves packRects untouched only if the packer guarantees it; repack to be safe)
		RectAtlasPacker.pack(reflectionAtlasSize, planeCount, packSizes, packRects);

		final int maxDimension = Math.max(reflectionViewportWidth, reflectionViewportHeight);
		for (int i = 0; i < planeCount; i++) {
			final WaterPlane plane = planes[i];
			final RectAtlasPacker.Rect rect = packRects[i];
			final float scale = (float) rect.size / maxDimension;
			plane.atlasX = rect.x;
			plane.atlasY = rect.y;
			plane.atlasWidth = Math.max(1, Math.round(reflectionViewportWidth * scale));
			plane.atlasHeight = Math.max(1, Math.round(reflectionViewportHeight * scale));
			plane.struct.atlasRect.set(
				(float) plane.atlasX / reflectionAtlasSize,
				(float) plane.atlasY / reflectionAtlasSize,
				(float) plane.atlasWidth / reflectionAtlasSize,
				(float) plane.atlasHeight / reflectionAtlasSize
			);
		}
		return true;
	}

	@Override
	public void draw(RenderState renderState, int overlayColor) {
		if (planeCount <= 0)
			return;

		renderState.program.set(sceneReflectionProgram);
		if (zoneRenderer.indirectDrawCmds != null)
			renderState.ido.set(zoneRenderer.indirectDrawCmds.id);

		// The shader expects the fog color in sRGB, which is what the linear-blending framebuffer encodes to
		final float[] fogColor = plugin.configLinearAlphaBlending
			? environmentManager.currentFogColor
			: ColorUtils.linearToSrgb(environmentManager.currentFogColor);

		renderState.drawFramebuffer.set(reflectionFramebuffer);
		renderState.viewport.set(0, 0, reflectionAtlasSize, reflectionAtlasSize);
		renderState.clearColor.set(fogColor[0], fogColor[1], fogColor[2], 1f);
		renderState.clearDepth.set(0);
		renderState.toggle(GL_FRAMEBUFFER_SRGB, plugin.configLinearAlphaBlending);
		renderState.disable.set(GL_SCISSOR_TEST);
		renderState.apply();
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		for (int i = 0; i < planeCount; i++)
			planes[i].render(renderState);

		// Bind the water reflection atlas to the reflection map unit
		glActiveTexture(TEXTURE_UNIT_WATER_REFLECTION_MAP);
		glBindTexture(GL_TEXTURE_2D, reflectionColorTexture);
	}

	final class WaterPlane {
		private static final int BOUNDS_STRIDE = 4; // minX, minZ, maxX, maxZ per zone

		public final WaterPlaneStruct struct;
		public final Camera camera;
		public final CommandBuffer cmd;
		public final int index;

		public float waterHeight;
		public int waterMinX, waterMinZ, waterMaxX, waterMaxZ;

		public double screenArea;
		public double priorityWeight;
		public float distanceToCamera;
		public int atlasX, atlasY, atlasWidth, atlasHeight;

		private int[] zoneBounds = new int[BOUNDS_STRIDE * 16];
		public int zoneCount;

		private WaterPlane(WaterPlaneStruct struct, int index) {
			this.struct = struct;
			this.index = index;
			camera = new Camera().setCullingId(ZoneRenderer.CAMERA_COUNT++).setFlipY(true).setReverseZ(true);
			cmd = new CommandBuffer("WaterPlane - " + index);
		}

		public void reset() {
			waterMinX = waterMinZ = Integer.MAX_VALUE;
			waterMaxX = waterMaxZ = Integer.MIN_VALUE;
			zoneCount = 0;
			cmd.reset();
		}

		public void expandWaterBounds(SceneContext sceneContext, int zx, int zz) {
			final int zoneSize = CHUNK_SIZE * LOCAL_TILE_SIZE;
			final int minX = (zx * CHUNK_SIZE - sceneContext.sceneOffset) * LOCAL_TILE_SIZE;
			final int minZ = (zz * CHUNK_SIZE - sceneContext.sceneOffset) * LOCAL_TILE_SIZE;
			final int maxX = minX + zoneSize;
			final int maxZ = minZ + zoneSize;

			waterMinX = Math.min(waterMinX, minX);
			waterMinZ = Math.min(waterMinZ, minZ);
			waterMaxX = Math.max(waterMaxX, maxX);
			waterMaxZ = Math.max(waterMaxZ, maxZ);

			final int base = zoneCount * BOUNDS_STRIDE;
			if (base >= zoneBounds.length)
				zoneBounds = Arrays.copyOf(zoneBounds, zoneBounds.length * 2);
			zoneBounds[base] = minX;
			zoneBounds[base + 1] = minZ;
			zoneBounds[base + 2] = maxX;
			zoneBounds[base + 3] = maxZ;
			zoneCount++;

			if (developerTools.isReflectionMapOverlayEnabled()) {
				DebugDraw.drawMinMax(
					minX, -waterHeight - 1, minZ,
					maxX, -waterHeight + 1, maxZ,
					getDebugPlaneColor(index),
					0,
					false
				);
			}
		}

		/**
		 * Tests if a zone's reflection is visible by projecting its AABB corners onto
		 * the water plane (y = waterHeight) from the reflected camera, then checking if
		 * that footprint overlaps any of this plane's water zones.
		 */
		public boolean testZoneReflectionVisibility(
			int zoneMinX, int zoneMinY, int zoneMinZ,
			int zoneMaxX, int zoneMaxY, int zoneMaxZ
		) {
			if (zoneCount <= 0 || !camera.intersectsAABB(zoneMinX, zoneMinY, zoneMinZ, zoneMaxX, zoneMaxY, zoneMaxZ))
				return false;

			final float camX = camera.getPositionX();
			final float camY = camera.getPositionY();
			final float camZ = camera.getPositionZ();

			float hitMinX = Float.POSITIVE_INFINITY, hitMaxX = Float.NEGATIVE_INFINITY;
			float hitMinZ = Float.POSITIVE_INFINITY, hitMaxZ = Float.NEGATIVE_INFINITY;
			int projected = 0;

			for (int corner = 0; corner < 8; corner++) {
				final float cx = (corner & 1) == 0 ? zoneMinX : zoneMaxX;
				final float cy = (corner & 2) == 0 ? zoneMinY : zoneMaxY;
				final float cz = (corner & 4) == 0 ? zoneMinZ : zoneMaxZ;

				final float dy = cy - camY;
				if (Math.abs(dy) < 1e-5f)
					continue;

				// Only corners between the camera and the water plane project onto it
				final float t = (waterHeight - camY) / dy;
				if (t <= 0f || t > 1f)
					continue;

				final float hx = camX + (cx - camX) * t;
				final float hz = camZ + (cz - camZ) * t;
				hitMinX = min(hitMinX, hx);
				hitMaxX = max(hitMaxX, hx);
				hitMinZ = min(hitMinZ, hz);
				hitMaxZ = max(hitMaxZ, hz);
				projected++;
			}

			final int skipped = 8 - projected;
			if (projected == 0)
				return skipped > 0; // Always true here, but kept explicit: nothing projected, so be conservative

			// Be conservative if some corners couldn't be projected
			if (skipped > 0) {
				final float expand = CHUNK_SIZE * LOCAL_TILE_SIZE * 2.0f;
				hitMinX -= expand;
				hitMaxX += expand;
				hitMinZ -= expand;
				hitMaxZ += expand;
			}

			if (hitMaxX < waterMinX || hitMinX > waterMaxX || hitMaxZ < waterMinZ || hitMinZ > waterMaxZ)
				return false;

			for (int base = 0, end = zoneCount * BOUNDS_STRIDE; base < end; base += BOUNDS_STRIDE) {
				if (hitMaxX >= zoneBounds[base] && hitMinX <= zoneBounds[base + 2] &&
					hitMaxZ >= zoneBounds[base + 1] && hitMinZ <= zoneBounds[base + 3])
					return true;
			}
			return false;
		}

		public void render(RenderState renderState) {
			if (zoneCount <= 0)
				return;

			struct.camera.write(camera);
			struct.height.set(-waterHeight);

			uboReflectionPlanes.cullingPlane.set(0.0f, -1.0f, 0.0f, -waterHeight);
			uboReflectionPlanes.upload();

			plugin.uboGlobal.sceneCamera.write(camera);
			plugin.uboGlobal.upload();

			renderState.drawFramebuffer.set(reflectionFramebuffer);
			renderState.viewport.set(atlasX, atlasY, atlasWidth, atlasHeight);

			// Since the game was never designed to be viewed from below, a lot of
			// things are missing triangles underneath. In most cases, it's fine
			// visually to render the top face from below.
			renderState.disable.set(GL_CULL_FACE);

			renderState.enable.set(GL_DEPTH_TEST);
			renderState.enable.set(GL_BLEND);
			renderState.enable.set(GL_CLIP_DISTANCE0);
			renderState.depthFunc.set(GL_GEQUAL);
			renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);

			cmd.execute(renderState);
		}
	}
}
