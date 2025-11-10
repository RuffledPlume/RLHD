package rs117.hd.opengl.buffer.uniforms;

import rs117.hd.opengl.buffer.UniformStructuredBuffer;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;

public class UBOInstanced extends UniformStructuredBuffer<GLBuffer> {
	public static int MAX_INSTANCES = 1000;
	public static int MAX_MODEL_ID_COUNT = 10000;

	public InstancedDrawInfo[] instances = addStructs(new InstancedDrawInfo[MAX_INSTANCES], InstancedDrawInfo::new);
	public Property[] modelId = addPropertyArray(PropertyType.Int, "modelId", MAX_MODEL_ID_COUNT);

	public UBOInstanced() {
		super(GL_DYNAMIC_DRAW);
	}

	public static class InstancedDrawInfo extends UniformStructuredBuffer.StructProperty {
		public Property offset = addProperty(PropertyType.Int, "offset");
		public Property count = addProperty(PropertyType.Int, "count");
	}
}
