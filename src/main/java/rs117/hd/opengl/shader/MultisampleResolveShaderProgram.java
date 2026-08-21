package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;

public class MultisampleResolveShaderProgram extends ShaderProgram {
	private final UniformTexture uniSourceMS;
	private final Uniform1i uniSampleCount;

	public MultisampleResolveShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "ui_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "oit_msaa_resolve_frag.glsl"));

		uniSourceMS = addUniformTexture("sourceMS");
		uniSampleCount = addUniform1i("sampleCount");
	}

	public void setup(int textureUnit, int sampleCount) {
		uniSourceMS.set(textureUnit);
		uniSampleCount.set(sampleCount);
	}
}
