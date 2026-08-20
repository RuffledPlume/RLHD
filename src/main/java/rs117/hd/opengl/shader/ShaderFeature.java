package rs117.hd.opengl.shader;

public interface ShaderFeature {
	ShaderFeature[] NONE = {};

	int ordinal();
	String name();

	default String getDefineName() { return "FEATURE_" + name(); }
	default ShaderFeature[] getDependencies() { return NONE; }
	default int mask() { return 1 << ordinal(); }

	static int mask(ShaderFeature... features) {
		int mask = 0;
		for (var feature : features)
			mask |= feature.mask();
		return mask;
	}
}
