plugins {
    id("vovoscan.kmp-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:together:domain"))
            implementation(project(":core:network"))
        }
        commonTest.dependencies { implementation(project(":core:testing")) }
    }
}
