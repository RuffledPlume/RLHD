package rs117.hd.utils;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;

@Slf4j
public class SceneView {
	private static final int PROJECTION_MATRIX_DIRTY = 1;
	private static final int VIEW_MATRIX_DIRTY = 1 << 1;
	private static final int VIEW_PROJ_MATRIX_DIRTY = 1 << 2;
	private static final int INV_VIEW_PROJ_MATRIX_DIRTY = 1 << 3;
	private static final int FRUSTUM_PLANES_DIRTY = 1 << 4;

	private static final int VIEW_PROJ_CHANGED = VIEW_PROJ_MATRIX_DIRTY | INV_VIEW_PROJ_MATRIX_DIRTY | FRUSTUM_PLANES_DIRTY;
	private static final int PROJ_CHANGED = PROJECTION_MATRIX_DIRTY | VIEW_PROJ_CHANGED;
	private static final int VIEW_CHANGED = VIEW_MATRIX_DIRTY | VIEW_PROJ_CHANGED;

	private final FrameTimer frameTimer;

	private float[] viewMatrix;
	private float[] projectionMatrix;
	private float[] viewProjMatrix;
	private float[] invViewProjMatrix;

	private final float[][] frustumPlanes = new float[6][4];
	private final float[] position = new float[3];
	private final float[] orientation = new float[2];

	private int dirtyFlags = PROJ_CHANGED | VIEW_CHANGED;

	private int viewportWidth = 10;
	private int viewportHeight = 10;

	private float zoom = 1.0f;
	private float nearPlane = 0.5f;
	private float farPlane = 0.0f;
	private boolean isOrthographic = false;

	public enum VisibilityResult { UNKNOWN, IN_PROGRESS, HIDDEN, VISIBLE }

	private VisibilityResult[] tileVisibility = new VisibilityResult[MAX_Z * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE];

	private final AsyncCullingJob[] cullingJobs = new AsyncCullingJob[MAX_Z];
	private final AsyncTileVisibilityClear clearJob = new AsyncTileVisibilityClear();

	public SceneView(FrameTimer frameTimer, boolean isOrthographic) {
		this.frameTimer = frameTimer;
		this.isOrthographic = isOrthographic;

		for (int plane = 0; plane < MAX_Z; plane++) {
			cullingJobs[plane] = new AsyncCullingJob(this, plane);
		}
	}

	public boolean isDirty() {
		return dirtyFlags != 0;
	}

