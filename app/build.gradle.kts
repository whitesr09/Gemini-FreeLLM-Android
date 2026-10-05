plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingStoreFile = System.getenv("ANDROID_SIGNING_KEYSTORE_PATH")
val signingStorePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
val signingConfigured = listOf(
    signingStoreFile,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.nshd.geminifreellm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nshd.geminifreellm"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            if (signingConfigured) {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword!!
                keyAlias = signingKeyAlias!!
                keyPassword = signingKeyPassword!!
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (signingConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("release") {
            if (signingConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
