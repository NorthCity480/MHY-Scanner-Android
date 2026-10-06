#!/usr/bin/env bash
set -euo pipefail
# Source remains on shared storage; Gradle/JDK/SDK stay in Termux private storage.
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${MHY_APP_VERSION:-0.1.8}"
CACHE="${MHY_BUILD_CACHE:-$HOME/.cache/mhy-android-build}"
export JAVA_HOME="${JAVA_HOME:-$PREFIX/lib/jvm/java-17-openjdk}"
SDK="${ANDROID_HOME:-$CACHE/sdk}"
GRADLE="${GRADLE_BIN:-$CACHE/gradle-8.9/bin/gradle}"
if [ ! -f "$GRADLE" ] || [ ! -f "$SDK/platforms/android-35/android.jar" ] || ! command -v aapt2 >/dev/null; then
  printf 'Missing build tools. Read README.md -> Termux build preparation.\n' >&2
  exit 1
fi
printf 'sdk.dir=%s\n' "$SDK" > "$PROJECT/local.properties"
cd "$PROJECT"
bash "$GRADLE" -Dorg.gradle.native=false -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:testDebugUnitTest :app:assembleDebug "$@"
mkdir -p "$PROJECT/dist"
cp app/build/outputs/apk/debug/app-debug.apk "dist/MHY-Scanner-Android-v${VERSION}-debug.apk"
printf '\nBuilt: %s\n' "$PROJECT/dist/MHY-Scanner-Android-v${VERSION}-debug.apk"
