#!/usr/bin/env bash
set -euo pipefail

# Run from frontend/ on a Linux or macOS build host with:
# Node/npm matching package.json, Rust + aarch64-linux-android target,
# JDK 17+, Android SDK, Android NDK, and Android command-line tools.
npm ci

# Generate the native Gradle project once. The generated project is kept under
# src-tauri/gen/android; this command is safe to rerun on an existing project.
if [ ! -d "src-tauri/gen/android" ]; then
  npx tauri android init --ci
fi

npm run build:tauri
npx tauri android build --ci --apk --target aarch64
