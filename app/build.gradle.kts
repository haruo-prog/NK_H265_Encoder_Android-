plugins {
    id("com.android.application")
}

android {
    namespace = "jp.co.nkts.golftracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.co.nkts.golftracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
}
