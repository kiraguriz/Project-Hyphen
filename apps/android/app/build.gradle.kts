plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystore = System.getenv("HYPHEN_ANDROID_KEYSTORE")
val releaseKeyAlias = System.getenv("HYPHEN_ANDROID_KEY_ALIAS")
val releaseKeystorePassword = System.getenv("HYPHEN_ANDROID_KEYSTORE_PASSWORD")
val releaseKeyPassword = System.getenv("HYPHEN_ANDROID_KEY_PASSWORD")
val releaseSigningValues = listOf(
    releaseKeystore,
    releaseKeyAlias,
    releaseKeystorePassword,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
val releaseSigningPartial = releaseSigningValues.any { !it.isNullOrBlank() } && !releaseSigningConfigured
if (releaseSigningPartial) {
    throw GradleException("Set all HYPHEN_ANDROID_* release signing environment variables, or none.")
}

android {
    // Working-name application id; final id requires the naming/trademark
    // decision (plan §12.1) before any public release.
    namespace = "dev.hyphen.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hyphen.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("releaseEnv") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("releaseEnv")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Surface failing test names + stack traces in CI. The repo check runs
// `gradlew --quiet test`, which suppresses lifecycle output, so also mirror
// failures onto the quiet log level — otherwise a failure only prints the
// opaque "N tests completed, 1 failed" line.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
        quiet {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(composeBom)
}
