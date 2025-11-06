#version 330

uniform sampler2D oitResolve;

out vec4 FragColor;

void main() {
    vec4 accum = texelFetch(oitResolve, ivec2(gl_FragCoord.xy), 0);
    accum /= accum.a;
    FragColor = vec4(accum.rgb, 1.0);
}
