package rs117.hd.opengl.renderjobs;

import rs117.hd.data.SceneDrawContext;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

public class UploadGPUIntBuffer extends RenderJob {
	private static final JobPool<UploadGPUIntBuffer> POOL = new JobPool<>(UploadGPUIntBuffer::new);

	private GLBuffer glBuffer;
	private GpuIntBuffer buffer;

	@Override
	protected void doRenderWork(SceneDrawContext drawContext, SceneContext sceneContext) {
		buffer.flip();
		glBuffer.upload(buffer);
		buffer.clear();

		POOL.push(this);
	}

	public static void submit(GLBuffer glBuffer, GpuIntBuffer buffer) {
		UploadGPUIntBuffer job = POOL.pop();
		job.glBuffer = glBuffer;
		job.buffer = buffer;
		job.submit(SUBMIT_SERIAL);
	}
}
