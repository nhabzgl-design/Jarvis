# Android build notes

The Android target uses the isolated mobile Rust entry point and the Android-specific Tauri config:

- App label: ZERO
- Android application ID: `com.zero.assistant`
- Mobile entry point: `src-tauri/src/mobile.rs`
- Desktop backend remains in `src-tauri/src/desktop.rs`

## Build prerequisites

Install Node.js 22.22+, npm 11.19+ (below 12), JDK 17+, Rust with the `aarch64-linux-android` target, Android SDK/platform tools, and Android NDK. Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) and `NDK_HOME`.

From `frontend/`, the build script validates these requirements and checks for `package-lock.json` before it runs `npm ci`. It then generates the Android Gradle project if it is missing, builds the web assets, invokes the Tauri Android APK build for arm64, and searches for the actual APK output.

## Generate Android project manually (optional)

The normal `npm run android:build` command automatically runs Android project initialization when `src-tauri/gen/android` is missing. If you want to generate it separately, run this from `frontend/`:

```sh
npm run android:init
```

This maps to `tauri android init --ci` and uses Tauri's automatically merged `tauri.android.conf.json` configuration. The generated Gradle project is placed under `src-tauri/gen/android`.

## Build APK

From `frontend/`, run:

```sh
npm run android:build
```

The script prints the APK path only if it finds an APK under the generated Android project's Gradle `build/outputs` directories. A successful script syntax check is not an Android build; the actual SDK/NDK build and device installation still need to be performed.

## Important current limitation

This milestone only creates the platform-specific Tauri shell. It does not yet contain llama.cpp, a GGUF model manager, or Android speech recognition/TextToSpeech. The existing desktop UI still expects the Python API and is not yet a working offline mobile assistant. Do not distribute this shell as a finished AI app.
