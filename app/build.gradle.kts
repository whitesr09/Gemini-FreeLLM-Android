plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nshd.geminifreellm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nshd.geminifreellm"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "2.2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions.unitTests.isIncludeAndroidResources = true

    // Permanent CI signing: when the release keystore is present (decoded from
    // the CI_KEYSTORE_BASE64 secret by the workflow), both debug and release
    // APKs are signed with the SAME key, so updates install over the old
    // version without uninstalling. Local builds fall back to the default
    // debug key when the keystore file is absent.
    val ciKeystoreFile = file("ci-signing.keystore")
    val hasCiKeystore = ciKeystoreFile.exists()
    signingConfigs {
        create("ci") {
            storeFile = ciKeystoreFile
            storePassword = System.getenv("CI_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("CI_KEY_ALIAS")
            keyPassword = System.getenv("CI_KEY_PASSWORD")
        }
    }
    buildTypes {
        debug {
            if (hasCiKeystore) signingConfig = signingConfigs.getByName("ci")
        }
        release {
            if (hasCiKeystore) signingConfig = signingConfigs.getByName("ci")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
