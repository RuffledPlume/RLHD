package rs117.hd.opengl;

import java.nio.IntBuffer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.data.ModelBufferData;
import rs117.hd.opengl.renderjobs.UploadGPUIntBuffer;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;

@Slf4j
public final class ModelDrawBuffer extends GLBuffer {
	private GpuIntBuffer stagingIndices;
	@Getter
	private int indicesCount = 0;

	public ModelDrawBuffer(String name) {
		super(name + " Indices", GL_ELEMENT_ARRAY_BUFFER, GL_STREAM_DRAW);
	}

	public void addModel(ModelBufferData modelData) {
		if(modelData == null || modelData.vertexOffset <= 0) {
			return;
		}

		final IntBuffer buf = stagingIndices.ensureCapacity(modelData.vertexCount).getBuffer();
		for (int v = 0; v < modelData.vertexCount; v++) {
			buf.put(modelData.renderBufferOffset + v);
			indicesCount++;
		}
	}

	public void upload() {
		UploadGPUIntBuffer.submit(this, stagingIndices);
	}

	@Override
	public void initialize() {
		super.initialize();
		stagingIndices = new GpuIntBuffer(800);
	}

	@Override
	public void destroy() {
		stagingIndices.destroy();
		super.destroy();
	}

	public void draw() {
		if(indicesCount <= 0){
			return;
		}

		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, id);
		glDrawElements(GL_TRIANGLES, indicesCount, GL_UNSIGNED_INT, 0);
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
	}

	public void clear() {
		indicesCount = 0;
	}
}
