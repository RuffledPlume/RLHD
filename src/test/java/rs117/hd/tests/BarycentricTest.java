package rs117.hd.tests;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static rs117.hd.utils.MathUtils.*;

public class BarycentricTest {

	@Test
	public void testVertexV0() {
		float[] v0 = {0f, 0f, 0f};
		float[] v1 = {1f, 0f, 0f};
		float[] v2 = {0f, 1f, 0f};

		float[] result = barycentric(v0, v1, v2, v0);

		assertArrayEquals(new float[]{1f, 0f, 0f}, result, 1e-6f);
	}

	@Test
	public void testVertexV1() {
		float[] v0 = {0f, 0f, 0f};
		float[] v1 = {1f, 0f, 0f};
		float[] v2 = {0f, 1f, 0f};

		float[] result = barycentric(v0, v1, v2, v1);

		assertArrayEquals(new float[]{0f, 1f, 0f}, result, 1e-6f);
	}

	@Test
	public void testVertexV2() {
		float[] v0 = {0f, 0f, 0f};
		float[] v1 = {1f, 0f, 0f};
		float[] v2 = {0f, 1f, 0f};

		float[] result = barycentric(v0, v1, v2, v2);

		assertArrayEquals(new float[]{0f, 0f, 1f}, result, 1e-6f);
	}

	@Test
	public void testCenterPoint() {
		float[] v0 = {0f, 0f, 0f};
		float[] v1 = {1f, 0f, 0f};
		float[] v2 = {0f, 1f, 0f};

		float[] p = {1f / 3f, 1f / 3f, 0f};

		float[] result = barycentric(v0, v1, v2, p);

		assertEquals(1f / 3f, result[0], 1e-6f);
		assertEquals(1f / 3f, result[1], 1e-6f);
		assertEquals(1f / 3f, result[2], 1e-6f);
	}

	@Test
	public void testMidpointEdgeV0V1() {
		float[] v0 = {0f, 0f, 0f};
		float[] v1 = {1f, 0f, 0f};
		float[] v2 = {0f, 1f, 0f};

		float[] p = {0.5f, 0f, 0f};

		float[] result = barycentric(v0, v1, v2, p);

		assertEquals(0.5f, result[0], 1e-6f);
		assertEquals(0.5f, result[1], 1e-6f);
		assertEquals(0f,   result[2], 1e-6f);
	}

	@Test
	public void testPointOutsideTriangle() {
		float[] v0 = {0f, 0f, 0f};
		float[] v1 = {1f, 0f, 0f};
		float[] v2 = {0f, 1f, 0f};

		float[] p = {1.2f, 1.2f, 0f};

		float[] result = barycentric(v0, v1, v2, p);

		// One or more should be negative when outside triangle
		assertTrue(result[0] < 0f || result[1] < 0f || result[2] < 0f);
	}
}
