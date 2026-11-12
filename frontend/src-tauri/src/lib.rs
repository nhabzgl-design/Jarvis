// Keep the mature desktop runtime isolated from Android. The Android target
// must not compile desktop process management, system tray, or desktop keyring.
#[cfg(not(target_os = "android"))]
mod desktop;
#[cfg(not(target_os = "android"))]
pub use desktop::run;

#[cfg(target_os = "android")]
mod mobile;
#[cfg(target_os = "android")]
pub use mobile::run;
