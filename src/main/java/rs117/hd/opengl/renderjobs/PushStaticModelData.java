package rs117.hd.opengl.renderjobs;

import rs117.hd.data.SceneDrawContext;
import rs117.hd.data.StaticRenderableInstance;
import rs117.hd.data.StaticTileData;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.SceneCullingManager;

import static net.runelite.api.Perspective.*;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;

public class PushStaticModelData extends RenderJob {
	private static final JobPool<PushStaticModelData> POOL = new JobPool<>(PushStaticModelData::new);

	private SceneCullingManager.CullingResults cullingResults;

	@Override
	protected void doRenderWork(SceneDrawContext drawContext, SceneContext sceneContext) {
		for(int i = 0; i < cullingResults.getNumVisibleTiles(); i++) {
			final int tileIdx = cullingResults.getVisibleTile(i);
			final StaticTileData tileData = sceneContext.staticTileData[tileIdx];

			final int tileX = (tileData.tileExX - SCENE_OFFSET) * LOCAL_TILE_SIZE;
			final int tileY = (tileData.tileExY - SCENE_OFFSET) * LOCAL_TILE_SIZE;

			if(tileData.modelBuffer != null) {
				drawContext.modelPassthroughBuffer.ensureCapacity(8).getBuffer()
					.put(tileData.modelBuffer.vertexOffset)
					.put(tileData.modelBuffer.uvOffset)
					.put(tileData.modelBuffer.vertexCount / 3)
					.put(drawContext.renderBufferOffset)
					.put(0)
					.put(tileX)
					.put(0)
					.put(tileY);

				tileData.modelBuffer.renderBufferOffset = drawContext.renderBufferOffset;
				drawContext.renderBufferOffset += tileData.modelBuffer.vertexCount;
				drawContext.numPassthroughModels++;
			}

			if(tileData.underwaterBuffer != null) {
				drawContext.modelPassthroughBuffer.ensureCapacity(8).getBuffer()
					.put(tileData.underwaterBuffer.vertexOffset)
					.put(tileData.underwaterBuffer.uvOffset)
					.put(tileData.underwaterBuffer.vertexCount / 3)
					.put(drawContext.renderBufferOffset)
					.put(0)
					.put(tileX)
					.put(0)
					.put(tileY);

				tileData.underwaterBuffer.renderBufferOffset = drawContext.renderBufferOffset;
				drawContext.renderBufferOffset += tileData.underwaterBuffer.vertexCount;
				drawContext.numPassthroughModels++;
			}

			if(tileData.paintBuffer != null) {
				drawContext.modelPassthroughBuffer.ensureCapacity(8).getBuffer()
					.put(tileData.paintBuffer.vertexOffset)
					.put(tileData.paintBuffer.uvOffset)
					.put(tileData.paintBuffer.vertexCount / 3)
					.put(drawContext.renderBufferOffset)
					.put(0)
					.put(tileX)
					.put(0)
					.put(tileY);
				tileData.paintBuffer.renderBufferOffset = drawContext.renderBufferOffset;
				drawContext.renderBufferOffset += tileData.paintBuffer.vertexCount;
				drawContext.numPassthroughModels++;
			}

			for(int k = 0; k < tileData.renderables.size(); k++) {
				StaticRenderableInstance instance = tileData.renderables.get(k);

				final int faceCount = instance.renderableBuffer.vertexCount / 3;
				drawContext.modelSortingBuffers.bufferForTriangles(faceCount).ensureCapacity(8).getBuffer()
					.put(instance.renderableBuffer.vertexOffset)
					.put(instance.renderableBuffer.uvOffset)
					.put(faceCount)
					.put(drawContext.renderBufferOffset)
					.put(instance.orientation | (instance.renderable.hillskew ? 1 : 0) << 26 |  tileData.plane << 24)
					.put(instance.x)
					.put(instance.y << 16 | instance.renderable.height & 0xFFFF)
					.put(instance.z);

				instance.renderableBuffer.renderBufferOffset = drawContext.renderBufferOffset;
				drawContext.renderBufferOffset += instance.renderableBuffer.vertexCount;
			}
		}

		checkGLErrors();

		POOL.push(this);
	}

	public static void addToQueue(SceneCullingManager.CullingResults cullingResults) {
		PushStaticModelData job = POOL.pop();
		job.cullingResults = cullingResults;
		job.submit(SUBMIT_SERIAL);
	}
}
