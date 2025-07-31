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
	private float[] viewMatrix;
	private float[] projectionMatrix;
	private float[] viewProjMatrix;
	private float[] invViewProjMatrix;

	private final float[][] frustumPlanes = new float[6][4];
	private final float[] position = new float[3];
	private final float[] orientation = new float[2];

	private boolean viewMatrixDirty = true;
	private boolean projectionMatrixDirty = true;
	private boolean viewProjMatrixDirty = true;
	private boolean invViewProjMatrixDirty = true;
	private boolean frustumPlanesDirty = true;

	private int viewportWidth = 10;
	private int viewportHeight = 10;

	private float zoom = 1.0f;
	private float nearPlane = 0.5f;
	private float farPlane = 1000.0f;
	private boolean isOrthographic = false;
	private boolean isReverseZ = false;
	private boolean invertPosition = false;

	public enum VisibilityResult { UNKNOWN, HIDDEN, VISIBLE }

	private final VisibilityResult[] tileVisibility = new VisibilityResult[MAX_Z * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE];
	private final AsyncCullingJob[] cullingJobs = new AsyncCullingJob[MAX_Z];
	private FrameTimer frameTimer;

	public SceneView(boolean isReverseZ, boolean isOrthographic, boolean invertPosition) {
		this.isReverseZ = isReverseZ;
		this.isOrthographic = isOrthographic;
		this.invertPosition = invertPosition;

		for (int plane = 0; plane < MAX_Z; plane++) {
			cullingJobs[plane] = new AsyncCullingJob(this, plane);
		}
	}

	public boolean isDirty() {
		return viewMatrixDirty | projectionMatrixDirty | viewProjMatrixDirty;
	}

	public SceneView setViewportWidth(int newViewportWidth) {
		if (viewportWidth != newViewportWidth) {
			viewportWidth = newViewportWidth;
			projectionMatrixDirty = true;
			viewProjMatrixDirty = true;
			invViewProjMatrixDirty = true;
			frustumPlanesDirty = true;
		}
		return this;
	}

	public SceneView setViewportHeight(int newViewportHeight) {
		if (viewportHeight != newViewportHeight) {
			viewportHeight = newViewportHeight;
			projectionMatrixDirty = true;
			viewProjMatrixDirty = true;
			invViewProjMatrixDirty = true;
			frustumPlanesDirty = true;
		}
		return this;
	}

	public SceneView setNearPlane(float newNearPlane) {
		if (nearPlane != newNearPlane) {
			nearPlane = newNearPlane;
			projectionMatrixDirty = true;
			viewProjMatrixDirty = true;
			invViewProjMatrixDirty = true;
			frustumPlanesDirty = true;
		}
		return this;
	}

	public SceneView setFarPlane(float newFarPlane) {
		if (farPlane != newFarPlane) {
			farPlane = newFarPlane;
			projectionMatrixDirty = true;
			viewProjMatrixDirty = true;
			invViewProjMatrixDirty = true;
			frustumPlanesDirty = true;
		}
		return this;
	}

	public SceneView setZoom(float newZoom) {
		if (zoom != newZoom) {
			zoom = newZoom;
			projectionMatrixDirty = true;
			viewProjMatrixDirty = true;
			invViewProjMatrixDirty = true;
			frustumPlanesDirty = true;
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
			viewMatrixDirty = true;
			viewProjMatrixDirty = true;
			invViewProjMatrixDirty = true;
			frustumPlanesDirty = true;
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
			viewMatrixDirty = true;
			viewProjMatrixDirty = true;
			invViewProjMatrixDirty = true;
			frustumPlanesDirty = true;
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
			viewMatrixDirty = true;
			viewProjMatrixDirty = true;
			invViewProjMatrixDirty = true;
			frustumPlanesDirty = true;
		}
		return this;
	}

	public SceneView setPosition(float[] newPosition) {
		if (position[0] != newPosition[0] || position[1] != newPosition[1] || position[2] != newPosition[2]) {
			position[0] = newPosition[0];
			position[1] = newPosition[1];
			position[2] = newPosition[2];
			viewMatrixDirty = true;
			viewProjMatrixDirty = true;
			invViewProjMatrixDirty = true;
			frustumPlanesDirty = true;
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
			viewMatrixDirty = true;
			viewProjMatrixDirty = true;
			invViewProjMatrixDirty = true;
			frustumPlanesDirty = true;
		}
		return this;
	}

	public SceneView setYaw(float yaw) {
		if (orientation[0] != yaw) {
			orientation[0] = yaw;
			viewMatrixDirty = true;
			viewProjMatrixDirty = true;
			invViewProjMatrixDirty = true;
			frustumPlanesDirty = true;
		}
		return this;
	}

	public float getYaw() {
		return orientation[0];
	}

	public SceneView setPitch(float pitch) {
		if (orientation[1] != pitch) {
			orientation[1] = pitch;
			viewMatrixDirty = true;
			viewProjMatrixDirty = true;
			invViewProjMatrixDirty = true;
			frustumPlanesDirty = true;
		}
		return this;
	}

	public float getPitch() {
		return orientation[1];
	}

	public float[] getOrientation() {
		return Arrays.copyOf(orientation, 2);
	}

	public float[] transformPoint(float[] position) {
		calculateViewProjMatrix();
		Mat4.projectVec(position, viewProjMatrix, position);
		return position;
	}

	public float[] invTransformPoint(float[] position) {
		calculateInvViewProjMatrix();
		Mat4.projectVec(position, invViewProjMatrix, position);
		return position;
	}

	@SneakyThrows
	public void performAsyncTileCulling(FrameTimer frameTimer, SceneContext ctx, boolean checkUnderwater) {
		if (ctx == null) {
			return;
		}

		for (AsyncCullingJob planeJob : cullingJobs) {
			planeJob.complete(true);
		}

		calculateFrustumPlanes();
		Arrays.fill(tileVisibility, VisibilityResult.UNKNOWN);
		this.frameTimer = frameTimer;

		for (AsyncCullingJob planeJob : cullingJobs) {
			planeJob.tileHeights = ctx.scene.getTileHeights()[planeJob.plane];
			planeJob.underwaterDepthLevels =
				checkUnderwater && ctx.underwaterDepthLevels != null ? ctx.underwaterDepthLevels[planeJob.plane] : null;

			planeJob.inFlight = true;
			HdPlugin.THREAD_POOL.execute(planeJob);
		}
	}

	@SneakyThrows
	public boolean isTileVisible(int plane, int tileExX, int tileExY) {
		final int tileIdx = (plane * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE) + (tileExX * EXTENDED_SCENE_SIZE) + tileExY;
		VisibilityResult result = tileVisibility[tileIdx];

		// Async Job has already processed the result, so we can use it without waiting
		if (result != VisibilityResult.UNKNOWN) {
			return result == VisibilityResult.VISIBLE;
		}

		frameTimer.begin(Timer.VISIBILITY_CHECK);
		do {
			cullingJobs[plane].complete(false); // Spin Wait, so that if this tiles result becomes available we can continue
			result = tileVisibility[tileIdx];
		} while (result == VisibilityResult.UNKNOWN);
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
			viewMatrixDirty = true;
			viewProjMatrixDirty = true;
			invViewProjMatrixDirty = true;
			frustumPlanesDirty = true;
		}
		return this;
	}

	public float[] getForwardDirection() {
		calculateViewMatrix();
		return new float[] { -viewMatrix[2], -viewMatrix[6], -viewMatrix[10] };
	}

	private void calculateViewMatrix() {
		if (viewMatrixDirty) {
			viewMatrix = Mat4.identity();
			Mat4.mul(viewMatrix, Mat4.rotateX(orientation[1]));
			Mat4.mul(viewMatrix, Mat4.rotateY(orientation[0]));
			if (position[0] != 0 || position[1] != 0 || position[2] != 0) {
				Mat4.mul(
					viewMatrix,
					Mat4.translate(
						invertPosition ? -position[0] : position[0],
						invertPosition ? -position[1] : position[1],
						invertPosition ? -position[2] : position[2]
					)
				);
			}
			viewMatrixDirty = false;
		}
	}

	public float[] getViewMatrix() {
		calculateViewMatrix();
		return Arrays.copyOf(viewMatrix, viewMatrix.length);
	}

	private void calculateProjectionMatrix() {
		if (projectionMatrixDirty) {
			if (isOrthographic) {
				projectionMatrix = Mat4.scale(zoom, zoom, zoom);
				Mat4.mul(projectionMatrix, Mat4.orthographic(viewportWidth, viewportHeight, nearPlane));
			} else {
				projectionMatrix = Mat4.scale(zoom, zoom, 1);
				if (isReverseZ) {
					Mat4.mul(
						projectionMatrix,
						Mat4.perspectiveReverseZ(viewportWidth, viewportHeight, nearPlane, farPlane)
					);
				} else {
					Mat4.mul(
						projectionMatrix,
						Mat4.perspective(viewportWidth, viewportHeight, nearPlane, farPlane)
					);
				}
			}
			projectionMatrixDirty = false;
		}
	}

	public float[] getProjectionMatrix() {
		calculateProjectionMatrix();
		return Arrays.copyOf(projectionMatrix, projectionMatrix.length);
	}

	private void calculateViewProjMatrix() {
		if (viewProjMatrixDirty) {
			calculateViewMatrix();
			calculateProjectionMatrix();

			viewProjMatrix = Mat4.identity();
			Mat4.mul(viewProjMatrix, projectionMatrix);
			Mat4.mul(viewProjMatrix, viewMatrix);

			viewProjMatrixDirty = false;
		}
	}

	public float[] getViewProjMatrix() {
		calculateViewProjMatrix();
		return Arrays.copyOf(viewProjMatrix, viewProjMatrix.length);
	}

	private void calculateInvViewProjMatrix() {
		if (invViewProjMatrixDirty) {
			invViewProjMatrix = Mat4.inverse(getViewProjMatrix());
			invViewProjMatrixDirty = false;
		}
	}

	public float[] getInvViewProjMatrix() {
		calculateInvViewProjMatrix();
		return Arrays.copyOf(invViewProjMatrix, invViewProjMatrix.length);
	}

	private void calculateFrustumPlanes() {
		if (frustumPlanesDirty) {
			calculateViewProjMatrix();
			Mat4.extractPlanes(
				viewProjMatrix,
				frustumPlanes[0], frustumPlanes[1],
				frustumPlanes[2], frustumPlanes[3],
				frustumPlanes[4], frustumPlanes[5],
				true
			);
			frustumPlanesDirty = false;
		}
	}

	public float[][] getFrustumPlanes() {
		calculateFrustumPlanes();
		return frustumPlanes.clone();
	}

	@RequiredArgsConstructor
	public static final class AsyncCullingJob implements Runnable {
		private final ReentrantLock lock = new ReentrantLock();
		private final SceneView view;
		private final int plane;

		private boolean inFlight = false;
		public int[][] tileHeights;
		public int[][] underwaterDepthLevels;


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

		@SneakyThrows
		@Override
		public void run() {
			lock.lock();
			final int tileSceneOffset = (-SCENE_OFFSET) * LOCAL_TILE_SIZE;
			int tileIdx = (plane * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE);
			for (int tileExX = 0, x = tileSceneOffset; tileExX < EXTENDED_SCENE_SIZE; tileExX++, x += LOCAL_TILE_SIZE) {
				final int[] heightsR0 = tileHeights[tileExX];
				final int[] heightsR1 = tileHeights[tileExX + 1];

				for (int tileExY = 0, z = tileSceneOffset; tileExY < EXTENDED_SCENE_SIZE; tileExY++, z += LOCAL_TILE_SIZE, tileIdx++) {
					final int h0 = heightsR0[tileExY];
					final int h1 = heightsR1[tileExY];
					final int h2 = heightsR0[tileExY + 1];
					final int h3 = heightsR1[tileExY + 1];

					boolean isVisible = HDUtils.IsTileVisible(x, z, h0, h1, h2, h3, view.frustumPlanes);

					if (!isVisible && underwaterDepthLevels != null) {
						final int dl0 = underwaterDepthLevels[tileExX][tileExY];
						final int dl1 = underwaterDepthLevels[tileExX + 1][tileExY];
						final int dl2 = underwaterDepthLevels[tileExX][tileExY + 1];
						final int dl3 = underwaterDepthLevels[tileExX + 1][tileExY + 1];

						if (dl0 > 0 || dl1 > 0 || dl2 > 0 || dl3 > 0) {
							final int uh0 = h0 + (dl0 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl0 - 1] : 0);
							final int uh1 = h1 + (dl1 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl1 - 1] : 0);
							final int uh2 = h2 + (dl2 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl2 - 1] : 0);
							final int uh3 = h3 + (dl3 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl3 - 1] : 0);

							isVisible = HDUtils.IsTileVisible(x, z, uh0, uh1, uh2, uh3, view.frustumPlanes);
						}
					}

					view.tileVisibility[tileIdx] = isVisible ? VisibilityResult.VISIBLE : VisibilityResult.HIDDEN;
				}
			}
			lock.unlock();
			inFlight = false;
		}
	}
}
