package rs117.hd.utils.buffer;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class GpuMappedBuffer {
	public enum BufferType {
		BYTE,
		INT,
		FLOAT
	}

	public final GLBuffer owner;

	public int accessType;
	public BufferType bufferType;

	public ByteBuffer buffer;
	public IntBuffer bufferInt;
	public FloatBuffer bufferFloat;

	public boolean isMapped() {
		return buffer != null;
	}

	public int getTypedPosition() {
		Buffer buf = getTypedBuffer();
		if (buf != null) {
			return buf.position();
		}
		return 0;
	}

	public void ensureCapacity(int size) {
		if (buffer != null) {
			Buffer buf = getTypedBuffer();
			owner.ensureCapacity((buf.position() + size) * getTypedSize());
		}
	}

	public long getTypedSize() {
		switch (bufferType) {
			case BYTE:
				return 1;
			case INT:
			case FLOAT:
				return 4L;
		}
		return 0L;
	}

	public <T extends Buffer> T getTypedBuffer() {
		if (buffer == null) {
			return null;
		}

		switch (bufferType) {
			case BYTE:
				return (T) buffer;
			case INT:
				return (T) bufferInt;
			case FLOAT:
				return (T) bufferFloat;
		}
		return null;
	}

	public void put(int x, int y, int z, int w) {
		if (bufferInt != null) {
			bufferInt.put(x);
			bufferInt.put(y);
			bufferInt.put(z);
			bufferInt.put(w);
		}
	}

	public void put(float x, float y, float z, float w) {
		if (bufferFloat != null) {
			bufferFloat.put(x);
			bufferFloat.put(y);
			bufferFloat.put(z);
			bufferFloat.put(w);
		}
	}

	public void put(float x, float y, float z, int w) {
		if (bufferFloat != null) {
			bufferFloat.put(x);
			bufferFloat.put(y);
			bufferFloat.put(z);
			bufferFloat.put(Float.intBitsToFloat(w));
		}
	}

	public void put(int[] array) {
		if (bufferInt != null && array != null) {
			bufferInt.put(array);
		}
	}

	public void put(float[] array) {
		if (bufferFloat != null && array != null) {
			bufferFloat.put(array);
		}
	}

	public void put(IntBuffer inBuffer) {
		if (bufferInt != null) {
			bufferInt.put(inBuffer);
		}
	}

	public void put(FloatBuffer inBuffer) {
		if (bufferFloat != null) {
			bufferFloat.put(inBuffer);
		}
	}
}
