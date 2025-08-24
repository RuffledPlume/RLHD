package rs117.hd.opengl.renderjobs;

import rs117.hd.data.ModelSortingBuffers;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.scene.SceneContext;

import static rs117.hd.HdPlugin.NORMAL_SIZE;
import static rs117.hd.HdPlugin.UV_SIZE;
import static rs117.hd.HdPlugin.VERTEX_SIZE;
import static rs117.hd.HdPlugin.checkGLErrors;

public class UploadSceneBuffer extends RenderJob{
	private static final JobPool<UploadSceneBuffer> POOL = new JobPool<>(UploadSceneBuffer::new);

	@Override
	protected void doRenderWork(SceneDrawContext drawContext, SceneContext sceneContext) {

		sceneContext.stagingBufferVertices.flip();
		sceneContext.stagingBufferUvs.flip();
		sceneContext.stagingBufferNormals.flip();

		drawContext.hStagingBufferVertices.upload(sceneContext.stagingBufferVertices, drawContext.dynamicOffsetVertices * 4L * VERTEX_SIZE);
		drawContext.hStagingBufferUvs.upload(sceneContext.stagingBufferUvs, drawContext.dynamicOffsetUvs * 4L * UV_SIZE);
		drawContext.hStagingBufferNormals.upload(sceneContext.stagingBufferNormals, drawContext.dynamicOffsetVertices * 4L * NORMAL_SIZE);

		sceneContext.stagingBufferVertices.clear();
		sceneContext.stagingBufferUvs.clear();
		sceneContext.stagingBufferNormals.clear();

		drawContext.modelPassthroughBuffer.flip();
		drawContext.hModelPassthroughBuffer.upload(drawContext.modelPassthroughBuffer);
		drawContext.modelPassthroughBuffer.clear();

		final ModelSortingBuffers modelSortingBuffers = drawContext.modelSortingBuffers;
		for (int i = 0; i < modelSortingBuffers.modelSortingBuffers.length; i++) {
			var buffer = modelSortingBuffers.modelSortingBuffers[i];
			buffer.flip();
			modelSortingBuffers.hModelSortingBuffers[i].upload(buffer);
			buffer.clear();
		}

		// Output buffers
		// each vertex is an ivec4, which is 16 bytes
		drawContext.hRenderBufferVertices.ensureCapacity(drawContext.renderBufferOffset * 16L);
		// each vertex is an ivec4, which is 16 bytes
		drawContext.hRenderBufferUvs.ensureCapacity(drawContext.renderBufferOffset * 16L);
		// each vertex is an ivec4, which is 16 bytes
		drawContext.hRenderBufferNormals.ensureCapacity(drawContext.renderBufferOffset * 16L);

		checkGLErrors();

		POOL.push(this);
	}

	public static void addToQueue() { POOL.pop().submit(SUBMIT_SERIAL); }
}
