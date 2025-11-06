#version 330

layout (location = 0) in vec2 vPos;

out vec2 fUv;

void main() {
    gl_Position = vec4(vPos, 0, 1);
}
