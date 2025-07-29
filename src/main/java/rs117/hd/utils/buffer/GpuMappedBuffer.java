package rs117.hd.utils.buffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryUtil;

@Slf4j
@RequiredArgsConstructor
public class GpuMappedBuffer {
	public final GLBuffer owner;

	public int accessType;
	public long address;
	public ByteBuffer buffer;

	public boolean isMapped() {
		return buffer != null;
	}

	public int position() {
		return buffer != null ? buffer.position() : 0;
	}

	public void ensureCapacity(int numBytes) {
		if (buffer != null)
			owner.ensureCapacity(buffer.position() + numBytes);
	}

	public void put(int x, int y, int z, int w) {
		if (buffer != null) {
			buffer.putInt(x);
			buffer.putInt(y);
			buffer.putInt(z);
			buffer.putInt(w);
		}
	}

	public void put(float x, float y, float z, float w) {
		if (buffer != null) {
			buffer.putFloat(x);
			buffer.putFloat(y);
			buffer.putFloat(z);
			buffer.putFloat(w);
		}
	}

	public void put(float x, float y, float z, int w) {
		if (buffer != null) {
			buffer.putFloat(x);
			buffer.putFloat(y);
			buffer.putFloat(z);
			buffer.putInt(w);
		}
	}

	public void put(int[] array) {
		if (buffer != null && array != null) {
			for (int val : array) {
				buffer.putInt(val);
			}
		}
	}

	public void put(float[] array) {
		if (buffer != null && array != null) {
			for (float val : array) {
				buffer.putFloat(val);
			}
		}
	}

	public void put(IntBuffer inBuffer) {
		if (buffer != null) {
			int srcPos = inBuffer.position();
			int srcRem = inBuffer.limit() - srcPos;
			if (srcRem > 0) {
				long srcBufferAddress = MemoryUtil.memAddress(inBuffer);
				assert srcBufferAddress != 0;

				int dstPos = position();
				long dstAddress = address + dstPos * (long) Integer.BYTES;
				long byteCount = srcRem * (long) Integer.BYTES;

				MemoryUtil.memCopy(srcBufferAddress, dstAddress, byteCount);

				buffer.position(dstPos + (int) byteCount);
				inBuffer.position(srcPos + srcRem);
			}
		}
	}

	public void put(FloatBuffer inBuffer) {
		if (buffer != null) {
			int srcPos = inBuffer.position();
			int srcRem = inBuffer.limit() - srcPos;
			if (srcRem > 0) {
				long srcBufferAddress = MemoryUtil.memAddress(inBuffer);
				assert srcBufferAddress != 0;

				int dstPos = position();
				long dstAddress = address + dstPos * (long) Integer.BYTES;
				long byteCount = srcRem * (long) Integer.BYTES;

				MemoryUtil.memCopy(srcBufferAddress, dstAddress, byteCount);

				buffer.position(dstPos + (int) byteCount);
				inBuffer.position(srcPos + srcRem);
			}
		}
	}
}
