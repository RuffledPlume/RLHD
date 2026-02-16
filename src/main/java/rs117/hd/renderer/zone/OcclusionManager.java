package rs117.hd.renderer.zone;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.shader.OcclusionShaderProgram;
import rs117.hd.opengl.uniforms.UBOOcclusion;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL15.glBeginQuery;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glEndQuery;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL15C.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL33.GL_ANY_SAMPLES_PASSED;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.checkGLErrors;

@Slf4j
@Singleton
public class OcclusionManager {
	public static final int SCENE_QUERY = 0;
	public static final int DIRECTIONAL_QUERY = 1;
	public static final int QUERY_COUNT = 2;

	@Getter
	private static OcclusionManager instance;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ZoneRenderer zoneRenderer;

	@Inject
	private HdPluginConfig config;

	@Inject
	private FrameTimer frameTimer;

	private RenderState renderState;
	private OcclusionShaderProgram occlusionProgram;
	private UBOOcclusion uboOcclusion;

	private final ConcurrentLinkedQueue<OcclusionQuery> freeQueries = new ConcurrentLinkedQueue<>();
	private final List<OcclusionQuery> queuedQueries = new ArrayList<>();
	private final List<OcclusionQuery> prevQueuedQueries = new ArrayList<>();

	private final int[] result = new int[1];

	@Getter
	private boolean active;

	@Getter
	private int queryCount = 0;
	private final int[] passedQueryCount = new int[QUERY_COUNT];

	private int glCubeVAO;
	private int glCubeVBO;

	public void initialize(RenderState renderState, OcclusionShaderProgram occlusionProgram, UBOOcclusion uboOcclusion) {
		this.renderState = renderState;
		this.occlusionProgram = occlusionProgram;
		this.uboOcclusion = uboOcclusion;

		instance = this;
		active = config.occlusionCulling();

		{
			// Create cube VAO
			glCubeVAO = glGenVertexArrays();
			glCubeVBO = glGenBuffers();
			glBindVertexArray(glCubeVAO);

			FloatBuffer vboCubeData = BufferUtils.createFloatBuffer(108) // 36 vertices * 3 components
				.put(new float[] {
					// Front face (-Z)
					-1, -1, -1,
					1, -1, -1,
					1, 1, -1,
					-1, -1, -1,
					1, 1, -1,
					-1, 1, -1,

					// Back face (+Z)
					-1, -1, 1,
					-1, 1, 1,
					1, 1, 1,
					-1, -1, 1,
					1, 1, 1,
					1, -1, 1,

					// Left face (-X)
					-1, -1, -1,
					-1, 1, -1,
					-1, 1, 1,
					-1, -1, -1,
					-1, 1, 1,
					-1, -1, 1,

					// Right face (+X)
					1, -1, -1,
					1, 1, 1,
					1, 1, -1,
					1, -1, -1,
					1, -1, 1,
					1, 1, 1,

					// Bottom face (-Y)
					-1, -1, -1,
					-1, -1, 1,
					1, -1, 1,
					-1, -1, -1,
					1, -1, 1,
					1, -1, -1,

					// Top face (+Y)
					-1, 1, -1,
					1, 1, -1,
					1, 1, 1,
					-1, 1, -1,
					1, 1, 1,
					-1, 1, 1
				})
				.flip();
			glBindBuffer(GL_ARRAY_BUFFER, glCubeVBO);
			glBufferData(GL_ARRAY_BUFFER, vboCubeData, GL_STATIC_DRAW);

			// position attribute
			glEnableVertexAttribArray(0);
			glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
		}
	}

	public int getPassedQueryCount(int type) {
		return passedQueryCount[type];
	}

	public OcclusionQuery obtainQuery() {
		OcclusionQuery query = freeQueries.poll();
		if(query == null)
			query = new OcclusionQuery();
		return query;
	}

