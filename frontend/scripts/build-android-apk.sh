#!/usr/bin/env bash
set -euo pipefail

# Run from the frontend/ directory on a machine with Android Studio,
# Android SDK/NDK, JDK 17+, Rust Android targets, and Node/npm installed.
npm ci
npm run build:tauri
npx tauri android build --apk --config src-tauri/tauri.android.conf.json
