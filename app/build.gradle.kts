import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "me.ri3d.adb"
    // Compose 1.12 (BOM 2026.09.00) is built against API 37; targetSdk stays 36.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "me.ri3d.adb"
        // Phone-side requirement only. The car is reached over the ADB wire protocol,
        // which works with head units back to Android 4.x (see README, "Car compatibility").
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    // Release signing: keystore.properties (ignored by git) names the keystore and its passwords.
    // Without it the release build stays unsigned. CI writes the file from repository secrets.
    val keystoreProperties = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreProperties.exists()) {
            val props = Properties().apply { keystoreProperties.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        abortOnError = true
        // Versions are pinned on purpose (README, "Build").
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

// The deliverable is named My-Headunit.apk: `./gradlew :app:assembleDebug` copies it to the project root.
val copyApk by tasks.registering {
    val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val target = rootProject.layout.projectDirectory.file("My-Headunit.apk")
    inputs.file(apk)
    outputs.file(target)
    doLast { apk.get().asFile.copyTo(target.asFile, overwrite = true) }
}
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(copyApk) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
}
