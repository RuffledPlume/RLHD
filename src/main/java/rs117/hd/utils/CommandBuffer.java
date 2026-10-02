package rs117.hd.utils;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import rs117.hd.opengl.GLFence;
import rs117.hd.opengl.shader.ShaderProgram;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLShaderStorage;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static org.lwjgl.opengl.ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40.glDrawArraysIndirect;
import static org.lwjgl.opengl.GL40.glDrawElementsIndirect;
import static org.lwjgl.opengl.GL43.glMultiDrawArraysIndirect;
import static rs117.hd.HdPlugin.SUPPORTS_SHADER_STORAGE;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class CommandBuffer {
	private static final int GL_MULTI_DRAW_ARRAYS_TYPE = 0;
	private static final int GL_MULTI_DRAW_ARRAYS_INDIRECT_TYPE = 1;
	private static final int GL_DRAW_ARRAYS_TYPE = 2;
	private static final int GL_DRAW_ARRAYS_INDIRECT_TYPE = 3;
	private static final int GL_DRAW_ELEMENTS_TYPE = 4;
	private static final int GL_DRAW_ELEMENTS_INDIRECT_TYPE = 5;
	private static final int GL_DRAW_CALL_TYPE_COUNT = 6;

	private static final int GL_BIND_VERTEX_ARRAY_TYPE = 6;
	private static final int GL_BIND_INDIRECT_ARRAY_TYPE = 7;
	private static final int GL_BIND_TEXTURE_UNIT_TYPE = 8;
	private static final int GL_BIND_SHADER_STORAGE_TYPE = 9;
	private static final int GL_DEPTH_MASK_TYPE = 10;
	private static final int GL_COLOR_MASK_TYPE = 11;
	private static final int GL_BLEND_FUNC_TYPE = 12;
	private static final int GL_USE_PROGRAM = 13;
	private static final int GL_TIMER = 14;

	private static final int GL_TOGGLE_TYPE = 15; // Combined glEnable & glDisable
	private static final int GL_FENCE_SYNC = 16;

	private static final int GL_EXECUTE_SUB_COMMAND_BUFFER = 17;

	private static final long INT_MASK = 0xFFFF_FFFFL;
	private static final int DRAW_MODE_MASK = 0xF;

	private static final ThreadLocal<ArrayDeque<CommandBuffer>> CALL_STACK = ThreadLocal.withInitial(ArrayDeque::new);

	private Object[] objects = new Object[8];
	private int objectCount = 0;
	private int lastObjectIdx = -1;

	public final String name;

	@Setter
	private FrameTimer frameTimer;

	private long[] cmd = new long[(int) KiB];
	private int writeHead = 0;

	public CommandBuffer(String name) {
		this.name = name;
	}

	private void ensureCapacity(int numLongs) {
		if (writeHead + numLongs >= cmd.length)
			cmd = Arrays.copyOf(cmd, cmd.length * 2);
	}

	private boolean includes(CommandBuffer subCommandBuffer) {
		if (this == subCommandBuffer)
			return true;
		for (int i = 0; i < objectCount; i++)
			if (objects[i] instanceof CommandBuffer && ((CommandBuffer) objects[i]).includes(this))
				return true;
		return false;
	}

	@Override
	public String toString() {
		return String.format("%s (size: %d)", name, writeHead);
	}

	public boolean isEmpty() {
		return writeHead == 0;
	}

	public void BindVertexArray(int vao, GLBuffer ebo) {
		ensureCapacity(2);
		cmd[writeHead++] = GL_BIND_VERTEX_ARRAY_TYPE & 0xFF;
		cmd[writeHead++] = (long) writeObject(ebo) << 32 | vao & INT_MASK;
	}

	public void BindVertexArray(int vao) {
		BindVertexArray(vao, null);
	}

	public void FenceSync(GLFence fence, int condition) {
		ensureCapacity(2);
		cmd[writeHead++] = GL_FENCE_SYNC & 0xFF | (long) condition << 8;
		cmd[writeHead++] = writeObject(fence);
	}

	public void BindIndirectArray(int ido) {
		ensureCapacity(1);
		cmd[writeHead++] = GL_BIND_INDIRECT_ARRAY_TYPE & 0xFF | (long) ido << 8;
	}

	public void BindTextureUnit(int type, int texId, int bindingIndex) {
		ensureCapacity(2);
		cmd[writeHead++] = GL_BIND_TEXTURE_UNIT_TYPE & 0xFF | (long) type << 8;
		cmd[writeHead++] = texId | (long) bindingIndex << 32;
	}

	public void bindShaderStorage(GLShaderStorage buffer) {
		ensureCapacity(1);
		cmd[writeHead++] = GL_BIND_SHADER_STORAGE_TYPE & 0xFF | (long) writeObject(buffer) << 8;
	}

	public void SetShader(ShaderProgram program) {
		ensureCapacity(1);
		int objectIdx = writeObject(program);
		cmd[writeHead++] = GL_USE_PROGRAM & 0xFF | (long) objectIdx << 8;
	}

	public void PushTimer(Timer timer) {
		ensureCapacity(1);
		cmd[writeHead++] = GL_TIMER & 0xFF | 1 << 8 | (long) timer.ordinal() << 9;
	}

	public void PopTimer(Timer timer) {
		ensureCapacity(1);
		cmd[writeHead++] = GL_TIMER & 0xFF | (long) timer.ordinal() << 9;
	}

	public void ExecuteSubCommandBuffer(CommandBuffer subCommandBuffer) {
		ensureCapacity(1);
		assert !subCommandBuffer.includes(this);
		int objectIdx = writeObject(subCommandBuffer);
		cmd[writeHead++] = GL_EXECUTE_SUB_COMMAND_BUFFER & 0xFF | (long) objectIdx << 8;
	}

	public void DepthMask(boolean writeDepth) {
		ensureCapacity(1);
		cmd[writeHead++] = GL_DEPTH_MASK_TYPE & 0xFF | (writeDepth ? 1 : 0) << 8;
	}

	public void ColorMask(boolean writeRed, boolean writeGreen, boolean writeBlue, boolean writeAlpha) {
		ensureCapacity(1);
		cmd[writeHead++] =
			GL_COLOR_MASK_TYPE & 0xFF |
			(writeRed ? 1 : 0) << 8 |
			(writeGreen ? 1 : 0) << 9 |
			(writeBlue ? 1 : 0) << 10 |
			(writeAlpha ? 1 : 0) << 11;
	}

	public void BlendFunc(int sfactorRGB, int dfactorRGB, int sfactorAlpha, int dfactorAlpha) {
		ensureCapacity(3);

		cmd[writeHead++] = GL_BLEND_FUNC_TYPE & 0xFF;
		cmd[writeHead++] = ((long) sfactorRGB & INT_MASK) | ((long) dfactorRGB & INT_MASK) << 32;
		cmd[writeHead++] = ((long) sfactorAlpha & INT_MASK) | ((long) dfactorAlpha & INT_MASK) << 32;
	}

	public void MultiDrawArrays(int mode, int[] offsets, int[] counts) {
		MultiDrawArrays(mode, offsets, counts, counts.length);
	}

	public void MultiDrawArrays(int mode, int[] offsets, int[] counts, int drawCount) {
		assert offsets.length == counts.length;
		assert counts.length >= drawCount;
		assert (mode & DRAW_MODE_MASK) == mode;
		if (drawCount == 0)
			return;

		ensureCapacity(1 + drawCount);
		cmd[writeHead++] = GL_MULTI_DRAW_ARRAYS_TYPE & 0xFF | mode << 8 | (long) drawCount << 32;
		for (int i = 0; i < drawCount; i++)
			cmd[writeHead++] = (long) offsets[i] << 32 | counts[i] & INT_MASK;
	}

	public void DrawElements(int mode, int vertexCount, long offset) {
		ensureCapacity(2);
		cmd[writeHead++] = GL_DRAW_ELEMENTS_TYPE & 0xFF | (mode & DRAW_MODE_MASK) << 8 | (long) vertexCount << 32;
		cmd[writeHead++] = offset;
	}

	public void DrawArrays(int mode, int offset, int vertexCount) {
		ensureCapacity(2);
		cmd[writeHead++] = GL_DRAW_ARRAYS_TYPE & 0xFF | (mode & DRAW_MODE_MASK) << 8;
		cmd[writeHead++] = (long) offset << 32 | vertexCount & INT_MASK;
	}

	public void DrawArraysIndirect(int mode, int vertexOffset, int vertexCount, GpuIntBuffer indirectBuffer) {
		ensureCapacity(2);

		// https://registry.khronos.org/OpenGL-Refpages/gl4/html/glDrawArraysIndirect.xhtml
		int indirectOffset = indirectBuffer.position();
		try {
			indirectBuffer.ensureCapacity(4).getBuffer()
				.put(vertexCount)  // count
				.put(1)         // primCount
				.put(vertexOffset) // first
				.put(0);        // baseInstance (reserved 4.1 prior)
		} catch (Exception e) {
			log.debug(
				"Failed to write DrawArraysIndirect buffer position={} remaining={} capacity={}",
				indirectBuffer.getBuffer().position(),
				indirectBuffer.getBuffer().remaining(),
				indirectBuffer.getBuffer().capacity(),
				e
			);
		}

		cmd[writeHead++] = GL_DRAW_ARRAYS_INDIRECT_TYPE & 0xFF | (long) mode << 8;
		cmd[writeHead++] = (long) indirectOffset * Integer.BYTES;
	}

	public void DrawElementsIndirect(int mode, int indexCount, int indexOffset, GpuIntBuffer indirectBuffer) {
		ensureCapacity(2);

		// https://registry.khronos.org/OpenGL-Refpages/gl4/html/glDrawElementsIndirect.xhtml
		int indirectOffset = indirectBuffer.position();
		try {
			indirectBuffer.ensureCapacity(5).getBuffer()
				.put(indexCount)    // count
				.put(1)          // instanceCount
				.put(indexOffset)   // firstIndex
				.put(0)          // baseVertex
				.put(0);         // baseInstance
		} catch (Exception e) {
			log.debug(
				"Failed to write DrawArraysIndirect buffer position={} remaining={} capacity={}",
				indirectBuffer.getBuffer().position(),
				indirectBuffer.getBuffer().remaining(),
				indirectBuffer.getBuffer().capacity(),
				e
			);
		}

		cmd[writeHead++] = GL_DRAW_ELEMENTS_INDIRECT_TYPE & 0xFF | (long) mode << 8;
		cmd[writeHead++] = (long) indirectOffset * Integer.BYTES;
	}

	public void MultiDrawArraysIndirect(int mode, int[] vertexOffsets, int[] vertexCounts, GpuIntBuffer indirectBuffer) {
		MultiDrawArraysIndirect(mode, vertexOffsets, vertexCounts, vertexCounts.length, indirectBuffer);
	}

	public void MultiDrawArraysIndirect(int mode, int[] vertexOffsets, int[] vertexCounts, int drawCount, GpuIntBuffer indirectBuffer) {
		assert vertexOffsets.length == vertexCounts.length;
		assert vertexCounts.length >= drawCount;
		assert (mode & DRAW_MODE_MASK) == mode;
		if (drawCount == 0)
			return;

		ensureCapacity(2);
		int indirectOffset = indirectBuffer.position();

		// https://registry.khronos.org/OpenGL-Refpages/gl4/html/glMultiDrawArraysIndirect.xhtml
		indirectBuffer.ensureCapacity(drawCount * 4);
		try {
			IntBuffer buf = indirectBuffer.getBuffer();
			for (int i = 0; i < drawCount; i++) {
				buf.put(vertexCounts[i]);  // count
				buf.put(1);              // instanceCount
				buf.put(vertexOffsets[i]); // first
				buf.put(0);             // baseInstance
			}
		} catch (Exception e) {
			log.debug(
				"Failed to write DrawArraysIndirect buffer drawCount={} position={} remaining={} capacity={}",
				drawCount,
				indirectBuffer.getBuffer().position(),
				indirectBuffer.getBuffer().remaining(),
				indirectBuffer.getBuffer().capacity(),
				e
			);
		}

		cmd[writeHead++] = GL_MULTI_DRAW_ARRAYS_INDIRECT_TYPE & 0xFF | (long) mode << 8 | (long) drawCount << 32;
		cmd[writeHead++] = (long) indirectOffset * Integer.BYTES;
	}

	public void Enable(int capability) {
		Toggle(capability, true);
	}

	public void Disable(int capability) {
		Toggle(capability, false);
	}

	public void Toggle(int capability, boolean enabled) {
		ensureCapacity(1);
		cmd[writeHead++] = GL_TOGGLE_TYPE | (enabled ? 1L : 0L) << 8 | (capability & INT_MASK) << 9;
	}

	public void execute(RenderState renderState) {
		var callStack = CALL_STACK.get();
		if (callStack.contains(this))
			throw new IllegalStateException(String.format(
				"Command buffer recursion error: [%s, %s]",
				callStack.stream().map(Object::toString).collect(Collectors.joining(", ")),
				this
			));

		if (frameTimer != null)
			frameTimer.begin(Timer.EXECUTE_COMMAND_BUFFER);
		callStack.push(this);

		// Force VAO state to reapply to ensure it is in sync with the render state
		renderState.vao.invalidate();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer offsets = null, counts = null;
			long data;
			int readHead = 0, type;
			while (readHead < writeHead) {
				// Casting from long to int keeps the lower 32 bits
				data = cmd[readHead++];
				type = (int) data & 0xFF;
				if (type < GL_DRAW_CALL_TYPE_COUNT)
					renderState.apply();

				switch (type) {
					case GL_DEPTH_MASK_TYPE: {
						final int state = (int) (data >> 8) & 1;
						renderState.depthMask.set(state == 1);
						break;
					}
					case GL_COLOR_MASK_TYPE: {
						final boolean red = ((data >> 8) & 1) == 1;
						final boolean green = ((data >> 9) & 1) == 1;
						final boolean blue = ((data >> 10) & 1) == 1;
						final boolean alpha = ((data >> 11) & 1) == 1;
						renderState.colorMask.set(red, green, blue, alpha);
						break;
					}
					case GL_BLEND_FUNC_TYPE: {
						data = cmd[readHead++];
						final int sfactorRGB = (int) data;
						final int dfactorRGB = (int) (data >>> 32);

						data = cmd[readHead++];
						final int sfactorAlpha = (int) data;
						final int dfactorAlpha = (int) (data >>> 32);
						renderState.blendFunc.set(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha);
						break;
					}
					case GL_BIND_VERTEX_ARRAY_TYPE: {
						data = cmd[readHead++];
						final int eboIdx = (int) (data >> 32);
						final int vao = (int) data;
						final int ebo = eboIdx >= 0 ? ((GLBuffer) objects[eboIdx]).id : 0;
						renderState.vao.setVaoAndEbo(vao, ebo);
						break;
					}
					case GL_BIND_INDIRECT_ARRAY_TYPE: {
						renderState.ido.set((int) (data >> 8));
						break;
					}
					case GL_BIND_TEXTURE_UNIT_TYPE: {
						final int texType = (int) (data >> 8);
						data = cmd[readHead++];
						final int texUnit = (int) (data >> 32);
						final int texId = (int) data;

						renderState.texture.set(texType, texUnit, texId);
						break;
					}
					case GL_BIND_SHADER_STORAGE_TYPE: {
						GLShaderStorage buffer = (GLShaderStorage) objects[(int) (data >> 8)];

						if (SUPPORTS_SHADER_STORAGE) {
							glBindBufferBase(GL_SHADER_STORAGE_BUFFER, buffer.getBindingIndex(), buffer.id);
						} else {
							renderState.texture.set(GL_TEXTURE_BUFFER, buffer.getTextureUnit(), buffer.getTexId());
						}
						break;
					}
					case GL_USE_PROGRAM: {
						final int objectIdx = (int) (data >> 8);
						renderState.program.set((ShaderProgram) objects[objectIdx]);
						break;
					}
					case GL_TIMER: {
						if (frameTimer != null) {
							final int timerOrdinal = (int) (data >> 9);
							assert timerOrdinal >= 0 && timerOrdinal < Timer.TIMERS.length;
							final var timer = Timer.TIMERS[timerOrdinal];
							if (((data >> 8) & 1) == 1) {
								frameTimer.begin(timer);
							} else {
								frameTimer.end(timer);
							}
						}
						break;
					}
					case GL_TOGGLE_TYPE: {
						final int capability = (int) (data >>> 9);
						if (((data >> 8) & 1) != 0) {
							renderState.enable.set(capability);
						} else {
							renderState.disable.set(capability);
						}
						break;
					}
					case GL_FENCE_SYNC: {
						final int condition = (int) (data >> 8);
						GLFence fence = (GLFence) objects[(int) cmd[readHead++]];
						fence.handle = glFenceSync(condition, 0);
						break;
					}
					case GL_DRAW_ARRAYS_TYPE: {
						final int mode = (int) data >> 8;
						data = cmd[readHead++];
						final int offset = (int) (data >> 32);
						final int count = (int) data;

						glDrawArrays(mode, offset, count);
						break;
					}
					case GL_DRAW_ELEMENTS_TYPE: {
						final int mode = (int) data >> 8;
						final int vertexCount = (int) (data >> 32);
						glDrawElements(mode, vertexCount, GL_UNSIGNED_INT, cmd[readHead++]);
						break;
					}
					case GL_MULTI_DRAW_ARRAYS_TYPE: {
						final int mode = (int) data >> 8;
						final int drawCount = (int) (data >> 32);

						if (offsets == null || offsets.capacity() < drawCount) {
							offsets = stack.callocInt(drawCount);
							counts = stack.callocInt(drawCount);
						}

						for (int i = 0; i < drawCount; i++) {
							data = cmd[readHead++];
							offsets.put((int) (data >> 32));
							counts.put((int) data);
						}

						offsets.flip();
						counts.flip();

						glMultiDrawArrays(mode, offsets, counts);

						offsets.clear();
						counts.clear();
						break;
					}
					case GL_DRAW_ARRAYS_INDIRECT_TYPE: {
						final int mode = (int) data >> 8;
						glDrawArraysIndirect(mode, cmd[readHead++]);
						break;
					}
					case GL_DRAW_ELEMENTS_INDIRECT_TYPE: {
						final int mode = (int) data >> 8;
						glDrawElementsIndirect(mode, GL_UNSIGNED_INT, cmd[readHead++]);
						break;
					}
					case GL_MULTI_DRAW_ARRAYS_INDIRECT_TYPE: {
						final int mode = (int) data >> 8;
						final int drawCount = (int) (data >> 32);
						glMultiDrawArraysIndirect(mode, cmd[readHead++], drawCount, 0);
						break;
					}
					case GL_EXECUTE_SUB_COMMAND_BUFFER: {
						((CommandBuffer) objects[(int) (data >> 8)]).execute(renderState);
						break;
					}
					default:
						throw new IllegalArgumentException("Encountered an unknown DrawCall type: " + type);
				}
			}
			renderState.apply();
		} finally {
			callStack.pop();
			if (frameTimer != null)
				frameTimer.end(Timer.EXECUTE_COMMAND_BUFFER);
		}
	}

	private int writeObject(Object obj) {
		if (obj == null)
			return -1;

		if (lastObjectIdx >= 0 && objects[lastObjectIdx] == obj)
			return lastObjectIdx;

		for (int i = 0; i < objectCount; i++)
			if (objects[i] == obj)
				return lastObjectIdx = i;

		if (objectCount == objects.length)
			objects = Arrays.copyOf(objects, objects.length * 2);
		objects[objectCount] = obj;
		return lastObjectIdx = objectCount++;
	}

	public void reset() {
		Arrays.fill(objects, 0, objectCount, null);

		writeHead = 0;
		objectCount = 0;
		lastObjectIdx = -1;
	}
}
