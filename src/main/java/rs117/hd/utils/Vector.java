package rs117.hd.utils;

import java.util.Arrays;

public class Vector {
	public static float[] copy(float[] v) {
		return Arrays.copyOf(v, v.length);
	}

	/**
	 * Computes a + b, storing it in the out array
	 */
	public static float[] add(float[] out, float[] a, float[] b) {
		for (int i = 0; i < out.length; i++)
			out[i] = a[i] + b[i];
		return out;
	}

	public static float[] mul(float[] out, float[] a, float[] b) {
		for (int i = 0; i < out.length; i++)
			out[i] = a[i] * b[i];
		return out;
	}

	public static float[] mul(float[] out, float[] a, float val) {
		for (int i = 0; i < out.length; i++)
			out[i] = a[i] * val;
		return out;
	}

	public static float[] div(float[] out, float[] a, float val) {
		for (int i = 0; i < out.length; i++)
			out[i] = a[i] / val;
		return out;
	}

	/**
	 * Computes a - b, storing it in the out array
	 */
	public static float[] subtract(float[] out, float[] a, float[] b) {
		for (int i = 0; i < out.length; i++)
			out[i] = a[i] - b[i];
		return out;
	}

	public static int[] subtract(int[] out, int[] a, int[] b) {
		for (int i = 0; i < out.length; i++)
			out[i] = a[i] - b[i];
		return out;
	}

	public static float[] pow(float[] out, float[] in, float exp) {
		for (int i = 0; i < out.length; i++)
			out[i] = (float) Math.pow(in[i], exp);
		return out;
	}

	public static float[] pow(float[] in, float exp) {
		return pow(new float[in.length], in, exp);
	}

	public static float dot(int[] a, int[] b) {
		float f = 0;
		int len = Math.min(a.length, b.length);
		for (int i = 0; i < len; i++)
			f += (float) a[i] * b[i]; // cast to float to prevent int overflow
		return f;
	}

	public static float dot(float[] a, float[] b) {
		float f = 0;
		int len = Math.min(a.length, b.length);
		for (int i = 0; i < len; i++)
			f += a[i] * b[i];
		return f;
	}

	public static float[] cross(float[] out, float[] a, float[] b) {
		out[0] = a[1] * b[2] - a[2] * b[1];
		out[1] = a[2] * b[0] - a[0] * b[2];
		out[2] = a[0] * b[1] - a[1] * b[0];
		return out;
	}

	public static float length(float... vector) {
		float lengthSquared = 0;
		for (float v : vector)
			lengthSquared += v * v;
		return (float) Math.sqrt(lengthSquared);
	}

	public static void normalize(float[] vector) {
		float length = length(vector);
		if (length == 0)
			return;
		length = 1 / length;
		for (int i = 0; i < vector.length; i++)
			vector[i] *= length;
	}

	public static void abs(float[] out, float[] v) {
		for (int i = 0; i < out.length; i++)
			out[i] = Math.abs(v[i]);
	}

	public static float[] planeFromPoints(float[] p0, float[] p1, float[] p2) {
		float[] v1 = subtract(new float[3], p1, p0);
		float[] v2 = subtract(new float[3], p2, p0);
		float[] normal = cross(new float[3], v1, v2);
		normalize(normal);
		float d = -dot(normal, p0);
		return new float[] {normal[0], normal[1], normal[2], d};
	}

	public static float[] planeFromPointNormal(float[] point, float[] normal) {
		float[] normalizedNormal = copy(normal);
		normalize(normalizedNormal);
		float d = -dot(normalizedNormal, point);
		return new float[] {normalizedNormal[0], normalizedNormal[1], normalizedNormal[2], d};
	}

	public static float[] planeFromTwoPoints(float[] p0, float[] p1) {
		float[] normal = subtract(new float[3], p1, p0);
		return planeFromPointNormal(p0, normal);
	}

	public static float distanceToPlane(float[] plane, float x, float y, float z) {
		return (plane[0] * x + plane[1] * y + plane[2] * z) + plane[3];
	}
}
