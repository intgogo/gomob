plugins {
    alias(libs.plugins.gomob.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "io.gomob.model"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
}
