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
        versionCode = 8
        versionName = "1.5.0"

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
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.media3:media3-common:1.9.4")
    implementation("androidx.media3:media3-transformer:1.9.4")
    implementation("androidx.media3:media3-effect:1.9.4")

    implementation("io.github.jamaismagic.ffmpeg:ffmpeg-kit-main-full-gpl-16kb:6.1.4")
}
