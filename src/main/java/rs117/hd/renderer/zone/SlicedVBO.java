package rs117.hd.renderer.zone;

import rs117.hd.utils.SliceAllocator;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static org.lwjgl.opengl.GL11C.GL_INT;
import static org.lwjgl.opengl.GL11C.GL_SHORT;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferSubData;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30C.GL_HALF_FLOAT;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static org.lwjgl.opengl.GL30C.glVertexAttribIPointer;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.checkGLErrors;

public class SlicedVBO {
	// Zone vertex format
	// pos short vec3(x, y, z)
	// uvw short vec3(u, v, w)
	// normal short vec3(nx, ny, nz)
	// alphaBiasHsl int
	// materialData int
	// terrainData int
	static final int VERT_SIZE = 32;

	// Metadata format
	// worldViewIndex int int
	// sceneOffset int vec2(x, y)
	static final int METADATA_SIZE = 12;

	private final SliceAllocator<Slice> allocator = new SliceAllocator<>(Slice::new, 1, 100, false);
	private final GpuIntBuffer stagingBuffer = new GpuIntBuffer(VERT_SIZE * 1000);
	private boolean stagingBufferLock;
	private int glVao, glVbo, glMetadata;

	public void initialise(int eboShared) {
		glVao = glGenVertexArrays();
		glVbo = glGenBuffers();
		glMetadata = glGenBuffers();

		glBindVertexArray(glVao);
		glBindBuffer(GL_ARRAY_BUFFER, glVbo);

		// The element buffer is part of VAO state
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboShared);

		// Position
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_SHORT, false, VERT_SIZE, 0);

		// UVs
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 3, GL_HALF_FLOAT, false, VERT_SIZE, 6);

		// Normals
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 3, GL_SHORT, false, VERT_SIZE, 12);

		// Alpha, bias & HSL
		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 1, GL_INT, VERT_SIZE, 20);

		// Material data
		glEnableVertexAttribArray(4);
		glVertexAttribIPointer(4, 1, GL_INT, VERT_SIZE, 24);

		// Terrain data
		glEnableVertexAttribArray(5);
		glVertexAttribIPointer(5, 1, GL_INT, VERT_SIZE, 28);

		glBindBuffer(GL_ARRAY_BUFFER, glMetadata);

		// WorldView index (not ID)
		glEnableVertexAttribArray(6);
		//glVertexAttribDivisor(6, 1); TODO: Metadata should be part of the VBO?
		glVertexAttribIPointer(6, 1, GL_INT, METADATA_SIZE, 0);

		// Scene offset
		glEnableVertexAttribArray(7);
		//glVertexAttribDivisor(7, 1); TODO: Metadata should be part of the VBO?
		glVertexAttribIPointer(7, 2, GL_INT, METADATA_SIZE, 4);

		checkGLErrors();

		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	public GpuIntBuffer getStagingBuffer() {
		assert !stagingBufferLock;
		stagingBufferLock = true;
		return stagingBuffer;
	}

	public Slice uploadStagingBuffer() {
		assert stagingBufferLock;
		stagingBufferLock = false;
		stagingBuffer.flip();

		int byteSize = stagingBuffer.getBuffer().limit() * Integer.BYTES;
		Slice vboSlice = allocator.allocate(byteSize);

		glBindBuffer(GL_ARRAY_BUFFER, glVbo);
		glBufferSubData(GL_ARRAY_BUFFER, vboSlice.getOffset(), stagingBuffer.getBuffer());
		glBindBuffer(GL_ARRAY_BUFFER, 0);

		stagingBuffer.clear();
		return vboSlice;
	}

	public void free() {
		if (glVao != 0)
			glDeleteVertexArrays(glVao);
		glVao = 0;

		if (glVbo != 0)
			glDeleteBuffers(glVbo);
		glVbo = 0;

		if (glMetadata != 0)
			glDeleteBuffers(glMetadata);
		glMetadata = 0;
	}

	public class Slice extends SliceAllocator.Slice {
		public Slice(int offset, int size) {
			super(offset, size);
		}

		@Override
		protected void allocate() {
			int newCapacity = allocator.getCapacity();

			int newVbo = glGenBuffers();
			glBindBuffer(GL_ARRAY_BUFFER, newVbo);
			glBufferData(GL_ARRAY_BUFFER, newCapacity, GL_DYNAMIC_DRAW);

			if (glVbo != 0) {
				glBindBuffer(GL_COPY_READ_BUFFER, glVbo);
				glBindBuffer(GL_COPY_WRITE_BUFFER, newVbo);

				int oldSize = glGetBufferParameteri(GL_COPY_READ_BUFFER, GL_BUFFER_SIZE);
				glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, Math.min(oldSize, newCapacity));

				glBindBuffer(GL_COPY_READ_BUFFER, 0);
				glBindBuffer(GL_COPY_WRITE_BUFFER, 0);

				glDeleteBuffers(glVbo);
			}

			glVbo = newVbo;
			glBindBuffer(GL_ARRAY_BUFFER, 0);
		}

		public int getVAO() { return glVao; }

		@Override
		protected void onFreed() {}
	}
}
