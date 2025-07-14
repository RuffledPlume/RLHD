package rs117.hd.opengl;

import java.nio.ShortBuffer;
import rs117.hd.HdPlugin;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.Vector;

public class AsyncTileCulling implements Runnable {
	public HdPlugin plugin;
	public ShortBuffer buffer;

	public int xMin;
	public int yMin;
	public int xMax;
	public int yMax;

	public AsyncTileCulling(HdPlugin plugin, int xOffset, int yOffset, int xCount, int yCount) {
		this.plugin = plugin;
		this.xMin = xOffset;
		this.yMin = yOffset;
		this.xMax = xOffset + xCount;
		this.yMax = yOffset + yCount;
	}

	private void putLightIndex(int x, int y, int z, short value) {
		int bufferPosition = z * plugin.tileCountX * plugin.tileCountY + y * plugin.tileCountX + x;
		buffer.put(bufferPosition, value);
	}


	@Override
	public void run() {
		for (int y = yMin; y < yMax; y++) {
			final float ndcMinY = (2.0f * y) / plugin.tileCountY - 1.0f;
			final float ndcMaxY = (2.0f * (y + 1)) / plugin.tileCountY - 1.0f;
			for (int x = xMin; x < xMax; x++) {
				final float ndcMinX = (2.0f * x) / plugin.tileCountX - 1.0f;
				final float ndcMaxX = (2.0f * (x + 1)) / plugin.tileCountX - 1.0f;

				float[] tile_bl = new float[] {ndcMinX, ndcMinY, 0.0f, 1.0f};
				float[] tile_br = new float[] {ndcMaxX, ndcMinY, 0.0f, 1.0f};
				float[] tile_tl = new float[] {ndcMinX, ndcMaxY, 0.0f, 1.0f};
				float[] tile_tr = new float[] {ndcMaxX, ndcMaxY, 0.0f, 1.0f};

				Mat4.projectVec(tile_bl, plugin.invProjectionMatrix, tile_bl);
				Mat4.projectVec(tile_br, plugin.invProjectionMatrix, tile_br);
				Mat4.projectVec(tile_tl, plugin.invProjectionMatrix, tile_tl);
				Mat4.projectVec(tile_tr, plugin.invProjectionMatrix, tile_tr);

				float[][] tilePlanes =
					{
						Vector.planeFromPoints(plugin.cameraPosition, tile_bl, tile_tl), // Left
						Vector.planeFromPoints(plugin.cameraPosition, tile_tr, tile_br), // Right
						Vector.planeFromPoints(plugin.cameraPosition, tile_br, tile_bl), // Bottom
						Vector.planeFromPoints(plugin.cameraPosition, tile_tl, tile_tr) // Top
					};

				int tileLightIndex = 0;
				for (int lightIdx = 0; lightIdx < plugin.sceneContext.numVisibleLights; lightIdx++) {
					Light light = plugin.sceneContext.lights.get(lightIdx);
					boolean intersects = HDUtils.isSphereInsideFrustum(
						light.pos[0] + plugin.cameraShift[0],
						light.pos[1],
						light.pos[2] + plugin.cameraShift[1],
						light.radius,
						tilePlanes);

					if (intersects) {
						putLightIndex(x, y, lightIdx, (short)(lightIdx + 1)); // Put the index in the 3D Texture
						tileLightIndex++;
					}

					if (tileLightIndex >= plugin.configMaxLightsPerTile) {
						break; // Tile cant fit any more lights in
					}
				}

				for (; tileLightIndex < plugin.configMaxLightsPerTile; tileLightIndex++) {
					putLightIndex(x, y, tileLightIndex, (short)0);
				}
			}
		}

		plugin.tiledLightingLatch.countDown();
	}
}
