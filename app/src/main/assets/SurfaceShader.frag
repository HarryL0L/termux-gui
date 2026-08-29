#version 100
#extension GL_OES_EGL_image_external : require

precision mediump float;

varying vec2 vPos;
uniform samplerExternalOES hbSampler;
uniform sampler2D cursorSampler;
uniform vec4 cursorRect;
uniform vec2 cursorSize;
uniform float cursorVisible;

void main() {
    vec4 color = texture2D(hbSampler, vPos);
    if (cursorVisible > 0.5 && gl_FragCoord.x >= cursorRect.x && gl_FragCoord.x < cursorRect.z && gl_FragCoord.y >= cursorRect.y && gl_FragCoord.y < cursorRect.w) {
        vec2 uv = (gl_FragCoord.xy - cursorRect.xy) / cursorSize;
        vec4 cursor = texture2D(cursorSampler, uv);
        color = mix(color, cursor, cursor.a);
    }
    gl_FragColor = color;
}
