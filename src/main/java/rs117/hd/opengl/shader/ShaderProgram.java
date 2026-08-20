package rs117.hd.opengl.shader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.uniforms.UniformBuffer;
import rs117.hd.utils.Destructible;
import rs117.hd.utils.DestructibleHandler;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.APPLE;

@Slf4j
public class ShaderProgram implements Destructible {
	@RequiredArgsConstructor
	private static class UniformBufferBlockPair {
		public final UniformBuffer<?> buffer;
		public final int uboProgramIndex;
		private int bindingIndex = -1;
	}

	public static class ShaderVariant {
		private final ShaderProgram program;
		@Getter
		private final int featureMask;
		private final int glProgram;
		private final Map<UniformProperty, Integer> uniformLocations = new HashMap<>();
		private final List<UniformBufferBlockPair> uniformBlockMappings = new ArrayList<>();

		private ShaderVariant(ShaderProgram program, int featureMask, int glProgram) {
			this.program = program;
			this.featureMask = featureMask;
			this.glProgram = glProgram;
		}

		public void use() {
			program.activeVariant = this;
			glUseProgram(glProgram);

			for (int i = 0; i < uniformBlockMappings.size(); i++ ) {
				final var pair = uniformBlockMappings.get(i);
				if(pair.bindingIndex != pair.buffer.getBindingIndex()) {
					glUniformBlockBinding(glProgram, pair.uboProgramIndex, pair.buffer.getBindingIndex());
					pair.bindingIndex = pair.buffer.getBindingIndex();
				}
			}
		}
	}

	private final List<UniformProperty> uniformProperties = new ArrayList<>();
	private final ShaderFeature[] features;
	private final int allFeaturesMask;

	protected final ShaderTemplate shaderTemplate;

	private ShaderIncludes baseIncludes;
	private final Map<Integer, ShaderVariant> variants = new HashMap<>();
	private ShaderVariant activeVariant;

	@Getter
	private boolean viable = true;

	public ShaderProgram(Consumer<ShaderTemplate> templateConsumer) {
		this(templateConsumer, ShaderFeature.NONE);
	}

	public ShaderProgram(Consumer<ShaderTemplate> templateConsumer, ShaderFeature[] features) {
		shaderTemplate = new ShaderTemplate();
		templateConsumer.accept(shaderTemplate);
		this.features = features;
		this.allFeaturesMask = ShaderFeature.mask(features);
	}

	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		destroy();
		baseIncludes = includes.copy();

		try {
			compileVariant(allFeaturesMask);
		} catch (ShaderException ex) {
			viable = false;
			throw ex;
		}

