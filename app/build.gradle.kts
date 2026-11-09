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
        versionCode = 65
        versionName = "0.65"

        // ベータ版（知人への配布用）は gradle assembleDebug -Pbeta でビルドする
        val beta = project.hasProperty("beta")
        if (beta) versionName = versionName + "-beta"
        buildConfigField("boolean", "IS_BETA", beta.toString())
        buildConfigField(
            "String", "UPDATE_URL",
            if (beta) "\"https://github.com/shinichitakebuchi-netizen/kooky-keyboard-beta/releases/latest\""
            else "\"https://github.com/shinichitakebuchi-netizen/kooky-keyboard/releases/latest\""
        )
    }

    buildFeatures {
        buildConfig = true
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
