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
        flatDir { dirs("third_party/berxel-android/aar") }
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

include(":feature:scan")
include(":feature:gallery")
include(":feature:calibration")
include(":feature:settings")
