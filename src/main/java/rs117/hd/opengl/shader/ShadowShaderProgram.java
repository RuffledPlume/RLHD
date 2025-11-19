package rs117.hd.opengl.shader;

import java.io.IOException;
import rs117.hd.config.ShadowMode;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_GAME;

public class ShadowShaderProgram extends ShaderProgram {
	private final UniformTexture uniShadowMap = addUniformTexture("textureArray");

	private ShadowMode mode;
	private boolean useGeomShader = true;

	public ShadowShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "shadow_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniShadowMap.set(TEXTURE_UNIT_GAME);
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		super.compile(includes.copy().define("SHADOW_MODE", mode).define("USE_GEOM_SHADER", useGeomShader));
	}

	public void setMode(ShadowMode mode) {
		this.mode = mode;
		if (mode == ShadowMode.DETAILED && useGeomShader) {
			shaderTemplate.add(GL_GEOMETRY_SHADER, "shadow_geom.glsl");
		} else {
			shaderTemplate.remove(GL_GEOMETRY_SHADER);
		}
	}

	public void setUseGeomShader(boolean use) {
		useGeomShader = use;
		setMode(mode);
	}

	public static class Fast extends ShadowShaderProgram {
		public Fast() {
			super();
			setMode(ShadowMode.FAST);
		}
	}

	public static class Detailed extends ShadowShaderProgram {
		public Detailed() {
			super();
			setMode(ShadowMode.DETAILED);
		}
	}
}
