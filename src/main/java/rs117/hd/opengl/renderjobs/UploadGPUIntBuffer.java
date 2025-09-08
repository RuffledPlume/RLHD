package rs117.hd.opengl.renderjobs;

import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.AWTContextWrapper;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.ObjectPool;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

public class UploadGPUIntBuffer extends RenderJob {
	private static final ObjectPool<UploadGPUIntBuffer> POOL = new ObjectPool<>(UploadGPUIntBuffer::new);

	private GLBuffer glBuffer;
	private GpuIntBuffer buffer;

	public UploadGPUIntBuffer() {super(POOL);}

	@Override
	protected void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext) {
		buffer.flip();
		glBuffer.upload(buffer);
		buffer.clear();
	}

	public static void submit(GLBuffer glBuffer, GpuIntBuffer buffer) {
		UploadGPUIntBuffer job = POOL.pop();
		job.glBuffer = glBuffer;
		job.buffer = buffer;
		job.submit(SUBMIT_SERIAL);
	}
}
