#version 330

uniform sampler2DMS firstLayerMS;
uniform sampler2DMS lastLayerMS;
uniform int sampleCount;

layout(location = 0) out float outFirstLayer;
layout(location = 1) out float outLastLayer;

void main() {
	ivec2 coords = ivec2(gl_FragCoord.xy);
	float nearest = 1e6;
	float farthest = -1e6;
	for (int i = 0; i < sampleCount; i++) {
		nearest = min(nearest, texelFetch(firstLayerMS, coords, i).r);
		farthest = max(farthest, texelFetch(lastLayerMS, coords, i).r);
	}

	outFirstLayer = nearest;
	outLastLayer = farthest;
}
