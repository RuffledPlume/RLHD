#pragma once

#include LIGHT_COUNT
#include LIGHT_COUNT_PER_TILE
#include TILED_LIGHTING_USE_SUBGROUP

#if TILED_LIGHTING_USE_SUBGROUP
#undef TILED_LIGHTING_USE_SUBGROUP // TODO: TiledLighting Cells don't align with warps at the moment...
#endif

#if TILED_LIGHTING_USE_SUBGROUP
#extension GL_KHR_shader_subgroup_basic : enable
#extension GL_KHR_shader_subgroup_ballot : enable
#extension GL_KHR_shader_subgroup_vote : enable
#endif

struct PointLight
{
    vec4 position;
    vec3 color;
};

layout(std140) uniform PointLightUniforms {
    PointLight PointLightArray[LIGHT_COUNT];
};
