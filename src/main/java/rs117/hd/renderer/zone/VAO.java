package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLTextureBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.NVIDIA_GPU;
import static rs117.hd.HdPlugin.SUPPORTS_INDIRECT_DRAW;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
class VAO {
	public static final int INITIAL_SIZE = (int) (16 * MiB);

	// Temp vertex format
	// pos float vec3(x, y, z)
	// uvw short vec3(u, v, w)
	// normal short vec3(nx, ny, nz)
	static final int VERT_SIZE = 28;

	// Metadata format
	// worldViewIndex int
	// dummy sceneOffset ivec2 for macOS workaround
	static final int METADATA_SIZE = 12;

	int vao;
	boolean used;

	private final GLBuffer vboRender;

	private final GLBuffer vboStaging;
	private final GpuIntBuffer vboOverflow;
	private boolean hasVboOverflown;

	private final GLTextureBuffer tbo;
	private final GpuIntBuffer tboOverflow;
	private boolean hasTboOverflown;

	private GLBuffer vboM;

	private final ReentrantReadWriteLock resizeSync = new ReentrantReadWriteLock();

	private int[] drawOffsets = new int[16];
	private int[] drawCounts = new int[16];
	private int drawRangeCount;

	VAO() {
		this.vboOverflow = new GpuIntBuffer(INITIAL_SIZE / Integer.BYTES);
		this.vboRender = new GLBuffer("VAO::VBO", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
		this.vboStaging = new GLBuffer("VAO::VBO_STAGING", GL_COPY_READ_BUFFER, GL_STREAM_COPY);
		this.vboStaging.setWantsPersistent(true);

		this.tboOverflow = new GpuIntBuffer(INITIAL_SIZE / Integer.BYTES);
		this.tbo = new GLTextureBuffer("Textured Faces", GL_DYNAMIC_DRAW);
		this.tbo.setWantsPersistent(true);
	}

	void initialize(@Nonnull GLBuffer vboMetadata) {
		vboM = vboMetadata;
		vao = glGenVertexArrays();
		tbo.initialize(INITIAL_SIZE);
		vboRender.initialize(INITIAL_SIZE);
		vboStaging.initialize(INITIAL_SIZE);

		bindRenderVAO();
		bindMetadataVAO();
	}

	private void bindMetadataVAO() {
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vboM.id);

		// WorldView index (not ID)
		glEnableVertexAttribArray(6);
		glVertexAttribDivisor(6, 1);
		glVertexAttribIPointer(6, 1, GL_INT, METADATA_SIZE, 0);

		if (!NVIDIA_GPU) {
			// Workaround for incorrect implementations of disabled vertex attribs, particularly on macOS
			glEnableVertexAttribArray(7);
			glVertexAttribDivisor(7, 1);
			glVertexAttribIPointer(7, 2, GL_INT, METADATA_SIZE, 4);
		}

		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
	}

	void bindRenderVAO() {
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vboRender.id);

