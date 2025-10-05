#!/usr/bin/env bash
set -euo pipefail
# Downloads Gradle 8.7 distribution and extracts gradle-wrapper.jar into app/android/gradle/wrapper
ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
ANDROID_DIR="$ROOT_DIR/app/android"
WRAPPER_DIR="$ANDROID_DIR/gradle/wrapper"
mkdir -p "$WRAPPER_DIR"
ZIP_URL="https://services.gradle.org/distributions/gradle-8.7-bin.zip"
TMP_ZIP=$(mktemp)
TMP_DIR=$(mktemp -d)
curl -fsSL "$ZIP_URL" -o "$TMP_ZIP"
unzip -q "$TMP_ZIP" -d "$TMP_DIR"
JAR=$(find "$TMP_DIR" -name 'gradle-wrapper-*.jar' | head -n1)
if [[ -z "$JAR" ]]; then
  echo "gradle-wrapper jar not found" >&2
  exit 1
fi
cp "$JAR" "$WRAPPER_DIR/gradle-wrapper.jar"
echo "Wrapper jar placed at $WRAPPER_DIR/gradle-wrapper.jar"
