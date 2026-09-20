#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D PrevSampler;

uniform float Strength;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 current = texture(DiffuseSampler, texCoord);
    vec4 prev = texture(PrevSampler, texCoord);
    fragColor = mix(current, prev, Strength);
}
