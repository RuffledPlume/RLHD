package rs117.hd.renderer.zone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;
import rs117.hd.opengl.shader.ShaderProgram;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.buffer.GLShaderStorage;
import rs117.hd.utils.collections.IntHashSet;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.SUPPORTS_INDIRECT_DRAW;
import static rs117.hd.HdPlugin.SUPPORTS_MULTI_INDIRECT_DRAW;
import static rs117.hd.renderer.zone.Zone.ZONE_VERTEX_NUM_BYTES;
import static rs117.hd.renderer.zone.ZoneRenderer.eboAlpha;
import static rs117.hd.renderer.zone.ZoneRenderer.indirectDrawCmdsStaging;

final class ZoneDrawContext {
	private static final int NONE = 0;
	private static final int STATIC = 1;
	private static final int TEMP = 2;
	private static final int STATIC_UNSORTED = 3;

	private static final int INTS_PER_VERTEX = ZONE_VERTEX_NUM_BYTES / Integer.BYTES;

	private final WorldViewContext viewContext;
	private final List<Batch> batches = new ArrayList<>();

	// Alpha draw state. Consecutive models sharing the same mode, VAO and storage buffers are drawn together.
	private final RangeList alphaRanges = new RangeList();
	private int alphaFaceCount;
	private int eboAlphaOffset;
	private int lastDrawMode;
	private int lastVao;
	@Nullable
	private GLShaderStorage lastTboF, lastTboM, lastTboZ;

	ZoneDrawContext(WorldViewContext viewContext) {
		this.viewContext = viewContext;
	}

	// ----------------------------------------------------------------------------------------------------
	// Geometry: level and roof ranges from the opaque vertex buffer, or the alpha one when `alpha` is set
	// ----------------------------------------------------------------------------------------------------

	void renderGeometry(
		Zone zone,
		CommandBuffer target,
		@Nullable ShaderProgram shader,
		@Nullable ZoneRoofFades.Fade fade,
		int flags
	) {
		renderGeometry(zone, target, shader, fade, flags, false);
	}

	void renderGeometry(
		Zone zone,
		CommandBuffer target,
		@Nullable ShaderProgram shader,
		@Nullable ZoneRoofFades.Fade fade,
		int flags,
		boolean alpha
	) {
		if (!canDrawGeometry(zone))
			return;

		final boolean fadingOnly = (flags & Zone.DRAW_FADING_ONLY) != 0;
		final int currentLevel = viewContext.level;
		final int fadedLevels = fade != null ? fade.levels : 0;
		final IntHashSet hidden = hiddenRoofs(fade, flags);
		final int firstLevel = fadingOnly ? 0 : viewContext.minLevel;
		final int lastLevel = fadingOnly ? 3 : maxLevel(flags);

		final int[] levelOffsets = alpha ? zone.alphaLevelOffsets : zone.levelOffsets;
		final int[][] roofStart = alpha ? zone.alphaRoofStart : zone.roofStart;
		final int[][] roofEnd = alpha ? zone.alphaRoofEnd : zone.roofEnd;

		for (int level = firstLevel; level <= lastLevel; ++level) {
			int[] rids = zone.rids[level];
			int[] levelRoofStart = roofStart[level];
			int[] levelRoofEnd = roofEnd[level];
			int start = alpha ? zone.alphaLevelStart(level) : zone.levelStart(level);
			int levelEnd = levelOffsets[level];

			// Levels that are fading out are only drawn by the fading pass
			if ((fadedLevels & (1 << level)) != 0) {
				if (fadingOnly)
					pushRange(zone, target, shader, alpha, start, levelEnd);
				continue;
			}

			// No roofs to hide, so the level is drawn as a single range
			if (!fadingOnly && (rids.length == 0 || hidden.isEmpty() || level <= currentLevel)) {
				pushRange(zone, target, shader, alpha, start, levelEnd);
				continue;
			}

			// Draw the roofs that are hidden (fading pass) or visible (normal pass)
			for (int roofIdx = 0; roofIdx < rids.length; ++roofIdx) {
				int rid = rids[roofIdx];
				if (rid > 0 && hidden.contains(rid) == fadingOnly && levelRoofEnd[roofIdx] > levelRoofStart[roofIdx])
					pushRange(zone, target, shader, alpha, levelRoofStart[roofIdx], levelRoofEnd[roofIdx]);
			}

			// Everything after the last roof isn't part of one, and is always drawn
			if (!fadingOnly)
				pushRange(zone, target, shader, alpha, endOfRoofs(rids, levelRoofEnd, start), levelEnd);
		}
	}

