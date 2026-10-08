plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fiaz.movereminder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fiaz.movereminder"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

dependencies {
    // The app itself has no dependencies - plain framework APIs only.
    // JUnit is used only by the unit tests and is not part of the APK.
    testImplementation("junit:junit:4.13.2")
}
