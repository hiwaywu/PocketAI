pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first: Maven Central itself (and the plugin portal, which redirects
        // to it) often rate-limits cloud builds with HTTP 429.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // GeckoView (Firefox's engine) for the in-app browser.
        maven("https://maven.mozilla.org/maven2/")
    }
}
rootProject.name = "PocketAI"
include(":app")
