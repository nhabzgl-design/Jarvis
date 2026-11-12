# Android build notes

The Android target uses the isolated mobile Rust entry point and the Android-specific Tauri config:

- App label: ZERO
- Android application ID: `com.zero.assistant`
- Mobile entry point: `src-tauri/src/mobile.rs`
- Desktop backend remains in `src-tauri/src/desktop.rs`

## Build prerequisites

Install the Android SDK, Android NDK, platform tools, JDK 17 or newer, Rust, and the Android Rust targets required by Tauri 2. The exact SDK/NDK versions must match the Tauri 2 toolchain installed on the build host.

## Generate Android project

From `frontend/`, run:

```sh
npx tauri android init --config src-tauri/tauri.android.conf.json
```

This generates the Gradle Android project under `src-tauri/gen/android`. Review and commit the generated project before relying on CI.

## Build APK

From `frontend/`, run:

```sh
bash scripts/build-android-apk.sh
```

The Tauri Android build output is expected under the generated Android project's Gradle build output. Inspect the actual output path from the build log rather than assuming a release artifact exists.

## Important current limitation

This milestone only creates the platform-specific Tauri shell. It does not yet contain llama.cpp, a GGUF model manager, or Android speech recognition/TextToSpeech. The existing desktop UI still expects the Python API and is not yet a working offline mobile assistant. Do not distribute this shell as a finished AI app.
