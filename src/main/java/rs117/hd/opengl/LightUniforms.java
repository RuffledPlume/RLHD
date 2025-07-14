package rs117.hd.opengl;

import rs117.hd.config.MaxDynamicLights;

import static org.lwjgl.opengl.GL15C.*;

public class LightUniforms extends UniformBuffer {
	public LightUniforms() {
		super("Lights", GL_DYNAMIC_DRAW);
	}

	public static final int MAX_LIGHTS = 1000; // Struct is 64 Bytes, UBO Max size is 64 KB

	public LightStruct[] lights = addStructs(new LightStruct[MAX_LIGHTS], LightStruct::new);

	public static class LightStruct extends UniformBuffer.StructProperty {
		public Property position = addProperty(PropertyType.FVec4, "position");
		public Property color = addProperty(PropertyType.FVec3, "color");
	}
}
