package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import rs117.hd.utils.Camera;
import rs117.hd.utils.buffer.GLMappedBuffer;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.collections.PrimitiveFloatArray;
import rs117.hd.utils.collections.PrimitiveIntArray;

import static rs117.hd.utils.MathUtils.*;

public final class MeshClusterer {
	private static final ConcurrentPool<Cluster> POOL = new ConcurrentPool<>(Cluster::new);

	private static final int MAX_TRIS_PER_CLUSTER = 64;
	private static final float MAX_NORMAL_DOT = 0.8f;
	private static final float MAX_DISTANCE = 128f;
	private static final float MAX_DISTANCE_SQ = MAX_DISTANCE * MAX_DISTANCE;

	private final List<Cluster> clusters = new ArrayList<>();

	private final float[] triNormal = new float[3];
	private final float[] triCentroid = new float[3];
	private final float[] tmpNormal = new float[3];

	@Getter
	private int totalIndices;

	public static int calculateVisibleClusters(Camera camera, int[] clusterDraws, int[] clusterDrawArgs) {
		final float camX = camera.getPositionX();
		final float camY = camera.getPositionY();
		final float camZ = camera.getPositionZ();

		int visibleClusters = 0;

		for (int i = 0; i < clusterDraws.length; i += 10) {
			float cx = Float.intBitsToFloat(clusterDraws[i]);
			float cy = Float.intBitsToFloat(clusterDraws[i + 1]);
			float cz = Float.intBitsToFloat(clusterDraws[i + 2]);
			float radius = Float.intBitsToFloat(clusterDraws[i + 3]);

			if (!camera.intersectsSphere(cx, cy, cz, radius))
				continue;

			float vx = camX - cx;
			float vy = camY - cy;
			float vz = camZ - cz;

			float distSq = vx * vx + vy * vy + vz * vz;

			if (distSq > 1e-8f) {
				float invLen = 1f / sqrt(distSq);

				vx *= invLen;
				vy *= invLen;
				vz *= invLen;

				float axisX = Float.intBitsToFloat(clusterDraws[i + 4]);
				float axisY = Float.intBitsToFloat(clusterDraws[i + 5]);
				float axisZ = Float.intBitsToFloat(clusterDraws[i + 6]);
				float coneCos = Float.intBitsToFloat(clusterDraws[i + 7]);

				float dot = vx * axisX + vy * axisY + vz * axisZ;

				if (dot <= -coneCos)
					continue;
			}

			int base = visibleClusters << 1;

			clusterDrawArgs[base] = clusterDraws[i + 8];
			clusterDrawArgs[base + 1] = clusterDraws[i + 9];

			visibleClusters++;
		}

		return visibleClusters;
	}

	public void addFace(
		float v0x, float v0y, float v0z,
		float v1x, float v1y, float v1z,
		float v2x, float v2y, float v2z,
		int i0, int i1, int i2
	) {
		float ux = v1x - v0x;
		float uy = v1y - v0y;
		float uz = v1z - v0z;

		float vx = v2x - v0x;
		float vy = v2y - v0y;
		float vz = v2z - v0z;

		vec3(
			triNormal,
			uy * vz - uz * vy,
			uz * vx - ux * vz,
			ux * vy - uy * vx
		);

		normalize(triNormal, triNormal);

		vec3(
			triCentroid,
			(v0x + v1x + v2x) * 0.33333334f,
			(v0y + v1y + v2y) * 0.33333334f,
			(v0z + v1z + v2z) * 0.33333334f
		);

		Cluster best = null;
		float bestScore = Float.MAX_VALUE;

		for (Cluster c : clusters) {
			if (c.triangleCount >= MAX_TRIS_PER_CLUSTER)
				continue;

			float cx, cy, cz;

			if (c.triangleCount > 0) {
				float inv = 1f / c.triangleCount;
				cx = c.centroid[0] * inv;
				cy = c.centroid[1] * inv;
				cz = c.centroid[2] * inv;

				copyTo(tmpNormal, c.avgNormal);
				normalize(tmpNormal, tmpNormal);
			} else {
				cx = triCentroid[0];
				cy = triCentroid[1];
				cz = triCentroid[2];
				copyTo(tmpNormal, triNormal);
			}

			float normalDot = dot(triNormal, tmpNormal);
			if (normalDot < MAX_NORMAL_DOT)
				continue;

			float dx = triCentroid[0] - cx;
			float dy = triCentroid[1] - cy;
			float dz = triCentroid[2] - cz;

			float distSq = dx * dx + dy * dy + dz * dz;
			if (distSq > MAX_DISTANCE_SQ)
				continue;

			float score = distSq + (1f - normalDot) * 1024f;

			if (score < bestScore) {
				bestScore = score;
				best = c;
			}
		}

		if (best == null) {
			best = POOL.acquire();
			clusters.add(best);
		}

		best.addTriangle(i0, i1, i2, triCentroid, triNormal);
		totalIndices += 3;
	}

