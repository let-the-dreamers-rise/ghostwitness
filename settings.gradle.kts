pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\.komputing(\\..*)?")
                includeGroup("com.github.multiformats")
                includeGroup("com.walletconnect.Scarlet")
            }
        }
    }
}

rootProject.name = "ghostwitness"

include(":core", ":app")
