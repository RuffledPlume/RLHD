package rs117.hd.data;

import rs117.hd.utils.buffer.GpuIntBuffer;

public final class ModelBufferData {
	public int vertexOffset;
	public int uvOffset;
	public int vertexCount;
	public int renderBufferOffset;

	public ModelBufferData(int vertexOffset, int uvOffset, int vertexCount) {
		this.vertexOffset = vertexOffset;
		this.uvOffset = uvOffset;
		this.vertexCount = vertexCount;
	}

	public ModelBufferData() {}

	public void push(SceneDrawContext drawContext, int tileX, int tileY) {
		renderBufferOffset = drawContext.renderBufferOffset;
		drawContext.renderBufferOffset += vertexCount;
		drawContext.numPassthroughModels++;

		drawContext.modelPassthroughBuffer.ensureCapacity(8).getBuffer()
			.put(vertexOffset)
			.put(uvOffset)
			.put(vertexCount / 3)
			.put(renderBufferOffset)
			.put(0)
			.put(tileX)
			.put(0)
			.put(tileY);
	}

	public void push(SceneDrawContext drawContext, GpuIntBuffer modelInfoBuffer, StaticRenderableInstance instance, int plane) {
		renderBufferOffset = drawContext.renderBufferOffset;
		drawContext.renderBufferOffset += vertexCount;

		modelInfoBuffer.ensureCapacity(8).getBuffer()
			.put(vertexOffset)
			.put(uvOffset)
			.put(vertexCount / 3)
			.put(renderBufferOffset)
			.put(instance.orientation | (instance.renderable.hillskew ? 1 : 0) << 26 |  plane << 24)
			.put(instance.x)
			.put(instance.y << 16 | instance.renderable.height & 0xFFFF)
			.put(instance.z);
	}
}
