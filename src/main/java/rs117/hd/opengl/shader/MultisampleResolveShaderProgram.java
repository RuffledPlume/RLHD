package rs117.hd.opengl.shader;

import java.io.IOException;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.opengl.Utils.labelObject;

public class MultisampleResolveShaderProgram extends ShaderProgram {
	private final UniformTexture uniSourceMS;
	private final Uniform1i uniSampleCount;
	private int resolveFBO;

	private final String resolveFunc;

	private MultisampleResolveShaderProgram(String resolveFunc) {
		super(t -> t
			.add(GL_VERTEX_SHADER, "ui_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "msaa_resolve_frag.glsl"));
		this.resolveFunc = resolveFunc;
		uniSourceMS = addUniformTexture("sourceMS");
		uniSampleCount = addUniform1i("sampleCount");
	}

	public void setup(int textureUnit, int sampleCount) {
		uniSourceMS.set(textureUnit);
		uniSampleCount.set(sampleCount);
	}

	public void resolve(
		RenderState renderState,
		int resolveVao,
		int textureUnit,
		int sourceTexture,
		int destinationTexture,
		int sampleCount
	) {
		if (resolveFBO == 0) {
			resolveFBO = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, resolveFBO);
			labelObject(GL_FRAMEBUFFER, resolveFBO, "MSAA Resolve FBO");
		}

		glActiveTexture(textureUnit);
		glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, sourceTexture);

		use();
		setup(textureUnit, sampleCount);

		renderState.framebuffer.set(GL_FRAMEBUFFER, resolveFBO);
		renderState.disable.set(GL_MULTISAMPLE);
		renderState.disable.set(GL_BLEND);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.vao.setVao(resolveVao);
		renderState.apply();

		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, destinationTexture, 0);
		glDrawBuffer(GL_COLOR_ATTACHMENT0);
		glDrawArrays(GL_TRIANGLES, 0, 3);
	}

	@Override
	public void destroy() {
		super.destroy();
		if (resolveFBO != 0)
			glDeleteFramebuffers(resolveFBO);
		resolveFBO = 0;
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		super.compile(includes.copy().define("RESOLVE_FUNC", resolveFunc));
	}

	public static class Min extends MultisampleResolveShaderProgram {
		public Min() { super("min"); }
	}

	public static class Max extends MultisampleResolveShaderProgram {
		public Max() { super("max"); }
	}
}
