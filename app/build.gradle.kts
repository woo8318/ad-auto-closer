plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version is injected by CI from the git tag (e.g. -PversionName=0.2.0).
// Locally it falls back to the default below.
val appVersionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode = appVersionName.split(".").let { parts ->
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    (major * 10000 + minor * 100 + patch).coerceAtLeast(1)
}

android {
    namespace = "com.adautocloser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adautocloser"
        minSdk = 26          // adaptive icons + modern accessibility APIs
        targetSdk = 34       // Android 14 / One UI 6.1 (Galaxy S24)
        versionCode = appVersionCode
        versionName = appVersionName

        // GitHub repo used by the in-app updater to find the latest release.
        // ⚠️ Replace GITHUB_OWNER with your actual GitHub username before first release.
        buildConfigField("String", "GITHUB_OWNER", "\"woo8318\"")
        buildConfigField("String", "GITHUB_REPO", "\"ad-auto-closer\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // debug APK is signed with the auto-generated debug key → installs without a keystore
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
