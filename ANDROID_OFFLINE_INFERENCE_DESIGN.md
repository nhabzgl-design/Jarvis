# ZERO Android offline inference implementation

## Scope
Implement the first usable Android milestone on `android-mobile-port` only. Do not merge to `main` until a physical-device offline test passes.

## Runtime decision
Use the upstream `llama.cpp` Android Kotlin binding in `examples/llama.android/lib`, rather than attempting to load GGUF through LiteRT. The upstream Android sample exposes:
- `AiChat.getInferenceEngine(context)`
- `InferenceEngine.loadModel(path)`
- `InferenceEngine.sendUserPrompt(prompt)` as a Kotlin Flow of generated tokens
- `InferenceEngine.destroy()`

The binding builds llama.cpp through Android NDK/CMake and targets `arm64-v8a`. Upstream's current sample requires Android API 33; the target device is Android 14 (API 34), so this minimum is compatible with the target but would exclude older Android devices. Do not raise the app-wide minimum SDK until the generated Tauri project and manifest are verified.

## Initial model
Start with the official `Qwen/Qwen3-0.6B-GGUF` repository's Q8_0 GGUF as a provenance-safe baseline (about 639 MB according to the official Hugging Face model card). Use a conservative 2048-token context for the first on-device test. The model is Apache-2.0. A smaller Q4_K_M conversion is available from third parties, but should not be the initial baseline until its conversion provenance and output quality are validated.

This 0.6B model is a compatibility/performance proof of concept, not a promise of strong reasoning or fluent Tagalog. Benchmark response quality, load time, peak memory, thermal behavior, and tokens/second on the actual phone before considering a larger model.

## Required integration work
1. Pin upstream llama.cpp at `e0949d09b130707b3fa93d2cff616024231cf939` (master HEAD inspected on 2026-11-12); do not build against a moving `master` in CI. Revalidate the Android sample at this exact revision before integration.
2. Vendor or fetch the Android Kotlin binding and its matching llama.cpp source at that same commit.
3. Integrate the Kotlin inference engine into the generated Tauri Android Gradle project without editing `main`.
4. Add a narrow, lifecycle-safe Android bridge between the WebView UI and Kotlin inference. Do not expose arbitrary JavaScript interfaces or start an unauthenticated network listener.
5. Add model download/import to app-private storage with progress, checksum, free-space checks, cancellation, and explicit user action. Do not bundle a 0.8 GB model inside the APK.
6. Replace the placeholder Message box with model load/send/streaming response states and visible recoverable errors.
7. Add automated build checks and a documented physical-device test: download once, disable Wi-Fi/mobile data, ask a test prompt, and confirm a generated response.
8. Only after offline chat passes, implement Android SpeechRecognizer and TextToSpeech. Wake-word/background microphone service is a separate later milestone.

## Known limitations and risks
- A 0.6B model may produce weak or repetitive Tagalog responses; successful inference does not prove high answer quality.
- Model file size is not peak runtime memory. KV cache, native allocations, and WebView/app memory also matter.
- The upstream Android example currently uses a modern Android SDK/NDK toolchain that must be reconciled with this repository's existing Tauri Gradle generation and pinned CI NDK before integration.
- No claim of offline capability, working voice, or install-ready production release is valid until physical-device testing succeeds.


## Verified upstream references (2026-11-12)
- llama.cpp Android guide: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
- Android binding sample: https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android
- Pinned llama.cpp revision: `e0949d09b130707b3fa93d2cff616024231cf939`
- Official Qwen GGUF model card: https://huggingface.co/Qwen/Qwen3-0.6B-GGUF

The upstream Android sample currently declares `minSdk = 33`, uses NDK `29.0.13113456`, and CMake `3.31.6`. The existing ZERO workflow currently installs NDK `27.2.12479018` and API 35, so the build toolchain must be reconciled before the module can be integrated. This is a concrete build blocker, not something to hide with a placeholder UI.
