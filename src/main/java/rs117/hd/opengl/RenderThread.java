package rs117.hd.opengl;

import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;

@Singleton
public final class RenderThread extends Thread {
	private static final Logger log = LoggerFactory.getLogger(RenderThread.class);
	private static RenderThread instance;

	public static boolean isRenderThread() { return Thread.currentThread() == instance; }

	@Inject
	private FrameTimer frameTimer;

	private boolean running = true;

	// Non-locking circular buffer
	public static final int bufferSize = 4096;
	private final DrawCommand[] drawBuffers = new DrawCommand[bufferSize];  // Assume a fixed-size buffer
	private volatile int writeIndex = 0, readIndex = 0;
	private AtomicBoolean isParked = new AtomicBoolean(false);
	private List<DrawCommand> waitingQueue = new ArrayList<>();

	private AtomicBoolean waitingOnCompletion = new AtomicBoolean(false);
	private final Semaphore waitingOnCompletionSema = new Semaphore(0);

	public  RenderThread() {
		setName("RenderThread");
		setPriority(Thread.MAX_PRIORITY);
		instance = this;
		start();
	}

	public void startUp() {
		running = true;
	}

	public void shutDown() {
		complete();
		running = false;
	}

	private int getPendingCommandCount() {
		return (writeIndex - readIndex + bufferSize) % bufferSize;
	}

	@Override
	public void run() {
		while (true) {
			do {
				int localReadIndex = readIndex;
				while (localReadIndex != writeIndex) {
					final DrawCommand cmd = drawBuffers[localReadIndex];
					drawBuffers[localReadIndex] = null;
					if (cmd != null) {
						try {
							cmd.process();
						} catch (Exception e) {
							log.error("e: ", e);
						} finally {
							cmd.finished();
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

			// Finally check if there are still no commands to be proccessed before parking
			if (getPendingCommandCount() <= 0 ) {
				isParked.set(true);
				LockSupport.park();
				isParked.set(false);
			}
		}
	}

	private void writeCommand(DrawCommand command) {
		final int localWriteIndex = writeIndex;
		final int nextWriteIndex = (localWriteIndex + 1) % bufferSize;
		if(nextWriteIndex == readIndex) {
			LockSupport.unpark(this);
			do {
				// Buffer is full, wait or yield to the render thread
				Thread.onSpinWait();
			} while(nextWriteIndex == readIndex);
		}

		drawBuffers[localWriteIndex] = command;
		writeIndex = nextWriteIndex;
	}

	private void flushQueue(boolean force) {
		// Attempt to flush the waiting queue
		final int len = waitingQueue.size();
		for(int i = 0; i < len; i++) {
			// Grab from the front of the queue
			DrawCommand command = waitingQueue.get(0);
			PrepareResult result = command.prepare(force);
			if(result != PrepareResult.Skip) {
				if(running) {
					if(result == PrepareResult.Waiting) {
						// Since this Command cannot be queued onto the RenderThread until some work is done
						// Ensure that we've unparked to try and complete the pending work
						if(!waitingQueue.isEmpty() && isParked.get()) {
							LockSupport.unpark(this);
						}
						return; // We've encountered a command that isn't ready, stop flushing
					} else if(result == PrepareResult.Ready) {
						writeCommand(command);
					} else {
						command.finished();
					}
				} else {
					command.process();
					command.finished();
				}
			} else {
				command.finished();
			}
			waitingQueue.remove(0);
		}
	}

	public void queueCommand(DrawCommand command) {
		waitingQueue.add(command);
		flushQueue(false);
	}

	public boolean complete() {
		if(!running){
			return false;
		}

		frameTimer.begin(Timer.RENDER_THREAD_COMPLETE);

		// Make sure we complete any pending work
		flushQueue(true);

		if(getPendingCommandCount() > 0) {
			try {
				waitingOnCompletion.set(true);
				LockSupport.unpark(this);
				waitingOnCompletionSema.acquire();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}

		frameTimer.end(Timer.RENDER_THREAD_COMPLETE);

		return true;
	}

	public enum PrepareResult { Skip, Waiting, Ready};

	public static abstract class DrawCommand {
		public DrawCommandType type;

		public PrepareResult prepare(boolean forced) { return PrepareResult.Ready; }
		public abstract void process();
		public void finished() {
			type.release(this);
		}
	}
}
