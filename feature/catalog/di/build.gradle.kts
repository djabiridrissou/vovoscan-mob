plugins {
    id("vovoscan.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:catalog:presentation"))
            implementation(project(":feature:catalog:data"))
            implementation(project(":core:network"))
            implementation(project(":core:database"))
            implementation(project(":core:sources"))
            api(libs.koin.core)
            implementation(libs.koin.core.viewmodel)
        }
        commonTest.dependencies {
            implementation(project(":core:testing"))
            implementation(libs.koin.test)
        }
    }
}
