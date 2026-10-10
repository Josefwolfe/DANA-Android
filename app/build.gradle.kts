plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStorePath = providers.environmentVariable("DANA_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("DANA_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("DANA_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("DANA_KEY_PASSWORD").orNull
val releaseSigningReady = listOf(
    releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.dana.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dana.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.5.0-beta"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        if (releaseSigningReady) {
            create("danaRelease") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("danaRelease")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47")
    testImplementation("junit:junit:4.13.2")
}
