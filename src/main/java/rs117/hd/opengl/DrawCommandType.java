package rs117.hd.opengl;

import java.lang.reflect.Constructor;
import rs117.hd.HdPlugin;

public enum DrawCommandType {
	DrawTile(HdPlugin.DrawTileCommand.class),
	DrawTilePaint(HdPlugin.DrawTilePaintCommand.class),
	DrawModel(HdPlugin.DrawModelCommand.class);

	private final Class<? extends RenderThread.DrawCommand> commandClazz;
	private final int poolSize = RenderThread.bufferSize * 2;
	private RenderThread.DrawCommand[] pool = null;
	private volatile int readIndex = 1, writeIndex = 0;

	DrawCommandType(Class<? extends RenderThread.DrawCommand> commandClazz) {
		this.commandClazz = commandClazz;
	}

	private void spawnPool(Object owner) {
		if(pool != null) {
			return;
		}

		pool = new RenderThread.DrawCommand[poolSize];

		try {
			Constructor<? extends RenderThread.DrawCommand> CommandConstructor = (Constructor<? extends RenderThread.DrawCommand>) commandClazz.getDeclaredConstructors()[0];
			for(int i = 0; i < poolSize; i++) {
				final RenderThread.DrawCommand result = (owner != null ? CommandConstructor.newInstance(owner) : CommandConstructor.newInstance());
				result.type = this;
				pool[i] = result;
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public final <T extends RenderThread.DrawCommand> T get() {
		// Check if the buffer is empty (no commands to get)
		if (readIndex == writeIndex) {
			throw new IllegalStateException("Buffer is full, cannot release command.");
		}

		// Retrieve the command at the current read index
		@SuppressWarnings("unchecked")
		T command = (T) pool[readIndex];
		pool[readIndex] = null;

		// Advance the read index in a circular manner
		readIndex = (readIndex + 1) % poolSize;

		return command;
	}

	public final void release(RenderThread.DrawCommand command) {
		// Check if the buffer is full (cannot release more commands)
		int nextWriteIndex = (writeIndex + 1) % poolSize;
		if (nextWriteIndex == readIndex) {
			throw new IllegalStateException("Buffer is full, cannot release command.");
		}

		// Add the command back to the pool at the current write index
		pool[writeIndex] = command;

		// Advance the write index in a circular manner
		writeIndex = nextWriteIndex;
	}

	public static void spawnAllPools(Object owner) {
		DrawTile.spawnPool(owner);
		DrawTilePaint.spawnPool(owner);
		DrawModel.spawnPool(owner);
	}
}
