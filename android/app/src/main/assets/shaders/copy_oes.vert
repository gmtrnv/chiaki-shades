#version 300 es
// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Copy pass: decoder frame (OES texture) -> stream-sized FBO.
in vec4 aPosition;
in vec2 aTexCoord;
out vec2 vTexCoord;
uniform mat4 uSTMatrix;
void main() {
    gl_Position = aPosition;
    vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}
