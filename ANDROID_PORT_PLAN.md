# Android port plan

## Goal

Produce an installable Android APK for the user's Infinix Android 14 device, with a local/offline-capable language model and native Android speech input/output. The repository's existing desktop app must continue to work unchanged on `main`.

## Current verified state

- This branch currently starts from OpenJarvis commit `45850fc84d79b47fb9ec0b6906c71e191177f20d`.
- The existing frontend is React/Vite and its Tauri shell is configured as a desktop app.
- The existing Rust Tauri backend launches desktop processes (`uv`, Python `jarvis serve`, and Ollama) and uses desktop credential-store integrations.
- Speech input/output in the Python package currently relies on `sounddevice`; it is not an Android-native microphone/TTS implementation.
- There is no Android Gradle project or Android manifest in the imported source tree.
- The existing frontend calls the OpenJarvis HTTP API. It cannot provide local inference on Android by itself.

## Non-negotiable constraints

- Keep all Android work on `android-mobile-port`; do not change `main` until the port is reviewed and tested.
- No paid APIs, paid hosting, or API keys are required for the offline path.
- Do not claim the app is offline-capable until a local model has actually generated a response with network access disabled.
- Do not claim an APK is installable until a release APK has been built and installed/tested on a physical Android device.
- Preserve the previous ZERO identity and wake phrase unless the user explicitly approves a change. Repository name Jarvis does not itself authorize changing the in-app name or wake phrase.

## Architecture decision

The desktop Python process-launching backend must not be compiled or invoked on Android. Android needs a separate mobile runtime and platform-specific Rust/Kotlin integration.

The proposed mobile path is:

1. Tauri 2 Android project and Gradle/NDK build setup.
2. A mobile-only Rust entry point, separated from the existing desktop entry point using target-specific compilation.
3. A local inference runtime based on llama.cpp, built for Android arm64 and exposed through a narrow JNI/Rust bridge.
4. GGUF model download, storage, selection, and deletion with storage checks and explicit user consent.
5. Android-native speech recognition and Android TextToSpeech, with microphone permission handling and graceful fallback.
6. A mobile-specific UI path that does not wait for the desktop Python server or desktop setup screen.
7. CI build, artifact inspection, install test, and offline inference test.

## Device/model caution

The reported 8 GB physical RAM plus 8 GB extended/virtual RAM is not 16 GB of physical memory. Start with a small quantized model and measure actual peak memory, load time, thermal behavior, and token speed on the device before recommending a larger model. Model download size is not the same as runtime memory use.

## Milestones and acceptance criteria

### M1 — Android shell
- Android Gradle project generated from the supported Tauri 2 Android tooling.
- App installs and launches on Android 14.
- Desktop build remains unaffected.

### M2 — Mobile runtime separation
- Android target does not compile desktop-only process, tray, autostart, or desktop keyring code.
- Mobile app opens its own screen without requiring a remote server.

### M3 — Local inference
- llama.cpp Android arm64 runtime builds and loads a locally stored GGUF model.
- A prompt produces a real model response while the device is offline.
- Model download and inference failures are visible and recoverable.

### M4 — Voice
- Native Android speech recognition receives microphone input with runtime permission.
- Android TextToSpeech speaks the generated response.
- No paid speech API is used.

### M5 — Release validation
- GitHub Actions produces a signed/unsigned test APK artifact as configured.
- APK package metadata and ABI are inspected.
- Physical-device installation and offline chat are confirmed before calling it a usable release.

## Current status

Planning and source inspection are complete. The first code milestone is committed: the existing desktop Rust runtime is isolated in `desktop.rs`, Android now has a separate minimal Tauri entry point in `mobile.rs`, desktop-only Cargo dependencies are excluded from Android, and an Android config preserves the ZERO app ID. The Android-specific app startup and a minimal ZERO mobile shell are now committed. The Android shell deliberately reports that local inference is not yet connected; it does not fabricate model replies. Android-specific capability/config and an aarch64 APK build script are also committed. The generated Gradle project, llama.cpp Android library, JNI/Kotlin bridge, GGUF model manager, native microphone recognition, native TextToSpeech integration, and actual APK build/install test are still outstanding. A separate implementation design now records the supported arm64 NDK build flags and offline acceptance tests.