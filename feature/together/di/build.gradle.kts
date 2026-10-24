plugins {
    id("vovoscan.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:together:presentation"))
            implementation(project(":feature:together:data"))
            implementation(project(":core:network"))
            api(libs.koin.core)
            implementation(libs.koin.core.viewmodel)
        }
        commonTest.dependencies {
            implementation(project(":core:testing"))
            implementation(libs.koin.test)
        }
    }
}
