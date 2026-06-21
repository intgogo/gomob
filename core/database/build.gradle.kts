plugins {
    alias(libs.plugins.gomob.android.library)
    alias(libs.plugins.gomob.android.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.gomob.database"
}

// 导出 Room schema 到模块内 schemas/ 目录，供 MigrationTestHelper 做 migration 自动化验证。
// 首次构建后会生成 schemas/io.gomob.database.GomobDatabase/<version>.json，应随源码提交。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
