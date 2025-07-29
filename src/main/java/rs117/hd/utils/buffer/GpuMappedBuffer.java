package rs117.hd.utils.buffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
		if (buffer != null) {
			for (int j : array) {
				buffer.putInt(j);
			}
		}
	}

	public void put(float[] array) {
		if (buffer != null) {
			for (float j : array) {
				buffer.putFloat(j);
			}
		}
	}

	public void put(IntBuffer inBuffer) {
		if (buffer != null) {
			IntBuffer intBuffer = buffer.asIntBuffer();
			intBuffer.put(inBuffer);
			buffer.position(intBuffer.position() * Integer.BYTES);

			//long offsetAddress = address + position() * (long) Integer.BYTES;
			//long byteCount = (inBuffer.limit() - inBuffer.position()) * (long) Integer.BYTES;
			//MemoryUtil.memCopy(MemoryUtil.memAddress(inBuffer), offsetAddress, byteCount);
			//buffer.position(buffer.position() + (int) byteCount);
		}
	}

	public void put(FloatBuffer inBuffer) {
		if (buffer != null) {
			FloatBuffer floatBuffer = buffer.asFloatBuffer();
			floatBuffer.put(inBuffer);
			buffer.position(floatBuffer.position() * Float.BYTES);

			//long offsetAddress = address + position() * (long) Integer.BYTES;
			//long byteCount = (inBuffer.limit() - inBuffer.position()) * (long) Float.BYTES;
			//MemoryUtil.memCopy(MemoryUtil.memAddress(inBuffer), offsetAddress, byteCount);
			//buffer.position(buffer.position() + (int) byteCount);
		}
	}
}
