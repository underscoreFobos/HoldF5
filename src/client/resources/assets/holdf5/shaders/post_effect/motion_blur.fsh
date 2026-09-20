#version 150

uniform sampler2D InSampler;
uniform sampler2D PrevSampler;

layout(std140) uniform Strength {
    float blendStrength;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 current = texture(InSampler, texCoord);
    vec4 prev = texture(PrevSampler, texCoord);
    fragColor = mix(current, prev, blendStrength);
}
