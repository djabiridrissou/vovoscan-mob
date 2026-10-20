plugins {
    id("vovoscan.kmp-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:auth:domain"))
            implementation(project(":core:network"))
            implementation(project(":core:security"))
        }
        commonTest.dependencies { implementation(project(":core:testing")) }
    }
}
