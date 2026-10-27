plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.kooky.keyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "jp.kooky.keyboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "0.20"
    }

    // 毎回同じ鍵で署名して、上書きインストールできるようにする
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
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
