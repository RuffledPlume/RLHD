#version 330

#include "scene_common.glsl"

out vec3 FragColor;

void main() {
    vec4 frag = shadeFragment();
    if(frag.a < 0.5)
        discard;
    FragColor = frag.rgb;
}
