package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;

public class OitRangeResolveShaderProgram extends ShaderProgram {
	private final UniformTexture uniFirstLayerMS;
	private final UniformTexture uniLastLayerMS;
	private final Uniform1i uniSampleCount;

	public OitRangeResolveShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "ui_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "oit_range_resolve_frag.glsl"));

		uniFirstLayerMS = addUniformTexture("firstLayerMS");
		uniLastLayerMS = addUniformTexture("lastLayerMS");
		uniSampleCount = addUniform1i("sampleCount");
	}

	public void setup(int firstLayerTextureUnit, int lastLayerTextureUnit, int sampleCount) {
		uniFirstLayerMS.set(firstLayerTextureUnit);
		uniLastLayerMS.set(lastLayerTextureUnit);
		uniSampleCount.set(sampleCount);
	}
}
