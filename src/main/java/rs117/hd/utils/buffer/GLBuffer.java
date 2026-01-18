/*
 * Copyright (c) 2021, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.utils.buffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.*;
import rs117.hd.utils.HDUtils;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL44.GL_CLIENT_STORAGE_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT;
import static org.lwjgl.opengl.GL44.glBufferStorage;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@RequiredArgsConstructor
public class GLBuffer
{
	public final String name;
	public final int target;
	public final int usage;

	public int id;
	public long size;

	@Setter
	private boolean wantsPersistent;
	@Setter
	private boolean wantsClientStorage;
	@Getter
	private long writtenBytes;
	@Getter
	private boolean persistent;
	@Getter
	private boolean mapped;
	@Getter
	private int mappedFlags;
	@Getter
	private IntBuffer intView;
	@Getter
	private FloatBuffer floatView;
	@Getter
	private ByteBuffer mappedBuffer;

	public static boolean supportsPersistentBuffers() {
		return GL_CAPS.GL_ARB_buffer_storage;
	}

	public void initialize() {
		initialize(0);
	}

	public void initialize(long initialCapacity) {
		id = glGenBuffers();
		// Initialize both GL and CL buffers to buffers of a single byte or more,
		// to ensure that valid buffers are given to compute dispatches.
		// This is particularly important on Apple M2 Max, where an uninitialized buffer leads to a crash
		ensureCapacity(max(1, initialCapacity));
	}

	public void destroy() {
		if(mapped)
			unmap();

		if (id != 0) {
			glDeleteBuffers(id);
			id = 0;
		}

		size = 0;
	}

	public boolean ensureCapacity(long numBytes) {
		return ensureCapacity(0, numBytes);
	}

	public boolean ensureCapacity(long byteOffset, long numBytes) {
		numBytes += byteOffset;
		if (numBytes <= size) {
			glBindBuffer(target, id);
			return false;
		}

		numBytes = HDUtils.ceilPow2(numBytes);
		if (log.isTraceEnabled()) {
			log.trace(
				"{} buffer '{}'\t{}",
				size > 0 ? "Resizing" : "Creating",
				name,
				String.format("%.2f MB -> %.2f MB", size / 1e6, numBytes / 1e6)
			);
		}

		final boolean wasMapped = mapped;
		if (wasMapped) unmap();

		int oldBuffer = id;
		if (byteOffset > 0)
			id = glGenBuffers();

		glBindBuffer(target, id);

		if(supportsPersistentBuffers() && wantsPersistent) {
			glBufferStorage(target, numBytes, GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT | (wantsClientStorage ? GL_CLIENT_STORAGE_BIT : 0));

			mappedBuffer = glMapBufferRange(target, 0, numBytes, GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_UNSYNCHRONIZED_BIT);
			if(mappedBuffer != null) {
				intView = mappedBuffer.asIntBuffer();
				floatView = mappedBuffer.asFloatBuffer();
				persistent = true;
				mapped = true;
			}
		}

		if(!persistent)
			glBufferData(target, numBytes, usage);

		if (byteOffset > 0) {
			glBindBuffer(GL_COPY_READ_BUFFER, oldBuffer);
			glCopyBufferSubData(GL_COPY_READ_BUFFER, target, 0, 0, byteOffset);
			glDeleteBuffers(oldBuffer);
		}

		size = numBytes;

		if (log.isDebugEnabled() && GL_CAPS.OpenGL43) {
			GL43C.glObjectLabel(GL43C.GL_BUFFER, id, name);
			if(checkGLErrors())
				log.error("Errors encountered on buffer {} offset: {} size: {} mapped: {} persistent: {}", name, byteOffset, numBytes, mapped,
					persistent
				);
		}

		// If was mapped, re-mapp without GL_MAP_INVALIDATE_BUFFER_BIT, since we may have previously written data
		if(wasMapped && !persistent)
			map(mappedFlags & ~(GL_MAP_INVALIDATE_BUFFER_BIT), (int)getWrittenBytes(), size);

		return true;
	}

	public void upload(ByteBuffer data) {
		upload(data, 0);
	}

	public void upload(ByteBuffer data, long byteOffset) {
		long numBytes = data.remaining();
		ensureCapacity(byteOffset, numBytes);
		if(persistent) {
			mappedBuffer.position((int)byteOffset);
			mappedBuffer.put(data);
		} else {
			glBufferSubData(target, byteOffset, data);
		}
	}

	public void upload(IntBuffer data) {
		upload(data, 0);
	}

	public void upload(IntBuffer data, long byteOffset) {
		long numBytes = 4L * data.remaining();
		ensureCapacity(byteOffset, numBytes);
		if(persistent) {
			intView.position((int)(byteOffset / 4));
			intView.put(data);
		} else {
			glBufferSubData(target, byteOffset, data);
		}
	}

	public void upload(FloatBuffer data) {
		upload(data, 0);
	}

	public void upload(FloatBuffer data, long byteOffset) {
		long numBytes = 4L * data.remaining();
		ensureCapacity(byteOffset, numBytes);
		if(persistent) {
			floatView.position((int)(byteOffset / 4));
			floatView.put(data);
		} else {
			glBufferSubData(target, byteOffset, data);
		}
	}

	public void upload(GpuIntBuffer data) {
		upload(data.getBuffer());
	}

	public void upload(GpuIntBuffer data, long byteOffset) {
		upload(data.getBuffer(), byteOffset);
	}

	public void upload(GpuFloatBuffer data) {
		upload(data.getBuffer());
	}

	public void upload(GpuFloatBuffer data, long byteOffset) {
		upload(data.getBuffer(), byteOffset);
	}

	public GLBuffer map(int flags) {
		return map(flags, 0, size);
	}

	public void copyTo(GLBuffer dst, long srcOffsetBytes, long dstOffsetBytes, long numBytes) {
		if (numBytes <= 0)
			return;

		dst.ensureCapacity(dstOffsetBytes + numBytes);

		if (GL_CAPS.OpenGL45 ) {
			GL45.glCopyNamedBufferSubData(id, dst.id, srcOffsetBytes, dstOffsetBytes, numBytes);
		} else {
			glBindBuffer(GL_COPY_READ_BUFFER, id);
			glBindBuffer(GL_COPY_WRITE_BUFFER, dst.id);
			glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, srcOffsetBytes, dstOffsetBytes, numBytes);
			glBindBuffer(GL_COPY_READ_BUFFER, 0);
			glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
		}
	}

	public GLBuffer map(int flags, int byteOffset, long size) {
		if(persistent) {
			mappedBuffer.position(byteOffset);
			intView.position(byteOffset / Integer.BYTES);
			floatView.position(byteOffset / Integer.BYTES);
			return this;
		}

		assert !mapped;
		glBindBuffer(target, id);

		ByteBuffer buf;
		mappedFlags = flags;
		if (usage != GL_STATIC_DRAW) {
			mappedFlags |= GL_MAP_FLUSH_EXPLICIT_BIT;
			buf = glMapBufferRange(
				target,
				0,
				size,
				mappedFlags,
				mappedBuffer
			);
		} else {
			int access = (mappedFlags & (GL_MAP_WRITE_BIT | GL_MAP_READ_BIT)) != 0 ? GL_READ_WRITE : (mappedFlags & GL_MAP_WRITE_BIT) != 0 ? GL_WRITE_ONLY : GL_READ_ONLY;
			buf = glMapBuffer(target, access, mappedBuffer);
		}
		if (buf == null) {
			checkGLErrors();
			throw new RuntimeException("unable to map GL buffer " + id + " offset " + byteOffset + " size " + size);
		}
		if (buf != mappedBuffer) {
			mappedBuffer = buf;
			intView = mappedBuffer.asIntBuffer();
			floatView = mappedBuffer.asFloatBuffer();
		}
		mappedBuffer.position(byteOffset);
		intView.position(byteOffset / Integer.BYTES);
		floatView.position(byteOffset / Integer.BYTES);
		glBindBuffer(target, 0);
		mapped = true;
		return this;
	}

	public void unmap() {
		assert mapped;
		writtenBytes = mappedBuffer.position();
		writtenBytes = max(writtenBytes, intView.position() * (long)Integer.BYTES);
		writtenBytes = max(writtenBytes, floatView.position() * (long)Integer.BYTES);

		glBindBuffer(target, id);

		if (usage != GL_STATIC_DRAW && (mappedFlags & GL_MAP_FLUSH_EXPLICIT_BIT) != 0)
			glFlushMappedBufferRange(target, 0, writtenBytes);

		if(!persistent) {
			glUnmapBuffer(target);
			mapped = false;
		}

		glBindBuffer(target, 0);
	}
}
