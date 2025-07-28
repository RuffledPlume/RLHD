package rs117.hd.opengl;

import rs117.hd.utils.buffer.GpuIntBuffer;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glDrawElements;

public class ModelDrawBuffer {
	private final GpuIntBuffer indicesBuffer = new GpuIntBuffer();

	private int[] stagingBuffer = new int[3];
	private boolean hasFlipped = false;

	public void addModel(ModelDrawList.ModelInfo info) {
		int renderBufferOffset = info.getRenderBufferOffset();
		int vertexCount = info.getVertexCount();

		assert vertexCount % 3 == 0;
		indicesBuffer.ensureCapacity(vertexCount);
		if(stagingBuffer.length < vertexCount) {
			stagingBuffer = new int[vertexCount];
		}

		int vertexIDx = renderBufferOffset;
		for (int i = 0; i < vertexCount; i++) {
			stagingBuffer[i] = vertexIDx++;
		}
		indicesBuffer.getBuffer().put(stagingBuffer, 0, vertexCount);
	}

	public void draw() {
		if(!hasFlipped) {
			indicesBuffer.flip();
			hasFlipped = true;
		}
		glDrawElements(GL_TRIANGLES, indicesBuffer.getBuffer());
	}

	public void clear() {
		indicesBuffer.clear();
		hasFlipped = false;
	}
}
