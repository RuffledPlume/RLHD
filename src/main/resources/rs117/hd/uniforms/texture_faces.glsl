#pragma once

#include <utils/texture_buffer_reader.glsl>

#define TEXTURE_FACE_IS_MODEL (1 << 31)
#define TEXTURE_FACE_ZONE_ID_SHIFT 20
#define TEXTURE_FACE_ZONE_ID_MASK 0x7FF00000
#define TEXTURE_FACE_OFFSET_MASK 0xFFFFF

#define PARSER_TARGET_BUFFER textureFaces

SETUP_BUFFER(textureFaces, TEXTURE_FACES_SSBO_BINDING)

struct StaticFaceData {
    // STATIC_FACE_FORMAT
    ivec3 AlphaBiasHsl;
    ivec3 MaterialData;
    ivec3 TerrainData;
};

struct ModelFaceData {
    // MODEL_FACE_FORMAT
    ivec3 AlphaBiasHsl;
    int MaterialData;
};

bool isModelFace(int packedFaceData) {
    return (packedFaceData & TEXTURE_FACE_IS_MODEL) != 0;
}

// Id of the zone the face belongs to, used to look up the zone's data in the zone table.
int getZoneId(int packedFaceData) {
    return (packedFaceData & TEXTURE_FACE_ZONE_ID_MASK) >> TEXTURE_FACE_ZONE_ID_SHIFT;
}

int getFaceOffset(int packedFaceData) {
    return packedFaceData & TEXTURE_FACE_OFFSET_MASK;
}

// STATIC_FACE_FORMAT
BEGIN_BUFFER_PARSER(getStaticFaceData, StaticFaceData, false)
    READ_IVEC3(AlphaBiasHsl)
    READ_IVEC3(MaterialData)
    READ_IVEC3(TerrainData)
END_BUFFER_PARSER()

// MODEL_FACE_FORMAT
BEGIN_BUFFER_PARSER(getModelFaceData, ModelFaceData, false)
    int packedHslAB = READ_RAW_INT();
    int packedHslCAlphaBias = READ_RAW_INT();
    int alphaBias = packedHslCAlphaBias & 0xFFFF0000;

    data.AlphaBiasHsl = ivec3(
        (packedHslAB & 0xFFFF) | alphaBias,
        ((packedHslAB >> 16) & 0xFFFF) | alphaBias,
        packedHslCAlphaBias
    );

    READ_INT(MaterialData)
END_BUFFER_PARSER()

#undef PARSER_TARGET_BUFFER
