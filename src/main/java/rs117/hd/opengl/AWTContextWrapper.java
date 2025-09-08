package rs117.hd.opengl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.data.SceneDrawContext;
import rs117.hd.opengl.renderjobs.DrawFence;
import rs117.hd.opengl.renderjobs.RenderJob;
import rs117.hd.scene.SceneContext;
import rs117.hd.utils.ObjectPool;

@Slf4j
public class AWTContextWrapper {
	private static final ObjectPool<ChangeAWTOwnership> POOL = new ObjectPool<>(ChangeAWTOwnership::new);

	public enum Owner { Client, None, RenderThread }

	@Getter
	private final AWTContext context;
	private Owner currentOwner = Owner.Client;

	public AWTContextWrapper(AWTContext context) {
		this.context = context;
	}

	public void destroy() {
		if(isRenderThreadOwner()) {
			setOwnership(Owner.Client);
		}
		context.destroy();
	}

	public int getBackBuffer() {
		if(context != null) {
			return context.getFramebuffer(false);
		}
		return 0;
	}

	public boolean isOwner(Owner owner) { return currentOwner == owner;}

	public boolean isClientOwner() {return isOwner(Owner.Client);}
	public boolean isRenderThreadOwner() {return isOwner(Owner.RenderThread);}

	private void setOwnership(Owner newOwner) {
		if(currentOwner != newOwner) {
			//log.debug("AWTContextWrapper - {} -> {}", currentOwner, newOwner);
			currentOwner = newOwner;
		}
	}

	public void queueOwnershipChange(Owner newOwner) {
		ChangeAWTOwnership job = POOL.pop();
		job.newOwner = newOwner;

		// TODO: Revisit, this could cause unnecessary blocking on the ClientThread
		if(newOwner == Owner.Client) {
			// We're taking ownership from the RenderThread
			if(isRenderThreadOwner() || ChangeAWTOwnership.PENDING_CHANGES > 0) {
				ChangeAWTOwnership.PENDING_CHANGES++;
				// Block until RenderThread has Released ownership of the AWTContext
				job.submit(RenderJob.SUBMIT_SERIAL).complete();
			}

			assert currentOwner == Owner.None;
			context.makeCurrent();
			setOwnership(Owner.Client);
		} else {
			if(!isOwner(Owner.Client) && ChangeAWTOwnership.PENDING_CHANGES > 0) {
				DrawFence.addToQueue(); // Only Fence if we have changed queued, we need to wait until we know if client has taken ownership
			}

			if(isOwner(Owner.Client)) {
				context.detachCurrent();
				setOwnership(Owner.None);
			}

			ChangeAWTOwnership.PENDING_CHANGES++;
			job.submit(RenderJob.SUBMIT_SERIAL);
		}
	}

	private static class ChangeAWTOwnership extends RenderJob {
		protected static int PENDING_CHANGES = 0;
		protected Owner newOwner;

		public ChangeAWTOwnership() {super(POOL);}

		@Override
		protected void doRenderWork(AWTContextWrapper awtContextWrapper, SceneDrawContext drawContext, SceneContext sceneContext) {
			ChangeAWTOwnership.PENDING_CHANGES--;

			if (newOwner == Owner.RenderThread) {

				// Ownership can only transfer when no one has it
				if(awtContextWrapper.currentOwner == Owner.None) {
					awtContextWrapper.context.makeCurrent();
					awtContextWrapper.setOwnership(Owner.RenderThread);

					if (HdPlugin.GL_SERIAL_THREAD_CAPS == null) {
						HdPlugin.GL_SERIAL_THREAD_CAPS = GL.createCapabilities();
					}
				}
			} else {
				awtContextWrapper.context.detachCurrent();
				awtContextWrapper.setOwnership(Owner.None);
			}
		}
	}
}
