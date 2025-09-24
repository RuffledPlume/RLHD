package rs117.hd.scene;

import java.util.ArrayDeque;
import java.util.HashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.data.GPUModel;

@Slf4j
@Singleton
public class ModelManager {

	@Inject
	private SceneUploader sceneUploader;

	private HashMap<Long, GPUModel> modelCache = new HashMap<>();
	private ArrayDeque<GPUModel> unusedModels = new ArrayDeque<>();

	public GPUModel getOrUploadModel(Model model) {
		// Obtain the model hash which should be stored in the Buffer & UV Buffer Offset
		long modelHash = ((long)model.getBufferOffset()) | (((long)model.getUvBufferOffset()) << 32);

		if(modelCache.containsKey(modelHash)) {
			return modelCache.get(modelHash);
		}

		// Model hasn't been cached yet, we need to upload it and add it to the cache
		// TODO: This needs to work with model overrides?
		// TODO: Dynamic Models need to go via a different route?

		sceneUploader.upload();

		return null;
	}

	public void freeGPUModel(GPUModel model) {
		if(modelCache.containsKey(model.hash)) {
			modelCache.remove(model.hash);
			unusedModels.add(model);
		}
	}
}
