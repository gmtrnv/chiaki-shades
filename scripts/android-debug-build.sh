#!/bin/bash
# Builds the Android debug APK on Windows (Git Bash) and optionally installs it.
#   bash scripts/android-debug-build.sh            # build only
#   bash scripts/android-debug-build.sh install    # build + adb install on $ADB_DEVICE
# Needs: JDK 17, Android SDK with NDK 26.1.10909125 + CMake 3.22.1, protoc (winget
# Google.Protobuf) and a uv venv at .venv with `protobuf` (uv venv && uv pip install protobuf).
set -e
REPO="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME_17:-/c/Program Files/Eclipse Adoptium/jdk-17.0.13.11-hotspot}"
LOCALAPPDATA_U="$(cygpath -u "$LOCALAPPDATA")"
# Windows "C:\..." paths break bash PATH (":" is the separator), so convert them first
PROTOC_DIR=$(ls -d "$LOCALAPPDATA_U"/Microsoft/WinGet/Packages/Google.Protobuf*/bin 2>/dev/null | head -1)
export PATH="$REPO/.venv/Scripts:${PROTOC_DIR:+$PROTOC_DIR:}$JAVA_HOME/bin:$PATH"
command -v protoc >/dev/null || { echo "protoc not found"; exit 1; }

# BUILD_TYPE=release is used by scripts/android-release-build.sh
TYPE="${BUILD_TYPE:-debug}"
TASK="assemble$(echo "${TYPE:0:1}" | tr a-z A-Z)${TYPE:1}"
cd "$REPO/android"
# PRUEBA=1 builds the "test" copy (io.github.gmtrnv.chiaki.prueba, "Chiaki Shades (prueba)"): it
# installs next to the real app with no settings or consoles, like a clean download
./gradlew.bat "$TASK" --console=plain ${PRUEBA:+-Pprueba}

if [ "$1" = "install" ]; then
	ADB="$LOCALAPPDATA_U/Android/Sdk/platform-tools/adb.exe"
	"$ADB" ${ADB_DEVICE:+-s "$ADB_DEVICE"} install -r "app/build/outputs/apk/$TYPE/app-$TYPE.apk"
fi