	public int[] buildClusters(GLMappedBuffer eboBuffer) {
		IntBuffer ebo = eboBuffer.intView();
		assert ebo.remaining() >= totalIndices;

		int[] clusterDraws = new int[clusters.size() * 10];

		int indexOffset = 0;
		int drawOffset = 0;

		for (Cluster c : clusters) {
			if (c.triangleCount == 0)
				continue;

			float inv = 1f / c.triangleCount;

			c.centroid[0] *= inv;
			c.centroid[1] *= inv;
			c.centroid[2] *= inv;

			normalize(c.avgNormal, c.avgNormal);
			copyTo(c.coneAxis, c.avgNormal);

			float minCos = 1f;
			float maxRadiusSq = 0f;

			float cx = c.centroid[0];
			float cy = c.centroid[1];
			float cz = c.centroid[2];

			for (int i = 0; i < c.triangleCount; i++) {
				int base = i * 3;

				float nx = c.triNormals.array[base];
				float ny = c.triNormals.array[base + 1];
				float nz = c.triNormals.array[base + 2];

				float cos = nx * c.coneAxis[0] + ny * c.coneAxis[1] + nz * c.coneAxis[2];
				minCos = Math.min(minCos, cos);

				float tx = c.triCentroids.array[base];
				float ty = c.triCentroids.array[base + 1];
				float tz = c.triCentroids.array[base + 2];

				float dx = tx - cx;
				float dy = ty - cy;
				float dz = tz - cz;

				float distSq = dx * dx + dy * dy + dz * dz;
				maxRadiusSq = Math.max(maxRadiusSq, distSq);
			}

			c.coneAngle = minCos;
			c.radius = sqrt(maxRadiusSq);

			ebo.put(c.indices.array, 0, c.indices.length);

			clusterDraws[drawOffset++] = Float.floatToIntBits(cx);
			clusterDraws[drawOffset++] = Float.floatToIntBits(cy);
			clusterDraws[drawOffset++] = Float.floatToIntBits(cz);
			clusterDraws[drawOffset++] = Float.floatToIntBits(c.radius);

			clusterDraws[drawOffset++] = Float.floatToIntBits(c.coneAxis[0]);
			clusterDraws[drawOffset++] = Float.floatToIntBits(c.coneAxis[1]);
			clusterDraws[drawOffset++] = Float.floatToIntBits(c.coneAxis[2]);
			clusterDraws[drawOffset++] = Float.floatToIntBits(c.coneAngle);

			clusterDraws[drawOffset++] = indexOffset;
			clusterDraws[drawOffset++] = c.indices.length;

			indexOffset += c.indices.length;

			c.reset();
			POOL.recycle(c);
		}

		clusters.clear();
		return clusterDraws;
	}

	static class Cluster {
		final PrimitiveIntArray indices = new PrimitiveIntArray();
		final PrimitiveFloatArray triCentroids = new PrimitiveFloatArray();
		final PrimitiveFloatArray triNormals = new PrimitiveFloatArray();

		final float[] centroid = new float[3];
		final float[] avgNormal = new float[3];
		final float[] coneAxis = new float[3];

		int triangleCount;
		float coneAngle;
		float radius;

		void addTriangle(int i0, int i1, int i2, float[] triCentroid, float[] normal) {
			indices.put(i0);
			indices.put(i1);
			indices.put(i2);

			triangleCount++;

			add(centroid, centroid, triCentroid);
			add(avgNormal, avgNormal, normal);

			triCentroids.put(triCentroid, 0, 3);
			triNormals.put(normal, 0, 3);
		}

		void reset() {
			indices.reset();
			triCentroids.reset();
			triNormals.reset();

			vec3(centroid, 0, 0, 0);
			vec3(avgNormal, 0, 0, 0);
			vec3(coneAxis, 0, 0, 0);

			triangleCount = 0;
			coneAngle = 0;
			radius = 0;
		}
	}
}