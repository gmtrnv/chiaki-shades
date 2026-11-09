#!/bin/bash
# Validates the GLSL ES 3.00 shaders in assets/shaders with the NDK's glslc, without a device.
# glslc only accepts ES 3.10+ for SPIR-V, so the #version line is bumped for the check
# (3.10 is a superset). Mali can still be stricter: always test on the device too.
# Usage: bash scripts/check-shader.sh [file.frag ...]   (default: every .frag in assets/shaders)
REPO="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
GLSLC="$(ls "$SDK"/ndk/*/shader-tools/*/glslc* 2>/dev/null | tail -1)"
if [ -z "$GLSLC" ]; then
  echo "glslc not found under $SDK/ndk" >&2
  exit 2
fi
[ $# -eq 0 ] && set -- "$REPO"/android/app/src/main/assets/shaders/*.frag
TMP="$(mktemp -d)"
status=0
for f in "$@"; do
  out="$TMP/$(basename "$f")"
  sed '1s/#version 300 es/#version 310 es/' "$f" > "$out"
  # the OES copy shader needs the external texture extension, which glslc does not know
  if grep -q samplerExternalOES "$out"; then
    echo "SKIP $f (samplerExternalOES)"
    continue
  fi
  if "$GLSLC" --target-env=opengl -fauto-map-locations -fauto-bind-uniforms -fshader-stage=frag -o /dev/null "$out"; then
    echo "OK $f"
  else
    status=1
  fi
done
rm -rf "$TMP"
exit $status
