#version 300 es
// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform samplerExternalOES uTex;
void main() {
    fragColor = texture(uTex, vTexCoord);
}
