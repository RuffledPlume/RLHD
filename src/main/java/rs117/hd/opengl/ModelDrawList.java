package rs117.hd.opengl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.HdPlugin;
import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL15.GL_WRITE_ONLY;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glMapBuffer;
import static org.lwjgl.opengl.GL15.glUnmapBuffer;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;

public class ModelDrawList extends SharedGLBuffer {
	private ModelInfo[] modelInfos = new ModelInfo[1000];
	private final ArrayList<Integer>[] drawTypeModelIndices = new ArrayList[RenderBufferPass.PASSES_COUNT];
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
		for (int i = 0; i < drawTypeModelIndices.length; i++) {
			drawTypeModelIndices[i] = new ArrayList<>();
		}
	}

	private void ensureModelInfoCapacity() {
		if (numWrittenModels >= modelInfos.length) {
			int newLength = modelInfos.length * 2;
			modelInfos = Arrays.copyOf(modelInfos, newLength);


			if (mappedBufferAddress != 0) {
				// Unmap the buffer, before we update the capacity
				glBindBuffer(target, id);
				glUnmapBuffer(target);
				mappedBufferAddress = 0;
				mappedBuffer = null;
			}

			ensureCapacity(newLength * ModelInfo.ELEMENT_COUNT * (long) Integer.BYTES);
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

	public void append(IntBuffer buffer, int vertexCount) {
		numWrittenModels += buffer.limit() / ModelInfo.ELEMENT_COUNT;
		ensureModelInfoCapacity();
		ensureBufferMapped();

		if (mappedBuffer != null) {
			mappedBuffer.asIntBuffer().put(buffer);
			RenderBufferPass.SCENE.renderBufferOffset += vertexCount;
		}
	}

	public ModelInfo pop() {
		ModelInfo result = modelInfos[numWrittenModels];
		if (result == null) {
			long addressOffset = numWrittenModels * ModelInfo.ELEMENT_COUNT * (long) Integer.BYTES;
			modelInfos[numWrittenModels] = result = new ModelInfo(this, addressOffset);
		}

		ensureBufferMapped();
		return mappedBufferAddress != 0 ? result : null;
	}

	public int getWrittenModels() {
		return numWrittenModels;
	}

	public void buildRenderRanges(RenderBufferPass pass) {
		if (pass.renderBufferOffset == 0 && pass.drawPassIdx > 0) {
			RenderBufferPass prevPass = RenderBufferPass.ALL_PASSES[pass.drawPassIdx - 1];
			pass.renderBufferOffset = prevPass.renderBufferOffset + prevPass.renderBufferOffset;
		}

		ArrayList<Integer> modelInfoIndices = drawTypeModelIndices[pass.drawPassIdx];
		for (int modelIndex : modelInfoIndices) {
			ModelInfo info = modelInfos[modelIndex];
			info.setRenderBufferOffset(pass.renderBufferOffset + pass.renderBufferSize);
			pass.renderBufferSize += info.vertexCount;
		}
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
		for (RenderBufferPass pass : RenderBufferPass.ALL_PASSES) {
			pass.clear();
		}

		for (ArrayList<Integer> drawTypeModelIndex : drawTypeModelIndices) {
			drawTypeModelIndex.clear();
		}
	}

	public enum RenderBufferPass {
		SCENE(0),
		DIRECTIONAL(1);

		public static final RenderBufferPass[] ALL_PASSES = RenderBufferPass.values();
		public static final int PASSES_COUNT = ALL_PASSES.length;

		public final int drawPassIdx;

		public int renderBufferOffset;
		public int renderBufferSize;

		RenderBufferPass(int drawPassIdx) {
			this.drawPassIdx = drawPassIdx;
		}

		public void draw() {
			glDrawArrays(GL_TRIANGLES, renderBufferOffset, renderBufferSize);
		}

		public void clear() {
			renderBufferOffset = 0;
			renderBufferSize = 0;
		}

		public static int getRenderBufferSize() {
			int renderbufferEnd = 0;
			for (RenderBufferPass pass : ALL_PASSES) {
				renderbufferEnd = Math.max(renderbufferEnd, pass.renderBufferOffset + pass.renderBufferSize);
			}
			return renderbufferEnd;
		}
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
		private int vertexCount;

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

		public void setVertexCount(int vertexCount) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + FACE_COUNT, vertexCount / 3);
			this.vertexCount = vertexCount;
		}

		public void setFaceCount(int faceCount) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + FACE_COUNT, faceCount);
			vertexCount = faceCount * 3;
		}

		public void setRenderBufferOffset(int renderBufferOffset) {
			MemoryUtil.memPutInt(owner.mappedBufferAddress + addressOffset + RENDER_BUFFER_OFFSET, renderBufferOffset);
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

		public void push(int drawPassIdx) {
			if (drawPassIdx >= 0) {
				owner.drawTypeModelIndices[drawPassIdx].add(owner.numWrittenModels);
				owner.numWrittenModels++;
				owner.ensureModelInfoCapacity();
			}
		}

		public void push(RenderBufferPass pass) {
			if (pass != null) {
				push(pass.drawPassIdx);
			}
		}
	}
}
