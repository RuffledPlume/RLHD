package rs117.hd.utils.buffer;

import java.nio.IntBuffer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opencl.CL30.*;
import static org.lwjgl.opengl.GL33.*;

public final class ModelInfoGLBuffer extends SharedGLBuffer {
	@Getter
	private final GpuIntBuffer gpuBuffer = new GpuIntBuffer();

	private ModelInfoData[] modelInfos;
	private IntBuffer stagingBuffer;
	private int written = 0;

	@Getter
	private int totalWritten;

	public ModelInfoGLBuffer(String name) {
		super(name, GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);

		modelInfos = new ModelInfoData[100];
		stagingBuffer = MemoryUtil.memAllocInt(ModelInfoData.ELEMENT_COUNT * modelInfos.length);

		long stagingBufferOffset =  MemoryUtil.memAddress(stagingBuffer);
		for(int i = 0; i < modelInfos.length; i++) {
			modelInfos[i] = new ModelInfoData(stagingBufferOffset);
			stagingBufferOffset += ModelInfoData.ELEMENT_COUNT * Integer.BYTES;
		}
	}

	public ModelInfoData pop() {
		return modelInfos[written];
	}

	public void push() {
		written++;
		totalWritten++;

		if(written >= modelInfos.length) {
			copyStagingBuffer();
		}
	}

	private void copyStagingBuffer() {
		if(written <= 0){
			return;
		}

		int writtenElements = written * ModelInfoData.ELEMENT_COUNT;
		gpuBuffer.ensureCapacity(writtenElements);
		gpuBuffer.getBuffer().put(stagingBuffer);
		stagingBuffer.clear();

		written = 0;
	}

	public void append(IntBuffer buffer) {
		gpuBuffer.ensureCapacity(buffer.limit()).put(buffer);
		totalWritten += buffer.limit() / 8;
	}

	public void upload() {
		copyStagingBuffer();

		gpuBuffer.flip();
		upload(gpuBuffer);
		gpuBuffer.clear();
	}

	public void clear() {
		totalWritten = 0;
	}

	@Override
	public void destroy() {
		super.destroy();

		if(stagingBuffer != null) {
			MemoryUtil.memFree(stagingBuffer);
			stagingBuffer = null;
		}

		modelInfos = null;
	}

	@RequiredArgsConstructor
	public static final class ArrayBackedValue {
		private final long address;

		public void set(int value) {
			MemoryUtil.memPutInt(address, value);
		}

		public int get() {
			return MemoryUtil.memGetInt(address);
		}
	}

	public static final class ModelInfoData {
		public static final int ELEMENT_COUNT = 8;

		private ModelInfoData(long addressOffset) {
			vertexOffset = new ArrayBackedValue(addressOffset);
			uvOffset = new ArrayBackedValue(addressOffset + 4);
			faceCount = new ArrayBackedValue(addressOffset + 8);
			renderBufferOffset = new ArrayBackedValue(addressOffset + 12);
			flags = new ArrayBackedValue(addressOffset + 16);
			x = new ArrayBackedValue(addressOffset + 20);
			y = new ArrayBackedValue(addressOffset + 24);
			z = new ArrayBackedValue(addressOffset + 28);
		}

		public ArrayBackedValue vertexOffset;
		public ArrayBackedValue uvOffset;
		public ArrayBackedValue faceCount;
		public ArrayBackedValue renderBufferOffset;
		public ArrayBackedValue flags;
		public ArrayBackedValue x;
		public ArrayBackedValue y;
		public ArrayBackedValue z;
	}
}
