plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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

dependencies {
    testImplementation(libs.junit)
}
