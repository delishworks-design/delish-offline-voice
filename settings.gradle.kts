pluginManagement {
    repositories {
        // Maven Central carries the Kotlin/AGP plugin markers, so it goes first —
        // plugins.gradle.org is not reachable from this network.
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
}

rootProject.name = "Delish"
include(":app")