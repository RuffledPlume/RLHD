package rs117.hd.opengl;

import org.lwjgl.system.MemoryUtil;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_WRITE_ONLY;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;

public class ModelDrawBuffer extends GLBuffer {

	private int count = 0;

	public ModelDrawBuffer(String name) {
		super(name + " Indices", GL_ELEMENT_ARRAY_BUFFER, GL_STREAM_DRAW);
	}

	public void addModel(ModelDrawList.ModelInfo info) {
		int renderBufferOffset = info.getRenderBufferOffset();
		int vertexCount = info.getVertexCount();

		assert vertexCount % 3 == 0;
		ensureCapacity((count + vertexCount) * (long) Integer.BYTES);

		map(GL_WRITE_ONLY);
		if (isMapped()) {
			long address = mappedBufferAddress + count * (long) Integer.BYTES;
			int vertexIDx = renderBufferOffset;
			for (int i = 0; i < vertexCount; i++) {
				MemoryUtil.memPutInt(address, vertexIDx);
				count++;
				vertexIDx++;
				address += Integer.BYTES;
			}
		}
	}

	public void draw() {
		if (isMapped()) {
			unmap(count * Integer.BYTES);
		}

		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, id);
		glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, 0);
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
	}

	public void clear() {
		unmap(0);
		count = 0;
	}
}
