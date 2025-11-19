/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
#version 330

#include <uniforms/global.glsl>
#include <uniforms/world_views.glsl>

layout (location = 0) in vec3 vPosition;
layout (location = 1) in vec3 vUv;
layout (location = 2) in vec3 vNormal;
layout (location = 3) in int vAlphaBiasHsl;
layout (location = 4) in int vMaterialData;
layout (location = 5) in int vTerrainData;
layout (location = 6) in int vWorldViewId;
layout (location = 7) in ivec2 vSceneBase;

#if USE_GEOM_SHADER
out vec3 gPosition;
out vec3 gUv;
out vec3 gNormal;
out int gAlphaBiasHsl;
out int gMaterialData;
out int gTerrainData;
out int gWorldViewId;

void main() {
    vec3 sceneOffset = vec3(vSceneBase.x, 0, vSceneBase.y);
    gPosition = vec3(getWorldViewProjection(vWorldViewId) * vec4(sceneOffset + vPosition, 1));
    gUv = vUv;
    gNormal = vNormal;
    gAlphaBiasHsl = vAlphaBiasHsl;
    gMaterialData = vMaterialData;
    gTerrainData = vTerrainData;
    gWorldViewId = vWorldViewId;
}
#else
out FragmentData {
    vec3 position;
    vec2 uv;
    vec3 normal;
    vec3 texBlend;
} OUT;

flat out ivec3 fAlphaBiasHsl;
flat out ivec3 fMaterialData;
flat out ivec3 fTerrainData;
flat out vec3 T;
flat out vec3 B;

void main() {
    vec3 sceneOffset = vec3(vSceneBase.x, 0, vSceneBase.y);
    vec3 worldPosition = vec3(getWorldViewProjection(vWorldViewId) * vec4(sceneOffset + vPosition, 1));

    vec4 clipPosition = projectionMatrix * vec4(worldPosition, 1.0);
#if ZONE_RENDERER
    int depthBias = (vAlphaBiasHsl >> 16) & 0xff;
    clipPosition.z += depthBias / 128.0;
#endif
    gl_Position = clipPosition;

    // Calculate tangent-space vectors
    mat2 triToUv = mat2(1); // TODO: Calculate tangent during upload
    if (determinant(triToUv) == 0)
        triToUv = mat2(1);
    mat2 uvToTri = inverse(triToUv) * -1; // Flip UV direction, since OSRS UVs are oriented strangely
    mat2x3 triToWorld = mat2x3(1); // TODO: Inverse of something
    mat2x3 TB = triToWorld * uvToTri; // Preserve scale in order for displacement to interact properly with shadow mapping
    T = TB[0];
    B = TB[1];
    vec3 N = normalize(cross(triToWorld[0], triToWorld[1]));

#if UNDO_VANILLA_SHADING && ZONE_RENDERER && 0
    if ((vMaterialData >> MATERIAL_FLAG_UNDO_VANILLA_SHADING & 1) == 1) {
        vec3 normal = vNormal;
        float magnitude = length(normal);
        if (magnitude == 0) {
            normal = N;
        } else {
            normal /= magnitude;
        }
        // TODO: Rotate normal for player shading reversal
        undoVanillaShading(vAlphaBiasHsl, normal);
    }
#endif

    OUT.position = worldPosition;
    OUT.uv = vUv.xy;
#if FLAT_SHADING
    OUT.normal = N;
#else
    OUT.normal = length(vNormal) == 0 ? N : normalize(vNormal);
#endif
    //OUT.texBlend = vec3(0.0); TODO: Dunno *Shrug*

    fAlphaBiasHsl = ivec3(vAlphaBiasHsl);
    fMaterialData = ivec3(vMaterialData);
    fTerrainData = ivec3(vTerrainData);
}
#endif