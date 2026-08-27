#version 310 es

in vec2 pos;
in vec2 tpos;

out vec2 vPos;

void main() {
    gl_Position = vec4(pos, 0.0, 1.0);
    vPos = tpos
;
}
