package rs117.hd.opengl.renderjobs;

import rs117.hd.data.ModelSortingBuffers;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.AWTContextWrapper;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.ObjectPool;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_INT;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glVertexAttribIPointer;
import static rs117.hd.HdPlugin.NORMAL_SIZE;
import static rs117.hd.HdPlugin.UV_SIZE;
import static rs117.hd.HdPlugin.VERTEX_SIZE;

public class UploadSceneBuffer extends RenderJob{
	private static final ObjectPool<UploadSceneBuffer> POOL = new ObjectPool<>(UploadSceneBuffer::new);

	private int vaoScene;

	public UploadSceneBuffer() { super(POOL);}

	@Override
	protected void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext) {
		sceneContext.stagingBufferVertices.flip();
		sceneContext.stagingBufferUvs.flip();
		sceneContext.stagingBufferNormals.flip();
		drawContext.modelPassthroughBuffer.flip();

		drawContext.hStagingBufferVertices.upload(sceneContext.stagingBufferVertices, drawContext.dynamicOffsetVertices * 4L * VERTEX_SIZE);
		drawContext.hStagingBufferUvs.upload(sceneContext.stagingBufferUvs, drawContext.dynamicOffsetUvs * 4L * UV_SIZE);
		drawContext.hStagingBufferNormals.upload(sceneContext.stagingBufferNormals, drawContext.dynamicOffsetVertices * 4L * NORMAL_SIZE);
		drawContext.hModelPassthroughBuffer.upload(drawContext.modelPassthroughBuffer);

		sceneContext.stagingBufferVertices.clear();
		sceneContext.stagingBufferUvs.clear();
		sceneContext.stagingBufferNormals.clear();
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

		updateSceneVao(drawContext.hRenderBufferVertices, drawContext.hRenderBufferUvs, drawContext.hRenderBufferNormals);
	}

	private void updateSceneVao(GLBuffer vertexBuffer, GLBuffer uvBuffer, GLBuffer normalBuffer) {
		glBindVertexArray(vaoScene);

		glEnableVertexAttribArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer.id);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, 0);

		glEnableVertexAttribArray(1);
		glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer.id);
		glVertexAttribIPointer(1, 1, GL_INT, 16, 12);

		glEnableVertexAttribArray(2);
		glBindBuffer(GL_ARRAY_BUFFER, uvBuffer.id);
		glVertexAttribPointer(2, 3, GL_FLOAT, false, 16, 0);

		glEnableVertexAttribArray(3);
		glBindBuffer(GL_ARRAY_BUFFER, uvBuffer.id);
		glVertexAttribIPointer(3, 1, GL_INT, 16, 12);

		glEnableVertexAttribArray(4);
		glBindBuffer(GL_ARRAY_BUFFER, normalBuffer.id);
		glVertexAttribPointer(4, 4, GL_FLOAT, false, 0, 0);

		glBindVertexArray(0);
	}

	public static void addToQueue(int vaoScene) {
		UploadSceneBuffer job = POOL.pop();
		job.vaoScene = vaoScene;
		job.submit(SUBMIT_SERIAL);
	}
}
