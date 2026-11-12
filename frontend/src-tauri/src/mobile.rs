// Minimal Android Tauri entry point. Local inference and native voice are
// added in later milestones; this shell deliberately does not start the
// desktop Python/Ollama process tree.
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("error while running the Jarvis Android application");
}
