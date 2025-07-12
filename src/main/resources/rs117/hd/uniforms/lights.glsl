#pragma once

#include LIGHT_COUNT

struct PointLight
{
    vec4 position;
    vec3 color;
};

layout(std140) uniform PointLightUniforms {
    PointLight PointLightArray[LIGHT_COUNT];
};

layout(std140) uniform TiledLightingIndiciesUniforms {
	ivec4 tiledLightIndicies[24 * 11 * 12]; // TODO: This needs converting into a TextureLookup, 4000 indicies isn't enough
};

#include LIGHT_GETTER
