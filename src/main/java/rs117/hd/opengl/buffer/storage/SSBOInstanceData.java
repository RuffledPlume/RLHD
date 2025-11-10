package rs117.hd.opengl.buffer.storage;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import rs117.hd.opengl.buffer.ShaderStructuredBuffer;
import rs117.hd.scene.DynamicObjectManager.GPUModelData;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ObjectPool;

public class SSBOInstanceData extends ShaderStructuredBuffer {
	public static final ObjectPool<ModelInstanceData> INSTANCE_DATA_POOL = new ObjectPool<>(ModelInstanceData::new, 100);
	public static class ModelInstanceData {
		public int position_X;
		public int position_Y;
		public int position_Z;
		public int height;
		public int flags;

		public void set(Renderable renderable, Model model, ModelOverride override, int x, int y, int z) {
			position_X = x;
			position_Y = y;
			position_Z = z;
			height = model.getModelHeight();
			flags = ((override.windDisplacementModifier + 3) & 0x7) << 12
					| (override.windDisplacementMode.ordinal() & 0x7) << 9
					| (override.invertDisplacementStrength ? 1 : 0) << 8;
		}
	}

	@RequiredArgsConstructor
	public static class InstanceData extends StructProperty {
		public Property offset = addProperty(PropertyType.Int, "offset");
		public Property count = addProperty(PropertyType.Int, "count");

		public GPUModelData modelData;
		public final List<ModelInstanceData> instances = new ArrayList<>();
	}

	private final List<InstanceData> instances = new ArrayList<>();
	private int instanceCount;

	public InstanceData obtainInstance(GPUModelData gpuModelData) {
		for(InstanceData instance : instances) { // TODO: Optimise :)
			if(instance.modelData == gpuModelData)
				return instance;
		}

		if(instanceCount >= instances.size()) {
			InstanceData newInstance = new InstanceData();
			instances.add(newInstance);
			instanceCount++;
			return newInstance;
		}

		return instances.get(instanceCount++);
	}

	public void reset() {
		for(InstanceData instance : instances) {
			instance.modelData = null;
		}
		instanceCount = 0;
	}
}