	public SceneView setViewportWidth(int newViewportWidth) {
		if (viewportWidth != newViewportWidth) {
			viewportWidth = newViewportWidth;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public SceneView setViewportHeight(int newViewportHeight) {
		if (viewportHeight != newViewportHeight) {
			viewportHeight = newViewportHeight;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public SceneView setNearPlane(float newNearPlane) {
		if (nearPlane != newNearPlane) {
			nearPlane = newNearPlane;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public float getNearPlane() {
		return nearPlane;
	}

	public SceneView setFarPlane(float newFarPlane) {
		if (farPlane != newFarPlane) {
			farPlane = newFarPlane;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public float getFarPlane() {
		return farPlane;
	}

	public SceneView setZoom(float newZoom) {
		if (zoom != newZoom) {
			zoom = newZoom;
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public float getPositionX() {
		return position[0];
	}

	public void translateX(float xOffset) {
		setPositionX(getPositionX() + xOffset);
	}

	public SceneView setPositionX(float x) {
		if (position[0] != x) {
			position[0] = x;
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public float getPositionY() {
		return position[1];
	}

	public void translateY(float yOffset) {
		setPositionY(getPositionY() + yOffset);
	}

	public SceneView setPositionY(float y) {
		if (position[1] != y) {
			position[1] = y;
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public float getPositionZ() {
		return position[2];
	}

	public void translateZ(float zOffset) {
		setPositionZ(getPositionZ() + zOffset);
	}

	public SceneView setPositionZ(float z) {
		if (position[2] != z) {
			position[2] = z;
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public SceneView setPosition(float[] newPosition) {
		if (position[0] != newPosition[0] || position[1] != newPosition[1] || position[2] != newPosition[2]) {
			position[0] = newPosition[0];
			position[1] = newPosition[1];
			position[2] = newPosition[2];
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public float[] getPosition() {
		return Arrays.copyOf(position, 3);
	}

	public SceneView translate(float[] translation) {
		if (translation[0] != 0.0f || translation[1] != 0.0f || translation[2] != 0.0f) {
			position[0] += translation[0];
			position[1] += translation[1];
			position[2] += translation[2];
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public SceneView setYaw(float yaw) {
		if (orientation[0] != yaw) {
			orientation[0] = yaw;
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public float getYaw() {
		return orientation[0];
	}

	public SceneView setPitch(float pitch) {
		if (orientation[1] != pitch) {
			orientation[1] = pitch;
			dirtyFlags |= VIEW_CHANGED;
		}
		return this;
	}

	public float getPitch() {
		return orientation[1];
	}

	public float[] getOrientation() {
		return Arrays.copyOf(orientation, 2);
	}

	@SneakyThrows
	public void performAsyncTileCulling(SceneContext ctx, boolean checkUnderwater) {
		if (ctx == null) {
			return;
		}

		clearJob.complete(true);
		for (AsyncCullingJob planeJob : cullingJobs) {
			planeJob.complete(true);
		}

		calculateFrustumPlanes();

		// Swap the Visibility results with last frames, which should have been cleared by now
		VisibilityResult[] temp = tileVisibility;
		tileVisibility = clearJob.prevTileVisibility;
		clearJob.execute(temp);

		for (AsyncCullingJob planeJob : cullingJobs) {
			planeJob.execute(
				ctx.scene.getTileHeights()[planeJob.plane],
				checkUnderwater && ctx.underwaterDepthLevels != null ? ctx.underwaterDepthLevels[planeJob.plane] : null
			);
		}
	}

	@SneakyThrows
	public boolean isTileVisible(int plane, int tileExX, int tileExY) {
		frameTimer.begin(Timer.VISIBILITY_CHECK);
		final int tileIdx = (plane * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE) + (tileExX * EXTENDED_SCENE_SIZE) + tileExY;
		VisibilityResult result = tileVisibility[tileIdx];

		if (result == VisibilityResult.UNKNOWN) {
			// Process on client thread, rather than waiting for result
			result = cullingJobs[plane].performTileCulling(tileIdx, tileExX, tileExY);
		}

		// If the Tile is still in-progress then wait for the job to complete
		while (result == VisibilityResult.IN_PROGRESS) {
			Thread.yield();
			result = tileVisibility[tileIdx];
		}

		frameTimer.end(Timer.VISIBILITY_CHECK);
		return result == VisibilityResult.VISIBLE;
	}

	public boolean isModelVisible(Model model, int x, int y, int z) {
		calculateFrustumPlanes();
		return HDUtils.isModelVisible(x, y, z, model, frustumPlanes);
	}

	public SceneView setOrientation(float[] newOrientation) {
		if (orientation[0] != newOrientation[0] || orientation[1] != newOrientation[1]) {
			orientation[0] = newOrientation[0];
			orientation[1] = newOrientation[1];
			dirtyFlags |= PROJ_CHANGED;
		}
		return this;
	}

	public float[] getForwardDirection() {
		calculateViewMatrix();
		return new float[] { -viewMatrix[2], -viewMatrix[6], -viewMatrix[10] };
	}

	private void calculateViewMatrix() {
		if ((dirtyFlags & VIEW_MATRIX_DIRTY) != 0) {
			viewMatrix = Mat4.identity();
			Mat4.mul(viewMatrix, Mat4.rotateX(orientation[1]));
			Mat4.mul(viewMatrix, Mat4.rotateY(orientation[0]));
			if (position[0] != 0 || position[1] != 0 || position[2] != 0) {
				Mat4.mul(
					viewMatrix,
					Mat4.translate(
						-position[0],
						-position[1],
						-position[2]
					)
				);
			}
			dirtyFlags &= ~VIEW_MATRIX_DIRTY;
		}
	}

	public float[] getViewMatrix() {
		calculateViewMatrix();
		return Arrays.copyOf(viewMatrix, viewMatrix.length);
	}

	private void calculateProjectionMatrix() {
		if ((dirtyFlags & PROJECTION_MATRIX_DIRTY) != 0) {
			projectionMatrix = Mat4.scale(zoom, zoom, 1.0f);
			if (isOrthographic) {
				Mat4.mul(projectionMatrix, Mat4.orthographic(viewportWidth, viewportHeight, nearPlane));
			} else {
				if (farPlane > 0.0f) {
					Mat4.mul(projectionMatrix, Mat4.perspective(viewportWidth, viewportHeight, nearPlane, farPlane));
				} else {
					Mat4.mul(projectionMatrix, Mat4.perspective(viewportWidth, viewportHeight, nearPlane));
				}
			}
			dirtyFlags &= ~PROJECTION_MATRIX_DIRTY;
		}
	}

	public float[] getProjectionMatrix() {
		calculateProjectionMatrix();
		return Arrays.copyOf(projectionMatrix, projectionMatrix.length);
	}

	private void calculateViewProjMatrix() {
		if ((dirtyFlags & VIEW_PROJ_MATRIX_DIRTY) != 0) {
			calculateViewMatrix();
			calculateProjectionMatrix();

			viewProjMatrix = Mat4.identity();
			Mat4.mul(viewProjMatrix, projectionMatrix);
			Mat4.mul(viewProjMatrix, viewMatrix);

			dirtyFlags &= ~VIEW_PROJ_MATRIX_DIRTY;
		}
	}

	public float[] getViewProjMatrix() {
		calculateViewProjMatrix();
		return Arrays.copyOf(viewProjMatrix, viewProjMatrix.length);
	}

	private void calculateInvViewProjMatrix() {
		if ((dirtyFlags & INV_VIEW_PROJ_MATRIX_DIRTY) != 0) {
			calculateViewProjMatrix();
			invViewProjMatrix = Mat4.inverse(viewProjMatrix);
			dirtyFlags &= ~INV_VIEW_PROJ_MATRIX_DIRTY;
		}
	}

	public float[] getInvViewProjMatrix() {
		calculateInvViewProjMatrix();
		return Arrays.copyOf(invViewProjMatrix, invViewProjMatrix.length);
	}

	private void calculateFrustumPlanes() {
		if ((dirtyFlags & FRUSTUM_PLANES_DIRTY) != 0) {
			calculateViewProjMatrix();
			Mat4.extractPlanes(
				viewProjMatrix,
				frustumPlanes[0], frustumPlanes[1],
				frustumPlanes[2], frustumPlanes[3],
				frustumPlanes[4], frustumPlanes[5],
				true
			);
			dirtyFlags &= ~FRUSTUM_PLANES_DIRTY;
		}
	}

	public float[][] getFrustumPlanes() {
		calculateFrustumPlanes();
		return frustumPlanes.clone();
	}

	public float[][] getFrustumCorners() {
		calculateInvViewProjMatrix();
		return Mat4.extractFrustumCorners(invViewProjMatrix);
	}

	public static final class AsyncTileVisibilityClear implements Runnable {
		private final ReentrantLock lock = new ReentrantLock();

		private VisibilityResult[] prevTileVisibility = new VisibilityResult[MAX_Z * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE];
		private boolean inFlight = false;

		public void execute(VisibilityResult[] prevTileVisibility) {
			this.prevTileVisibility = prevTileVisibility;
			inFlight = true;
			HdPlugin.THREAD_POOL.execute(this);
		}

		@SneakyThrows
		public void complete(boolean block) {
			if (!inFlight) return;

			if (block) {
				lock.lock();
				lock.unlock();
				inFlight = false;
			} else {
				if (lock.tryLock(100, TimeUnit.NANOSECONDS)) {
					lock.unlock();
					inFlight = false;
				}
			}
		}

		@Override
		public void run() {
			lock.lock();
			Arrays.fill(prevTileVisibility, VisibilityResult.UNKNOWN);
			lock.unlock();
		}
	}

	@RequiredArgsConstructor
	public static final class AsyncCullingJob implements Runnable {
		private final ReentrantLock lock = new ReentrantLock();
		private final ReentrantLock cullingLock = new ReentrantLock();
		private final SceneView view;
		private final int plane;

		private boolean inFlight = false;
		private int[][] tileHeights;
		private int[][] underwaterDepthLevels;

		public void execute(int[][] tileHeights, int[][] underwaterDepthLevels) {
			this.tileHeights = tileHeights;
			this.underwaterDepthLevels = underwaterDepthLevels;
			inFlight = true;
			HdPlugin.THREAD_POOL.execute(this);
		}

		@SneakyThrows
		public void complete(boolean block) {
			if (!inFlight) return;

			if (block) {
				lock.lock();
				lock.unlock();
				inFlight = false;
			} else {
				if (lock.tryLock(100, TimeUnit.NANOSECONDS)) {
					lock.unlock();
					inFlight = false;
				}
			}
		}

		@Override
		public void run() {
			lock.lock();
			try {
				int tileIdx = plane * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE;
				for (int tileExX = 0; tileExX < EXTENDED_SCENE_SIZE; tileExX++) {
					for (int tileExY = 0; tileExY < EXTENDED_SCENE_SIZE; tileExY++, tileIdx++) {
						performTileCulling(tileIdx, tileExX, tileExY);
					}
				}
			} finally {
				lock.unlock();
				inFlight = false;
			}
		}

		public VisibilityResult performTileCulling(int tileIdx, int tileExX, int tileExY) {
			VisibilityResult result = view.tileVisibility[tileIdx];
			if (result != VisibilityResult.UNKNOWN) { // Skip over tiles that are being processed or are known
				return result;
			}
			view.tileVisibility[tileIdx] = VisibilityResult.IN_PROGRESS; // Signal that we are processing this tile (Could be Client or Job Thread doing so)

			final int h0 = tileHeights[tileExX][tileExY];
			final int h1 = tileHeights[tileExX + 1][tileExY];
			final int h2 = tileHeights[tileExX][tileExY + 1];
			final int h3 = tileHeights[tileExX + 1][tileExY + 1];

			int x = (tileExX - SCENE_OFFSET) * LOCAL_TILE_SIZE;
			int z = (tileExY - SCENE_OFFSET) * LOCAL_TILE_SIZE;

			result = HDUtils.IsTileVisible(x, z, h0, h1, h2, h3, view.frustumPlanes) ? VisibilityResult.VISIBLE : VisibilityResult.HIDDEN;

			if (result == VisibilityResult.HIDDEN && underwaterDepthLevels != null) {
				final int dl0 = underwaterDepthLevels[tileExX][tileExY];
				final int dl1 = underwaterDepthLevels[tileExX + 1][tileExY];
				final int dl2 = underwaterDepthLevels[tileExX][tileExY + 1];
				final int dl3 = underwaterDepthLevels[tileExX + 1][tileExY + 1];

				if (dl0 > 0 || dl1 > 0 || dl2 > 0 || dl3 > 0) {
					final int uh0 = h0 + (dl0 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl0 - 1] : 0);
					final int uh1 = h1 + (dl1 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl1 - 1] : 0);
					final int uh2 = h2 + (dl2 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl2 - 1] : 0);
					final int uh3 = h3 + (dl3 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl3 - 1] : 0);

					result = HDUtils.IsTileVisible(x, z, uh0, uh1, uh2, uh3, view.frustumPlanes) ?
						VisibilityResult.VISIBLE :
						VisibilityResult.HIDDEN;
				}
			}

			return view.tileVisibility[tileIdx] = result;
		}
	}
}
