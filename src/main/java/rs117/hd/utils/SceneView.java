package rs117.hd.utils;

import java.util.Arrays;
import net.runelite.api.*;

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

	public boolean isTileVisible(int x, int z, int h0, int h1, int h2, int h3) {
		float[][] cullingPlanes = frustumPlanes;
		if (frustumPlanesDirty) {
			cullingPlanes = getFrustumPlanes();
		}
		return HDUtils.IsTileVisible(x, z, h0, h1, h2, h3, cullingPlanes);
	}

	public boolean isModeVisible(Model model, int x, int y, int z) {
		float[][] cullingPlanes = frustumPlanes;
		if (frustumPlanesDirty) {
			cullingPlanes = getFrustumPlanes();
		}
		return HDUtils.isModelVisible(x, y, z, model, cullingPlanes);
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

	public float[] getViewMatrix() {
		if (viewMatrixDirty) {
			viewMatrix = Mat4.rotateX(orientation[1]);
			Mat4.mul(viewMatrix, Mat4.rotateY(orientation[0]));
			Mat4.mul(
				viewMatrix,
				Mat4.translate(
					invertPosition ? -position[0] : position[0],
					invertPosition ? -position[1] : position[1],
					invertPosition ? -position[2] : position[2]
				)
			);
			viewMatrixDirty = false;
		}

		return viewMatrix;
	}

	public float[] getProjectionMatrix() {
		if (projectionMatrixDirty) {
			projectionMatrix = Mat4.scale(zoom, zoom, 1);
			if (isOrthographic) {
				// TODO: Cook something up here.. First get scene projection working
			} else {
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

		return Arrays.copyOf(projectionMatrix, projectionMatrix.length);
	}

	public float[] getViewProjMatrix() {
		if (viewProjMatrixDirty) {
			viewProjMatrix = getProjectionMatrix();
			Mat4.mul(viewProjMatrix, getViewMatrix());
			viewProjMatrixDirty = false;
		}

		return Arrays.copyOf(viewProjMatrix, viewProjMatrix.length);
	}

	public float[] getInvViewProjMatrix() {
		if (invViewProjMatrixDirty) {
			invViewProjMatrix = Mat4.inverse(getViewProjMatrix());
			invViewProjMatrixDirty = false;
		}
		return Arrays.copyOf(invViewProjMatrix, invViewProjMatrix.length);
	}

	public float[][] getFrustumPlanes() {
		if (frustumPlanesDirty) {
			Mat4.extractPlanes(
				getViewProjMatrix(),
				frustumPlanes[0], frustumPlanes[1],
				frustumPlanes[2], frustumPlanes[3],
				frustumPlanes[4], frustumPlanes[5],
				true
			);
			frustumPlanesDirty = false;
		}
		return frustumPlanes.clone();
	}
}
