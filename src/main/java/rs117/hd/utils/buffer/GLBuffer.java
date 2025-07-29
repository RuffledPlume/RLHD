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
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.utils.HDUtils;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.checkGLErrors;

@Slf4j
@RequiredArgsConstructor
public class GLBuffer
{
	public final String name;
	public final int target;
	public final int usage;

	public final GpuMappedBuffer mapped = new GpuMappedBuffer(this);

	public int id;
	public long size;

	private Client client;

	private ClientThread clientThread;

	private final Semaphore clientThreadSema = new Semaphore(0);

	public void initialize(Client client, ClientThread clientThread) {
		initialize(client, clientThread, 0);
	}

	public void initialize(Client client, ClientThread clientThread, long initialCapacity) {
		this.client = client;
		this.clientThread = clientThread;

		id = glGenBuffers();
		// Initialize both GL and CL buffers to buffers of a single byte or more,
		// to ensure that valid buffers are given to compute dispatches.
		// This is particularly important on Apple M2 Max, where an uninitialized buffer leads to a crash
		ensureCapacity(Math.max(1, initialCapacity));
	}

	public void destroy() {
		size = 0;

		if (id != 0) {
			glDeleteBuffers(id);
			id = 0;
		}
	}

	public GpuMappedBuffer map(int access, GpuMappedBuffer.BufferType bufferType) {
		return map(access, 0, bufferType);
	}

	@SneakyThrows
	public GpuMappedBuffer map(int access, int bytesOffset, GpuMappedBuffer.BufferType bufferType) {
		if (!mapped.isMapped()) {
			if (!client.isClientThread()) {
				clientThread.invoke(() -> {
					map(access, bytesOffset, bufferType);
					clientThreadSema.release();
				});
				clientThreadSema.acquire();
				return mapped;
			}
			glBindBuffer(target, id);
			ByteBuffer buf = glMapBuffer(target, access);
			if (buf != null) {
				mapped.accessType = access;
				mapped.bufferType = bufferType;
				mapped.buffer = buf;
				mapped.bufferInt = bufferType == GpuMappedBuffer.BufferType.INT ? mapped.buffer.asIntBuffer() : null;
				mapped.bufferFloat = bufferType == GpuMappedBuffer.BufferType.FLOAT ? mapped.buffer.asFloatBuffer() : null;
			}
			glBindBuffer(target, 0);
		}

		if (mapped.buffer != null) mapped.buffer.position(bytesOffset);
		if (mapped.bufferInt != null) mapped.bufferInt.position(bytesOffset / 4);
		if (mapped.bufferFloat != null) mapped.bufferFloat.position(bytesOffset / 4);

		return mapped;
	}

	@SneakyThrows
	public void unmap() {
		assert client.isClientThread();
		if (mapped.isMapped()) {
			glBindBuffer(target, id);
			glUnmapBuffer(target);
			mapped.buffer = null;
			mapped.bufferInt = null;
			mapped.bufferFloat = null;
			glBindBuffer(target, 0);
		}
	}

	public void ensureCapacity(long numBytes) {
		ensureCapacity(0, numBytes);
	}

	@SneakyThrows
	public void ensureCapacity(long byteOffset, long numBytes) {
		long newSize = byteOffset + numBytes;
		if (newSize <= size) {
			if (client.isClientThread()) {
				glBindBuffer(target, id);
			}
			return;
		}

		if (!client.isClientThread()) {
			clientThread.invoke(() -> {
				ensureCapacity(byteOffset, numBytes);
				clientThreadSema.release();
			});
			clientThreadSema.acquire();
			return;
		}

		newSize = HDUtils.ceilPow2(newSize);
		if (log.isDebugEnabled() && newSize > 1e6)
			log.debug("Resizing buffer '{}'\t{}", name, String.format("%.2f MB -> %.2f MB", size / 1e6, newSize / 1e6));

		int mappedBufferPosition = -1;
		if (mapped.isMapped()) {
			mappedBufferPosition = mapped.getTypedPosition() * (int) mapped.getTypedSize();
			mapped.buffer.position(mappedBufferPosition);
			unmap();
		}

		if (byteOffset > 0) {
			// Create a new buffer and copy the old data to it
			int oldBuffer = id;
			id = glGenBuffers();
			glBindBuffer(target, id);
			glBufferData(target, newSize, usage);

			glBindBuffer(GL_COPY_READ_BUFFER, oldBuffer);
			glCopyBufferSubData(GL_COPY_READ_BUFFER, target, 0, 0, byteOffset);
			glDeleteBuffers(oldBuffer);
		} else {
			glBindBuffer(target, id);
			glBufferData(target, newSize, usage);
		}

		size = newSize;

		if (mappedBufferPosition != -1) {
			map(mapped.accessType, mappedBufferPosition, mapped.bufferType);
			glBindBuffer(target, id);
		}

		if (log.isDebugEnabled() && HdPlugin.GL_CAPS.OpenGL43) {
			GL43C.glObjectLabel(GL43C.GL_BUFFER, id, name);
			checkGLErrors();
		}
	}

	public void upload(ByteBuffer data) {
		upload(data, 0);
	}

	public void upload(ByteBuffer data, long byteOffset) {
		assert client.isClientThread();
		unmap();
		long numBytes = data.remaining();
		ensureCapacity(byteOffset, numBytes);
		glBufferSubData(target, byteOffset, data);
	}

	public void upload(IntBuffer data) {
		upload(data, 0);
	}

	public void upload(IntBuffer data, long byteOffset) {
		assert client.isClientThread();
		unmap();
		long numBytes = 4L * data.remaining();
		ensureCapacity(byteOffset, numBytes);
		glBufferSubData(target, byteOffset, data);
	}

	public void upload(FloatBuffer data) {
		upload(data, 0);
	}

	public void upload(FloatBuffer data, long byteOffset) {
		assert client.isClientThread();
		unmap();
		long numBytes = 4L * data.remaining();
		ensureCapacity(byteOffset, numBytes);
		glBufferSubData(target, byteOffset, data);
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
}
