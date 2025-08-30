package rs117.hd.data;

import lombok.RequiredArgsConstructor;
import rs117.hd.opengl.ModelDrawBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;

@RequiredArgsConstructor
public class SceneDrawContext {

	public final ModelDrawBuffer sceneDrawBuffer = new ModelDrawBuffer("Scene");
	public final ModelDrawBuffer directionalDrawBuffer = new ModelDrawBuffer("Directional Shadow");

	public final ModelSortingBuffers modelSortingBuffers;

	public final SharedGLBuffer hStagingBufferVertices;
	public final SharedGLBuffer hStagingBufferUvs;
	public final SharedGLBuffer hStagingBufferNormals;

	public final SharedGLBuffer hRenderBufferVertices;
	public final SharedGLBuffer hRenderBufferUvs;
	public final SharedGLBuffer hRenderBufferNormals;

	public final SceneDrawOrder sceneDrawOrder = new SceneDrawOrder();

	// TODO: Move back to HDPlugin.java
	public final SharedGLBuffer hModelPassthroughBuffer = new SharedGLBuffer(
		"Model Passthrough", GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);

	public GpuIntBuffer modelPassthroughBuffer;

	public int dynamicOffsetVertices;
	public int dynamicOffsetUvs;

	public int renderBufferOffset;
	public int numPassthroughModels;
	public int tileCount;

	public void initialise() {
		modelPassthroughBuffer = new GpuIntBuffer();

		hModelPassthroughBuffer.initialize();

		sceneDrawBuffer.initialize();
		directionalDrawBuffer.initialize();
	}

	public void destroy() {
		if (modelPassthroughBuffer != null)
			modelPassthroughBuffer.destroy();
		modelPassthroughBuffer = null;

		hModelPassthroughBuffer.destroy();

		sceneDrawBuffer.destroy();
		directionalDrawBuffer.destroy();
	}

	public void reset() {
		sceneDrawOrder.reset();

		sceneDrawBuffer.clear();
		directionalDrawBuffer.clear();

		renderBufferOffset = 0;
		numPassthroughModels = 0;
		tileCount = 0;
	}
}