	void renderGeometryLevel(Zone zone, CommandBuffer target, @Nullable ShaderProgram shader, int level) {
		renderGeometryLevel(zone, target, shader, level, false);
	}

	void renderGeometryLevel(Zone zone, CommandBuffer target, @Nullable ShaderProgram shader, int level, boolean alpha) {
		if (!canDrawGeometry(zone))
			return;

		pushRange(
			zone, target, shader, alpha,
			alpha ? zone.alphaLevelStart(level) : zone.levelStart(level),
			(alpha ? zone.alphaLevelOffsets : zone.levelOffsets)[level]
		);
	}

	void renderAlphaShadows(CommandBuffer target, boolean includeRoofs, boolean rootScene) {
		for (Zone[] column : viewContext.zones) {
			for (Zone zone : column) {
				if (!zone.initialized || zone.viewContext != viewContext || rootScene && !zone.inShadowFrustum)
					continue;
				renderGeometry(zone, target, null, null, includeRoofs ? Zone.DRAW_INCLUDE_ROOFS : 0, true);
			}
		}
		flush(target);
	}

	private static boolean canDrawGeometry(Zone zone) {
		return zone.fadingAlpha <= 1.0 && zone.viewContext != null;
	}

	/** Returns the end of the last roof on a level, or the fallback if the level has none. */
	private static int endOfRoofs(int[] rids, int[] roofEnd, int fallback) {
		for (int i = rids.length - 1; i >= 0; --i)
			if (rids[i] > 0)
				return roofEnd[i];
		return fallback;
	}

	private int maxLevel(int flags) {
		return (flags & Zone.DRAW_INCLUDE_ROOFS) != 0 ? 3 : viewContext.maxLevel;
	}

	private IntHashSet hiddenRoofs(@Nullable ZoneRoofFades.Fade fade, int flags) {
		if ((flags & Zone.DRAW_INCLUDE_ROOFS) != 0)
			return Zone.EMPTY_ROOF;
		return fade != null && fade.roofs != null ? fade.roofs : viewContext.hideRoofIds;
	}

	private void pushRange(Zone zone, CommandBuffer target, @Nullable ShaderProgram shader, boolean alpha, int start, int end) {
		assert end >= start;
		if (end > start)
			batch(zone, target, shader, alpha).ranges.push(start, end);
	}

	// ----------------------------------------------------------------------------------------------------
	// Alpha
	// ----------------------------------------------------------------------------------------------------

