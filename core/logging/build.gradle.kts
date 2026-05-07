plugins {
    alias(libs.plugins.gomob.android.library)
    alias(libs.plugins.gomob.android.hilt)
}

android {
    namespace = "io.gomob.logging"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:network"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
}
