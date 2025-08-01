package rs117.hd.opengl.uniforms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.buffer.SharedGLBuffer;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public class UBOCompute extends UniformBuffer<SharedGLBuffer> {
	public static final int MAX_CHARACTER_POSITION_COUNT = 50;

	private static final Comparator<CharacterPositionPair> CHARACTER_POSITION_PAIR_COMPARATOR =
		Comparator.comparingDouble(p -> p.dist);

	// Camera uniforms
	public Property yaw = addProperty(PropertyType.Float, "yaw");
	public Property pitch = addProperty(PropertyType.Float, "pitch");
	public Property centerX = addProperty(PropertyType.Int, "centerX");
	public Property centerY = addProperty(PropertyType.Int, "centerY");
	public Property zoom = addProperty(PropertyType.Int, "zoom");
	public Property cameraX = addProperty(PropertyType.Float, "cameraX");
	public Property cameraY = addProperty(PropertyType.Float, "cameraY");
	public Property cameraZ = addProperty(PropertyType.Float, "cameraZ");

	// Wind uniforms
	public Property windDirectionX = addProperty(PropertyType.Float, "windDirectionX");
	public Property windDirectionZ = addProperty(PropertyType.Float, "windDirectionZ");
	public Property windStrength = addProperty(PropertyType.Float, "windStrength");
	public Property windCeiling = addProperty(PropertyType.Float, "windCeiling");
	public Property windOffset = addProperty(PropertyType.Float, "windOffset");

	private final Property wobbleCount = addProperty(PropertyType.Int, "wobbleCount");
	private final Property[] wobblePositions = addPropertyArray(PropertyType.FVec4, "wobblePositions", MAX_CHARACTER_POSITION_COUNT);

	private final Property characterPositionCount = addProperty(PropertyType.Int, "characterPositionCount");
	private final Property[] characterPositions = addPropertyArray(PropertyType.FVec3, "characterPositions", MAX_CHARACTER_POSITION_COUNT);

	private final ArrayList<WobblePair> wobblePairs = new ArrayList<>(wobblePositions.length);
	private int activeWobbles;

	private final ArrayList<CharacterPositionPair> characterPositionsPairs = new ArrayList<>(characterPositions.length);
	private int writtenCharacterPositions;
	private float playerPosX, playerPosZ;

	private static class WobblePair {
		public int x;
		public int y;
		public int plane;
		public float wobble;
	}

	private static class CharacterPositionPair {
		public float x;
		public float z;
		public float radius;
		public float dist = Float.MAX_VALUE;
	}

	public UBOCompute() {
		super(GL_DYNAMIC_DRAW, CL_MEM_READ_ONLY);
	}

	private CharacterPositionPair getCharacterPositionPair() {
		if (writtenCharacterPositions >= characterPositionsPairs.size()) {
			CharacterPositionPair newPair = new CharacterPositionPair();
			characterPositionsPairs.add(newPair);
			return newPair;
		}

		return characterPositionsPairs.get(writtenCharacterPositions);
	}

	public void updateWobbles(float deltaTime) {
		for (int i = activeWobbles - 1; i >= 0; i--) {
			WobblePair pair = wobblePairs.get(i);
			pair.wobble -= deltaTime;

			if (pair.wobble <= 0.0f) {
				if (i < activeWobbles - 1) {
					// Move the Pair to the end
					wobblePairs.remove(i);
					wobblePairs.add(wobblePairs.size() - 1, pair);
				}
				activeWobbles--;
			} else {
				wobblePositions[i].set((float) pair.x, (float) pair.plane, (float) pair.y, pair.wobble);
			}
		}
		wobbleCount.set(activeWobbles);
	}

	public void addWobbleHit(int x, int y, int plane) {
		if (activeWobbles < wobblePositions.length) {
			WobblePair pair = null;
			for (int i = 0; i < activeWobbles; i++) {
				WobblePair other = wobblePairs.get(i);
				if (other.x == x && other.y == y && other.plane == plane) {
					pair = other;
					break;
				}
			}

			if (pair == null) {
				if (activeWobbles < wobblePairs.size()) {
					pair = wobblePairs.get(activeWobbles);
				} else {
					pair = new WobblePair();
					wobblePairs.add(pair);
				}
				activeWobbles++;
			}

			pair.x = x;
			pair.y = y;
			pair.plane = plane;
			pair.wobble = 1.0f;
		}
	}

	public void addCharacterPosition(int localX, int localZ, int modelRadius) {
		int writeIndex = writtenCharacterPositions;
		CharacterPositionPair pair = getCharacterPositionPair();
		characterPositionsPairs.remove(writeIndex);

		pair.x = localX;
		pair.z = localZ;
		pair.radius = modelRadius * 1.25f;

		if (writeIndex == 0) {
			playerPosX = pair.x;
			playerPosZ = pair.z;
			pair.dist = 0.0f;
		} else {
			pair.dist = Math.abs(playerPosX - pair.x) + Math.abs(playerPosZ - pair.z);

			if (writeIndex > 1) {
				int index = Collections.binarySearch(
					characterPositionsPairs.subList(1, writeIndex),
					pair,
					CHARACTER_POSITION_PAIR_COMPARATOR
				);

				writeIndex = index >= 0 ? index : -index - 1;
			}
		}

		characterPositionsPairs.add(writeIndex, pair);
		writtenCharacterPositions++;
	}

	@Override
	protected void preUpload() {
		for (int i = 0; i < writtenCharacterPositions; i++) {
			CharacterPositionPair pair = characterPositionsPairs.get(i);
			pair.dist = Float.MAX_VALUE;

			if (i < characterPositions.length)
				characterPositions[i].set(pair.x, pair.z, pair.radius);
		}
		characterPositionCount.set(Math.min(writtenCharacterPositions, characterPositions.length));
		writtenCharacterPositions = 0;
	}
}
