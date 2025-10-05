#!/usr/bin/env bash
set -euo pipefail

# Build signed release APK using environment variables for signing
# Required env: ANDROID_KEYSTORE, ANDROID_KEY_ALIAS, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_PASSWORD

cd app/android
./gradlew clean assembleRelease

echo "APK built under app/android/app/build/outputs/apk/release" >&2
