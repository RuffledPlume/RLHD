package rs117.hd.opengl.shader;
import java.io.IOException;
import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_OIT_COLOR_ACCUM;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_OIT_NET_COVERAGE;

public class OITCompositeShaderProgram extends ShaderProgram {
	private final UniformTexture uniColorAccum;
	private final UniformTexture uniNetCoverage;

	public OITCompositeShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "ui_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "oit_composite_frag.glsl"));

		uniColorAccum = addUniformTexture("colorAccum");
		uniNetCoverage = addUniformTexture("netCoverage");
	}
	@Override
	protected void initialize() {
		uniColorAccum.set(TEXTURE_UNIT_OIT_COLOR_ACCUM);
		uniNetCoverage.set(TEXTURE_UNIT_OIT_NET_COVERAGE);
	}

	protected boolean useSampleShading() {
		return false;
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		super.compile(includes.copy().define("OIT_COMPOSITE_SAMPLE_SHADING", useSampleShading()));
	}

	// Reads colorAccum/netCoverage as true multisample textures and runs the fragment shader once
	// per MSAA sample (via GL_ARB_sample_shading), instead of resolving/averaging them down to one
	// value per pixel first - see oit_composite_frag.glsl for why averaging causes a black fringe
	// at MSAA silhouette edges. This is a single full-screen triangle, so per-sample shading here
	// is cheap regardless of scene complexity. Only compiled/used when ZoneRenderer detects support
	// for GL_ARB_sample_shading.
	public static class SampleShading extends OITCompositeShaderProgram {
		@Override
		protected boolean useSampleShading() {
			return true;
		}
	}
}
