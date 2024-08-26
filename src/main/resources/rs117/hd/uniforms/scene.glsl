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

layout(binding = 2) uniform sampler2DArray textureArray;
layout(binding = 3) uniform sampler2D shadowMap;

layout(location = 0) uniform mat4 projectionMatrix;
layout(location = 1) uniform vec3 cameraPos;
layout(location = 2) uniform float drawDistance;
layout(location = 3) uniform int expandedMapLoadingChunks;
layout(location = 4) uniform mat4 lightProjectionMatrix;
layout(location = 5) uniform float elapsedTime;
layout(location = 6) uniform float colorBlindnessIntensity;
layout(location = 7) uniform int useFog;
layout(location = 8) uniform float fogDepth;
layout(location = 9) uniform vec3 fogColor;
layout(location = 10) uniform vec3 waterColorLight;
layout(location = 11) uniform vec3 waterColorMid;
layout(location = 12) uniform vec3 waterColorDark;
layout(location = 13) uniform vec3 ambientColor;
layout(location = 14) uniform float ambientStrength;
layout(location = 15) uniform vec3 lightColor;
layout(location = 16) uniform float lightStrength;
layout(location = 17) uniform vec3 underglowColor;
layout(location = 18) uniform float underglowStrength;
layout(location = 19) uniform float groundFogStart;
layout(location = 20) uniform float groundFogEnd;
layout(location = 21) uniform float groundFogOpacity;
layout(location = 22) uniform float lightningBrightness;
layout(location = 23) uniform vec3 lightDir;
layout(location = 24) uniform float shadowMaxBias;
layout(location = 25) uniform int shadowsEnabled;
layout(location = 26) uniform int underwaterEnvironment;
layout(location = 27) uniform int underwaterCaustics;
layout(location = 28) uniform vec3 underwaterCausticsColor;
layout(location = 29) uniform float underwaterCausticsStrength;

// general HD settings
layout(location = 30) uniform float saturation;
layout(location = 31) uniform float contrast;

layout(location = 32) uniform int pointLightsCount; // number of lights in current frame