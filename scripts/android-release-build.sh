#!/bin/bash
# Builds the optimized, release-signed APK (the one to publish) and optionally installs it.
#   bash scripts/android-release-build.sh            # build only
#   bash scripts/android-release-build.sh install    # build + adb install on $ADB_DEVICE
# Output: android/app/build/outputs/apk/release/app-release.apk
# Needs the release key configured in android/local.properties (chiakiKeystore, chiakiKeystorePW,
# chiakiKeyAlias, chiakiKeyPW); see "Build" in README.md. Without it the APK would come out unsigned.
set -e
REPO="$(cd "$(dirname "$0")/.." && pwd)"
grep -q "^chiakiKeystore=" "$REPO/android/local.properties" 2>/dev/null || {
	echo "Release key not configured in android/local.properties (see \"Build\" in README.md)"; exit 1; }
BUILD_TYPE=release bash "$REPO/scripts/android-debug-build.sh" "$@"
echo "APK: $REPO/android/app/build/outputs/apk/release/app-release.apk"
