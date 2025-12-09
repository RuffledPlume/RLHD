package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.SliceAllocator;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public class SlicedVAO {
	// Must be larger than the largest single model
	private static final int INITIAL_SIZE = 4 * 1024 * 1024;

	private final SliceAllocator<VBOSlice> allocator;
	private final VAO vao;

	public SlicedVAO(int ebo) {
		vao = new VAO(INITIAL_SIZE);
		vao.initialize(ebo);

		allocator = new SliceAllocator<>(VBOSlice::new, 1, INITIAL_SIZE, true);
	}

	public VBOSlice allocate(int byteSize) {
		assert byteSize > 0;
		VBOSlice slice = allocator.allocate(byteSize);
		if(!vao.vbo.mapped)
			vao.vbo.map();
		return slice;
	}

	public void drawAll(CommandBuffer cmd) {
		if(vao.vbo.mapped)
			vao.vbo.unmap();

		cmd.BindVertexArray(vao.vao);

		for (VBOSlice slice : allocator) {
			int start = slice.getOffset() / VAO.VERT_SIZE;
			int count = slice.getSize() / VAO.VERT_SIZE;

			cmd.DrawArrays(GL_TRIANGLES, start, count);
		}
	}

	public void clear() {
		allocator.clear();
	}

	public void destroy() {
		vao.destroy();
	}

	public final class VBOSlice extends SliceAllocator.Slice {
		private IntBuffer mappedBuffer;
		private IntBuffer sliceBuffer;

		private VBOSlice(int offset, int size) {
			super(offset, size);
		}

		@Override
		protected void allocate() {
			int requiredBytes = (int) HDUtils.ceilPow2(offset + size);
			if (requiredBytes <= vao.vbo.size)
				return;

			boolean wasMapped = vao.vbo.mapped;
			if (wasMapped)
				vao.vbo.unmap();

			int oldBuf = vao.vbo.bufId;
			int newBuf = glGenBuffers();

			// Allocate new buffer
			glBindBuffer(GL_COPY_WRITE_BUFFER, newBuf);
			glBufferData(GL_COPY_WRITE_BUFFER, requiredBytes, GL_DYNAMIC_DRAW);

			// Copy old contents
			glBindBuffer(GL_COPY_READ_BUFFER, oldBuf);
			glCopyBufferSubData(
				GL_COPY_READ_BUFFER,
				GL_COPY_WRITE_BUFFER,
				0,
				0,
				vao.vbo.size
			);

			// Replace buffer in VAO
			glBindVertexArray(vao.vao);
			glBindBuffer(GL_ARRAY_BUFFER, newBuf);

			glDeleteBuffers(oldBuf);

			vao.vbo.bufId = newBuf;
			vao.vbo.size = requiredBytes;

			if (wasMapped)
				vao.vbo.map();

			log.debug("Resized VAO VBO to {} bytes", requiredBytes);
		}

		@Override
		protected void onFreed() {}

		public IntBuffer getBuffer() {
			assert vao.vbo.mapped;

			if (vao.vbo.vb != mappedBuffer) {
				mappedBuffer = vao.vbo.vb;

				IntBuffer buf = mappedBuffer.duplicate();

				int start = offset / Integer.BYTES;
				int end   = start + size / Integer.BYTES;

				buf.position(start);
				buf.limit(end);

				sliceBuffer = buf.slice();
			}

			return sliceBuffer;
		}
	}
}

