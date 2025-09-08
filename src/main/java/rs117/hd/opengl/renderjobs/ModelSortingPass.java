package rs117.hd.opengl.renderjobs;

import java.util.List;
import org.lwjgl.opengl.*;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.AWTContextWrapper;
import rs117.hd.opengl.shader.ModelSortingComputeProgram;
import rs117.hd.opengl.shader.ShaderProgram;
import rs117.hd.opengl.uniforms.UBOCompute;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.ObjectPool;

import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static rs117.hd.HdPlugin.checkGLErrors;

public class ModelSortingPass extends RenderJob {
	private static final ObjectPool<ModelSortingPass> POOL = new ObjectPool<>(ModelSortingPass::new);

	private UBOCompute uboCompute;
	private ShaderProgram modelPassthroughComputeProgram;
	private List<ModelSortingComputeProgram> modelSortingComputePrograms;

	public ModelSortingPass() {super(POOL);}

	@Override
	protected void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext) {
		// Compute is split into a passthrough shader for unsorted models,
		// and multiple sizes of sorting shaders to better utilize the GPU

		// Bind shared buffers
		glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, drawContext.hStagingBufferVertices.id);
		glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, drawContext.hStagingBufferUvs.id);
		glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, drawContext.hStagingBufferNormals.id);
		glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, drawContext.hRenderBufferVertices.id);
		glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 5, drawContext.hRenderBufferUvs.id);
		glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 6, drawContext.hRenderBufferNormals.id);

		uboCompute.upload();

		// unordered
		modelPassthroughComputeProgram.use();
		glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, drawContext.hModelPassthroughBuffer.id);
		GL43C.glDispatchCompute(drawContext.numPassthroughModels, 1, 1);

		for (int i = 0; i < drawContext.modelSortingBuffers.numModelsToSort.length; i++) {
			if (drawContext.modelSortingBuffers.numModelsToSort[i] == 0)
				continue;

			modelSortingComputePrograms.get(i).use();
			glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, drawContext.modelSortingBuffers.hModelSortingBuffers[i].id);
			GL43C.glDispatchCompute(drawContext.modelSortingBuffers.numModelsToSort[i], 1, 1);
		}

		GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);

		checkGLErrors();
	}

	public static void addToQueue(UBOCompute uboCompute, ShaderProgram modelPassthroughComputeProgram, List<ModelSortingComputeProgram> modelSortingComputePrograms) {
		ModelSortingPass job = POOL.pop();
		job.uboCompute = uboCompute;
		job.modelPassthroughComputeProgram = modelPassthroughComputeProgram;
		job.modelSortingComputePrograms = modelSortingComputePrograms;
		job.submit(SUBMIT_SERIAL);
	}
}
