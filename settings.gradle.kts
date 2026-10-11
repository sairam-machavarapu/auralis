pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Auralis"
include(":app", ":core:ui", ":core:model", ":core:database")
include(":feature:home", ":feature:library", ":feature:player", ":feature:playlists",
    ":feature:search", ":feature:downloads", ":feature:extensions", ":feature:settings")
include(":service:playback", ":service:scanner", ":service:extensions")
