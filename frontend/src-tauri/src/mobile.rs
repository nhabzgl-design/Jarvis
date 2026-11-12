// Android-only Tauri entry point for ZERO.
//
// Keep desktop process management, Ollama sidecars, system tray, and desktop
// credential-store code out of the Android binary. The local inference engine
// and native Android voice bridge are intentionally separate integration
// milestones; this entry point must not claim those features are available
// until their native libraries and Kotlin bridge are registered and tested.
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("error while running the ZERO Android application");
}
