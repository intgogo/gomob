pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Berxel SDK 是 jar 不是 aar，模块层用 files(...) 引；这里不需要 flatDir
    }
}

rootProject.name = "gomob"

include(":app")

include(":core:common")
include(":core:model")
include(":core:data")
include(":core:database")
include(":core:domain")
include(":core:designsystem")
include(":core:ui")
include(":core:network")
include(":core:native-bridge")

include(":feature:auth")
include(":feature:home")
include(":feature:message")
include(":feature:collaboration")
include(":feature:scan3d")
include(":feature:profile")
