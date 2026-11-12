#!/usr/bin/env bash
set -euo pipefail
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
command -v node >/dev/null 2>&1 || fail "Node.js 22.22+ is required."
command -v npm >/dev/null 2>&1 || fail "npm 11.19+ is required."
command -v java >/dev/null 2>&1 || fail "JDK 17+ is required."
command -v rustup >/dev/null 2>&1 || fail "Rustup is required."
command -v cargo >/dev/null 2>&1 || fail "Cargo is required."
node -e 'const [a,b]=process.versions.node.split(".").map(Number); if(a<22 || (a===22 && b<22)) process.exit(1)' || fail "Node.js must be version 22.22 or newer."
npm -s --version | awk -F. '{ if ($1 != 11 || $2 < 19) exit 1 }' || fail "npm must be version 11.19 or newer and below 12."
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

# Build the pinned upstream llama.cpp Android inference library as an app module.
# Keep the repository at this exact path: its CMakeLists resolves the llama.cpp
# root by walking six parent directories from examples/llama.android/lib/src/main/cpp.
LLAMA_REF="e0949d09b130707b3fa93d2cff616024231cf939"
LLAMA_DIR="src-tauri/gen/android/llama.cpp"
if [ ! -d "$LLAMA_DIR/.git" ]; then
  git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git "$LLAMA_DIR"
fi
git -C "$LLAMA_DIR" fetch --depth 1 origin "$LLAMA_REF"
git -C "$LLAMA_DIR" checkout --detach "$LLAMA_REF"

python3 - <<'PY'
from pathlib import Path
import re

android_root = Path("src-tauri/gen/android")
# Gradle only reads the settings file at the project root passed to gradlew.
# Do not accidentally patch a nested settings file belonging to a plugin/sample.
root_settings = [
    android_root / "settings.gradle.kts",
    android_root / "settings.gradle",
]
settings_matches = [p for p in root_settings if p.is_file()]
if len(settings_matches) != 1:
    found = [str(p) for p in android_root.rglob("settings.gradle*")]
    raise SystemExit(
        f"Expected exactly one root Gradle settings file under {android_root}; "
        f"root matches={list(map(str, settings_matches))}; discovered={found}"
    )
settings = settings_matches[0]
app_gradle = android_root / "app" / "build.gradle.kts"
lib_gradle = android_root / "llama.cpp" / "examples" / "llama.android" / "lib" / "build.gradle.kts"
settings_dir = settings.parent
llama_project_dir = (
    android_root / "llama.cpp" / "examples" / "llama.android" / "lib"
).relative_to(settings_dir)


s = settings.read_text()
if settings.suffix == ".kts":
    include_line = 'include(":llama-lib")'
    project_line = f'project(":llama-lib").projectDir = file("{llama_project_dir.as_posix()}")'
    if include_line not in s:
        s += f"\n// ZERO local GGUF inference engine (pinned llama.cpp Android sample).\n{include_line}\n{project_line}\n"
        settings.write_text(s)
else:
    include_line = "include ':llama-lib'"
    project_line = f'project(":llama-lib").projectDir = file("{llama_project_dir.as_posix()}")'
    if include_line not in s:
        s += f"\n// ZERO local GGUF inference engine (pinned llama.cpp Android sample).\n{include_line}\n{project_line}\n"
        settings.write_text(s)

lib = lib_gradle.read_text()
lib = lib.replace(
    'plugins {\n    alias(libs.plugins.android.library)\n    alias(libs.plugins.jetbrains.kotlin.android)\n}',
    'plugins {\n    id("com.android.library")\n    id("org.jetbrains.kotlin.android")\n}'
)
lib = lib.replace('implementation(libs.androidx.core.ktx)', 'implementation("androidx.core:core-ktx:1.16.0")')
lib = lib.replace('implementation(libs.androidx.datastore.preferences)', 'implementation("androidx.datastore:datastore-preferences:1.1.7")')
lib = re.sub(r'\n\s*testImplementation\(libs\.junit\)', '', lib)
lib = re.sub(r'\n\s*androidTestImplementation\(libs\.androidx\.junit\)', '', lib)
if 'libs.plugins.' in lib or 'libs.androidx.' in lib:
    raise SystemExit("Unresolved sample version-catalog alias remains in llama-lib/build.gradle.kts")
lib_gradle.write_text(lib)

app = app_gradle.read_text()
if 'implementation(project(":llama-lib"))' not in app:
    match = re.search(r'(?m)^dependencies\s*\{', app)
    if not match:
        raise SystemExit("Could not locate app dependencies block")
    start = match.end()
    depth = 1
    i = start
    while i < len(app) and depth:
        if app[i] == "{":
            depth += 1
        elif app[i] == "}":
            depth -= 1
        i += 1
    if depth:
        raise SystemExit("Unbalanced app dependencies block")
    app = app[:i-1] + '\n    implementation(project(":llama-lib"))\n' + app[i-1:]
    app_gradle.write_text(app)
print("Pinned llama.cpp Android library wired into the ZERO APK Gradle build.")
PY


npm run build:tauri
npx tauri android build --ci --apk --target aarch64
APKS="$(find src-tauri/gen/android -type f -name '*.apk' -path '*/build/outputs/*' -print)"
[ -n "$APKS" ] || fail "Build completed but no APK was found under src-tauri/gen/android/**/build/outputs/."
printf '\nAPK artifact(s):\n%s\n' "$APKS"
