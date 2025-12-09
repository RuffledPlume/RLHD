package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Comparator;
import lombok.Getter;
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

	// Reused per-frame buffers (grow-only)
	private VBOSlice[] tempSlices = new VBOSlice[16];
	private int[] drawFirsts = new int[16];
	private int[] drawCounts = new int[16];
	private int drawRangeCount;

	public SlicedVAO(int ebo) {
		vao = new VAO(INITIAL_SIZE);
		vao.initialize(ebo);

		allocator = new SliceAllocator<>(VBOSlice::new, 1, INITIAL_SIZE, true);
	}

	public VBOSlice allocate(int byteSize) {
		assert byteSize > 0;
		VBOSlice slice = allocator.allocate(byteSize);
		if (!vao.vbo.mapped)
			vao.vbo.map();
		return slice;
	}

	public void drawAll(CommandBuffer cmd) {
		if (vao.vbo.mapped)
			vao.vbo.unmap();

		if (!allocator.iterator().hasNext())
			return;

		int sliceCount = 0;
		for (VBOSlice ignored : allocator)
			sliceCount++;

		ensureRangeCapacity(sliceCount);

		int i = 0;
		for (VBOSlice s : allocator)
			tempSlices[i++] = s;

		Arrays.sort(tempSlices, 0, sliceCount,
			Comparator.comparingInt(VBOSlice::getOffset));

		drawRangeCount = 0;

		VBOSlice first = tempSlices[0];
		int runOffset = first.getOffset();
		int runSize   = first.getSize();

		for (i = 1; i < sliceCount; i++) {
			VBOSlice s = tempSlices[i];

			if (runOffset + runSize == s.getOffset()) {
				runSize += s.getSize();
			} else {
				appendRange(runOffset, runSize);
				runOffset = s.getOffset();
				runSize   = s.getSize();
			}
		}

		appendRange(runOffset, runSize);

		if(drawRangeCount == 0)
			return;

		cmd.BindVertexArray(vao.vao);
		cmd.MultiDrawArrays(
			GL_TRIANGLES,
			drawFirsts,
			drawCounts,
			drawRangeCount
		);
	}

	private void ensureRangeCapacity(int sliceCount) {
		if (tempSlices.length < sliceCount) {
			int newCap = Integer.highestOneBit(sliceCount - 1) << 1;
			tempSlices = Arrays.copyOf(tempSlices, newCap);
		}

		if (drawFirsts.length < sliceCount) {
			int newCap = Integer.highestOneBit(sliceCount - 1) << 1;
			drawFirsts = Arrays.copyOf(drawFirsts, newCap);
			drawCounts = Arrays.copyOf(drawCounts, newCap);
		}
	}

	private void appendRange(int offsetBytes, int sizeBytes) {
		drawFirsts[drawRangeCount] = offsetBytes / VAO.VERT_SIZE;
		drawCounts[drawRangeCount] = sizeBytes   / VAO.VERT_SIZE;
		drawRangeCount++;
	}


	public void clear() {
		allocator.clear();
		allocator.defrag();
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
			if (wasMapped) {
				// unmap existing mapping before manipulating buffers
				vao.vbo.unmap();
			}

			final int oldBuf = vao.vbo.bufId;
			final int oldSize = vao.vbo.size;

			final int newBuf = glGenBuffers();

			// Allocate new buffer storage
			glBindBuffer(GL_COPY_WRITE_BUFFER, newBuf);
			glBufferData(GL_COPY_WRITE_BUFFER, requiredBytes, GL_DYNAMIC_DRAW);

			// Copy old contents oldBuf -> newBuf (GPU-side copy)
			glBindBuffer(GL_COPY_READ_BUFFER, oldBuf);
			glBindBuffer(GL_COPY_WRITE_BUFFER, newBuf);
			glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, oldSize);

			// Insert a fence and wait for GPU to finish the copy before deleting old buffer.
			long fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);

			final long TIMEOUT_NS = 2_000_000_000L; // 2 seconds
			int waitRes = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, TIMEOUT_NS);

			if (waitRes == GL_TIMEOUT_EXPIRED || waitRes == GL_WAIT_FAILED) {
				// Clean up fence and new buffer then restore old buffer binding to keep VAO valid.
				glDeleteSync(fence);
				glBindBuffer(GL_ARRAY_BUFFER, oldBuf);
				glDeleteBuffers(newBuf);
				throw new RuntimeException("Timeout or failure waiting for GPU during VBO resize");
			}

			// Bind new buffer to the VAO
			glBindVertexArray(vao.vao);
			glBindBuffer(GL_ARRAY_BUFFER, newBuf);
			glBindVertexArray(0);

			// Safe to delete old buffer now.
			glDeleteSync(fence);
			glDeleteBuffers(oldBuf);

			// Update bookkeeping for VAO.vbo
			vao.vbo.bufId = newBuf;
			vao.vbo.size = requiredBytes;

			// Invalidate cached slice mapped buffers so future getBuffer() recreates views
			clearSliceMappedCaches();

			if (wasMapped)
				vao.vbo.map();

			log.debug("Resized VAO VBO to {} bytes", requiredBytes);
		}

		@Override
		protected void onFreed() {}

		public int getVAO() {
			return vao.vao;
		}

		public IntBuffer getBuffer() {
			// must be mapped by caller
			assert vao.vbo.mapped;

			// If the underlying mapped buffer object changed since last time,
			// recreate our slice view against the current mapping.
			if (vao.vbo.vb != mappedBuffer) {
				mappedBuffer = vao.vbo.vb;

				IntBuffer dup = mappedBuffer.duplicate();
				int start = offset / Integer.BYTES;
				int end = start + size / Integer.BYTES;
				dup.position(start);
				dup.limit(end);
				sliceBuffer = dup.slice();
			}

			return sliceBuffer;
		}
	}

	private void clearSliceMappedCaches() {
		for (VBOSlice s : allocator) {
			s.mappedBuffer = null;
			s.sliceBuffer = null;
		}
	}
}
