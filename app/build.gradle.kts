plugins {
    id("com.android.application")
}

android {
    namespace = "jp.co.nkts.encoder"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.co.nkts.encoder"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.1"

        // Include native FFmpeg libraries for major Android device ABIs.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // FFmpegKit loads native libraries from the app nativeLibraryDir.
            // On Android 16/API 36 devices this must be extracted, otherwise FFmpegKit can fail to start.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Self-contained FFmpeg runtime for MP4 / AVI import and H.265 MP4 output.
    // This 16KB-page-size compatible fork is kept inside the APK; users do not install FFmpeg separately.
    implementation("io.github.jamaismagic.ffmpeg:ffmpeg-kit-main-full-gpl-16kb:6.1.4")
}
