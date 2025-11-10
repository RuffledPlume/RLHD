package rs117.hd.scene;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.renderer.zone.SceneUploader;
import rs117.hd.renderer.zone.SlicedVBO;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.ObjectPool;

@Singleton
@Slf4j
public class DynamicObjectManager {
	private static final ObjectPool<GPUModelData> MODEL_DATA_POOL = new ObjectPool<>(GPUModelData::new, 100);

	public static class GPUModelData {
		public int id;
		public int frame;
		public SlicedVBO.Slice opaqueSlice;
		public SlicedVBO.Slice alphaSlice;
	}


	@Inject
	private SceneUploader uploader;

	private final List<GPUModelData> cache = new ArrayList<>();
	private SlicedVBO opaqueVBO;
	private SlicedVBO alphaVBO;

	public void initialise(SlicedVBO opaqueVBO, SlicedVBO alphaVBO) {
		this.opaqueVBO = opaqueVBO;
		this.alphaVBO = alphaVBO;
	}

	private GPUModelData findModelData(int id, int frame) {
		for(GPUModelData model : cache) {
			if(model.id == id && model.frame == frame) {
				return model;
			}
		}

		GPUModelData model = MODEL_DATA_POOL.obtain();
		model.id = id;
		model.frame = frame;
		cache.add(model);
		return model;
	}

	public GPUModelData getOrUploadModel(Renderable renderable, Model model, ModelOverride modelOverride) {
		if(!(renderable instanceof DynamicObject)) {
			return null;
		}

		DynamicObject dynamicObject = (DynamicObject) renderable;
		if(dynamicObject.getModelZbuf() != null) {
			return null;
		}

		Animation anim = dynamicObject.getAnimation();
		if(anim == null) {
			return null;
		}

		GPUModelData modelData = findModelData(anim.getId(), dynamicObject.getAnimFrame());
		if(modelData.opaqueSlice == null && modelData.alphaSlice == null) {
			uploader.uploadTempModel(model, modelOverride, 0, 0, 0, 0, 0, 0, opaqueVBO.getStagingBuffer(), alphaVBO.getStagingBuffer());

			modelData.opaqueSlice = opaqueVBO.uploadStagingBuffer();
			modelData.alphaSlice = alphaVBO.uploadStagingBuffer();
		}

		return modelData;
	}
}
