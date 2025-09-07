package rs117.hd.opengl.renderjobs;

import rs117.hd.data.SceneDrawContext;
import rs117.hd.data.StaticRenderableInstance;
import rs117.hd.data.StaticTileData;
import rs117.hd.opengl.AWTContextWrapper;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.SceneCullingManager;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;

public class PushStaticModelData extends RenderJob {
	private static final JobPool<PushStaticModelData> POOL = new JobPool<>(PushStaticModelData::new);

	private SceneCullingManager.CullingResults cullingResults;

	public PushStaticModelData() {super(POOL);}

	@Override
	protected void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext) {
		for(int i = 0; i < cullingResults.getNumVisibleTiles(); i++) {
			final int tileIdx = cullingResults.getVisibleTile(i);
			final StaticTileData tileData = sceneContext.staticTileData[tileIdx];

			final int tileX = (tileData.tileExX - SCENE_OFFSET) * LOCAL_TILE_SIZE;
			final int tileY = (tileData.tileExY - SCENE_OFFSET) * LOCAL_TILE_SIZE;

			if(tileData.modelBuffer != null) {
				tileData.modelBuffer.push(drawContext, tileX,  tileY);
			}

			if(tileData.underwaterBuffer != null) {
				tileData.underwaterBuffer.push(drawContext, tileX, tileY);
			}

			if(tileData.paintBuffer != null) {
				tileData.paintBuffer.push(drawContext, tileX, tileY);
			}

			for(int k = 0; k < tileData.renderables.size(); k++) {
				final StaticRenderableInstance instance = tileData.renderables.get(k);
				final int faceCount = instance.renderableBuffer.vertexCount / 3;
				final GpuIntBuffer modelInfoBuffer = drawContext.modelSortingBuffers.bufferForTriangles(faceCount);

				instance.renderableBuffer.push(drawContext, modelInfoBuffer, instance, tileData.plane);
			}
		}
	}

	public static void addToQueue(SceneCullingManager.CullingResults cullingResults) {
		PushStaticModelData job = POOL.pop();
		job.cullingResults = cullingResults;
		job.submit(SUBMIT_SERIAL);
	}
}
