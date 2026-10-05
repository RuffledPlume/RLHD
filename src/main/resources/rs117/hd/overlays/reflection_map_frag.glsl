#version 330

#include <utils/constants.glsl>
#include <utils/color_utils.glsl>

uniform sampler2D colorMap;

in vec2 fUv;

out vec4 FragColor;

void main() {
    vec3 c = texture(colorMap, fUv).rgb;

    #if LINEAR_ALPHA_BLENDING
        // The atlas is stored in sRGB, so convert it for display.
        c = linearToSrgb(c);
    #endif

    FragColor = vec4(c, 1);
}
