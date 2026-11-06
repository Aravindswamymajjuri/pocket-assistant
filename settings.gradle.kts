pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "pocket-assistant"
include(":policy", ":desktop")
// The Windows build doesn't need the Android app or its SDK.
if (System.getenv("DESKTOP_ONLY") == null) include(":app")
