# ZERO AI Android Starter

This is an Android Studio **starter source project**, not a compiled APK. Build it with Android Studio on a computer, or import it into a compatible cloud build service.

## Included
- Native Android app UI named ZERO
- Android speech-recognition prompt using Filipino locale (`fil-PH`)
- Text input and conversation display
- Button actions to open YouTube, Maps and web search
- HTTPS connection to the backend from `ZERO_AI_Humanoid_Starter.zip`
- ElevenLabs spoken responses through the backend `/api/tts` route

Voice ID configured on the backend: `pNInz6obpgDQGcFmaJgB`

## Setup
1. First deploy the backend in `ZERO_AI_Humanoid_Starter.zip` to an HTTPS host (for example Render).
2. Add the backend environment variables described in its README.
3. Install Android Studio on a computer and open this folder as a project.
4. Build > Build APK(s), then install the generated APK on your Android phone.
5. Open ZERO and enter the backend HTTPS URL in the server field.
6. Tap the microphone and allow speech recognition if prompted.

## Important
- This is a starter, not a fully autonomous assistant.
- No API keys are embedded in the Android app.
- It does not access SMS, contacts, notifications, photos, banking apps, passwords, or arbitrary screen content.
- It does not send messages, delete data, buy things, or make calls automatically.
- Broader integrations require separate implementation, clear permission requests, and confirmation flows.
- A normal Android app cannot silently take over the whole phone. Accessibility-service functionality, if ever added, must be transparent and user-enabled.
- For production use, add authentication to the backend, per-user rate limits, abuse controls, and secure conversation storage if memory is desired.

## Build using a phone only (GitHub Actions)
1. Create a new GitHub repository named `ZERO-AI-Android` in your browser.
2. Upload the contents of this project folder to the repository root (the `app` folder, `build.gradle.kts`, `settings.gradle.kts`, and `.github` folder must be at the root; do not upload the outer ZIP folder as an extra nested directory).
3. Open the repository's **Actions** tab. If prompted, enable Actions.
4. Select **Build ZERO AI Android APK** and tap **Run workflow** (or wait for the first push build).
5. Open the completed workflow run and download the `ZERO-AI-debug-APK` artifact. Extract the downloaded ZIP to get the `.apk` file.
6. Open the APK on your Android phone and allow installation from that source if Android asks.

This creates a debug APK for testing only. Keep the repository private if you do not want the source public. Never put API keys or access tokens in the source code or repository.
