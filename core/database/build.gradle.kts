plugins {
    alias(libs.plugins.gomob.android.library)
    alias(libs.plugins.gomob.android.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.gomob.database"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
