plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.tienlen.game"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.tienlen.game"
        minSdk = 23          // Android 6.0 (FPT Play Box 2018)
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // ký bằng khóa debug để APK release cài được ngay, không cần keystore riêng
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
