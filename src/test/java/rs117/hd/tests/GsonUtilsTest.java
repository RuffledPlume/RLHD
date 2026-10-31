package rs117.hd.tests;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import org.junit.Test;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.utils.GsonUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class GsonUtilsTest {
	@Test
	public void createsDefaultsAdapterForSelfReferentialOverrideArray() {
		Gson gson = GsonUtils.wrap(new GsonBuilder().create());
		Gson importerGson = gson.newBuilder()
			.registerTypeAdapter(ModelOverride[].class,
				(JsonDeserializer<ModelOverride[]>) (json, type, context) -> gson.fromJson(json, ModelOverride[].class))
			.create();

		ModelOverride[] definitions = importerGson.fromJson("[{}]", ModelOverride[].class);
		assertEquals(1, definitions.length);
		assertNotNull(definitions[0]);
	}

	@Test
	public void compactsFloatingPointNumberArrays() {
		String input = "{\"uvs\": [\n  0.125,\n  -0.5e-3,\n  1\n]}";
		assertEquals("{\"uvs\": [ 0.125, -0.5e-3, 1 ]}", GsonUtils.compactNumberArrays(input));
	}

	@Test
	public void compactsVeryLargeNumberArraysWithoutRegexRecursion() {
		StringBuilder input = new StringBuilder("[\n");
		StringBuilder expected = new StringBuilder("[ ");
		for (int i = 0; i < 50_000; i++) {
			if (i > 0) {
				input.append(",\n");
				expected.append(", ");
			}
			input.append(i);
			expected.append(i);
		}
		input.append("\n]");
		expected.append(" ]");
		assertEquals(expected.toString(), GsonUtils.compactNumberArrays(input.toString()));
	}

	@Test
	public void doesNotCompactNumberArraysInsideStrings() {
		String input = "{\"text\": \"[1,\\n2]\", \"numbers\": [1,\n2]}";
		assertEquals("{\"text\": \"[1,\\n2]\", \"numbers\": [ 1, 2 ]}",
			GsonUtils.compactNumberArrays(input));
	}
}
