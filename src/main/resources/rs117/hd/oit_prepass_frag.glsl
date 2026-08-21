#version 330
#include "scene_common.glsl"

in float vViewZ;

layout(location = 0) out float outDepth;
layout(location = 1) out float outLastLayerDepth;
layout(location = 2) out float outCoverage;

void main() {
	vec4 outputColor = shadeFragment();
	if (outputColor.a <= 0.001)
		discard;

	outDepth = abs(vViewZ);
	outCoverage = outputColor.a;
	outLastLayerDepth = abs(vViewZ);
}