	public void readbackQueries() {
		active = config.occlusionCulling();

		if(prevQueuedQueries.isEmpty())
			return;

		frameTimer.begin(Timer.OCCLUSION_READBACK);
		queryCount = prevQueuedQueries.size();
		Arrays.fill(passedQueryCount, 0);
		for (int i = 0; i < queryCount; i++) {
			final OcclusionQuery query = prevQueuedQueries.get(i);
			if (!query.queued)
				continue;
			query.queued = false;

			for(int k = 0; k < QUERY_COUNT; k++) {
				if(query.id[k] == 0)
					continue;

				result[0] = 0;
				while (result[0] == 0)
					glGetQueryObjectiv(query.id[k], GL_QUERY_RESULT_AVAILABLE, result);

				if (!plugin.freezeCulling)
					query.occluded[k] = glGetQueryObjectui64(query.id[k], GL_QUERY_RESULT) == 0;

				if(!query.occluded[k])
					passedQueryCount[k]++;
			}
		}
		frameTimer.end(Timer.OCCLUSION_READBACK);
		prevQueuedQueries.clear();

		checkGLErrors();
	}

	public void occlusionPass() {
		if(queuedQueries.isEmpty() || !active)
			return;

		frameTimer.begin(Timer.RENDER_OCCLUSION);

		renderState.enable.set(GL_CULL_FACE);
		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(false);
		renderState.colorMask.set(false, false, false, false);
		renderState.apply();

		glBindVertexArray(glCubeVAO);
		occlusionProgram.use();

		int start = 0;
		int uboOffset = 0;
		for(int i = 0; i < queuedQueries.size(); i++) {
			final OcclusionQuery query = queuedQueries.get(i);
			if (query.count == 0)
				continue;
			assert query.count < UBOOcclusion.MAX_AABBS;

			if(uboOffset + query.count >= UBOOcclusion.MAX_AABBS) {
				flushQueries(start, i);
				start = i;
				uboOffset = 0;
			}

			if(query.id[0] == 0)
				glGenQueries(query.id);

			query.uboOffset = uboOffset;
			for(int k = 0; k < query.count; k++) {
				float posX = query.offsetX + query.aabb[k * 6];
				float posY = query.offsetY + query.aabb[k * 6 + 1];
				float posZ = query.offsetZ + query.aabb[k * 6 + 2];

				uboOcclusion.positions[uboOffset].set(posX, posY, posZ);
				uboOcclusion.sizes[uboOffset].set(query.aabb[k * 6 + 3], query.aabb[k * 6 + 4], query.aabb[k * 6 + 5]);
				uboOffset++;
			}
		}
		flushQueries(start, queuedQueries.size());

		renderState.disable.set(GL_CULL_FACE);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(true);
		renderState.colorMask.set(true, true, true, true);
		renderState.apply();

		prevQueuedQueries.addAll(queuedQueries);
		queuedQueries.clear();

		frameTimer.end(Timer.RENDER_OCCLUSION);

		checkGLErrors();
	}

	private void flushQueries(int start, int end) {
		uboOcclusion.upload();
		for(int k = 0; k < QUERY_COUNT; k++) {
			switch (k) {
				case SCENE_QUERY:
					renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
					renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.fboSceneDepth);
					occlusionProgram.viewProj.set(zoneRenderer.sceneCamera.getViewProjMatrix());
					break;
				case DIRECTIONAL_QUERY:
					if(!plugin.configShadowsEnabled)
						continue;
					renderState.viewport.set(0, 0, plugin.shadowMapResolution, plugin.shadowMapResolution);
					renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.fboShadowMap);
					occlusionProgram.viewProj.set(zoneRenderer.directionalCamera.getViewProjMatrix());
					break;
			}
			renderState.apply();

