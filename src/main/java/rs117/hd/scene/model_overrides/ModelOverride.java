package rs117.hd.scene.model_overrides;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.config.VanillaShadowMode;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.materials.Material;
import rs117.hd.utils.Props;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.ExpressionParser.asExpression;
import static rs117.hd.utils.ExpressionParser.parseExpression;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@NoArgsConstructor
public class ModelOverride
{
	public static final ModelOverride NONE = new ModelOverride(true);

	private static final Set<Integer> EMPTY = new HashSet<>();

	private static final int FLAG_HIDE = 1;
	private static final int FLAG_DISABLE_DETAIL_CULLING = 1 << 1;
	private static final int FLAG_RETAIN_VANILLA_UVS = 1 << 2;
	private static final int FLAG_FORCE_MATERIAL_CHANGES = 1 << 3;
	private static final int FLAG_FLAT_NORMALS = 1 << 4;
	private static final int FLAG_UPWARDS_NORMALS = 1 << 5;
	private static final int FLAG_HIDE_VANILLA_SHADOWS = 1 << 6;
	private static final int FLAG_RETAIN_VANILLA_SHADOWS_IN_PVM = 1 << 7;
	private static final int FLAG_HIDE_HD_SHADOWS_IN_PVM = 1 << 8;
	private static final int FLAG_CAST_SHADOWS = 1 << 9;
	private static final int FLAG_RECEIVE_SHADOWS = 1 << 10;
	private static final int FLAG_TERRAIN_VERTEX_SNAP = 1 << 11;
	private static final int FLAG_UNDO_VANILLA_SHADING = 1 << 12;
	private static final int FLAG_HIDE_AS_WATER_EFFECT = 1 << 13;
	private static final int FLAG_INVERT_DISPLACEMENT_STRENGTH = 1 << 14;
	private static final int FLAG_DISABLE_PRIORITY_SORTING = 1 << 15;
	private static final int FLAG_IS_DUMMY = 1 << 16;
	private static final int FLAG_IS_GENERATED = 1 << 17;
	private static final int FLAG_HAS_TRANSPARENCY = 1 << 18;
	private static final int FLAG_MIGHT_HAVE_TRANSPARENCY = 1 << 19;
	private static final int FLAG_MODIFIES_VANILLA_TEXTURE = 1 << 20;

	private static final int DEFAULT_FLAGS =
		FLAG_RETAIN_VANILLA_UVS |
		FLAG_CAST_SHADOWS |
		FLAG_RECEIVE_SHADOWS |
		FLAG_UNDO_VANILLA_SHADING;

	public String description = "UNKNOWN";

