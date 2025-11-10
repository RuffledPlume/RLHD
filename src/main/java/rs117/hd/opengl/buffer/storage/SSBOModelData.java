package rs117.hd.opengl.buffer.storage;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import net.runelite.api.*;
import rs117.hd.opengl.buffer.ShaderStructuredBuffer;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.SliceAllocator;


@Singleton
public class SSBOModelData extends ShaderStructuredBuffer {

	@RequiredArgsConstructor
	public static class ModelData extends StructProperty {
		public final int modelOffset;
		public final Property position = addProperty(PropertyType.IVec3, "position");
		public final Property height = addProperty(PropertyType.Int, "height");
		public final Property flags = addProperty(PropertyType.Int, "flags");

		public void set(Renderable renderable, Model model, ModelOverride override, int x, int y, int z) {
			position.set(x, y, z);
			height.set(model.getModelHeight());
			flags.set(((override.windDisplacementModifier + 3) & 0x7) << 12
					  | (override.windDisplacementMode.ordinal() & 0x7) << 9
					  | (override.invertDisplacementStrength ? 1 : 0) << 8);
		}
	}

	private final List<ModelData> modelDataProperties = new ArrayList<>();
	private final SliceAllocator<Slice> allocator = new SliceAllocator<>(Slice::new, 1, 100, false);

	public Slice obtainSlice(int size) {
		Slice slice = allocator.allocate(size);
		upload();
		return slice;
	}

	public void defrag() { allocator.defrag(); }

	public class Slice extends SliceAllocator.Slice {
		private int modelCount;

		public Slice(int offset, int size) {
			super(offset, size);
		}

		@Override
		protected void allocate() {
			while (modelDataProperties.size() < offset + size)
				modelDataProperties.add(addStruct(new ModelData(modelDataProperties.size())));
		}

		@Override
		protected void onFreed() { modelCount = 0; }

		public boolean hasSpace() { return modelCount < size; }

		public ModelData add() {
			if(!hasSpace()) {
				return null;
			}
			return modelDataProperties.get(offset + modelCount++);
		}
	}
}
