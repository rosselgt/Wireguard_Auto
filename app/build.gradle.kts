plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wgautotoggle"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.wgautotoggle"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Libreria ufficiale WireGuard per Android (backend userspace, no root)
    implementation("com.wireguard.android:tunnel:1.0.20260102")

    // Storage cifrato per la configurazione WireGuard (contiene la chiave privata)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Scansione QR per importare la configurazione WireGuard
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Richiesta dalla libreria WireGuard per le API Java 8+ su minSdk più vecchie
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}