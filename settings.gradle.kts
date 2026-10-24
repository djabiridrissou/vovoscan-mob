pluginManagement {
    includeBuild("build-logic")
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
    }
}

rootProject.name = "vovoscan-mobile"

include(":core:common", ":core:network", ":core:security", ":core:database", ":core:sources", ":core:testing")

// A feature = a vertical slice, split into layers: domain (pure Kotlin), data (local and remote
// data sources, repository), presentation (shared ViewModels), di (Koin module).
listOf(
    "account",
    "auth",
    "catalog",
    "episodes",
    "forum",
    "groups",
    "library",
    "notifications",
    "reader",
    "sources",
    "together",
).forEach { name ->
    include(":feature:$name:domain", ":feature:$name:data", ":feature:$name:presentation", ":feature:$name:di")
}

include(":shared", ":androidApp")
