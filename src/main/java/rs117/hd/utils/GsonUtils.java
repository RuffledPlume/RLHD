/*
 * Gson hacks.
 * Written in 2025 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package rs117.hd.utils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class GsonUtils {
	public static String location(JsonReader in) {
		var str = in.toString();
		int i = str.indexOf(" at ");
		if (i != -1)
			str = str.substring(i + 4);
		return str;
	}

	public static Gson wrap(Gson gson) {
		return gson.newBuilder()
			.setLenient()
			.setPrettyPrinting()
			.disableHtmlEscaping() // Disable HTML escaping for JSON exports (Gson never escapes when parsing regardless)
			.registerTypeAdapterFactory(new ExcludeDefaultsFactory())
			.registerTypeAdapter(Float.class, new RoundingAdapter(3))
			.create();
	}

	public static void deepInheritFrom(JsonObject overrides, JsonObject base) {
		for (var entry : base.entrySet()) {
			JsonElement value = overrides.get(entry.getKey());
			if (value == null) {
				overrides.add(entry.getKey(), entry.getValue());
			} else if (value.isJsonObject() && entry.getValue().isJsonObject()) {
				deepInheritFrom(value.getAsJsonObject(), entry.getValue().getAsJsonObject());
			}
		}
	}

	public static void shallowInheritFrom(JsonObject overrides, JsonObject base) {
		for (var entry : base.entrySet())
			if (!overrides.has(entry.getKey()))
				overrides.add(entry.getKey(), entry.getValue());
	}

	public static String compactNumberArrays(String json) {
		StringBuilder compacted = new StringBuilder(json.length());
		int copiedThrough = 0;
		boolean inString = false;
		boolean escaped = false;

		for (int i = 0; i < json.length(); i++) {
			char c = json.charAt(i);
			if (inString) {
				if (escaped)
					escaped = false;
				else if (c == '\\')
					escaped = true;
				else if (c == '"')
					inString = false;
				continue;
			}
			if (c == '"') {
				inString = true;
				continue;
			}
			if (c != '[')
				continue;

			NumberArray array = parseNumberArray(json, i);
			if (array == null)
				continue;
			compacted.append(json, copiedThrough, i).append(array.compactText);
			i = array.endIndex;
			copiedThrough = i + 1;
		}
		return copiedThrough == 0 ? json : compacted.append(json, copiedThrough, json.length()).toString();
	}

	@Nullable
	private static NumberArray parseNumberArray(String json, int start) {
		int cursor = skipWhitespace(json, start + 1);
		StringBuilder compact = new StringBuilder("[ ");
		int count = 0;
		while (cursor < json.length()) {
			int numberStart = cursor;
			int numberEnd = parseJsonNumber(json, cursor);
			if (numberEnd < 0)
				return null;
			cursor = numberEnd;
			if (count++ > 0)
				compact.append(", ");
			compact.append(json, numberStart, cursor);
			cursor = skipWhitespace(json, cursor);
			if (cursor >= json.length())
				return null;
			char separator = json.charAt(cursor++);
			if (separator == ']')
				return count >= 2 ? new NumberArray(cursor - 1, compact.append(" ]").toString()) : null;
			if (separator != ',')
				return null;
			cursor = skipWhitespace(json, cursor);
		}
		return null;
	}

	private static int parseJsonNumber(String json, int cursor) {
		int length = json.length();
		if (cursor < length && json.charAt(cursor) == '-')
			cursor++;
		if (cursor >= length)
			return -1;

		char firstDigit = json.charAt(cursor);
		if (firstDigit == '0') {
			cursor++;
		} else if (firstDigit >= '1' && firstDigit <= '9') {
			cursor++;
			while (cursor < length && isDigit(json.charAt(cursor)))
				cursor++;
		} else {
			return -1;
		}

		if (cursor < length && json.charAt(cursor) == '.') {
			cursor++;
			int fractionStart = cursor;
			while (cursor < length && isDigit(json.charAt(cursor)))
				cursor++;
			if (cursor == fractionStart)
				return -1;
		}

		if (cursor < length && (json.charAt(cursor) == 'e' || json.charAt(cursor) == 'E')) {
			cursor++;
			if (cursor < length && (json.charAt(cursor) == '+' || json.charAt(cursor) == '-'))
				cursor++;
			int exponentStart = cursor;
			while (cursor < length && isDigit(json.charAt(cursor)))
				cursor++;
			if (cursor == exponentStart)
				return -1;
		}
		return cursor;
	}

	private static int skipWhitespace(String text, int cursor) {
		while (cursor < text.length()) {
			char c = text.charAt(cursor);
			if (c != ' ' && c != '\t' && c != '\n' && c != '\r')
				break;
			cursor++;
		}
		return cursor;
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static final class NumberArray {
		private final int endIndex;
		private final String compactText;

		private NumberArray(int endIndex, String compactText) {
			this.endIndex = endIndex;
			this.compactText = compactText;
		}
	}

	public static Map<String, JsonObject> resolveParentDefinitions(
		Map<String, JsonObject> definitions,
		String type,
		@Nullable String rootParent,
		BiConsumer<JsonObject, JsonObject> inherit
	) {
		var resolved = new HashMap<String, JsonObject>();
		for (String name : definitions.keySet())
			resolveParentDefinition(name, definitions, resolved, new HashSet<>(), type, rootParent, inherit);
		resolved.values().removeIf(Objects::isNull);
		return resolved;
	}

	@Nullable
	private static JsonObject resolveParentDefinition(
		String name,
		Map<String, JsonObject> definitions,
		Map<String, JsonObject> resolved,
		Set<String> resolving,
		String type,
		@Nullable String rootParent,
		BiConsumer<JsonObject, JsonObject> inherit
	) {
		JsonObject result = resolved.get(name);
		if (resolved.containsKey(name))
			return result;
		JsonObject definition = definitions.get(name);
		if (definition == null)
			return null;
		if (!resolving.add(name)) {
			log.error("{} '{}' contains a parent loop", type, name);
			return null;
		}

		result = definition;
		JsonElement parent = definition.get("parent");
		if (parent != null) {
			if (!parent.isJsonPrimitive() || !parent.getAsJsonPrimitive().isString()) {
				log.error("{} '{}' has a non-string parent", type, name);
				result = null;
			} else {
				String parentName = parent.getAsString();
				if (!parentName.equals(rootParent)) {
					if (!definitions.containsKey(parentName)) {
						log.error("{} '{}' has an unknown parent '{}'", type, name, parentName);
						result = null;
					} else {
						JsonObject base = resolveParentDefinition(
							parentName, definitions, resolved, resolving, type, rootParent, inherit);
						if (base != null) {
							inherit.accept(result, base);
						} else {
							result = null;
						}
					}
				}
			}
		}
		resolving.remove(name);
		resolved.put(name, result);
		return result;
	}

	public static void removeNulls(JsonElement element) {
		if (element.isJsonObject()) {
			var iter = element.getAsJsonObject().entrySet().iterator();
			while (iter.hasNext()) {
				JsonElement value = iter.next().getValue();
				if (value.isJsonNull()) {
					iter.remove();
				} else {
					removeNulls(value);
				}
			}
		} else if (element.isJsonArray()) {
			var array = element.getAsJsonArray();
			for (int i = 0; i < array.size(); i++)
				removeNulls(array.get(i));
		}
	}

	public static void removeMatching(JsonObject target, JsonObject base) {
		var iter = target.entrySet().iterator();
		while (iter.hasNext()) {
			var entry = iter.next();
			JsonElement baseValue = base.get(entry.getKey());
			if (baseValue == null)
				continue;
			JsonElement value = entry.getValue();
			if (value.isJsonObject() && baseValue.isJsonObject()) {
				removeMatching(value.getAsJsonObject(), baseValue.getAsJsonObject());
				if (value.getAsJsonObject().size() == 0)
					iter.remove();
			} else if (value.equals(baseValue)) {
				iter.remove();
			}
		}
	}

	/**
	 * Saves an array of definitions to a JSON file while preserving the original JSON for every
	 * definition that wasn't changed. Unchanged definitions keep their exact on-disk representation,
	 * changed definitions are replaced in place, and new definitions are appended.
	 *
	 * @param path        the file to save to
	 * @param gson        the Gson instance used to serialize and compare definitions
	 * @param definitions the definitions to save
	 * @param type        the definition type
	 */
	public static <T> void saveJson(ResourcePath path, Gson gson, T[] definitions, Class<T> type, boolean compact) throws IOException {
		JsonArray existing = null;
		try {
			JsonElement parsed = new JsonParser().parse(path.loadString());
			if (parsed != null && parsed.isJsonArray())
				existing = parsed.getAsJsonArray();
		} catch (IOException | RuntimeException ignored) {
			// Write every definition if the file is missing or isn't valid JSON
		}

		JsonArray result = new JsonArray();
		if (existing == null) {
			for (T definition : definitions)
				if (definition != null)
					result.add(gson.toJsonTree(definition));
		} else {
			int existingCount = existing.size();
			JsonElement[] original = new JsonElement[existingCount];
			String[] normalized = new String[existingCount];
			Map<String, Deque<Integer>> indicesByJson = new HashMap<>();
			for (int i = 0; i < existingCount; i++) {
				JsonElement element = existing.get(i);
				original[i] = element;
				try {
					T parsed = gson.fromJson(element, type);
					if (parsed != null) {
						normalized[i] = gson.toJsonTree(parsed).toString();
						indicesByJson.computeIfAbsent(normalized[i], ignored -> new ArrayDeque<>()).add(i);
					}
				} catch (RuntimeException ignored) {
					// Leave this entry unmatched, so a changed definition can replace it
				}
			}

			List<JsonElement> serialized = new ArrayList<>(definitions.length);
			for (T definition : definitions)
				if (definition != null)
					serialized.add(gson.toJsonTree(definition));

			// Match unchanged definitions to their original entries first
			int[] matches = new int[serialized.size()];
			Arrays.fill(matches, -1);
			boolean[] used = new boolean[existingCount];
			for (int i = 0; i < serialized.size(); i++) {
				Deque<Integer> indices = indicesByJson.get(serialized.get(i).toString());
				if (indices != null && !indices.isEmpty()) {
					int index = indices.removeFirst();
					matches[i] = index;
					used[index] = true;
				}
			}

			// Align changed definitions with the remaining original entries in order
			int nextExisting = 0;
			for (int i = 0; i < serialized.size(); i++) {
				if (matches[i] != -1)
					continue;
				while (nextExisting < existingCount && used[nextExisting])
					nextExisting++;
				if (nextExisting >= existingCount)
					break;
				matches[i] = nextExisting;
				used[nextExisting] = true;
			}

			for (int i = 0; i < serialized.size(); i++) {
				JsonElement current = serialized.get(i);
				int match = matches[i];
				if (match != -1 && current.toString().equals(normalized[match]))
					result.add(original[match]);
				else
					result.add(current);
			}
		}

		String jsonStr = gson.toJson(result);
		if(compact)
			jsonStr = compactNumberArrays(jsonStr);

		path.mkdirs();
		path.writeString(jsonStr);
	}

	public static class RoundingAdapter extends TypeAdapter<Float> {
		private final float rounding;

		public RoundingAdapter(int decimals) {
			rounding = pow(10, decimals);
		}

		@Override
		public Float read(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.NULL)
				return null;
			return (float) in.nextDouble();
		}

		@Override
		public void write(JsonWriter out, Float src) throws IOException {
			if (src == null) {
				out.nullValue();
			} else {
				var result = round(src * rounding) / rounding;
				if (round(result) == result) {
					out.value((int) result); // Remove decimals when possible
				} else {
					out.value((Number) result); // Cast to Number so Gson removes unnecessary precision
				}
			}
		}
	}

	/**
	 * Make it less cumbersome to implement a TypeAdapter which respects the default Float adapter.
	 */
	@NoArgsConstructor
	@SuppressWarnings("unchecked")
	public static abstract class DelegateFloatAdapter<T> implements TypeAdapterFactory {
		protected TypeAdapter<Float> FLOAT_ADAPTER;
		protected boolean unwrapContainers; // Only apply directly to numbers, letting Gson handle any composite types

		@Override
		public <U> TypeAdapter<U> create(Gson gson, TypeToken<U> typeToken) {
			FLOAT_ADAPTER = gson.getAdapter(TypeToken.get(Float.class));
			var impl = this;
			var adapter = new TypeAdapter<U>() {
				@Override
				public U read(JsonReader in) throws IOException {
					return (U) impl.read(in);
				}

				@Override
				public void write(JsonWriter out, U value) throws IOException {
					impl.write(out, (T) value);
				}
			};

			if (!unwrapContainers)
				return adapter;

			// Register this as a TypeAdapterFactory for numbers downstream
			return gson.newBuilder()
				.registerTypeAdapterFactory(new TypeAdapterFactory() {
					@Override
					public <S> TypeAdapter<S> create(Gson gson, TypeToken<S> typeToken) {
						var type = typeToken.getRawType();
						if (type.isPrimitive()) {
							if (type != float.class)
								return null;
						} else if (!Float.class.isAssignableFrom(type)) {
							return null;
						}
						return (TypeAdapter<S>) adapter;
					}
				})
				.create()
				.getAdapter(typeToken);
		}

		public abstract T read(JsonReader in) throws IOException;
		public abstract void write(JsonWriter out, T value) throws IOException;
	}

	/**
	 * Because of the way this works internally, custom formatting by calling `JsonWriter#jsonValue` is not supported.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface ExcludeDefaults {}

	public interface ExcludeDefaultsProvider<T> {
		@Nullable
		T provideDefaults();
	}

	@RequiredArgsConstructor
	private static class ExcludeDefaultsAdapter<T> extends TypeAdapter<T> {
		private final Gson gson;
		private final TypeAdapter<T> type;
		private final JsonObject defaults;

		@Override
		public void write(JsonWriter out, T t) throws IOException {
			var json = type.toJsonTree(t);
			if (!json.isJsonObject()) {
				gson.toJson(json, out);
				return;
			}

			var defaults = this.defaults;
			if (t instanceof ExcludeDefaultsProvider) {
				var provided = ((ExcludeDefaultsProvider<?>) t).provideDefaults();
				if (provided != null) {
					try {
						// noinspection unchecked
						defaults = type.toJsonTree((T) provided).getAsJsonObject();
					} catch (ClassCastException ex) {
						log.error("Incorrect type provided by DefaultsProvider: {}, expected {}", provided.getClass(), t.getClass());
					}
				}
			}

			var obj = json.getAsJsonObject();
			for (var e : defaults.entrySet())
				if (Objects.equals(obj.get(e.getKey()), e.getValue()))
					obj.remove(e.getKey());

			// Make it possible to replace inherited non-null default values with explicit nulls
			out.setSerializeNulls(true);
			out.beginObject();
			for (var e : obj.entrySet()) {
				out.name(e.getKey());
				if (e.getValue().isJsonNull()) {
					out.nullValue();
				} else {
					gson.toJson(e.getValue(), out);
				}
			}
			out.endObject();
		}

		@Override
		public T read(JsonReader in) throws IOException {
			return type.read(in);
		}
	}

	private static class ExcludeDefaultsFactory implements TypeAdapterFactory {
		@Override
		public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
			for (var annotation : type.getRawType().getAnnotations()) {
				if (annotation.annotationType() == ExcludeDefaults.class) {
					try {
						var defaultDelegate = gson.getDelegateAdapter(this, type);
						T defaultObj;
						try {
							defaultObj = defaultDelegate.fromJson("{}");
						} catch (IOException ex) {
							return null; // Can't skip defaults for non-object types
						}

						if (defaultObj == null)
							return null; // No defaults available

						// TypeAdapter.toJsonTree serializes nulls. That can recurse into an unfinished
						// FutureTypeAdapter for self-referential fields (for example ModelOverride[]).
						var defaults = new JsonParser().parse(defaultDelegate.toJson(defaultObj)).getAsJsonObject();
						return new ExcludeDefaultsAdapter<>(gson, gson.getDelegateAdapter(this, type), defaults);
					} catch (Exception ex) {
						log.error("Unable to exclude defaults for {}", type, ex);
						break;
					}
				}
			}
			return null;
		}
	}

	public static class DegreesToRadians extends DelegateFloatAdapter<Float> {
		{
			unwrapContainers = true;
		}

		@Override
		public Float read(JsonReader in) throws IOException {
			var value = FLOAT_ADAPTER.read(in);
			return value == null ? null : value * DEG_TO_RAD;
		}

		@Override
		public void write(JsonWriter out, Float value) throws IOException {
			FLOAT_ADAPTER.write(out, value == null ? null : value * RAD_TO_DEG);
		}
	}
}
