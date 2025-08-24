package rs117.hd.data;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.buffer.GpuIntBuffer;
import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_STREAM_DRAW;
import static rs117.hd.HdPlugin.MAX_FACE_COUNT;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public final class ModelSortingBuffers {
	// ordered by face count from small to large
	public int numSortingBins;
	public int maxComputeThreadCount;
	public int[] modelSortingBinFaceCounts; // facesPerThread * threadCount
	public int[] modelSortingBinThreadCounts;
	public int[] numModelsToSort;
	public GpuIntBuffer[] modelSortingBuffers;
	public SharedGLBuffer[] hModelSortingBuffers;

	public void initialise(int maxThreadCount) {
		maxComputeThreadCount = maxThreadCount;

		int[] targetFaceCounts = {
			128,
			512,
			2048,
			4096,
			MAX_FACE_COUNT
		};

		int numBins = 0;
		int[] binFaceCounts = new int[targetFaceCounts.length];
		int[] binThreadCounts = new int[targetFaceCounts.length];

		int faceCount = 0;
		for (int targetFaceCount : targetFaceCounts) {
			if (faceCount >= targetFaceCount)
				continue;

			int facesPerThread = 1;
			int threadCount;
			while (true) {
				threadCount = ceil((float) targetFaceCount / facesPerThread);
				if (threadCount <= maxThreadCount)
					break;
				++facesPerThread;
			}

			faceCount = threadCount * facesPerThread;
			binFaceCounts[numBins] = faceCount;
			binThreadCounts[numBins] = threadCount;
			++numBins;
		}

		numSortingBins = numBins;
		modelSortingBinFaceCounts = Arrays.copyOf(binFaceCounts, numBins);
		modelSortingBinThreadCounts = Arrays.copyOf(binThreadCounts, numBins);
		numModelsToSort = new int[numBins];

		modelSortingBuffers = new GpuIntBuffer[numSortingBins];
		for (int i = 0; i < numSortingBins; i++)
			modelSortingBuffers[i] = new GpuIntBuffer();

		hModelSortingBuffers = new SharedGLBuffer[numSortingBins];
		for (int i = 0; i < numSortingBins; i++) {
			hModelSortingBuffers[i] = new SharedGLBuffer(
				"Model Sorting " + modelSortingBinFaceCounts[i], GL_ARRAY_BUFFER, GL_STREAM_DRAW, CL_MEM_READ_ONLY);
			// Initialize each model sorting buffer with capacity for 64 models
			hModelSortingBuffers[i].initialize();
		}

		log.debug("Spreading model sorting across {} bins: {}", numBins, modelSortingBinFaceCounts);
	}

	public void reset() {
		Arrays.fill(numModelsToSort, 0);
	}

	/**
	 * returns the correct buffer based on triangle count and updates model count
	 */
	public GpuIntBuffer bufferForTriangles(int triangles) {
		for (int i = 0; i < numSortingBins; i++) {
			if (modelSortingBinFaceCounts[i] >= triangles) {
				++numModelsToSort[i];
				return modelSortingBuffers[i];
			}
		}

		throw new IllegalStateException(
			"Ran into a model with more triangles than the plugin supports (" +
			triangles + " > " + MAX_FACE_COUNT + ")");
	}

	public void destroy() {
		numSortingBins = 0;
		modelSortingBinFaceCounts = null;
		modelSortingBinThreadCounts = null;
		numModelsToSort = null;

		if (modelSortingBuffers != null)
			for (var buffer : modelSortingBuffers)
				buffer.destroy();
		modelSortingBuffers = null;

		if (hModelSortingBuffers != null)
			for (var buffer : hModelSortingBuffers)
				buffer.destroy();
		hModelSortingBuffers = null;
	}
}
