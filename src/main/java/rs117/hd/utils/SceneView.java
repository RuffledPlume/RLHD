package rs117.hd.utils;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;

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

	private final boolean[][][] tileVisibility = new boolean[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE];
	private final Future<?>[] inflightCullingTasks = new Future[MAX_Z];
	private boolean isCullingInFlight = false;
	private FrameTimer frameTimer;

	public SceneView(boolean isReverseZ, boolean isOrthographic, boolean invertPosition) {
		this.isReverseZ = isReverseZ;
		this.isOrthographic = isOrthographic;
		this.invertPosition = invertPosition;
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

	private void completeTileCulling() {
		if (isCullingInFlight) {
			frameTimer.begin(Timer.VISIBILITY_CHECK);
			try {
				for (int plane = 0; plane < MAX_Z; plane++) {
					if (inflightCullingTasks[plane] != null) {
						inflightCullingTasks[plane].get(1, TimeUnit.SECONDS);
						inflightCullingTasks[plane] = null;
					}
				}
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				throw new RuntimeException(e);
			}
			frameTimer.end(Timer.VISIBILITY_CHECK);
			isCullingInFlight = false;
		}
	}

	public void performAsyncTileCulling(FrameTimer frameTimer, SceneContext ctx, boolean checkUnderwater) {
		if (ctx == null && !isCullingInFlight) {
			return;
		}

		this.frameTimer = frameTimer;
		calculateFrustumPlanes();

		isCullingInFlight = true;
		for (int plane = 0; plane < MAX_Z; plane++) {
			int finalPlane = plane;
			inflightCullingTasks[plane] = HdPlugin.THREAD_POOL.submit(() -> {
				for (int tileExX = 0; tileExX < EXTENDED_SCENE_SIZE; tileExX++) {
					for (int tileExY = 0; tileExY < EXTENDED_SCENE_SIZE; tileExY++) {
						int x = ((tileExX - SCENE_OFFSET) << Perspective.LOCAL_COORD_BITS) + LOCAL_HALF_TILE_SIZE;
						int z = ((tileExY - SCENE_OFFSET) << Perspective.LOCAL_COORD_BITS) + LOCAL_HALF_TILE_SIZE;

						int[][][] tileHeights = ctx.scene.getTileHeights();
						int h0 = tileHeights[finalPlane][tileExX][tileExY];
						int h1 = tileHeights[finalPlane][tileExX + 1][tileExY];
						int h2 = tileHeights[finalPlane][tileExX][tileExY + 1];
						int h3 = tileHeights[finalPlane][tileExX + 1][tileExY + 1];

						boolean isVisible = HDUtils.IsTileVisible(x, z, h0, h1, h2, h3, frustumPlanes);

						if (!isVisible && checkUnderwater) {
							int dl0 = ctx.underwaterDepthLevels[finalPlane][tileExX][tileExY];
							int dl1 = ctx.underwaterDepthLevels[finalPlane][tileExX + 1][tileExY];
							int dl2 = ctx.underwaterDepthLevels[finalPlane][tileExX][tileExY + 1];
							int dl3 = ctx.underwaterDepthLevels[finalPlane][tileExX + 1][tileExY + 1];

							if (dl0 > 0 || dl1 > 0 || dl2 > 0 || dl3 > 0) {
								int uh0 = h0 + (dl0 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl0 - 1] : 0);
								int uh1 = h1 + (dl1 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl1 - 1] : 0);
								int uh2 = h2 + (dl2 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl2 - 1] : 0);
								int uh3 = h3 + (dl3 > 0 ? ProceduralGenerator.DEPTH_LEVEL_SLOPE[dl3 - 1] : 0);

								isVisible = HDUtils.IsTileVisible(x, z, uh0, uh1, uh2, uh3, frustumPlanes);
							}
						}

						tileVisibility[finalPlane][tileExX][tileExY] = isVisible;
					}
				}
			});
		}
	}

	public boolean isTileVisible(int plane, int tileExX, int tileExY) {
		if (isCullingInFlight) {
			completeTileCulling();
		}

		return tileVisibility[plane][tileExX][tileExY];
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
}
