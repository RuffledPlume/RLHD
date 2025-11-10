package rs117.hd.scene;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.SliceAllocator;

@Slf4j
@Singleton
public class AnimatedObjectManager {
	private static class AnimationFrame extends SliceAllocator.Slice {
		public AnimationFrame(int offset, int size) {
			super(offset, size);
		}

		@Override
		protected void allocate() {}

		@Override
		protected void onFreed() { ; }
	}

	private static class AnimatedObject {
		public int referenceCount;
		public int id;
		public boolean needsUpload = true;

		public final List<AnimationFrame> frames = new ArrayList<>();
	}

	private final List<AnimatedObject> animatedObjects = new ArrayList<>(); // TODO: Make this a LRU Cache
	private final SliceAllocator<AnimationFrame> allocator = new SliceAllocator<>(AnimationFrame::new, 1, 100, false);

	private AnimatedObject getAnimatedObject(int id) {
		// TODO: replace with map instead of linear search, or maybe sorted & binary search
		for (AnimatedObject animatedObject : animatedObjects) {
			if (animatedObject.id == id) {
				return animatedObject;
			}
		}

		return null;
	}

	public void defrag() { allocator.defrag(); }
}
