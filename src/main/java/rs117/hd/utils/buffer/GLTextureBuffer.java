package rs117.hd.utils.buffer;

import lombok.Getter;

import static org.lwjgl.opengl.GL33C.*;

public class GLTextureBuffer extends GLBuffer {
	@Getter
	private int texId;

	public GLTextureBuffer(String name, int usage) {
		super(name, GL_TEXTURE_BUFFER, usage);
	}

	@Override
	public void initialize(long initialCapacity) {
		super.initialize(initialCapacity);

		// Create texture
		texId = glGenTextures();
		glBindTexture(target, texId);

		// RGB32 signed integer texture buffer
		glTexBuffer(target, GL_RGB32I, id);

		glBindTexture(target, 0);
	}

	@Override
	public void destroy() {
		if (texId != 0) {
			glDeleteTextures(texId);
			texId = 0;
		}

		super.destroy();
	}
}