	void renderAlpha(
		Zone zone,
		CommandBuffer target,
		int zx,
		int zz,
		int level,
		@Nullable ZoneRoofFades.Fade fade,
		int flags
	) {
		if (zone.alphaModels.isEmpty() || zone.fadingAlpha > 1.0 || zone.viewContext != viewContext)
			return;

		final boolean fadingOnly = (flags & Zone.DRAW_FADING_ONLY) != 0;
		final boolean depthOnly = (flags & Zone.DRAW_DEPTH_ONLY) != 0;
		final int minLevel = viewContext.minLevel;
		final int currentLevel = viewContext.level;
		final int maxLevel = maxLevel(flags);
		final int fadedLevels = fade != null ? fade.levels : 0;
		final IntHashSet hidden = hiddenRoofs(fade, flags);

		alphaRanges.clear();
		if (!depthOnly)
			zone.sortedAlphaFacesUpload.waitForCompletion();

		final int eboAlphaStart = ZoneRenderer.eboAlphaWriter.getWrittenInts();
		eboAlphaOffset = eboAlphaStart;
		for (Zone.AlphaModel model : zone.alphaModels) {
			if ((model.flags & Zone.AlphaModel.SKIP) != 0 || model.level != level || model.vao == -1)
				continue;

			if ((fadedLevels & (1 << level)) != 0) {
				if (!fadingOnly)
					continue;
			} else if (fadingOnly) {
				if (!hidden.contains(model.rid))
					continue;
			} else if (level < minLevel || level > maxLevel) {
				continue;
			} else if (level > currentLevel && hidden.contains(model.rid)) {
				continue;
			}

			int drawMode = model.isTemp() ? TEMP : depthOnly || model.asyncSortIdx < 0 ? STATIC_UNSORTED : STATIC;
			setAlphaState(target, drawMode, model);

			if (drawMode != STATIC) {
				alphaRanges.push(model.startpos, model.endpos);
				continue;
			}

			// Sorted models are drawn through the alpha EBO, so make sure this model's faces have been sorted
			if (model.needsSorting() && !zone.alphaSortingJob.forceProcessModelClient(model)) {
				while (model.needsSorting() && !zone.alphaSortingJob.isDone())
					zone.alphaSortingJob.waitForCompletion(10);
			}

			if (model.tempSortedFaces == null || model.sortedFacesLen <= 0)
				continue;

			zone.sortedAlphaFacesUpload.alphaModels.add(model);
			eboAlphaOffset += model.sortedFacesLen;
			alphaFaceCount += model.sortedFacesLen / 3;
		}

		if (eboAlphaOffset > eboAlphaStart && !zone.sortedAlphaFacesUpload.alphaModels.isEmpty()) {
			zone.sortedAlphaFacesUpload.eboAlphaView = ZoneRenderer.eboAlphaWriter.reserve(eboAlphaOffset - eboAlphaStart);
			zone.sortedAlphaFacesUpload.queue();
		}

		flushAlpha(target);
	}

	void renderFadingAlpha(CommandBuffer target, Zone zone, int zx, int zz, ZoneRoofFades.Fade fade) {
		for (int level = 0; level <= 3; ++level)
			renderAlpha(zone, target, zx, zz, level, fade, Zone.DRAW_FADING_ONLY);
	}

	/** Flushes pending alpha draws if the next model can't be drawn with the same state. */
	private void setAlphaState(CommandBuffer target, int drawMode, Zone.AlphaModel model) {
		GLShaderStorage tboZ = viewContext.sharedBuffers.tboZ;
		if (lastDrawMode == drawMode &&
			lastVao == model.vao &&
			lastTboF == model.tboF &&
			lastTboM == model.tboM &&
			lastTboZ == tboZ
		)
			return;

		flushAlpha(target);
		lastDrawMode = drawMode;
		lastVao = model.vao;
		lastTboF = model.tboF;
		lastTboM = model.tboM;
		lastTboZ = tboZ;
	}

	private void flushAlpha(CommandBuffer target) {
		if (lastDrawMode == STATIC) {
			if (alphaFaceCount > 0 && lastVao != 0) {
				int vertexCount = alphaFaceCount * 3;
				int firstIndex = eboAlphaOffset - vertexCount;
				target.BindVertexArray(lastVao, eboAlpha);
				bindShaderStorages(target, lastTboF, lastTboM, lastTboZ);
				if (SUPPORTS_INDIRECT_DRAW)
					target.DrawElementsIndirect(GL_TRIANGLES, vertexCount, firstIndex, indirectDrawCmdsStaging);
				else
					target.DrawElements(GL_TRIANGLES, vertexCount, Integer.BYTES * (long) firstIndex);
			}
			alphaFaceCount = 0;
		} else if (!alphaRanges.isEmpty()) {
			target.BindVertexArray(lastVao, null);
			bindShaderStorages(target, lastTboF, lastTboM, lastTboZ);
			alphaRanges.draw(target);
			alphaRanges.clear();
		}
	}

	// ----------------------------------------------------------------------------------------------------
	// Batching
	// ----------------------------------------------------------------------------------------------------

	void flush() {
		for (Batch batch : batches)
			batch.flush();
		batches.clear();
	}

