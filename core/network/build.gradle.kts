plugins {
    alias(libs.plugins.gomob.android.library)
    alias(libs.plugins.gomob.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.gomob.network"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
