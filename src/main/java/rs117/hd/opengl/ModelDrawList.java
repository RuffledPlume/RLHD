package rs117.hd.opengl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.HdPlugin;
import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL15.GL_WRITE_ONLY;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glMapBuffer;
import static org.lwjgl.opengl.GL15.glUnmapBuffer;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;

public class ModelDrawList extends SharedGLBuffer {
	private ModelInfo[] modelInfos = new ModelInfo[1000];
	private int numWrittenModels = 0;
	private ByteBuffer mappedBuffer = null;
	private long mappedBufferAddress = 0;

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
			modelInfos = Arrays.copyOf(modelInfos, modelInfos.length * 2);

			if (mappedBufferAddress != 0) {
				// Unmap the buffer, before we update the capacity
				glBindBuffer(target, id);
				glUnmapBuffer(target);
				mappedBufferAddress = 0;
				mappedBuffer = null;
			}

			ensureCapacity(modelInfos.length * ModelInfo.ELEMENT_COUNT * (long) Integer.BYTES);
			glBindBuffer(target, 0);
			HdPlugin.checkGLErrors();
		}
	}

	private void ensureBufferMapped() {
		if (mappedBufferAddress == 0) {
			glBindBuffer(target, id);
			mappedBuffer = glMapBuffer(target, GL_WRITE_ONLY);
			if (mappedBuffer != null) {
				mappedBufferAddress = MemoryUtil.memAddress(mappedBuffer);
			}
			glBindBuffer(target, 0);
			HdPlugin.checkGLErrors();
		}
	}

	public void append(IntBuffer buffer) {
		numWrittenModels += buffer.limit() / ModelInfo.ELEMENT_COUNT;
		ensureModelInfoCapacity();
		ensureBufferMapped();

		if (mappedBuffer != null) {
			mappedBuffer.asIntBuffer().put(buffer);
		}
	}

	public ModelInfo pop() {
		ModelInfo result = modelInfos[numWrittenModels];
		if (result == null) {
			long addressOffset = numWrittenModels * ModelInfo.ELEMENT_COUNT * (long) Integer.BYTES;
			modelInfos[numWrittenModels] = result = new ModelInfo(this, addressOffset);
		}
		ensureBufferMapped();
		return result;
	}

	public int getWrittenModels() {
		return numWrittenModels;
	}

	public void upload() {
		if (mappedBufferAddress != 0) {
			glBindBuffer(target, id);
			mappedBuffer.position(numWrittenModels * ModelInfo.ELEMENT_COUNT * Integer.BYTES);
			mappedBuffer = null;
			mappedBufferAddress = 0;
			glUnmapBuffer(target);
			glBindBuffer(target, 0);
		}
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

		public ModelInfo(ModelDrawList owner, long addressOffset) {
			this.owner = owner;
			this.addressOffset = addressOffset;
		}

		public ModelInfo setVertexOffset(int vertexOffset) {
			//if (owner.mappedBufferAddress != 0) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + VERTEX_OFFSET, vertexOffset);
			//}
			return this;
		}

		public ModelInfo setUvOffset(int uvOffset) {
			//if (owner.mappedBufferAddress != 0) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + UV_OFFSET, uvOffset);
			//}
			return this;
		}

		public ModelInfo setFaceCount(int faceCount) {
			//if (owner.mappedBufferAddress != 0) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + FACE_COUNT, faceCount);
			//}
			return this;
		}

		public ModelInfo setRenderBufferOffset(int renderBufferOffset) {
			//if (owner.mappedBufferAddress != 0) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + RENDER_BUFFER_OFFSET, renderBufferOffset);
			//}
			return this;
		}

		public ModelInfo setModelFlags(int modelFlags) {
			//if (owner.mappedBufferAddress != 0) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + MODEL_FLAGS, modelFlags);
			//}
			return this;
		}

		public ModelInfo setPositionX(int positionX) {
			//if (owner.mappedBufferAddress != 0) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + POSITION_X, positionX);
			//}
			return this;
		}

		public ModelInfo setPositionY(int positionY) {
			//if (owner.mappedBufferAddress != 0) {
			MemoryUtil.memPutShort(owner.mappedBufferAddress + addressOffset + POSITION_Y, (short) positionY);
			//}
			return this;
		}

		public ModelInfo setHeight(int height) {
			//if (owner.mappedBufferAddress != 0) {
			MemoryUtil.memPutShort(owner.mappedBufferAddress + addressOffset + HEIGHT, (short) height);
			//}
			return this;
		}

		public ModelInfo setPositionZ(int positionZ) {
			//if (owner.mappedBufferAddress != 0) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + POSITION_Z, positionZ);
			//}
			return this;
		}

		public void push() {
			owner.numWrittenModels++;
			owner.ensureModelInfoCapacity();
		}
	}
}
