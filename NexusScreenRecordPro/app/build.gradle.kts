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
        // Fresh package ID for this install attempt avoids conflicts with prior test packages.
        applicationId = "com.nexusfuturistica.screenrecordpro.a13installfix"
        minSdk = 29
        targetSdk = 34
        versionCode = 11
        versionName = "0.4.1-a13-install-fix"
    }

    signingConfigs {
        getByName("debug") {
            // Include both signature formats for compatibility with Android installers.
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
