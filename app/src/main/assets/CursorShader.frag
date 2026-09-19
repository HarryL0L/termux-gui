#version 100

precision mediump float;

varying vec2 vPos;
uniform sampler2D cursorSampler;

void main() {
    gl_FragColor = texture2D(cursorSampler, vPos);
}
