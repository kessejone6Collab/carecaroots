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
        // Isolated install-test id so it cannot collide with earlier test builds.
        applicationId = "com.nexusfuturistica.screenrecordpro.installdiagnostico"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "0.4.0-a13-install-test"
    }

    buildTypes {
        debug {
            // Keep the APK signed with the debug key for sideload testing,
            // but do not expose an app debuggable flag in the installed build.
            isDebuggable = false
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
