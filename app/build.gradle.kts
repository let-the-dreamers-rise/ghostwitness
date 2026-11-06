import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
}

// Optional: -Pgw.recoveryPub=<64 hex chars>. Without it the app runs in test mode (see RecoveryKeys.kt).
val recoveryPubHex: String = (findProperty("gw.recoveryPub") as String?)?.trim().orEmpty()
require(recoveryPubHex.matches(Regex("[0-9a-fA-F]*"))) { "gw.recoveryPub must be hex" }

android {
    namespace = "xyz.ghostwitness.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.ghostwitness.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "RECOVERY_PUB_HEX", "\"$recoveryPubHex\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.reown:android-core:1.6.0")
    implementation("com.reown:sign:1.6.0")
}