	void flush(CommandBuffer target) {
		for (Iterator<Batch> it = batches.iterator(); it.hasNext();) {
			Batch batch = it.next();
			if (batch.target == target) {
				it.remove();
				batch.flush();
			}
		}
	}

	void reset() {
		batches.clear();
		alphaRanges.clear();
		alphaFaceCount = 0;
		lastDrawMode = NONE;
	}

	/** Returns the target's most recent batch if it can be extended, otherwise starts a new one. */
	private Batch batch(Zone zone, CommandBuffer target, @Nullable ShaderProgram shader, boolean alpha) {
		SharedZoneBuffers buffers = zone.viewContext.sharedBuffers;
		int vao = alpha ? buffers.glVaoA : buffers.glVao;

		for (int i = batches.size() - 1; i >= 0; --i) {
			Batch batch = batches.get(i);
			if (batch.target != target)
				continue;
			if (batch.shader == shader && batch.vao == vao && batch.buffers == buffers)
				return batch;
			break;
		}

		Batch batch = new Batch(target, shader, vao, buffers);
		batches.add(batch);
		return batch;
	}

	private static void bindShaderStorages(
		CommandBuffer target,
		@Nullable GLShaderStorage tboF,
		@Nullable GLShaderStorage tboM,
		@Nullable GLShaderStorage tboZ
	) {
		target.bindShaderStorage(tboF);
		target.bindShaderStorage(tboM);
		target.bindShaderStorage(tboZ);
	}

	private static final class Batch {
		private final CommandBuffer target;
		@Nullable
		private final ShaderProgram shader;
		private final int vao;
		private final SharedZoneBuffers buffers;
		private final RangeList ranges = new RangeList();

		private Batch(CommandBuffer target, @Nullable ShaderProgram shader, int vao, SharedZoneBuffers buffers) {
			this.target = target;
			this.shader = shader;
			this.vao = vao;
			this.buffers = buffers;
		}

		private void flush() {
			if (ranges.isEmpty())
				return;

			if (shader != null)
				target.SetShader(shader);
			target.BindVertexArray(vao, null);
			bindShaderStorages(target, buffers.tboF, buffers.tboM, buffers.tboZ);
			ranges.draw(target);
		}
	}

	/** Ranges of vertex data, in ints, which are merged when adjacent and drawn as a single call. */
	private static final class RangeList {
		private int[] offsets = new int[32];
		private int[] counts = new int[32];
		private int size;

		boolean isEmpty() {
			return size == 0;
		}

		void clear() {
			size = 0;
		}

		void push(int start, int end) {
			assert end >= start;
			if (end <= start)
				return;

			if (size > 0 && offsets[size - 1] + counts[size - 1] == start) {
				counts[size - 1] = end - offsets[size - 1];
				return;
			}

			if (size == offsets.length) {
				offsets = Arrays.copyOf(offsets, size * 2);
				counts = Arrays.copyOf(counts, size * 2);
			}
			offsets[size] = start;
			counts[size] = end - start;
			size++;
		}

		/** Draws the ranges as arrays of vertices. This converts them in place, so clear them afterwards. */
		void draw(CommandBuffer target) {
			for (int i = 0; i < size; ++i) {
				int end = offsets[i] + counts[i];
				offsets[i] /= INTS_PER_VERTEX;
				counts[i] = end / INTS_PER_VERTEX - offsets[i];
			}

			if (size == 1) {
				if (SUPPORTS_INDIRECT_DRAW)
					target.DrawArraysIndirect(GL_TRIANGLES, offsets[0], counts[0], indirectDrawCmdsStaging);
				else
					target.DrawArrays(GL_TRIANGLES, offsets[0], counts[0]);
			} else if (SUPPORTS_MULTI_INDIRECT_DRAW) {
				target.MultiDrawArraysIndirect(GL_TRIANGLES, offsets, counts, size, indirectDrawCmdsStaging);
			} else {
				target.MultiDrawArrays(GL_TRIANGLES, offsets, counts, size);
			}
		}
	}
}