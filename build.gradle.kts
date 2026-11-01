plugins {
id("com.android.application")
id("org.jetbrains.kotlin.android")
}

android {
namespace = "com.nhabz.zero"
compileSdk = 35

defaultConfig {
    applicationId = "com.nhabz.zero"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
}

compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlinOptions {
    jvmTarget = "17"
}

}

dependencies {
implementation("androidx.core:core-ktx:1.15.0")
implementation("androidx.activity:activity-ktx:1.10.0")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
