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

// Une feature = une tranche verticale, découpée en couches : domain (Kotlin pur), data (sources locale et
// distante, dépôt), presentation (ViewModels partagés), di (module Koin).
listOf("auth", "catalog", "library", "reader", "sources").forEach { name ->
    include(":feature:$name:domain", ":feature:$name:data", ":feature:$name:presentation", ":feature:$name:di")
}

include(":shared", ":androidApp")
