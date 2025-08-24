package rs117.hd.data;

import java.util.Arrays;
import java.util.concurrent.Semaphore;
import lombok.SneakyThrows;

import static net.runelite.api.Constants.*;

public final class SceneDrawOrder {
	public static final int DRAW_TYPE_SCENE_PAINT = 0;
	public static final int DRAW_TYPE_TILE_MODEL = 1;
	public static final int DRAW_TYPE_STATIC_RENDERABLE = 2;
	public static final int DRAW_TYPE_DYNAMIC_RENDERABLE = 3;

	private int[] drawType = new int[MAX_Z * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE];
	private int[] drawOrder = new int[MAX_Z * EXTENDED_SCENE_SIZE * EXTENDED_SCENE_SIZE];
	private int size = 0;
	private final Semaphore semaphore = new Semaphore(0);

	private void add(int type, int index) {
		drawType[size] = type;
		drawOrder[size] = index;

		size++;

		if(size >= drawType.length) {
			drawType = Arrays.copyOf(drawType, size * 2);
			drawOrder = Arrays.copyOf(drawOrder, size * 2);
		}
	}

	public void addScenePaint(int tileIdx) { add(DRAW_TYPE_SCENE_PAINT, tileIdx); }

	public void addSceneTileModel(int tileIdx) { add(DRAW_TYPE_TILE_MODEL, tileIdx); }

	public void addStaticRenderable(int tileIdx, int renderableInstance) { add(DRAW_TYPE_STATIC_RENDERABLE, (tileIdx & 0xFFFF) | renderableInstance << 16); }

	public void addDynamicRenderable(int renderableIdx) { add(DRAW_TYPE_DYNAMIC_RENDERABLE, renderableIdx); }

	public int getDrawOrderSize() {
		return size;
	}

	public int getDrawType(int idx) {
		return drawType[idx];
	}

	public int getDrawOrder(int idx) {
		return drawOrder[idx];
	}

	public void signal() {
		semaphore.release(); // TODO: Maybe this should be Lock/Unlock
	}

	@SneakyThrows
	public void waitForSignal() {
		semaphore.acquire(); // TODO: Maybe this should be Lock/Unlock
	}

	public void reset() {
		size = 0;
	}
}
