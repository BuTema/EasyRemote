plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.artem.easyremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.artem.easyremote"
        minSdk = 26
        targetSdk = 36
        // Play needs a higher versionCode for every upload; CI passes the run number.
        versionCode = (System.getenv("EASYREMOTE_VERSION_CODE") ?: "1").toInt()
        versionName = "0.1.0"
    }

    // Release signing: when the four EASYREMOTE_* variables are set (CI reads them from GitHub
    // secrets) the APK is signed with your own key; otherwise it falls back to the debug key so
    // local builds still work.
    val ksFile = System.getenv("EASYREMOTE_KEYSTORE_FILE")
    val ownKey = !ksFile.isNullOrBlank() && file(ksFile).exists()
    if (ownKey) {
        signingConfigs {
            create("own") {
                storeFile = file(ksFile!!)
                storePassword = System.getenv("EASYREMOTE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("EASYREMOTE_KEY_ALIAS")
                keyPassword = System.getenv("EASYREMOTE_KEY_PASSWORD")
            }
        }
    }

    // "play" = Google Play build (paid extra PCs, Play Billing); "direct" = GitHub APK, no limit.
    flavorDimensions += "dist"
    productFlavors {
        create("direct") { dimension = "dist" }
        create("play") { dimension = "dist" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (ownKey) "own" else "debug")
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
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QR scanner provided by Google Play services: no camera permission, no bundled ML model.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // Google Play Billing (Java library; version 8+ is required by Play since 31 Aug 2026). Play build only.
    "playImplementation"("com.android.billingclient:billing:8.3.0")

    testImplementation("junit:junit:4.13.2")
}
