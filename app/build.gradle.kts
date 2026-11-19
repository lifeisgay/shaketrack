plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.expensetracker"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.expensetracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }
    signingConfigs {
        // Google sign-in only works when the APK is signed with the key whose SHA-1 is
        // registered in Google Cloud, so every build must use the same key.
        // CI writes it from the DEBUG_KEYSTORE_BASE64 secret; locally keep it at app/shaketrack.keystore.
        getByName("debug") {
            val ks = file("shaketrack.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "shaketrack"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Sign in with Google (Credential Manager)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