		assert isValid();
		use(allFeaturesMask);
	}

	protected void initialize() {}

	public boolean isValid() {
		return !variants.isEmpty();
	}

	public boolean isActive() {
		// Meant for debugging only
		return activeVariant != null && activeVariant.glProgram == glGetInteger(GL_CURRENT_PROGRAM);
	}

	@SuppressWarnings("unchecked")
	public <T extends UniformBuffer<?>> T getUniformBufferBlock(int uniformBlockIndex) {
		if (activeVariant == null)
			return null;
		for (var pair : activeVariant.uniformBlockMappings)
			if (pair.buffer.getBindingIndex() == uniformBlockIndex)
				return (T) pair.buffer;
		return null;
	}

	public void use() {
		getVariant().use();
	}

	public void use(int featureMask) {
		var variant = getVariant(featureMask);
		if (variant != null)
			variant.use();
	}

	public ShaderVariant getVariant() {
		return getVariant(allFeaturesMask);
	}

	private String getShaderName() {
		String name = getClass().getName();
		int packageEnd = name.lastIndexOf('.');
		return name.substring(packageEnd + 1).replace('$', '.');
	}

	private String getVariantName(int featureMask) {
		var enabledFeatures = new ArrayList<String>();
		for (var feature : features) {
			if ((featureMask & feature.mask()) != 0)
				enabledFeatures.add(feature.getDefineName());
		}

		String name = getShaderName();
		return enabledFeatures.isEmpty()
			? name + " [BASE]"
			: name + " [" + String.join(", ", enabledFeatures) + "]";
	}

	public ShaderVariant getVariant(int featureMask) {
		assert baseIncludes != null : "compile(ShaderIncludes) must be called before getVariant()";

		featureMask = normalize(featureMask);

		var variant = variants.get(featureMask);
		if (variant == null) {
			try {
				variant = compileVariant(featureMask);
			} catch (ShaderException | IOException ex) {
				log.error("Failed to compile variant of {} for feature mask {}", getClass().getSimpleName(), featureMask, ex);
				return null;
			}
		}

		return variant;
	}

	private int normalize(int featureMask) {
		boolean changed = true;
		while (changed) {
			changed = false;
			for (var feature : features) {
				if ((featureMask & (1 << feature.ordinal())) == 0)
					continue;
				for (var dependency : feature.getDependencies()) {
					int bit = 1 << dependency.ordinal();
					if ((featureMask & bit) == 0) {
						featureMask |= bit;
						changed = true;
					}
				}
			}
		}
		return featureMask;
	}

	private ShaderVariant compileVariant(int featureMask) throws ShaderException, IOException {
		var includes = baseIncludes.copy();
		for (var feature : features)
			includes.define(feature.getDefineName(), (featureMask & (1 << feature.ordinal())) != 0);

		int glProgram = shaderTemplate.compile(includes, getVariantName(featureMask));
		var variant = new ShaderVariant(this, featureMask, glProgram);

		for (var prop : uniformProperties) {
			int location = glGetUniformLocation(glProgram, prop.uniformName);
			if (location == -1 && !prop.ignoreMissing)
				log.warn(
					"{} has missing or unused {}: {} (feature mask {})",
					getClass().getSimpleName(), prop.getClass().getSimpleName(), prop.uniformName, featureMask);
			variant.uniformLocations.put(prop, location);
		}

		for (var ubo : includes.uniformBuffers) {
			int bindingIndex = glGetUniformBlockIndex(glProgram, ubo.getUniformBlockName());
			if (bindingIndex != -1)
				variant.uniformBlockMappings.add(new UniformBufferBlockPair(ubo, bindingIndex));
		}

		variants.put(featureMask, variant);

		// Bind the new variant just long enough to run initialize() against the right uniform locations, then
		// restore whatever was bound before - the caller takes care of binding it properly (or not at all, if
		// it was only resolved via getVariant() for deferred application).
		int previouslyBoundProgram = glGetInteger(GL_CURRENT_PROGRAM);
		var previouslyActiveVariant = activeVariant;
		activeVariant = variant;
		glUseProgram(glProgram);
		initialize();

		// Shader validation can be horribly slow on macOS with AMD GPUs
		if (!APPLE || log.isDebugEnabled()) {
			glValidateProgram(glProgram);
			if (glGetProgrami(glProgram, GL_VALIDATE_STATUS) == GL_FALSE) {
				String err = glGetProgramInfoLog(glProgram);
				log.error(
					"Failed to validate shader program: {} (feature mask {})",
					getClass().getSimpleName(), featureMask, new ShaderException(err));
			}
		}

		glUseProgram(previouslyBoundProgram);
		activeVariant = previouslyActiveVariant;

		return variant;
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() {
		if (!variants.isEmpty())
			DestructibleHandler.queueLeakedDestruction(this);
	}

	@Override
	public void destroy() {
		viable = true;
		for (var variant : variants.values())
			glDeleteProgram(variant.glProgram);
		variants.clear();
		activeVariant = null;
	}

	private static class UniformProperty {
		ShaderProgram program;
		String uniformName;
		boolean ignoreMissing;

		int location() {
			if (program.activeVariant == null)
				return -1;
			return program.activeVariant.uniformLocations.getOrDefault(this, -1);
		}
	}

	private <T extends UniformProperty> T addUniform(T property, String uniformName) {
		property.program = this;
		property.uniformName = uniformName;
		uniformProperties.add(property);
		return property;
	}

	public static class UniformBool extends UniformProperty {
		public void set(boolean bool) {
			assert program.isActive();
			glUniform1i(location(), bool ? 1 : 0);
		}
	}

	public UniformBool addUniformBool(String uniformName) {
		return addUniform(new UniformBool(), uniformName);
	}

	public static class UniformTexture extends UniformProperty {
		public void set(int textureUnit) {
			assert textureUnit >= GL_TEXTURE0 : "Did you accidentally pass in an image unit?";
			assert program.isActive();
			int location = location();
			if (location != -1)
				glUniform1i(location, textureUnit - GL_TEXTURE0);
		}
	}

	public UniformTexture addUniformTexture(String uniformName) {
		return addUniform(new UniformTexture(), uniformName);
	}

	public static class UniformImage extends UniformProperty {
		public void set(int imageUnit) {
			assert imageUnit < GL_TEXTURE0 : "Did you accidentally pass in a texture unit?";
			assert program.isActive();
			glUniform1i(location(), imageUnit);
		}
	}

	public UniformImage addUniformImage(String uniformName) {
		return addUniform(new UniformImage(), uniformName);
	}

	public static class Uniform1i extends UniformProperty {
		public void set(int value) {
			assert program.isActive();
			glUniform1i(location(), value);
		}
	}

	public Uniform1i addUniform1i(String uniformName) {
		return addUniform(new Uniform1i(), uniformName);
	}

	public static class Uniform2i extends UniformProperty {
		public void set(int x, int y) {
			assert program.isActive();
			glUniform2i(location(), x, y);
		}

		public void set(int... ivec2) {
			assert program.isActive();
			glUniform2iv(location(), ivec2);
		}
	}

	public Uniform2i addUniform2i(String uniformName) {
		return addUniform(new Uniform2i(), uniformName);
	}

	public static class Uniform3i extends UniformProperty {
		public void set(int x, int y, int z) {
			assert program.isActive();
			glUniform3i(location(), x, y, z);
		}

		public void set(int... ivec3) {
			assert program.isActive();
			glUniform3iv(location(), ivec3);
		}
	}

	public Uniform3i addUniform3i(String uniformName) {
		return addUniform(new Uniform3i(), uniformName);
	}

	public static class Uniform4i extends UniformProperty {
		public void set(int x, int y, int z, int w) {
			assert program.isActive();
			glUniform4i(location(), x, y, z, w);
		}

		public void set(int... ivec4) {
			assert program.isActive();
			glUniform4iv(location(), ivec4);
		}
	}

	public Uniform4i addUniform4i(String uniformName) {
		return addUniform(new Uniform4i(), uniformName);
	}

	public static class Uniform1f extends UniformProperty {
		public void set(float value) {
			assert program.isActive();
			glUniform1f(location(), value);
		}
	}

	public Uniform1f addUniform1f(String uniformName) {
		return addUniform(new Uniform1f(), uniformName);
	}

	public static class Uniform2f extends UniformProperty {
		public void set(float x, float y) {
			assert program.isActive();
			glUniform2f(location(), x, y);
		}

		public void set(float... vec2) {
			assert program.isActive();
			glUniform2fv(location(), vec2);
		}
	}

	public Uniform2f addUniform2f(String uniformName) {
		return addUniform(new Uniform2f(), uniformName);
	}

	public static class Uniform3f extends UniformProperty {
		public void set(float x, float y, float z) {
			assert program.isActive();
			glUniform3f(location(), x, y, z);
		}

		public void set(float... vec3) {
			assert program.isActive();
			glUniform3fv(location(), vec3);
		}
	}

	public Uniform3f addUniform3f(String uniformName) {
		return addUniform(new Uniform3f(), uniformName);
	}

	public static class Uniform4f extends UniformProperty {
		public void set(float x, float y, float z, float w) {
			assert program.isActive();
			glUniform4f(location(), x, y, z, w);
		}

		public void set(float... vec4) {
			assert program.isActive();
			glUniform4fv(location(), vec4);
		}
	}

	public Uniform4f addUniform4f(String uniformName) {
		return addUniform(new Uniform4f(), uniformName);
	}

	public static class UniformMat4 extends UniformProperty {
		public void set(float[] mat4) {
			assert program.isActive();
			glUniformMatrix4fv(location(), false, mat4);
		}
	}

	public UniformMat4 addUniformMat4(String uniformName) {
		return addUniform(new UniformMat4(), uniformName);
	}
}