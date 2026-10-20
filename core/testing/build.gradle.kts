// Faux partagés pour les tests : MockEngine, base SQLDelight en mémoire, coffre en mémoire, Turbine.
plugins {
    id("vovoscan.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:network"))
            api(project(":core:security"))
            api(project(":core:database"))
            api(libs.ktor.client.mock)
            api(libs.kotlinx.coroutines.test)
            api(libs.turbine)
            api(kotlin("test"))
        }
        androidMain.dependencies { implementation(libs.sqldelight.sqlite.driver) }
        iosMain.dependencies { implementation(libs.sqldelight.native.driver) }
    }
}
