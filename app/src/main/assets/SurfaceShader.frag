#version 310 es
#extension GL_OES_EGL_image_external_essl3 : require

precision mediump float;

in vec2 vPos;
out vec4 fragColor;
uniform samplerExternalOES hbSampler;

void main() {
    fragColor = texture(hbSampler, vPos)
;
}
