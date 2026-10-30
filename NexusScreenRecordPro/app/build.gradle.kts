plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nexusfuturistica.screenrecordpro"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.nexusfuturistica.screenrecordpro.a13gravacao3"
        minSdk = 29
        targetSdk = 34
        versionCode = 13
        versionName = "0.6.0-a13-gravacao-visivel"
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
