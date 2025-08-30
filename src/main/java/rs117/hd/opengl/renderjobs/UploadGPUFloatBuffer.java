package rs117.hd.opengl.renderjobs;

import net.runelite.rlawt.AWTContext;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.AWTContextWrapper;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GpuFloatBuffer;

public class UploadGPUFloatBuffer extends RenderJob {
	private static final JobPool<UploadGPUFloatBuffer> POOL = new JobPool<>(UploadGPUFloatBuffer::new);

	private GLBuffer glBuffer;
	private GpuFloatBuffer buffer;

	public UploadGPUFloatBuffer() {super(POOL);}

	@Override
	protected void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext) {
		glBuffer.upload(buffer);
	}

	public static void submit(GLBuffer glBuffer, GpuFloatBuffer buffer) {
		UploadGPUFloatBuffer job = POOL.pop();
		job.glBuffer = glBuffer;
		job.buffer = buffer;
		job.submit(SUBMIT_SERIAL);
	}
}
