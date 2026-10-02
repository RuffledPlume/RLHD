#pragma once

#include <utils/texture_buffer_reader.glsl>

#define ZONE_DATA_SIZE 6
#define PARSER_TARGET_BUFFER zoneData

#if ZONE_DATA_SSBO_BINDING != -1
SETUP_BUFFER(zoneData, ZONE_DATA_SSBO_BINDING)
#endif

// Per-zone data, addressed by the zone id packed into every vertex's face index.
struct ZoneStruct {
    // ZONE_DATA_FORMAT
    int worldViewIdx;
    ivec2 sceneBase;
    float fade;
    int faceBase;
    int modelBase;
};

// ZONE_DATA_FORMAT
BEGIN_BUFFER_PARSER(readZoneStruct, ZoneStruct, true)
    READ_INT(worldViewIdx)
    READ_IVEC2(sceneBase)
    READ_FLOAT(fade)
    READ_INT(faceBase)
    READ_INT(modelBase)
END_BUFFER_PARSER()

ZoneStruct getZoneStruct(int zoneId) {
    return readZoneStruct(zoneId * ZONE_DATA_SIZE);
}

#undef PARSER_TARGET_BUFFER
