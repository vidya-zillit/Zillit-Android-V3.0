pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack — kept available because several Zillit widgets (signature pad,
        // forked audioswitch for calling) are only published there. Not used yet.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Zillit V3"
include(":app")
