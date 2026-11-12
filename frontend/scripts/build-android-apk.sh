#!/usr/bin/env bash
set -euo pipefail
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
command -v node >/dev/null 2>&1 || fail "Node.js 22.22+ is required."
command -v npm >/dev/null 2>&1 || fail "npm 11.19+ is required."
command -v java >/dev/null 2>&1 || fail "JDK 17+ is required."
command -v rustup >/dev/null 2>&1 || fail "Rustup is required."
command -v cargo >/dev/null 2>&1 || fail "Cargo is required."
node -e 'const [a,b]=process.versions.node.split(".").map(Number); if(a<22 || (a===22 && b<22)) process.exit(1)' || fail "Node.js must be version 22.22 or newer."
npm -s --version | awk -F. '{ if ($1 < 11 || ($1 == 11 && $2 < 19)) exit 1 }' || fail "npm must be version 11.19 or newer and below 12."
java -version 2>&1 | grep -Eq 'version "1[7-9]|version "[2-9][0-9]' || fail "JDK 17 or newer is required."
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] && [ -d "$SDK" ] || fail "Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK directory."
[ -n "${NDK_HOME:-}" ] && [ -d "$NDK_HOME" ] || fail "Set NDK_HOME to an installed Android NDK directory."
[ -f package-lock.json ] || fail "package-lock.json is missing; npm ci cannot run."
rustup target list --installed | grep -qx 'aarch64-linux-android' || fail "Install target: rustup target add aarch64-linux-android"
printf 'Toolchain preflight passed. Node %s; npm %s.\n' "$(node --version)" "$(npm --version)"
npm ci
if [ ! -d "src-tauri/gen/android" ]; then npx tauri android init --ci; fi
[ -x src-tauri/gen/android/gradlew ] || fail "Tauri Android init did not produce the Gradle wrapper."
npm run build:tauri
npx tauri android build --ci --apk --target aarch64
APKS="$(find src-tauri/gen/android -type f -name '*.apk' -path '*/build/outputs/*' -print)"
[ -n "$APKS" ] || fail "Build completed but no APK was found under src-tauri/gen/android/**/build/outputs/."
printf '\nAPK artifact(s):\n%s\n' "$APKS"
