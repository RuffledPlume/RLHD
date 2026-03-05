package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import rs117.hd.utils.SliceAllocator;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;

public class SlicedMeshData {
	private final SliceAllocator<MeshSlice> allocator = new SliceAllocator<>(MeshSlice::new, 0, true);
	private final GLBuffer vbo = new GLBuffer("Zone::VBO", GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW);

	public int vboId() { return vbo.id; }

	public SlicedMeshData() {

	}

	public void initialize() {
		vbo.initialize();
	}

	public void destroy() {
		vbo.destroy();
	}

	public MeshSlice obtainSlice(int sizeBytes) {
		return allocator.allocate(sizeBytes);
	}

	public class MeshSlice extends SliceAllocator.Slice {
		protected MeshSlice(int offset, int size) {
			super(offset, size);
			canMove = true;
		}

		public IntBuffer getBuffer() {
			return null; // TODO: Either return the staging buffer or a view into the mapped VBO
		}

		@Override
		protected void allocate() {
			// TODO: In order to be thread safe, we need to check that this slice falls within the range of the allocated buffer
			// TODO: If not then we need to use a staging buffer which is uploaded to the VBO at a later point
			//vbo.ensureCapacity(offset, size);
		}

		@Override
		protected void onFreed() {
			// TODO: Null the Buffer
		}

		@Override
		protected void onMove(int oldOffset, int newOffset) {
			// TODO: If the buffer is still a staging buffer then there isn't anything to be done, otherwise if we're a mapped range into the gpu memory then we need to do a copyRange
			// TODO: Note the range might overlap, so we might need to copy to a staging buffer & then to the vbo again
		}
	}
}
