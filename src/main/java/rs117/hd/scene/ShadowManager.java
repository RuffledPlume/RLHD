package rs117.hd.scene;

import java.util.ArrayList;
import java.util.Collections;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.SceneManager;
import rs117.hd.renderer.zone.WorldViewContext;
import rs117.hd.renderer.zone.Zone;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.ShadowAtlasPacker;
import rs117.hd.utils.ShadowAtlasPacker.Rect;
import rs117.hd.utils.collections.PrimitiveIntArray;

import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NONE;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearDepth;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDrawBuffer;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11C.GL_LEQUAL;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.glTexImage3D;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT16;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_FUNC;
import static org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL30.GL_COMPARE_REF_TO_TEXTURE;
import static org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_POSITIONAL_SHADOW_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.opengl.uniforms.UBOLights.MAX_LIGHTS;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class ShadowManager implements LightManager.Listener {

	private static final int ATLAS_SIZE = 4096;
	private static final int NUM_FACES = 6;

	private static final int MAX_FACE_RESOLUTION = 512;
	private static final int MIN_FACE_RESOLUTION = 32;

	private static final float SHADOW_NEAR_PLANE = 10.0f;
	private static final float CUBEMAP_FACE_FRUSTUM_EXTENT = 2f; // yields exactly 90 degree FOV per axis, independent of resolution

	private static final int MIN_SIZE_EXP = Integer.numberOfTrailingZeros(MIN_FACE_RESOLUTION);
	private static final int MAX_SIZE_EXP = Integer.numberOfTrailingZeros(MAX_FACE_RESOLUTION);
	private static final int SIZE_TIER_COUNT = MAX_SIZE_EXP - MIN_SIZE_EXP + 1;
	private static final int SIZE_TIER_BITS = 32 - Integer.numberOfLeadingZeros(Math.max(1, SIZE_TIER_COUNT - 1));
	// Grid range is governed by the finest (smallest) tier, since that's where the most distinct positions exist
	private static final int GRID_CELLS = ATLAS_SIZE / MIN_FACE_RESOLUTION;
	private static final int GRID_BITS = 32 - Integer.numberOfLeadingZeros(GRID_CELLS - 1);
	private static final int PACKED_TOTAL_BITS = SIZE_TIER_BITS + 2 * GRID_BITS; // informational; verified <= 32 by construction

	private static final float[][] FACE_DIRECTIONS = {
		{  1,  0,  0 }, { -1,  0,  0 },
		{  0,  1,  0 }, {  0, -1,  0 },
		{  0,  0,  1 }, {  0,  0, -1 },
	};

	private static final float[][] FACE_UP_VECTORS = {
		{ 0, -1,  0 }, { 0, -1,  0 },
		{ 0,  0,  1 }, { 0,  0, -1 },
		{ 0, -1,  0 }, { 0, -1,  0 },
	};

	@Inject
	private HdPlugin plugin;

	@Inject
	private LightManager lightManager;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private ZoneRenderer zoneRenderer;

	@Inject
	private FrameTimer frameTimer;

	private final ArrayList<Light> shadowLights = new ArrayList<>();
	private final PrimitiveIntArray visibleIndices = new PrimitiveIntArray();

	// Reused per-frame scratch buffers for packing, sized to MAX_SHADOW_LIGHTS
	// up front to avoid per-frame allocation.
	private final int[] packSizes = new int[MAX_LIGHTS];
	private final Rect[] packRects = new Rect[MAX_LIGHTS];

	private final Camera camera = new Camera();

	private int fboShadow;
	private int texShadowCubemapArray;

	public ShadowManager() {
		for (int i = 0; i < packRects.length; i++)
			packRects[i] = new Rect();
	}

	public void initialize() {
		lightManager.addListener(this);

		texShadowCubemapArray = glGenTextures();

		glActiveTexture(TEXTURE_UNIT_POSITIONAL_SHADOW_MAP);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texShadowCubemapArray);

		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_DEPTH_COMPONENT16,
			ATLAS_SIZE, ATLAS_SIZE, NUM_FACES,
			0, GL_DEPTH_COMPONENT, GL_FLOAT, 0);

		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);

		glActiveTexture(TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

		fboShadow = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboShadow);

		glDrawBuffer(GL_NONE);
		glReadBuffer(GL_NONE);

		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	public void destroy() {
		visibleIndices.reset();
		shadowLights.clear();

		lightManager.removeListener(this);

		if (fboShadow != 0)
			glDeleteFramebuffers(fboShadow);
		fboShadow = 0;

		if (texShadowCubemapArray != 0)
			glDeleteTextures(texShadowCubemapArray);
		texShadowCubemapArray = 0;
	}

	public void update() {
		visibleIndices.reset();
		visibleIndices.ensureCapacity(shadowLights.size());

		for (int i = 0; i < shadowLights.size(); i++) {
			final Light light = shadowLights.get(i);
			light.shadowData.overlappingZones.reset();
			light.shadowData.atlasRect = null;

			if (light.visible && visibleIndices.length < MAX_LIGHTS)
				visibleIndices.put(i);
		}

		packShadowAtlas();
	}

	private void packShadowAtlas() {
		final int count = visibleIndices.length;
		if (count == 0)
			return;

		for (int i = 0; i < count; i++) {
			final Light light = shadowLights.get(visibleIndices.array[i]);
			packSizes[i] = estimateShadowResolution(light);
		}

		while (ShadowAtlasPacker.totalArea(packSizes, count) > (long) ATLAS_SIZE * ATLAS_SIZE) {
			int smallestIdx = -1;
			int smallestSize = Integer.MAX_VALUE;

			for (int i = 0; i < count; i++) {
				if (packSizes[i] > MIN_FACE_RESOLUTION && packSizes[i] < smallestSize) {
					smallestSize = packSizes[i];
					smallestIdx = i;
				}
			}

			if (smallestIdx < 0)
				break; // everything is already at the minimum; caller has genuinely too many lights

			packSizes[smallestIdx] >>= 1;
		}

		if(!ShadowAtlasPacker.pack(ATLAS_SIZE, count, packSizes, packRects)) {
			log.warn("Failed to pack light rects??!");
			return;
		}

		for (int i = 0; i < count; i++) {
			final Light light = shadowLights.get(visibleIndices.array[i]);
			light.shadowData.atlasRect = packRects[i];
		}
	}

	private int estimateShadowResolution(Light light) {
		final Camera sceneCamera = zoneRenderer.sceneCamera;
		final float distance = sceneCamera.distanceTo(light.pos[0], light.pos[1], light.pos[2]);
		if (distance <= SHADOW_NEAR_PLANE)
			return MAX_FACE_RESOLUTION;

		final float tanHalfFovY = sceneCamera.getViewportHeight() / sceneCamera.getZoom() / 2f;
		final float angularRadius = light.radius / distance;
		final float screenFraction = angularRadius / tanHalfFovY; // ~fraction of viewport height covered by the light's radius
		final float pixels = screenFraction * sceneCamera.getViewportHeight();

		// Quantize to a power of two within [MIN_FACE_RESOLUTION, MAX_FACE_RESOLUTION]
		int size = MIN_FACE_RESOLUTION;
		while (size < MAX_FACE_RESOLUTION && size < pixels)
			size <<= 1;
		return size;
	}

	public void buildDrawLists() {
		final WorldViewContext ctx = sceneManager.getRoot();

		for (int i = 0; i < visibleIndices.length; i++) {
			final int lightIndex = visibleIndices.array[i];
			final Light light = shadowLights.get(lightIndex);
			final ShadowData shadowData = light.shadowData;

			shadowData.drawBuffer.reset();
			if (shadowData.atlasRect == null)
				continue;

			for (int z = 0; z < shadowData.overlappingZones.length; z++) {
				final int zx = shadowData.overlappingZones.array[z] / ctx.sizeX;
				final int zz = shadowData.overlappingZones.array[z] % ctx.sizeX;

				ctx.zones[zx][zz].renderOpaque(shadowData.drawBuffer, 0, 0, 3, Collections.EMPTY_SET);
			}
		}
	}

	public boolean izZoneVisible(WorldViewContext context, Zone zone, int zx, int zz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		boolean isVisible = false;
		for(int i = 0; i < visibleIndices.length; i++) {
			final Light light = shadowLights.get(visibleIndices.array[i]);
			final boolean intersectsZone = HDUtils.SphereAABBIntersects(
				light.pos[0], light.pos[1], light.pos[2], light.radius,
				minX, minY, minZ,
				maxX, maxY, maxZ
			);
			if(!intersectsZone)
				continue;

			light.shadowData.overlappingZones.put(zx * context.sizeX + zz);
			isVisible = true;
		}

		return isVisible;
	}

	public void renderShadows(RenderState renderState) {
		frameTimer.begin(Timer.RENDER_POSITIONAL_SHADOWS);

		zoneRenderer.depthProgram.use();

		renderState.framebuffer.set(GL_FRAMEBUFFER, fboShadow);
		renderState.disable.set(GL_CULL_FACE);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(true);
		renderState.depthFunc.set(GL_LEQUAL);
		renderState.ido.set(zoneRenderer.indirectDrawCmds.id);

		glClearDepth(1);

		camera.setZoom(1.0f);
		camera.setViewportHeight(2);
		camera.setViewportWidth(2);
		camera.setNearPlane(SHADOW_NEAR_PLANE);

		boolean hasCleared = false;
		for (int i = 0; i < visibleIndices.length; i++) {
			final int lightIndex = visibleIndices.array[i];
			final Light light = shadowLights.get(lightIndex);
			final ShadowData shadowData = light.shadowData;

			if (shadowData.atlasRect == null || shadowData.drawBuffer.isEmpty())
				continue;

			assert light.radius == sqrt(light.radius * light.radius);

			camera.setPositionX(light.pos[0] + plugin.cameraShift[0]);
			camera.setPositionY(light.pos[1]);
			camera.setPositionZ(light.pos[2] + plugin.cameraShift[1]);
			camera.setFarPlane(light.radius);

			// Same pixel-space rect on every face layer for this light
			renderState.viewport.set(shadowData.atlasRect.x, shadowData.atlasRect.y, shadowData.atlasRect.size, shadowData.atlasRect.size);

			for (int face = 0; face < 6; face++) {
				camera.setLookDirection(FACE_DIRECTIONS[face], FACE_UP_VECTORS[face]);
				zoneRenderer.depthProgram.uniViewProjection.set(camera.getViewProjMatrix());

				renderState.framebufferTextureLayer.set(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, texShadowCubemapArray, 0, face);
				renderState.apply();

				// Each array layer is one cubemap face shared by every light. The
				// first light must therefore clear every face once; later lights
				// must preserve the depth already written to their other atlas rects.
				if (!hasCleared)
					glClear(GL_DEPTH_BUFFER_BIT);

				shadowData.drawBuffer.execute(renderState);
			}
			hasCleared = true;
		}


		renderState.disable.set(GL_DEPTH_TEST);

		frameTimer.end(Timer.RENDER_POSITIONAL_SHADOWS);
	}

	@Override
	public void onLightAdded(Light light) {
		if(!light.castShadows)
			return;

		light.shadowData = new ShadowData();
		shadowLights.add(light);
	}

	@Override
	public void onLightRemoved(Light light) {
		if(light.shadowData == null)
			return;

		light.shadowData = null;
		shadowLights.remove(light);
	}

	public static final class ShadowData {
		private final PrimitiveIntArray overlappingZones = new PrimitiveIntArray();
		private final CommandBuffer drawBuffer = new CommandBuffer("Shadow::DrawBuffer");

		private Rect atlasRect;

		public int pack() {
			if(atlasRect == null)
				return -1;

			final int size = atlasRect.size;
			final int exponent = Integer.numberOfTrailingZeros(size);
			final int tier = exponent - MIN_SIZE_EXP;
			final int gridX = atlasRect.x >> exponent;
			final int gridY = atlasRect.y >> exponent;

			final int packed = tier  | (gridX << SIZE_TIER_BITS) | (gridY << (SIZE_TIER_BITS + GRID_BITS));
			validate(packed);

			return packed;
		}

		public void validate(int packed) {
			final int tierMask = (1 << SIZE_TIER_BITS) - 1;
			final int gridMask = (1 << GRID_BITS) - 1;

			final int tier = packed & tierMask;
			final int gridX = (packed >> SIZE_TIER_BITS) & gridMask;
			final int gridY = (packed >> (SIZE_TIER_BITS + GRID_BITS)) & gridMask;

			final int exponent = tier + MIN_SIZE_EXP;

			final int x = gridX << exponent;
			final int y = gridY << exponent;
			final int size = 1 << exponent;

			assert atlasRect.x == x;
			assert atlasRect.y == y;
			assert atlasRect.size == size;
		}
	}
}
