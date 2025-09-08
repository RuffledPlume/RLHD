package rs117.hd.opengl.renderjobs;

import rs117.hd.data.SceneDrawContext;
import rs117.hd.data.SceneDrawOrder;
import rs117.hd.opengl.AWTContextWrapper;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.SceneCullingManager;
import rs117.hd.utils.ObjectPool;

public class BuildSceneDrawBuffer extends RenderJob {
	private static final ObjectPool<BuildSceneDrawBuffer> POOL = new ObjectPool<>(BuildSceneDrawBuffer::new);

	private SceneCullingManager.CullingResults cullingResults;

	public BuildSceneDrawBuffer() {super(POOL);}

	@Override
	protected void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext) {
		// Note: Due to a lack of ZBuffer, the SceneDrawBuffer has to wait the sceneDrawOrder to be built by DrawCallback
		drawContext.sceneDrawOrder.waitForSignal();

		int drawOrderCount = drawContext.sceneDrawOrder.getDrawOrderSize();
		for(int i = 0; i < drawOrderCount; ++i) {
			int drawType = drawContext.sceneDrawOrder.getDrawType(i);
			int drawOrder = drawContext.sceneDrawOrder.getDrawOrder(i);

			switch (drawType) {
				case SceneDrawOrder.DRAW_TYPE_SCENE_PAINT:
					drawContext.sceneDrawBuffer.addModel(sceneContext.staticTileData[drawOrder].paintBuffer);
					break;
				case SceneDrawOrder.DRAW_TYPE_TILE_MODEL:
					drawContext.sceneDrawBuffer.addModel(sceneContext.staticTileData[drawOrder].underwaterBuffer);
					drawContext.sceneDrawBuffer.addModel(sceneContext.staticTileData[drawOrder].modelBuffer);
					break;
				case SceneDrawOrder.DRAW_TYPE_STATIC_RENDERABLE:
					//int tileIdx = drawOrder & 0xFFFF;
					//int instanceIdx = drawOrder >> 16;
					//if(instanceIdx < sceneContext.staticTileData[tileIdx].renderables.size()) {
					//	drawContext.sceneDrawBuffer.addModel(sceneContext.staticTileData[tileIdx].renderables.get(instanceIdx).renderableBuffer);
					//}
					break;
				case SceneDrawOrder.DRAW_TYPE_DYNAMIC_RENDERABLE:
					// TODO: Implement
					break;
			}
		}

		drawContext.sceneDrawBuffer.upload();
	}

	public static void addToQueue(SceneCullingManager.CullingResults cullingResults) {
		BuildSceneDrawBuffer job = POOL.pop();
		job.cullingResults = cullingResults;
		job.submit(SUBMIT_SERIAL);
	}
}

