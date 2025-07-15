package rs117.hd.opengl;

import java.nio.ShortBuffer;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Mat4;
import rs117.hd.utils.Vector;
import rs117.hd.utils.threading.TwoDJob;

@Slf4j
public class AsyncTileCulling extends TwoDJob {
	private HdPlugin plugin;
	private ShortBuffer buffer;

	public static AsyncTileCulling createJob(HdPlugin plugin, ShortBuffer buffer) {
		AsyncTileCulling newJob = tryGetFromCacheOrCreate(AsyncTileCulling.class, AsyncTileCulling::new);
		newJob.plugin = plugin;
		newJob.buffer = buffer;
		return newJob;
	}

	private void putLightIndex(int x, int y, int z, short value) {
		if(buffer != null) {
			int bufferPosition = z * plugin.tileCountX * plugin.tileCountY + y * plugin.tileCountX + x;
			if(bufferPosition < buffer.limit()) {
				buffer.put(bufferPosition, value);
			} else {
				log.debug("Writing out of range.. x: {} y: {} z {} position: {} limit: {}", x, y, z, bufferPosition, buffer.limit());
			}
		}
	}

	@Override
	protected void execute(int tileX, int tileY) {
		final float ndcMinX = (2.0f * tileX) / plugin.tileCountX - 1.0f;
		final float ndcMaxX = (2.0f * (tileX + 1)) / plugin.tileCountX - 1.0f;

		final float ndcMinY = (2.0f * tileY) / plugin.tileCountY - 1.0f;
		final float ndcMaxY = (2.0f * (tileY + 1)) / plugin.tileCountY - 1.0f;

		float[] tile_bl = new float[] { ndcMinX, ndcMinY, 0.0f, 1.0f };
		float[] tile_br = new float[] { ndcMaxX, ndcMinY, 0.0f, 1.0f };
		float[] tile_tl = new float[] { ndcMinX, ndcMaxY, 0.0f, 1.0f };
		float[] tile_tr = new float[] { ndcMaxX, ndcMaxY, 0.0f, 1.0f };

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
				tilePlanes
			);

			if (intersects) {
				putLightIndex(tileX, tileY, tileLightIndex, (short) (lightIdx + 1)); // Put the index in the 3D Texture
				tileLightIndex++;
			}

			if (tileLightIndex >= plugin.configMaxLightsPerTile) {
				break; // Tile cant fit any more lights in
			}
		}

		for (; tileLightIndex < plugin.configMaxLightsPerTile; tileLightIndex++) {
			putLightIndex(tileX, tileY, tileLightIndex, (short) 0);
		}
	}
}
