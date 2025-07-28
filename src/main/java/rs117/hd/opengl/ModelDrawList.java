package rs117.hd.opengl;

import java.nio.IntBuffer;
import java.util.Arrays;
import lombok.Getter;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.HdPlugin;
import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL15.GL_WRITE_ONLY;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;

public class ModelDrawList extends SharedGLBuffer {
	private ModelInfo[] modelInfos = new ModelInfo[1000];
	private int numWrittenModels = 0;

	public ModelDrawList(String name) {
		super(name, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);
	}

	@Override
	public void initialize() {
		super.initialize();
		ensureCapacity(modelInfos.length * ModelInfo.ELEMENT_COUNT * (long) Integer.BYTES);
	}

	private void ensureModelInfoCapacity() {
		if (numWrittenModels >= modelInfos.length) {
			int newLength = modelInfos.length * 2;
			modelInfos = Arrays.copyOf(modelInfos, newLength);

			ensureCapacity(newLength * ModelInfo.ELEMENT_COUNT * (long) Integer.BYTES);
			glBindBuffer(target, 0);
			HdPlugin.checkGLErrors();
		}
	}

	public void append(IntBuffer buffer) {
		numWrittenModels += buffer.limit() / ModelInfo.ELEMENT_COUNT;
		ensureModelInfoCapacity();
		map(GL_WRITE_ONLY);

		if (mappedBuffer != null) {
			mappedBuffer.asIntBuffer().put(buffer);
		}
	}

	public ModelInfo pop() {
		ensureModelInfoCapacity();
		ModelInfo result = modelInfos[numWrittenModels];
		if (result == null) {
			long addressOffset = numWrittenModels * ModelInfo.ELEMENT_COUNT * (long) Integer.BYTES;
			modelInfos[numWrittenModels] = result = new ModelInfo(this, addressOffset);
		}

		map(GL_WRITE_ONLY);
		return mappedBufferAddress != 0 ? result : null;
	}

	public int getWrittenModels() {
		return numWrittenModels;
	}

	public void upload() {
		unmap(numWrittenModels * ModelInfo.ELEMENT_COUNT * Integer.BYTES);
	}

	public void reset() {
		numWrittenModels = 0;
	}

	public final static class ModelInfo {
		public static final int ELEMENT_COUNT = 8;

		public static final long VERTEX_OFFSET = 0;
		public static final long UV_OFFSET = 4;
		public static final long FACE_COUNT = 8;
		public static final long RENDER_BUFFER_OFFSET = 12;
		public static final long MODEL_FLAGS = 16;
		public static final long POSITION_X = 20;
		public static final long POSITION_Y = 26;
		public static final long HEIGHT = 24;
		public static final long POSITION_Z = 28;

		private final ModelDrawList owner;
		private final long addressOffset;

		@Getter private int renderBufferOffset;
		@Getter private int vertexCount;

		public ModelInfo(ModelDrawList owner, long addressOffset) {
			this.owner = owner;
			this.addressOffset = addressOffset;
		}

		public void setVertexOffset(int vertexOffset) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + VERTEX_OFFSET, vertexOffset);
		}

		public void setUvOffset(int uvOffset) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + UV_OFFSET, uvOffset);
		}

		public void setModelFlags(int modelFlags) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + MODEL_FLAGS, modelFlags);
		}

		public void setPositionX(int positionX) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + POSITION_X, positionX);
		}

		public void setPositionY(int positionY) {
			MemoryUtil.memPutShort(owner.mappedBufferAddress + addressOffset + POSITION_Y, (short) positionY);
		}

		public void setHeight(int height) {
			MemoryUtil.memPutShort(owner.mappedBufferAddress + addressOffset + HEIGHT, (short) height);
		}

		public void setPositionZ(int positionZ) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + POSITION_Z, positionZ);
		}

		public int push(int vertexCount, int renderBufferOffset) {
			this.renderBufferOffset = renderBufferOffset;
			this.vertexCount = vertexCount;
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + FACE_COUNT, vertexCount / 3);
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + RENDER_BUFFER_OFFSET, renderBufferOffset);
			owner.numWrittenModels++;
			return renderBufferOffset + vertexCount;
		}
	}
}
