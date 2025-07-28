package rs117.hd.opengl;

import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.HdPlugin;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_WRITE_ONLY;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;

public class ModelDrawBuffer extends GLBuffer {
	private int[] modelInfoStagingData = new int[64];
	private Future<?> asyncUpload;
	private int modelCount = 0;
	private int indicesCount = 0;

	public ModelDrawBuffer(String name) {
		super(name + " Indices", GL_ELEMENT_ARRAY_BUFFER, GL_STREAM_DRAW);
	}

	public void addModel(ModelDrawList.ModelInfo info) {
		modelInfoStagingData[modelCount * 2] = info.getRenderBufferOffset();
		modelInfoStagingData[modelCount * 2 + 1] = info.getVertexCount();
		indicesCount += info.getVertexCount();
		modelCount++;
		if (modelCount * 2 >= modelInfoStagingData.length) {
			modelInfoStagingData = Arrays.copyOf(modelInfoStagingData, modelInfoStagingData.length * 2);
		}
	}

	public void upload() {
		ensureCapacity(indicesCount * (long) Integer.BYTES);
		map(GL_WRITE_ONLY);
		if (isMapped()) {
			asyncUpload = HdPlugin.THREAD_POOL.submit(() -> {
				long address = mappedBufferAddress;
				for (int m = 0; m < modelCount; m++) {
					int renderBufferOffset = modelInfoStagingData[m * 2];
					int vertexCount = modelInfoStagingData[m * 2 + 1];

					int vertexIDx = renderBufferOffset;
					for (int i = 0; i < vertexCount; i++) {
						MemoryUtil.memPutInt(address, vertexIDx);
						vertexIDx++;
						address += Integer.BYTES;
					}
				}
			});
		}
	}

	@SneakyThrows
	public void draw() {
		if (isMapped()) {
			if (asyncUpload != null) {
				asyncUpload.get(1, TimeUnit.SECONDS);
				asyncUpload = null;
			}
			unmap(indicesCount * Integer.BYTES);
		}

		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, id);
		glDrawElements(GL_TRIANGLES, indicesCount, GL_UNSIGNED_INT, 0);
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
	}

	public void clear() {
		unmap(0);
		modelCount = 0;
		indicesCount = 0;
	}
}
