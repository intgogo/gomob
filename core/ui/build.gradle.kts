plugins {
    alias(libs.plugins.gomob.android.library)
    alias(libs.plugins.gomob.android.library.compose)
}

android {
    namespace = "io.gomob.ui"
}

dependencies {
    api(project(":core:designsystem"))
    api(project(":core:common"))
    api(project(":core:model"))
    implementation(libs.androidx.lifecycle.runtime.compose)
}
