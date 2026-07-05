plugins {
    id("com.android.application")
}

android {
    namespace = "jp.co.nkts.encoder"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.co.nkts.encoder"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // MP4/AVI input and H.265 MP4 output. 16KB page-size compatible FFmpegKit fork.
    implementation("io.github.jamaismagic.ffmpeg:ffmpeg-kit-main-full-gpl-16kb:6.1.4")
}
