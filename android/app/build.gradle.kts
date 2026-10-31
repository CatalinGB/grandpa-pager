import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release signing: put a gitignored `android/keystore.properties`
// alongside the project (storeFile/storePassword/keyAlias/keyPassword).
// Without it, the release build falls back to the debug key so local
// sideloading keeps working. NEVER commit the keystore or its passwords.
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreFile.exists() &&
    keystoreProps.getProperty("storeFile")?.isNotBlank() == true

android {
    namespace = "com.grandpapager"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.grandpapager"
        minSdk = 24
        targetSdk = 28
        versionCode = 6
        versionName = "1.5.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Use the real release keystore when configured; otherwise sign with
            // the debug key so the production UI can still be sideloaded locally.
            // No silent debug-key fallback for releases: a differently signed APK
            // would be rejected as an update. The task-graph check below fails the
            // build instead.
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Fail fast when a release artifact is requested without the real keystore.
    // (Kept here so debug-only builds still work on a fresh checkout.)
    gradle.taskGraph.whenReady {
        val releaseRequested = allTasks.any { it.name.contains("Release") }
        if (releaseRequested && !hasReleaseKeystore) {
            throw GradleException(
                "Refusing to build a release artifact without android/keystore.properties. " +
                    "A different signing key would be rejected as an update — see docs/runbook.md §5d."
            )
        }
    }

    buildFeatures {
        // Exposes BuildConfig.DEBUG so the receiver can hide dev-only UI (countdown).
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.11.0")
}
