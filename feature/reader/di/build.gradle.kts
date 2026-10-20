plugins {
    id("vovoscan.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:reader:presentation"))
            implementation(project(":feature:reader:data"))
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