			for (int i = start; i < end; i++) {
				final OcclusionQuery query = queuedQueries.get(i);
				if (query.count == 0)
					continue;
				assert query.count < UBOOcclusion.MAX_AABBS;

				occlusionProgram.offset.set(query.uboOffset);
				glBeginQuery(GL_ANY_SAMPLES_PASSED, query.id[k]);
				glDrawArraysInstanced(GL_TRIANGLES, 0, 36, query.count);
				glEndQuery(GL_ANY_SAMPLES_PASSED);
			}
		}
	}

	public void shutdown() {
		if(glCubeVAO != 0)
			glDeleteVertexArrays(glCubeVAO);
		glCubeVAO = 0;

		if(glCubeVBO != 0)
			glDeleteBuffers(glCubeVBO);
		glCubeVBO = 0;
	}

	public final class OcclusionQuery {
		private final int[] id = new int[QUERY_COUNT];
		private final boolean[] occluded = new boolean[QUERY_COUNT];

		@Getter
		private boolean queued;

		private int uboOffset;
		private float offsetX = 0;
		private float offsetY = 0;
		private float offsetZ = 0;

		private float[] aabb = new float[6];
		private int count = 0;

		public boolean isOccluded(int type) {
			return occluded[type] && active;
		}

		public boolean isVisible(int type) {
			return !isOccluded(type);
		}

		public boolean isFullyOccluded() {
			for(int i = 0; i < QUERY_COUNT; i++) {
				if(!isOccluded(i))
					return false;
			}
			return true;
		}

		public void setOffset(float x, float y, float z) {
			offsetX = x;
			offsetY = y;
			offsetZ = z;
		}

		public void addSphere(float x, float y, float z, float radius) {
			// TODO: Support drawing spheres for more exact occlusion
			float halfRadius = radius / 2;
			addAABB(x - halfRadius, y - halfRadius, z - halfRadius, radius, radius, radius);
		}

		public void addAABB(AABB aabb) {
			addAABB(aabb, 0, 0, 0);
		}

		public void addAABB(AABB aabb, float x, float y, float z) {
			addAABB(x + aabb.getCenterX(), y + aabb.getCenterY(), z + aabb.getCenterZ(), aabb.getExtremeX(), aabb.getExtremeY(), aabb.getExtremeY());
		}

		public void addMinMax(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			addAABB(minX, minY, minZ, maxX - minX, maxY - minY, maxZ - minZ);
		}

		public void addAABB(
			float posX, float posY, float posZ,
			float sizeX, float sizeY, float sizeZ) {
			if(!active)
				return;

			if(count * 6 >= aabb.length)
				aabb = Arrays.copyOf(aabb, aabb.length * 2);

			if(count > 1) {
				float newMinX = posX - sizeX / 2;
				float newMinY = posY - sizeY / 2;
				float newMinZ = posZ - sizeZ / 2;
				float newMaxX = posX + sizeX / 2;
				float newMaxY = posY + sizeY / 2;
				float newMaxZ = posZ + sizeZ / 2;

				for (int i = 0; i < count; i++) {
					if (newMinX >= (aabb[i * 6] - aabb[i * 6 + 3] / 2) && newMaxX <= (aabb[i * 6] + aabb[i * 6 + 3] / 2) &&
						newMinY >= (aabb[i * 6 + 1] - aabb[i * 6 + 4] / 2) && newMaxY <= (aabb[i * 6 + 1] + aabb[i * 6 + 4] / 2) &&
						newMinZ >= (aabb[i * 6 + 2] - aabb[i * 6 + 5] / 2) && newMaxZ <= (aabb[i * 6 + 2] + aabb[i * 6 + 5] / 2)) {
						// New AABB is contained, skip adding it
						return;
					}
				}
			}

			aabb[count * 6] = posX;
			aabb[count * 6 + 1] = posY;
			aabb[count * 6 + 2] = posZ;

			aabb[count * 6 + 3] = sizeX;
			aabb[count * 6 + 4] = sizeY;
			aabb[count * 6 + 5] = sizeZ;

			count++;
		}

		public void reset() {
			count = 0;
		}

		public void queue() {
			if(!active || queued) {
				return;
			}

			queued = true;
			synchronized (queuedQueries) {
				queuedQueries.add(this);
			}
		}

		public void free() {
			count = 0;
			queued = false;
			offsetX = 0;
			offsetY = 0;
			offsetZ = 0;
			Arrays.fill(occluded, false);
			freeQueries.add(this);
		}
	}
}
