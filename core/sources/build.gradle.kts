// Engine for rule-based sources (JSON manifests from the vovo-plugin repository), run on the device: their content never
// goes through vovoscan servers.
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
            // .vovo packages (zip) and SHA-256 of plugin manifests.
            api(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(project(":core:testing"))
        }
    }
}
