package rs117.hd.opengl;

import com.google.inject.Singleton;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import javax.inject.Inject;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;

@Singleton
public class RenderThread extends Thread {
	private static RenderThread instance;

	public static boolean isRenderThread() { return Thread.currentThread() == instance; }

	@Inject
	private FrameTimer frameTimer;

	private boolean running = true;

	// Non-locking circular buffer
	private final int bufferSize = 8192;
	private final int unparkThreshold = bufferSize / 8;
	private final DrawCommand[] drawBuffers = new DrawCommand[bufferSize];  // Assume a fixed-size buffer
	private final List<DrawCommand> clientThreadCommands = new ArrayList<>();
	private volatile int writeIndex = 0, readIndex = 0;
	private volatile boolean isParked = false;

	private AtomicBoolean waitingOnCompletion = new AtomicBoolean(false);
	private final Semaphore waitingOnCompletionSema = new Semaphore(0);

	private Map<Class, CommandPool> pools = new HashMap<>();

	public  RenderThread() {
		setName("RenderThread");
		setPriority(Thread.MAX_PRIORITY);
		instance = this;
	}

	public final void startUp() {
		running = true;
		start();
	}

	public final void shutDown() {
		running = false;
		interrupt();
	}

	private int getPendingCommandCount() {
		return (writeIndex - readIndex + bufferSize) % bufferSize;
	}

	@Override
	public final void run() {
		while (running) {
			do {
				int localReadIndex = readIndex;
				while (localReadIndex != writeIndex) {
					DrawCommand cmd = drawBuffers[localReadIndex];
					if (cmd != null) {
						try {
							cmd.process();
						} catch (Exception e) {
							System.err.println(e);
						} finally {
							cmd.poolOwner.commands_rt.add(cmd);
							drawBuffers[localReadIndex] = null;
						}
					}
					localReadIndex = (localReadIndex + 1) % drawBuffers.length;
				}

				readIndex = localReadIndex;  // Update the read index after processing
			} while (writeIndex != readIndex);

			if(waitingOnCompletion.get()) {
				waitingOnCompletionSema.release();
				waitingOnCompletion.set(false);
			}

			// Park when there is no more work to be done
			if (getPendingCommandCount() <= 0) {
				isParked = true;
				LockSupport.park();
				isParked = false;
			}
		}
	}

	private CommandPool getPool(Class CommandClass) {
		CommandPool pool = pools.get(CommandClass);
		if(pool == null) {
			// Create a new pool
			pool = new CommandPool();
			pool.CommandConstructor = CommandClass.getDeclaredConstructors()[0];
			pool.CommandConstructor.setAccessible(true);
			pools.put(CommandClass, pool);
		}
		return pool;
	}

	public final <T extends DrawCommand> T popCommand(Class<T> CommandClass) {
		return popCommand(CommandClass, null);
	}

	public final <T extends DrawCommand> T popCommand(Class<T> CommandClass, Object owner) {
		CommandPool pool = getPool(CommandClass);
		if(!pool.commands.isEmpty()) {
			final int last = pool.commands.size() - 1;
			T result = (T) pool.commands.get(last);
			pool.commands.remove(last);
			return result;
		}

		try {
			DrawCommand Result = (DrawCommand) (owner != null ? pool.CommandConstructor.newInstance(owner) : pool.CommandConstructor.newInstance());
			Result.poolOwner = pool;
			return (T) Result;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private <T extends DrawCommand> void pushCommand(T command) {
		if(running) {
			if(command.canRunOnRenderThread()) {
				frameTimer.begin(Timer.RENDER_THREAD_PUSH);

				final int localWriteIndex = writeIndex;
				final int nextWriteIndex = (localWriteIndex + 1) % bufferSize;

				while (nextWriteIndex == readIndex) {
					// Buffer is full, wait or yield to the render thread
					Thread.yield();
				}

				drawBuffers[localWriteIndex] = command;
				writeIndex = nextWriteIndex;

				// Only unpark thread after we've queued enough to saturate it with work
				// This is to avoid parking & unparking frequently
				if (isParked && getPendingCommandCount() > unparkThreshold) {
					LockSupport.unpark(this);
				}
				frameTimer.end(Timer.RENDER_THREAD_PUSH);
			}else {
				clientThreadCommands.add(command);
			}
		} else {
			command.process();
			command.poolOwner.commands.add(command);
		}
	}

	public final boolean complete() {
		if(!running){
			return false;
		}

		frameTimer.begin(Timer.RENDER_THREAD_COMPLETE);
		if(getPendingCommandCount() > 0) {
			try {
				waitingOnCompletion.set(true);
				LockSupport.unpark(this);
				waitingOnCompletionSema.acquire();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}

		//Execute commands which can run on the RenderThread
		for(DrawCommand cmd : clientThreadCommands) {
			try {
				cmd.process();
			} catch (Exception e) {
				System.err.println(e);
			} finally {
				cmd.poolOwner.commands_rt.add(cmd);
			}
		}
		clientThreadCommands.clear();

		for(CommandPool pool : pools.values()) {
			pool.flip();
		}

		frameTimer.end(Timer.RENDER_THREAD_COMPLETE);

		return true;
	}

	public static abstract class DrawCommand {
		protected CommandPool poolOwner;

		public boolean canRunOnRenderThread() { return true; }
		public final void push() { instance.pushCommand(this); }
		public abstract void process();
	}

	static class CommandPool {
		public Constructor CommandConstructor;

		public List<DrawCommand> commands_rt = new ArrayList<DrawCommand>();
		public List<DrawCommand> commands = new ArrayList<DrawCommand>();

		public void flip() {
			List<DrawCommand> temp = commands_rt;
			commands_rt = commands;
			commands = temp;
		}
	}
}
