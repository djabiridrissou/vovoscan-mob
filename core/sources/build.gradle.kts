// Moteur des sources à règles (manifestes JSON du dépôt vovo-plugin), exécuté sur l'appareil : leur contenu ne passe
// jamais par les serveurs vovoscan.
plugins {
    id("vovoscan.kmp-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(libs.ktor.client.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ksoup)
        }
        commonTest.dependencies { implementation(libs.ktor.client.mock) }
    }
}
