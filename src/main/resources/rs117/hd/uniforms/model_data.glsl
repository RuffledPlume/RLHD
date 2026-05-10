#pragma once

#include <utils/texture_buffer_reader.glsl>

uniform isamplerBuffer modelData;

struct ModelData {
    ivec3 position;
    int height;
};

#define PARSER_TARGET_BUFFER modelData

BEGIN_BUFFER_PARSER(getModelData, ModelData)
    READ_IVEC3(position)
    READ_INT(height)
END_BUFFER_PARSER()

#undef PARSER_TARGET_BUFFER