	// When, where or what the override should apply to
	public SeasonalTheme seasonalTheme;
	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] areas = {};
	@JsonAdapter(GamevalManager.NpcAdapter.class)
	public Set<Integer> npcIds = EMPTY;
	@JsonAdapter(GamevalManager.ObjectAdapter.class)
	public Set<Integer> objectIds = EMPTY;
	@JsonAdapter(GamevalManager.SpotanimAdapter.class)
	public Set<Integer> projectileIds = EMPTY;
	@JsonAdapter(GamevalManager.SpotanimAdapter.class)
	public Set<Integer> graphicsObjectIds = EMPTY;

	public Material baseMaterial = Material.NONE;
	public Material textureMaterial = Material.NONE;

	public UvType uvType = UvType.VANILLA;
	public float uvScale = 1;

	public int uvOrientation = 0;
	public int uvOrientationX = 0;
	public int uvOrientationY = 0;
	public int uvOrientationZ = 0;
	public int rotate = 0;
	public int windDisplacementModifier = 0;
	public int depthBias = -1;
	public int flags = DEFAULT_FLAGS;

	public float terrainVertexSnapThreshold = 0.125f;
	public float shadowOpacityThreshold = 0;

	private byte tzHaarRecolorType = (byte) TzHaarRecolorType.NONE.ordinal();
	private byte inheritTileColorType = (byte) InheritTileColorType.NONE.ordinal();
	private byte windDisplacementMode = (byte) WindDisplacement.DISABLED.ordinal();

	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] hideInAreas = {};

	public Map<Material, ModelOverride> materialOverrides;
	public ModelOverride[] colorOverrides;

	private JsonElement colors;

	public transient Map<AABB, ModelOverride> areaOverrides;
	public transient AhslPredicate ahslCondition;

	@FunctionalInterface
	public interface AhslPredicate {
		boolean test(int ahsl);
	}

	public void normalize(HdPlugin plugin) {
		// Ensure there are no nulls in case of invalid configuration during development
		if (baseMaterial == null) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid baseMaterial");
			baseMaterial = ModelOverride.NONE.baseMaterial;
		}
		if (textureMaterial == null) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid textureMaterial");
			textureMaterial = ModelOverride.NONE.textureMaterial;
		}
		if (uvType == null) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid uvType");
			uvType = ModelOverride.NONE.uvType;
		}
		if (!isValidOrdinal(tzHaarRecolorType, TzHaarRecolorType.values())) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid tzHaarRecolorType");
			setTzHaarRecolorType(ModelOverride.NONE.getTzHaarRecolorType());
		}
		if (!isValidOrdinal(inheritTileColorType, InheritTileColorType.values())) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid inheritTileColorType");
			setInheritTileColorType(ModelOverride.NONE.getInheritTileColorType());
		}
		if (!isValidOrdinal(windDisplacementMode, WindDisplacement.values())) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid windDisplacementMode");
			setWindDisplacementMode(ModelOverride.NONE.getWindDisplacementMode());
		}

		if (windDisplacementModifier < -3 || windDisplacementModifier > 3) {
			if (Props.DEVELOPMENT)
				throw new IllegalStateException("Invalid windDisplacementModifier (range is -3 to 3)");
			windDisplacementModifier = clamp(windDisplacementModifier, -3, 3);
		}

		setModifiesVanillaTexture(textureMaterial.modifiesVanillaTexture);

		boolean disableTextures = !plugin.configModelTextures && !forceMaterialChanges();
		if (disableTextures) {
			if (baseMaterial.modifiesVanillaTexture)
				baseMaterial = Material.NONE;
			if (textureMaterial.modifiesVanillaTexture)
				textureMaterial = Material.NONE;
		}

		setHasTransparency(setMightHaveTransparency(
			baseMaterial.hasTransparency ||
			textureMaterial.hasTransparency ||
			getTzHaarRecolorType() != TzHaarRecolorType.NONE));

		setHide(hide() || hideAsWaterEffect() && plugin.configHideVanillaWaterEffects);

		if (materialOverrides != null) {
			var normalized = new HashMap<Material, ModelOverride>();
			for (var entry : materialOverrides.entrySet()) {
				var override = entry.getValue();
				override.normalize(plugin);
				if (disableTextures && override.modifiesVanillaTexture())
					continue;
				setMightHaveTransparency(mightHaveTransparency() | override.mightHaveTransparency());
				normalized.put(entry.getKey(), override);
			}
			if (normalized.isEmpty())
				normalized = null;
			materialOverrides = normalized;
		}

		if (colorOverrides != null) {
			for (var override : colorOverrides) {
				override.normalize(plugin);
				setMightHaveTransparency(mightHaveTransparency() | override.mightHaveTransparency());
				override.ahslCondition = parseAhslConditions(override.colors);
			}
			colors = null;
		}

		if (uvOrientationX == 0)
			uvOrientationX = uvOrientation;
		if (uvOrientationY == 0)
			uvOrientationY = uvOrientation;
		if (uvOrientationZ == 0)
			uvOrientationZ = uvOrientation;

		if (retainVanillaShadowsInPvm()) {
			if (plugin.configVanillaShadowMode.retainInPvm)
				setHideVanillaShadows(false);
			if (plugin.configVanillaShadowMode == VanillaShadowMode.PREFER_IN_PVM && hideHdShadowsInPvm())
				setCastShadows(false);
		}

		if (!castShadows() && shadowOpacityThreshold == 0)
			shadowOpacityThreshold = 1;
	}

	public ModelOverride copy() {
		var copy = new ModelOverride();
		copy.description = description;
		copy.seasonalTheme = seasonalTheme;
		copy.areas = areas;
		copy.npcIds = npcIds;
		copy.objectIds = objectIds;
		copy.projectileIds = projectileIds;
		copy.graphicsObjectIds = graphicsObjectIds;
		copy.baseMaterial = baseMaterial;
		copy.textureMaterial = textureMaterial;
		copy.uvType = uvType;
		copy.uvScale = uvScale;
		copy.uvOrientation = uvOrientation;
		copy.uvOrientationX = uvOrientationX;
		copy.uvOrientationY = uvOrientationY;
		copy.uvOrientationZ = uvOrientationZ;
		copy.rotate = rotate;
		copy.flags = flags;
		copy.terrainVertexSnapThreshold = terrainVertexSnapThreshold;
		copy.shadowOpacityThreshold = shadowOpacityThreshold;
		copy.tzHaarRecolorType = tzHaarRecolorType;
		copy.inheritTileColorType = inheritTileColorType;
		copy.windDisplacementMode = windDisplacementMode;
		copy.windDisplacementModifier = windDisplacementModifier;
		copy.depthBias = depthBias;
		copy.hideInAreas = hideInAreas;
		copy.materialOverrides = materialOverrides;
		copy.colorOverrides = colorOverrides;
		copy.colors = colors;
		copy.areaOverrides = areaOverrides;
		copy.ahslCondition = ahslCondition;
		return copy;
	}

	private ModelOverride(boolean isDummy) {
		this();
		setIsDummy(isDummy);
	}

	private static boolean isValidOrdinal(byte ordinal, Enum<?>[] values) {
		int index = ordinal & 0xFF;
		return index < values.length;
	}

	private static <T extends Enum<T>> T getOrdinal(byte ordinal, T[] values) {
		int index = ordinal & 0xFF;
		return index < values.length ? values[index] : values[0];
	}

	private boolean hasFlag(int flag) {
		return (flags & flag) != 0;
	}

	private boolean setFlag(int flag, boolean value) {
		if (value) {
			flags |= flag;
		} else {
			flags &= ~flag;
		}
		return hasFlag(flag);
	}

	public boolean mightHaveTransparency() {
		return hasFlag(FLAG_MIGHT_HAVE_TRANSPARENCY);
	}

	public boolean setMightHaveTransparency(boolean value) { return setFlag(FLAG_MIGHT_HAVE_TRANSPARENCY, value); }

	public boolean hasTransparency() {
		return hasFlag(FLAG_HAS_TRANSPARENCY);
	}

	public void setHasTransparency(boolean value) { setFlag(FLAG_HAS_TRANSPARENCY, value); }

	public boolean isGenerated() {
		return hasFlag(FLAG_IS_GENERATED);
	}

	public void setGenerated(boolean value) {
		setFlag(FLAG_IS_GENERATED, value);
	}

	public boolean modifiesVanillaTexture() {
		return hasFlag(FLAG_MODIFIES_VANILLA_TEXTURE);
	}

	public void setModifiesVanillaTexture(boolean value) {
		setFlag(FLAG_MODIFIES_VANILLA_TEXTURE, value);
	}

	public boolean isDummy() {
		return hasFlag(FLAG_IS_DUMMY);
	}

	public void setIsDummy(boolean value) {
		setFlag(FLAG_IS_DUMMY, value);
	}

	public boolean hide() {
		return hasFlag(FLAG_HIDE);
	}

	public void setHide(boolean value) {
		setFlag(FLAG_HIDE, value);
	}

	public boolean disableDetailCulling() {
		return hasFlag(FLAG_DISABLE_DETAIL_CULLING);
	}

	public void setDisableDetailCulling(boolean value) {
		setFlag(FLAG_DISABLE_DETAIL_CULLING, value);
	}

	public boolean retainVanillaUvs() {
		return hasFlag(FLAG_RETAIN_VANILLA_UVS);
	}

	public void setRetainVanillaUvs(boolean value) { setFlag(FLAG_RETAIN_VANILLA_UVS, value); }

	public boolean forceMaterialChanges() {
		return hasFlag(FLAG_FORCE_MATERIAL_CHANGES);
	}

	public void setForceMaterialChanges(boolean value) {
		setFlag(FLAG_FORCE_MATERIAL_CHANGES, value);
	}

	public boolean flatNormals() {
		return hasFlag(FLAG_FLAT_NORMALS);
	}

	public void setFlatNormals(boolean value) { setFlag(FLAG_FLAT_NORMALS, value); }

	public boolean upwardsNormals() {
		return hasFlag(FLAG_UPWARDS_NORMALS);
	}

	public void setUpwardsNormals(boolean value) {
		setFlag(FLAG_UPWARDS_NORMALS, value);
	}

	public boolean hideVanillaShadows() {
		return hasFlag(FLAG_HIDE_VANILLA_SHADOWS);
	}

	public void setHideVanillaShadows(boolean value) {
		setFlag(FLAG_HIDE_VANILLA_SHADOWS, value);
	}

	public boolean retainVanillaShadowsInPvm() {
		return hasFlag(FLAG_RETAIN_VANILLA_SHADOWS_IN_PVM);
	}

	public void setRetainVanillaShadowsInPvm(boolean value) { setFlag(FLAG_RETAIN_VANILLA_SHADOWS_IN_PVM, value); }

	public boolean hideHdShadowsInPvm() {
		return hasFlag(FLAG_HIDE_HD_SHADOWS_IN_PVM);
	}

	public void setHideHdShadowsInPvm(boolean value) {
		setFlag(FLAG_HIDE_HD_SHADOWS_IN_PVM, value);
	}

	public boolean castShadows() {
		return hasFlag(FLAG_CAST_SHADOWS);
	}

	public void setCastShadows(boolean value) {
		setFlag(FLAG_CAST_SHADOWS, value);
	}

	public boolean receiveShadows() {
		return hasFlag(FLAG_RECEIVE_SHADOWS);
	}

	public void setReceiveShadows(boolean value) {
		setFlag(FLAG_RECEIVE_SHADOWS, value);
	}

	public boolean terrainVertexSnap() {
		return hasFlag(FLAG_TERRAIN_VERTEX_SNAP);
	}

	public void setTerrainVertexSnap(boolean value) {
		setFlag(FLAG_TERRAIN_VERTEX_SNAP, value);
	}

	public boolean undoVanillaShading() {
		return hasFlag(FLAG_UNDO_VANILLA_SHADING);
	}

	public void setUndoVanillaShading(boolean value) { setFlag(FLAG_UNDO_VANILLA_SHADING, value); }

	public boolean hideAsWaterEffect() {
		return hasFlag(FLAG_HIDE_AS_WATER_EFFECT);
	}

	public void setHideAsWaterEffect(boolean value) {
		setFlag(FLAG_HIDE_AS_WATER_EFFECT, value);
	}

	public boolean invertDisplacementStrength() {
		return hasFlag(FLAG_INVERT_DISPLACEMENT_STRENGTH);
	}

	public void setInvertDisplacementStrength(boolean value) {
		setFlag(FLAG_INVERT_DISPLACEMENT_STRENGTH, value);
	}

	public boolean disablePrioritySorting() {
		return hasFlag(FLAG_DISABLE_PRIORITY_SORTING);
	}

	public void setDisablePrioritySorting(boolean value) {
		setFlag(FLAG_DISABLE_PRIORITY_SORTING, value);
	}

	public TzHaarRecolorType getTzHaarRecolorType() {
		return getOrdinal(tzHaarRecolorType, TzHaarRecolorType.values());
	}

	public void setTzHaarRecolorType(TzHaarRecolorType value) {
		tzHaarRecolorType = (byte) value.ordinal();
	}

	public InheritTileColorType getInheritTileColorType() {
		return getOrdinal(inheritTileColorType, InheritTileColorType.values());
	}

	public void setInheritTileColorType(InheritTileColorType value) {
		inheritTileColorType = (byte) value.ordinal();
	}

	public WindDisplacement getWindDisplacementMode() {
		return getOrdinal(windDisplacementMode, WindDisplacement.values());
	}

	public void setWindDisplacementMode(WindDisplacement value) {
		windDisplacementMode = (byte) value.ordinal();
	}

	public static class AdapterFactory implements TypeAdapterFactory {
		@Override
		@SuppressWarnings("unchecked")
		public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
			if (type.getRawType() != ModelOverride.class)
				return null;

			var delegate = gson.getDelegateAdapter(this, type);
			return (TypeAdapter<T>) new TypeAdapter<ModelOverride>() {
				@Override
				public void write(JsonWriter out, ModelOverride value) throws IOException {
					delegate.write(out, (T) value);
				}

				@Override
				public ModelOverride read(JsonReader in) throws IOException {
					if (in.peek() == JsonToken.NULL) {
						in.nextNull();
						return null;
					}

					JsonElement element = new JsonParser().parse(in);
					if (!element.isJsonObject())
						return (ModelOverride) delegate.fromJsonTree(element);

					JsonObject object = element.getAsJsonObject();
					convertEnumOrdinal(object, "tzHaarRecolorType", TzHaarRecolorType.values());
					convertEnumOrdinal(object, "inheritTileColorType", InheritTileColorType.values());
					convertEnumOrdinal(object, "windDisplacementMode", WindDisplacement.values());

					var override = (ModelOverride) delegate.fromJsonTree(object);
					readFlag(object, override, "hide", FLAG_HIDE);
					readFlag(object, override, "disableDetailCulling", FLAG_DISABLE_DETAIL_CULLING);
					readFlag(object, override, "retainVanillaUvs", FLAG_RETAIN_VANILLA_UVS);
					readFlag(object, override, "forceMaterialChanges", FLAG_FORCE_MATERIAL_CHANGES);
					readFlag(object, override, "flatNormals", FLAG_FLAT_NORMALS);
					readFlag(object, override, "upwardsNormals", FLAG_UPWARDS_NORMALS);
					readFlag(object, override, "hideVanillaShadows", FLAG_HIDE_VANILLA_SHADOWS);
					readFlag(object, override, "retainVanillaShadowsInPvm", FLAG_RETAIN_VANILLA_SHADOWS_IN_PVM);
					readFlag(object, override, "hideHdShadowsInPvm", FLAG_HIDE_HD_SHADOWS_IN_PVM);
					readFlag(object, override, "castShadows", FLAG_CAST_SHADOWS);
					readFlag(object, override, "receiveShadows", FLAG_RECEIVE_SHADOWS);
					readFlag(object, override, "terrainVertexSnap", FLAG_TERRAIN_VERTEX_SNAP);
					readFlag(object, override, "undoVanillaShading", FLAG_UNDO_VANILLA_SHADING);
					readFlag(object, override, "hideAsWaterEffect", FLAG_HIDE_AS_WATER_EFFECT);
					readFlag(object, override, "invertDisplacementStrength", FLAG_INVERT_DISPLACEMENT_STRENGTH);
					readFlag(object, override, "disablePrioritySorting", FLAG_DISABLE_PRIORITY_SORTING);
					return override;
				}
			};
		}

		private static void readFlag(JsonObject object, ModelOverride override, String name, int flag) {
			var element = object.get(name);
			if (element != null && !element.isJsonNull())
				override.setFlag(flag, element.getAsBoolean());
		}

		private static void convertEnumOrdinal(JsonObject object, String name, Enum<?>[] values) {
			var element = object.get(name);
			if (element == null || element.isJsonNull() || !element.isJsonPrimitive())
				return;

			var primitive = element.getAsJsonPrimitive();
			if (primitive.isNumber())
				return;

			String value = primitive.getAsString();
			for (var enumValue : values) {
				if (enumValue.name().equals(value)) {
					object.addProperty(name, enumValue.ordinal());
					return;
				}
			}
		}
	}

	private AhslPredicate parseAhslConditions(JsonElement element) {
		if (element == null)
			return ahsl -> false;

		JsonArray arr;
		if (element.isJsonArray()) {
			arr = element.getAsJsonArray();
		} else {
			arr = new JsonArray();
			arr.add(element);
		}

		AhslPredicate combinedPredicate = null;

		for (var el : arr) {
			if (el.isJsonNull())
				continue;
			if (!el.isJsonPrimitive()) {
				log.warn("Skipping unexpected HSL condition '{}' in override '{}'", el, description);
				continue;
			}

			AhslPredicate condition;
			var prim = el.getAsJsonPrimitive();
			if (prim.isBoolean()) {
				boolean bool = prim.getAsBoolean();
				condition = ahsl -> bool;
			} else if (prim.isNumber()) {
				try {
					int targetHsl = prim.getAsInt();
					condition = ahsl -> (ahsl & 0xFFFF) == targetHsl;
				} catch (Exception ex) {
					log.warn("Expected integer, but got {} in override '{}'", el, description);
					continue;
				}
			} else if (prim.isString()) {
				var expr = asExpression(parseExpression(prim.getAsString()));

				if (Props.DEVELOPMENT) {
					// Ensure all variables are defined
					final Set<String> knownVariables = Set.of("a", "h", "s", "l", "hsl", "ahsl");
					for (var variable : expr.variables)
						if (!knownVariables.contains(variable))
							throw new IllegalStateException(
								"Expression '" + prim.getAsString() + "' contains unknown variable '" + variable + "'");
				}

				var predicate = expr.toPredicate();
				condition = ahsl -> predicate.test(key -> {
					switch (key) {
						case "a":
							return ahsl >>> 16 & 0xFF;
						case "h":
							return ahsl >>> 10 & 0x3F;
						case "s":
							return ahsl >>> 7 & 0x7;
						case "l":
							return ahsl & 0x7F;
						case "ahsl":
							return ahsl;
						case "hsl":
							return ahsl & 0xFFFF;
						default:
							assert false : "Unexpected variable: " + key;
							return 0;
					}
				});
			} else {
				log.warn("Skipping unexpected HSL condition primitive '{}' in override '{}'", el, description);
				continue;
			}

			if (combinedPredicate == null) {
				combinedPredicate = condition;
			} else {
				var prev = combinedPredicate;
				combinedPredicate = ahsl -> prev.test(ahsl) || condition.test(ahsl);
			}
		}

		if (combinedPredicate == null)
			return ahsl -> false;

		return combinedPredicate;
	}

	public void computeModelUvw(float[] out, int i, float x, float y, float z, int orientation) {
		float rad, cos, sin;
		float temp;
		if (orientation % 2048 != 0) {
			// Reverse baked vertex rotation
			rad = orientation * JAU_TO_RAD;
			cos = cos(rad);
			sin = sin(rad);
			temp = x * sin + z * cos;
			x = x * cos - z * sin;
			z = temp;
		}

		x = (x / LOCAL_TILE_SIZE + .5f) / uvScale;
		y = (y / LOCAL_TILE_SIZE + .5f) / uvScale;
		z = (z / LOCAL_TILE_SIZE + .5f) / uvScale;

		uvType.computeModelUvw(out, i, x, y, z);

		if (uvOrientation % 2048 != 0) {
			rad = uvOrientation * JAU_TO_RAD;
			cos = cos(rad);
			sin = sin(rad);
			x = out[i] - .5f;
			z = out[i + 1] - .5f;
			temp = x * sin + z * cos;
			x = x * cos - z * sin;
			z = temp;
			out[i] = x + .5f;
			out[i + 1] = z + .5f;
		}
	}

	public void fillUvsForFace(float[] out, Model model, int orientation, UvType uvType, int face, float[] workingSpace) {
		switch (uvType) {
			case WORLD_XY:
			case WORLD_XZ:
			case WORLD_YZ:
				uvType.computeWorldUvw(out, 0, uvScale);
				uvType.computeWorldUvw(out, 4, uvScale);
				uvType.computeWorldUvw(out, 8, uvScale);
				break;
			case MODEL_XY:
			case MODEL_XY_MIRROR_A:
			case MODEL_XY_MIRROR_B:
			case MODEL_XZ:
			case MODEL_XZ_MIRROR_A:
			case MODEL_XZ_MIRROR_B:
			case MODEL_YZ:
			case MODEL_YZ_MIRROR_A:
			case MODEL_YZ_MIRROR_B: {
				final float[] vertexX = model.getVerticesX();
				final float[] vertexY = model.getVerticesY();
				final float[] vertexZ = model.getVerticesZ();
				final int triA = model.getFaceIndices1()[face];
				final int triB = model.getFaceIndices2()[face];
				final int triC = model.getFaceIndices3()[face];

				computeModelUvw(out, 0, vertexX[triA], vertexY[triA], vertexZ[triA], orientation);
				computeModelUvw(out, 4, vertexX[triB], vertexY[triB], vertexZ[triB], orientation);
				computeModelUvw(out, 8, vertexX[triC], vertexY[triC], vertexZ[triC], orientation);
				break;
			}
			case BOX:
				computeBoxUvw(out, model, orientation, face, workingSpace);
				break;
			case VANILLA: {
				final byte[] textureFaces = model.getTextureFaces();
				int texFace = textureFaces == null ? -1 : textureFaces[face];
				if (texFace != -1) {
					texFace &= 0xff;

					final float[] vertexX = model.getVerticesX();
					final float[] vertexY = model.getVerticesY();
					final float[] vertexZ = model.getVerticesZ();
					final int texA = model.getTexIndices1()[texFace];
					final int texB = model.getTexIndices2()[texFace];
					final int texC = model.getTexIndices3()[texFace];

					out[0] = vertexX[texA];
					out[1] = vertexY[texA];
					out[2] = vertexZ[texA];
					out[4] = vertexX[texB];
					out[5] = vertexY[texB];
					out[6] = vertexZ[texB];
					out[8] = vertexX[texC];
					out[9] = vertexY[texC];
					out[10] = vertexZ[texC];
				}
				break;
			}
			case GEOMETRY:
			default:
				out[0] = 0;
				out[1] = 0;
				out[2] = 0;
				out[4] = 1;
				out[5] = 0;
				out[6] = 0;
				out[8] = 0;
				out[9] = 1;
				out[10] = 0;
				break;
		}
	}

	@SuppressWarnings({ "PointlessArithmeticExpression", "UnnecessaryLocalVariable" })
	private void computeBoxUvw(float[] out, Model model, int modelOrientation, int face, float[] workingSpace) {
		final float[] verticesX = model.getVerticesX();
		final float[] verticesY = model.getVerticesY();
		final float[] verticesZ = model.getVerticesZ();

		final float[] v = workingSpace;
		int vidx;
		vidx = model.getFaceIndices1()[face];
		v[0 * 3 + 0] = verticesX[vidx];
		v[0 * 3 + 1] = verticesY[vidx];
		v[0 * 3 + 2] = verticesZ[vidx];
		vidx = model.getFaceIndices2()[face];
		v[1 * 3 + 0] = verticesX[vidx];
		v[1 * 3 + 1] = verticesY[vidx];
		v[1 * 3 + 2] = verticesZ[vidx];
		vidx = model.getFaceIndices3()[face];
		v[2 * 3 + 0] = verticesX[vidx];
		v[2 * 3 + 1] = verticesY[vidx];
		v[2 * 3 + 2] = verticesZ[vidx];

		float rad, cos, sin;
		float temp;
		if (modelOrientation % 2048 != 0) {
			// Reverse baked vertex rotation
			rad = modelOrientation * JAU_TO_RAD;
			cos = cos(rad);
			sin = sin(rad);

			for (int i = 0; i < 3; i++) {
				temp = v[i * 3] * sin + v[i * 3 + 2] * cos;
				v[i * 3] = v[i * 3] * cos - v[i * 3 + 2] * sin;
				v[i * 3 + 2] = temp;
			}
		}

		for (int i = 0; i < 3; i++) {
			v[i * 3] = (v[i * 3] / LOCAL_TILE_SIZE + .5f) / uvScale;
			v[i * 3 + 1] = (v[i * 3 + 1] / LOCAL_TILE_SIZE + .5f) / uvScale;
			v[i * 3 + 2] = (v[i * 3 + 2] / LOCAL_TILE_SIZE + .5f) / uvScale;
		}

		// Compute face normal as cross(v[1] - v[0], v[2] - v[0])
		float nx = (v[3 + 1] - v[1]) * (v[6 + 2] - v[2]) - (v[3 + 2] - v[2]) * (v[6 + 1] - v[1]);
		float ny = (v[3 + 2] - v[2]) * (v[6 + 0] - v[0]) - (v[3 + 0] - v[0]) * (v[6 + 2] - v[2]);
		float nz = (v[3 + 0] - v[0]) * (v[6 + 1] - v[1]) - (v[3 + 1] - v[1]) * (v[6 + 0] - v[0]);
		float absNx = abs(nx);
		float absNy = abs(ny);
		float absNz = abs(nz);

		out[2] = out[6] = out[10] = 0;
		if (absNx > absNy && absNx > absNz) {
			// YZ plane
			float flip = sign(nx);
			for (int tri = 0; tri < 3; tri++) {
				out[tri * 4] = flip * -v[tri * 3 + 2];
				out[tri * 4 + 1] = v[tri * 3 + 1];
			}

			if (uvOrientationX % 2048 != 0) {
				rad = uvOrientationX * JAU_TO_RAD;
				cos = cos(rad);
				sin = sin(rad);

				for (int i = 0; i < 3; i++) {
					int j = i * 4;
					v[i * 3] = out[j] - .5f;
					v[i * 3 + 2] = out[j + 1] - .5f;
					temp = v[i * 3] * sin + v[i * 3 + 2] * cos;
					v[i * 3] = v[i * 3] * cos - v[i * 3 + 2] * sin;
					v[i * 3 + 2] = temp;
					out[j] = v[i * 3] + .5f;
					out[j + 1] = v[i * 3 + 2] + .5f;
				}
			}
		} else if (absNy > absNx && absNy > absNz) {
			// XZ
			float flip = sign(ny);
			for (int tri = 0; tri < 3; tri++) {
				out[tri * 4] = flip * -v[tri * 3];
				out[tri * 4 + 1] = v[tri * 3 + 2];
			}

			if (uvOrientationY % 2048 != 0) {
				rad = uvOrientationY * JAU_TO_RAD;
				cos = cos(rad);
				sin = sin(rad);

				for (int i = 0; i < 3; i++) {
					int j = i * 4;
					v[i * 3] = out[j] - .5f;
					v[i * 3 + 2] = out[j + 1] - .5f;
					temp = v[i * 3] * sin + v[i * 3 + 2] * cos;
					v[i * 3] = v[i * 3] * cos - v[i * 3 + 2] * sin;
					v[i * 3 + 2] = temp;
					out[j] = v[i * 3] + .5f;
					out[j + 1] = v[i * 3 + 2] + .5f;
				}
			}
		} else {
			// XY
			float flip = sign(nz);
			for (int tri = 0; tri < 3; tri++) {
				out[tri * 4] = flip * v[tri * 3];
				out[tri * 4 + 1] = v[tri * 3 + 1];
			}

			if (uvOrientationZ % 2048 != 0) {
				rad = uvOrientationZ * JAU_TO_RAD;
				cos = cos(rad);
				sin = sin(rad);

				for (int i = 0; i < 3; i++) {
					int j = i * 4;
					v[i * 3] = out[j] - .5f;
					v[i * 3 + 2] = out[j + 1] - .5f;
					temp = v[i * 3] * sin + v[i * 3 + 2] * cos;
					v[i * 3] = v[i * 3] * cos - v[i * 3 + 2] * sin;
					v[i * 3 + 2] = temp;
					out[j] = v[i * 3] + .5f;
					out[j + 1] = v[i * 3 + 2] + .5f;
				}
			}
		}
	}

	public void applyRotation(Model model) {
		switch (rotate) {
			case 0:
				break;
			case 90:
				model.rotateY90Ccw();
				break;
			case 180:
				model.rotateY180Ccw();
				break;
			case 270:
				model.rotateY270Ccw();
				break;
			default:
				log.debug(
					"Unsupported rotation of {} degrees in model override: '{}'",
					rotate,
					description
				);
				break;
		}
	}

	public void revertRotation(Model model) {
		switch (rotate) {
			case 90:
				model.rotateY270Ccw();
				break;
			case 180:
				model.rotateY180Ccw();
				break;
			case 270:
				model.rotateY90Ccw();
				break;
		}
	}

	@Nullable
	public final ModelOverride testColorOverrides(int ahsl) {
		final int len = colorOverrides.length;
		for (int i = 0; i < len; ++i) {
			final var inner = colorOverrides[i];
			if (inner.ahslCondition.test(ahsl))
				return inner;
		}

		return null;
	}
}
