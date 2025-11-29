package rs117.hd.renderer.zone;

import java.util.concurrent.LinkedBlockingDeque;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.opengl.uniforms.UBOWorldViews.WorldViewStruct;
import rs117.hd.utils.jobs.JobGroup;
import rs117.hd.utils.jobs.JobHandle;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.renderer.zone.SceneManager.NUM_ZONES;

@Slf4j
public class WorldViewContext {
	@RequiredArgsConstructor
	public static class SwapZone {
		final int x, z;
		final Zone zone;
	}

	final int worldViewId;
	final int sizeX, sizeZ;
	@Nullable
	WorldViewStruct uboWorldViewStruct;
	ZoneSceneContext sceneContext;
	Zone[][] zones;
	VBO vboM;
	LinkedBlockingDeque<SwapZone> pendingSwap = new LinkedBlockingDeque<>();
	LinkedBlockingDeque<Zone> pendingCull = new LinkedBlockingDeque<>();
	JobHandle lightJobHandle;
	JobHandle procGenHandle;
	JobGroup sceneLoadGroup;
	JobGroup streamingGroup;
	boolean isLoading = true;

	WorldViewContext(@Nullable WorldView worldView, @Nullable ZoneSceneContext sceneContext, UBOWorldViews uboWorldViews) {
		this.worldViewId = worldView == null ? -1 : worldView.getId();
		this.sceneContext = sceneContext;
		this.sizeX = worldView == null ? NUM_ZONES : worldView.getSizeX() >> 3;
		this.sizeZ = worldView == null ? NUM_ZONES : worldView.getSizeY() >> 3;
		if (worldView != null)
			uboWorldViewStruct = uboWorldViews.acquire(worldView);
		zones = new Zone[sizeX][sizeZ];
		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z] = new Zone();
	}

	void initMetadata() {
		if (vboM != null || uboWorldViewStruct == null)
			return;

		vboM = new VBO(VAO.METADATA_SIZE);
		vboM.initialize(GL_STATIC_DRAW);
		vboM.map();
		vboM.vb.put(uboWorldViewStruct.worldViewIdx + 1);
		vboM.unmap();
	}

	boolean update() {
		if(isLoading)
			return false;

		Zone cullZone;
		while((cullZone = pendingCull.poll()) != null)
			cullZone.free();

		boolean queuedWork = false;
		for(int x = 0; x < sizeX; x++) {
			for(int z = 0; z < sizeZ; z++) {
				if(zones[x][z].rebuild) {
					zones[x][z].rebuild = false;
					invalidateZone(x, z);
					queuedWork = true;
				}
			}
		}

		SwapZone swapZone;
		while((swapZone = pendingSwap.poll()) != null) {
			Zone curZone = zones[swapZone.x][swapZone.z];
			if(curZone != swapZone.zone) {
				curZone.free();
				zones[swapZone.x][swapZone.z] = swapZone.zone;
			}
		}

		return queuedWork;
	}

	void free(boolean block) {
		if(sceneLoadGroup != null)
			sceneLoadGroup.cancel(block);
		sceneLoadGroup = null;

		if(streamingGroup != null)
			streamingGroup.cancel(block);
		streamingGroup = null;

		if (sceneContext != null)
			sceneContext.destroy();
		sceneContext = null;

		if (uboWorldViewStruct != null)
			uboWorldViewStruct.free();
		uboWorldViewStruct = null;

		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z].free();

		Zone cullZone;
		while((cullZone = pendingCull.poll()) != null)
			cullZone.free();

		SwapZone swapScene;
		while((swapScene = pendingSwap.poll()) != null) {
			zones[swapScene.x][swapScene.z].free();
			zones[swapScene.x][swapScene.z] = swapScene.zone;
		}

		if (vboM != null)
			vboM.destroy();
		vboM = null;

		isLoading = true;
	}

	void invalidate() {
		log.debug("invalidate all zones for worldViewId: [{}]", worldViewId);
		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				invalidateZone(x, z);
	}

	void invalidateZone(int zx, int zz) {
		Zone curZone = zones[zx][zz];
		if(curZone.zoneUploadHandle != null)
			curZone.zoneUploadHandle.cancel(false);

		Zone newZone = new Zone();
		newZone.dirty = zones[zx][zz].dirty;

		curZone.zoneUploadHandle = ZoneUploadTask
			.build(this, sceneContext, newZone, zx, zz, true)
			.queue(streamingGroup, procGenHandle);
	}
}