		// Position
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, VERT_SIZE, 0);

		// UVs
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 3, GL_HALF_FLOAT, false, VERT_SIZE, 12);

		// Normals
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 3, GL_SHORT, false, VERT_SIZE, 18);

		// TextureFaceIdx
		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 1, GL_INT, VERT_SIZE, 24);
	}

	int getVertexPositionOffset() {
		return vboStaging.getIntView().position() + (hasVboOverflown ? vboOverflow.position() : 0);
	}

	void map() {
		if(!vboStaging.isMapped())
			vboStaging.map(GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT);
		vboStaging.getIntView().position(0);
		vboOverflow.clear();

		if(!tbo.isMapped())
			tbo.map(GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT);
		tbo.getIntView().position(0);
		tboOverflow.clear();

		reset();
	}

	synchronized void unmap(boolean coalesce) {
		vboStaging.unmap();
		if(vboOverflow.position() > 0) {
			long offset = vboStaging.getWrittenBytes();
			vboStaging.ensureCapacity(offset + vboOverflow.position());
			vboStaging.upload(vboOverflow, offset);
			if (vboRender == vboStaging) {
				// Rebind VAO since the VBO has changed
				bindRenderVAO();
			}
		}

		tbo.unmap();
		if(tboOverflow.position() > 0) {
			long offset = tbo.getWrittenBytes();
			tbo.upload(tboOverflow, offset);
		}

		mergeRanges();

		if(vboRender != vboStaging && drawRangeCount > 0) {
			if(coalesce && drawRangeCount > 1) {
				long renderVBOSize = vboRender.size;
				long destOffset = 0;
				for (int i = 0; i < drawRangeCount; i++) {
					long srcPos = drawOffsets[i] * (long)VERT_SIZE;
					long length = drawCounts[i] * (long)VERT_SIZE;
					vboStaging.copyTo(vboRender, srcPos, destOffset, length);
					destOffset += length;
				}

				if(vboRender.size != renderVBOSize)
					bindRenderVAO();

				drawOffsets[0] = 0;
				drawCounts[0] = (int)(destOffset / VERT_SIZE);
				drawRangeCount = 1;
			} else {
				vboStaging.copyTo(vboRender, 0, 0, vboStaging.getWrittenBytes());
			}
		}
	}

	void destroy() {
		vboStaging.destroy();
		tbo.destroy();
		glDeleteVertexArrays(vao);
		vao = 0;
	}

	synchronized VAOView beginDraw(int faceCount) {
		final int vertexSize = faceCount * 3 * (VERT_SIZE / 4);
		if (!hasVboOverflown && vboStaging.getIntView().remaining() < vertexSize)
			hasVboOverflown = true;

		final int tboSize = faceCount * 9;
		if (!hasTboOverflown && tbo.getIntView().remaining() < tboSize)
			hasTboOverflown = true;

		final boolean vboNeedsResize = hasVboOverflown && !vboOverflow.fits(vertexSize);
		final boolean tboNeedsResize = hasTboOverflown && !tboOverflow.fits(tboSize);

		if (vboNeedsResize || tboNeedsResize) {
			// Check if the overflow buffer has enough space, grabbing the lock to ensure no one is using it
			resizeSync.writeLock().lock();
			if (hasVboOverflown)
				vboOverflow.ensureCapacity(vertexSize);
			if (hasTboOverflown)
				tboOverflow.ensureCapacity(tboSize);
			resizeSync.writeLock().unlock();
		}

		int vertexOffset = getVertexPositionOffset() / (VAO.VERT_SIZE / 4);
		int drawIdx = drawRangeCount++;
		drawOffsets[drawIdx] = -1;
		drawCounts[drawIdx] = -1;
		if (drawRangeCount >= drawOffsets.length) {
			drawOffsets = Arrays.copyOf(drawOffsets, drawOffsets.length * 2);
			drawCounts = Arrays.copyOf(drawCounts, drawCounts.length * 2);
		}

		resizeSync.readLock().lock();

		IntBuffer targetVbo = hasVboOverflown ? vboOverflow.getBuffer() : vboStaging.getIntView();
		IntBuffer targetTbo = hasTboOverflown ? tboOverflow.getBuffer() : tbo.getIntView();

		IntBuffer drawVbo = targetVbo.duplicate();
		IntBuffer drawTbo = targetTbo.duplicate();

		int vboStartPos = targetVbo.position();
		int tboStartPos = targetTbo.position();

		drawVbo.limit(drawVbo.position() + vertexSize);
		drawTbo.limit(drawTbo.position() + tboSize);

		targetVbo.position(targetVbo.position() + vertexSize);
		targetTbo.position(targetTbo.position() + tboSize);

		return new VAOView(drawVbo, drawTbo, vao, tbo.getTexId(), vertexOffset, drawIdx, vboStartPos, tboStartPos, vertexSize, tboSize);
	}

	private synchronized void endDraw(VAOView view) {
		drawOffsets[view.drawIdx] = view.vertexOffset;
		drawCounts[view.drawIdx] = view.getVertexCount();

		// Check if we can reclaim unused buffer space
		// Only safe if no other thread has called beginDraw since our beginDraw
		IntBuffer targetVbo = hasVboOverflown ? vboOverflow.getBuffer() : vboStaging.getIntView();
		IntBuffer targetTbo = hasTboOverflown ? tboOverflow.getBuffer() : tbo.getIntView();

		int expectedVboEnd = view.vboStartPos + view.vboAllocatedSize;
		int expectedTboEnd = view.tboStartPos + view.tboAllocatedSize;

		// If positions match, no intervening beginDraw occurred - safe to shrink
		if (targetVbo.position() == expectedVboEnd && targetTbo.position() == expectedTboEnd) {
			int actualVboUsed = view.vbo.position() - view.vboStartPos;
			int actualTboUsed = view.tbo.position() - view.tboStartPos;

			if (actualVboUsed < view.vboAllocatedSize)
				targetVbo.position(view.vboStartPos + actualVboUsed);
			if (actualTboUsed < view.tboAllocatedSize)
				targetTbo.position(view.tboStartPos + actualTboUsed);
		}
	
		resizeSync.readLock().unlock();
	}

	void mergeRanges() {
		int newDrawRangeCount = 0;
		for (int i = 0; i < drawRangeCount; i++) {
			if (drawOffsets[i] != -1 && drawCounts[i] != -1) {
				if (newDrawRangeCount > 0 && drawOffsets[newDrawRangeCount - 1] + drawCounts[newDrawRangeCount - 1] == drawOffsets[i]) {
					drawCounts[newDrawRangeCount - 1] += drawCounts[i];
				} else {
					if (newDrawRangeCount != i) {
						drawOffsets[newDrawRangeCount] = drawOffsets[i];
						drawCounts[newDrawRangeCount] = drawCounts[i];
					}
					newDrawRangeCount++;
				}
			}
		}
		drawRangeCount = newDrawRangeCount;
	}

	void draw(CommandBuffer cmd) {
		if (drawRangeCount <= 0)
			return;

		cmd.BindVertexArray(vao);
		cmd.BindTextureUnit(GL_TEXTURE_BUFFER, tbo.getTexId(), TEXTURE_UNIT_TEXTURED_FACES);

		if (drawRangeCount == 1) {
			if (GL_CAPS.OpenGL40 && SUPPORTS_INDIRECT_DRAW) {
				cmd.DrawArraysIndirect(GL_TRIANGLES, drawOffsets[0], drawCounts[0], ZoneRenderer.indirectDrawCmdsStaging);
			} else {
				cmd.DrawArrays(GL_TRIANGLES, drawOffsets[0], drawCounts[0]);
			}
		} else {
			if (GL_CAPS.OpenGL43 && SUPPORTS_INDIRECT_DRAW) {
				cmd.MultiDrawArraysIndirect(GL_TRIANGLES, drawOffsets, drawCounts, drawRangeCount, ZoneRenderer.indirectDrawCmdsStaging);
			} else {
				cmd.MultiDrawArrays(GL_TRIANGLES, drawOffsets, drawCounts, drawRangeCount);
			}
		}
	}

	void reset() {
		used = false;
		drawRangeCount = 0;
		hasVboOverflown = false;
		hasTboOverflown = false;
	}

	@RequiredArgsConstructor
	public final class VAOView {
		public final IntBuffer vbo;
		public final IntBuffer tbo;
		public final int vao;
		public final int tboTexId;
		public final int vertexOffset;

		private final int drawIdx;
		private final int vboStartPos;
		private final int tboStartPos;
		private final int vboAllocatedSize;
		private final int tboAllocatedSize;

		public int getStartOffset() { return vertexOffset * (VAO.VERT_SIZE / 4); }

		public int getEndOffset() { return (vertexOffset + getVertexCount()) * (VAO.VERT_SIZE / 4); }

		public int getVertexCount() { return (vbo.position() / (VAO.VERT_SIZE / 4)) - vertexOffset; }

		public void end() { endDraw(this); }
	}
}
