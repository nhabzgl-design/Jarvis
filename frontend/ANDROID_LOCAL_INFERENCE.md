# Android local inference design

## Runtime choice

Use upstream `llama.cpp` as the local inference engine. The official project includes an Android Studio example app and documents Android NDK cross-compilation. For the first supported phone target, build only `arm64-v8a`; do not ship desktop Ollama or the Python OpenJarvis server inside the Android APK.

## Android native build baseline

The native library must be built with the Android NDK, not with the host machine's compiler. From a checked-out llama.cpp source tree:

```sh
cmake -S . -B build-android \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_LLAMAFILE=OFF \
  -DLLAMA_OPENSSL=OFF

cmake --build build-android --config Release --parallel
cmake --install build-android --prefix install-android --config Release
```

Do not add global `-march` flags. The upstream Android instructions specifically require `GGML_NATIVE=OFF` for cross-compilation and disable optional components that are not supported by the portable Android NDK setup. KleidiAI can be evaluated as a separate optimization after a working baseline exists.

## Integration into Tauri

The app must load the native inference library inside the Android application process. Do not attempt to launch the desktop Ollama process or an arbitrary executable from the app's writable data directory.

Planned bridge:
1. Package the JNI/shared native libraries for `arm64-v8a` under the generated Android app's native library directory.
2. Add a small Kotlin bridge that loads the native library and exposes model load, generate, cancel, and release operations.
3. Call the Kotlin bridge from a Tauri Android plugin or a narrowly scoped native mobile command.
4. Stream generated tokens back to the React UI without blocking the Android main thread.
5. Keep GGUF files in app-private storage; provide explicit model download, progress, deletion, and available-space checks.
6. Enforce a single active model context and release native memory when the app backgrounds or the model is unloaded.

## Model sizing and device constraints

The user's reported device memory is 8 GB physical RAM plus 8 GB extended/virtual RAM. Treat this as 8 GB physical RAM, not 16 GB. Start with a small quantized GGUF model and a conservative context window. Measure peak resident memory, load time, tokens/second, temperature, and battery drain on the actual phone. Never select a model based on download size alone.

## Offline acceptance test

- Download the model once while connected.
- Turn on airplane mode.
- Force-stop and reopen ZERO.
- Load the locally stored GGUF.
- Submit a prompt and confirm that generated tokens come from the local engine.
- Confirm that no cloud endpoint is contacted.
- Repeat after app restart and after the phone has been idle.

## Current status

This document is an implementation design only. The llama.cpp Android native library, JNI/Kotlin bridge, GGUF model manager, and offline inference path are not yet implemented or tested.
