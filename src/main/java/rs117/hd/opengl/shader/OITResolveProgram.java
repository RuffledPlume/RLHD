package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_OIT_RESOLVE;

public class OITResolveProgram extends ShaderProgram {
	private final UniformTexture uniOITResolve = addUniformTexture("oitResolve");

	public OITResolveProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "oit_resolve_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "oit_resolve_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniOITResolve.set(TEXTURE_UNIT_OIT_RESOLVE);
	}
}

