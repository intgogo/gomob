plugins {
    alias(libs.plugins.gomob.android.library)
}

android {
    namespace = "io.gomob.media"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
}
