#version 300 es
// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Fullscreen quad for the effect pass.
// vPos: 0..1 with the origin at the TOP-LEFT (same convention as mpv's HOOKED_pos).
in vec4 aPosition;
in vec2 aTexCoord;
out vec2 vPos;
void main() {
    gl_Position = aPosition;
    vPos = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
}
