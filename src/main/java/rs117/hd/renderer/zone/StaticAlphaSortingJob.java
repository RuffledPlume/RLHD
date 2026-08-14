package rs117.hd.renderer.zone;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.Zone.AlphaModel;
import rs117.hd.utils.Camera;
import rs117.hd.utils.jobs.Job;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.*;

@RequiredArgsConstructor
public final class StaticAlphaSortingJob extends Job {
	private FrameTimer frameTimer;

	private AlphaModel[] models = new AlphaModel[16];
	private int[] sortedAlphaIndicies;
	private int size = 0;

	private int yaw;
	private int yawSin;
	private int yawCos;
	private int pitch;
	private int pitchSin;
	private int pitchCos;

	public void addAlphaModel(AlphaModel m) {
		if (size == models.length) {
			final int newCapacity = ceilPow2(models.length * 2);
			models = Arrays.copyOf(models, newCapacity);
		}

		m.asyncSortIdx = size;
		models[size] = m;
		size++;
	}

	public void queue(Camera camera, int[] sortedAlphaIndicies) {
		if (frameTimer == null)
			frameTimer = getInjector().getInstance(FrameTimer.class);
		this.sortedAlphaIndicies = sortedAlphaIndicies;
		yaw = camera.getFixedYaw();
		yawSin = SINE14[yaw];
		yawCos = COSINE14[yaw];
		pitch = camera.getFixedPitch();
		pitchSin = SINE14[pitch];
		pitchCos = COSINE14[pitch];
		queue();
	}

	public void reset() {
		size = 0;
	}

	@Override
	protected void onRun() {
		long start = System.nanoTime();
		try (FacePrioritySorter sorter = FacePrioritySorter.POOL.acquire()) {
			for (int i = 0; i < size; i++) {
				final AlphaModel m = models[i];
				m.sortedIndiciesCount = 0;
				sorter.sortStaticModelFacesByDistance(sortedAlphaIndicies, m, yawCos, yawSin, pitchCos, pitchSin);
				m.setSorted();
			}
		}
		frameTimer.add(Timer.STATIC_ALPHA_SORT, System.nanoTime() - start);
	}
}
