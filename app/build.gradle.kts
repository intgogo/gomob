plugins {
    alias(libs.plugins.gomob.android.application)
    alias(libs.plugins.gomob.android.application.compose)
    alias(libs.plugins.gomob.android.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
}

fun secretProvider(name: String) =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name))

val releaseStoreFile = secretProvider("GOMOB_RELEASE_STORE_FILE")
val releaseStorePassword = secretProvider("GOMOB_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = secretProvider("GOMOB_RELEASE_KEY_ALIAS")
val releaseKeyPassword = secretProvider("GOMOB_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isPresent }

android {
    namespace = "io.gomob.scan"

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    defaultConfig {
        applicationId = "io.gomob.scan"
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "/DebugProbesKt.bin",
                "/META-INF/*.kotlin_module",
                "/META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:native-bridge"))
    implementation(project(":core:logging"))
    implementation(project(":core:realtime"))
    implementation(project(":core:media"))

    implementation(project(":feature:auth"))
    implementation(project(":feature:home"))
    implementation(project(":feature:message"))
    implementation(project(":feature:collaboration"))
    implementation(project(":feature:scan3d"))
    implementation(project(":feature:profile"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.startup)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.timber)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
