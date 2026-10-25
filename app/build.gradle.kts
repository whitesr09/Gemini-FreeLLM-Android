import java.util.Base64

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
        versionCode = 9
        versionName = "2.2.3"
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
            storePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
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

// App icon artwork is stored base64-encoded in 5 parts (app/icon-src/ic_logo.part*.b64)
// because it cannot be pushed as a binary file with the current tooling; the parts
// decode to the identical PNG at build time. To replace it with a directly-committed
// file, drop the real PNG at app/src/main/res/drawable-nodpi/ic_logo.png and delete
// this block plus the icon-src directory.
// Generate resources as a task so `clean` cannot delete the icon after it was decoded.
val generateAppIcon by tasks.registering {
    val iconSrcDir = file("icon-src")
    val generatedIcon = layout.buildDirectory.file("generated/res/appIcon/drawable-nodpi/ic_logo.png")
    inputs.files(fileTree(iconSrcDir) { include("ic_logo.part*.b64") })
    outputs.file(generatedIcon)
    doLast {
        if (file("src/main/res/drawable-nodpi/ic_logo.png").exists()) return@doLast
        val parts = iconSrcDir.listFiles { f -> f.name.matches(Regex("ic_logo\\.part\\d+\\.b64")) }
            ?.sortedBy { it.name }.orEmpty()
        if (parts.isNotEmpty()) {
            val encoded = parts.joinToString("") { it.readText().trim() }
            generatedIcon.get().asFile.apply {
                parentFile.mkdirs()
                writeBytes(Base64.getDecoder().decode(encoded))
            }
        }
    }
}
android.sourceSets.getByName("main").res.srcDir(layout.buildDirectory.dir("generated/res/appIcon"))
tasks.named("preBuild").configure { dependsOn(generateAppIcon) }
generateAppIcon.configure { mustRunAfter("clean") }

// Alternate launcher icons use the same trick: base64 parts in app/icon-src/ (binary
// files cannot be pushed with the current tooling) decoded at build time. Each part set
// decodes to one drawable used by its adaptive-icon foreground.
val generateAppIconGlow by tasks.registering {
    val iconSrcDir = file("icon-src")
    val generatedIcon = layout.buildDirectory.file("generated/res/appIconGlow/drawable-nodpi/ic_logo_glow.png")
    inputs.files(fileTree(iconSrcDir) { include("ic_logo_glow.part*.b64") })
    outputs.file(generatedIcon)
    doLast {
        if (file("src/main/res/drawable-nodpi/ic_logo_glow.png").exists()) return@doLast
        val parts = iconSrcDir.listFiles { f -> f.name.matches(Regex("ic_logo_glow\\.part\\d+\\.b64")) }
            ?.sortedBy { it.name }.orEmpty()
        if (parts.isNotEmpty()) {
            val encoded = parts.joinToString("") { it.readText().trim() }
            generatedIcon.get().asFile.apply {
                parentFile.mkdirs()
                writeBytes(Base64.getDecoder().decode(encoded))
            }
        }
    }
}
android.sourceSets.getByName("main").res.srcDir(layout.buildDirectory.dir("generated/res/appIconGlow"))
tasks.named("preBuild").configure { dependsOn(generateAppIconGlow) }
generateAppIconGlow.configure { mustRunAfter("clean") }

val generateAppIconBlue by tasks.registering {
    val iconSrcDir = file("icon-src")
    val generatedIcon = layout.buildDirectory.file("generated/res/appIconBlue/drawable-nodpi/ic_logo_blue.png")
    inputs.files(fileTree(iconSrcDir) { include("ic_logo_blue.part*.b64") })
    outputs.file(generatedIcon)
    doLast {
        if (file("src/main/res/drawable-nodpi/ic_logo_blue.png").exists()) return@doLast
        val parts = iconSrcDir.listFiles { f -> f.name.matches(Regex("ic_logo_blue\\.part\\d+\\.b64")) }
            ?.sortedBy { it.name }.orEmpty()
        if (parts.isNotEmpty()) {
            val encoded = parts.joinToString("") { it.readText().trim() }
            generatedIcon.get().asFile.apply {
                parentFile.mkdirs()
                writeBytes(Base64.getDecoder().decode(encoded))
            }
        }
    }
}
android.sourceSets.getByName("main").res.srcDir(layout.buildDirectory.dir("generated/res/appIconBlue"))
tasks.named("preBuild").configure { dependsOn(generateAppIconBlue) }
generateAppIconBlue.configure { mustRunAfter("clean") }

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
    implementation("org.mozilla:rhino:1.7.15") // on-device JavaScript runner (pure Java, no native libs)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
