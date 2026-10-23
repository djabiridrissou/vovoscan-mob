plugins {
    id("vovoscan.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:together:domain"))
            api(libs.androidx.lifecycle.viewmodel)
        }
        commonTest.dependencies { implementation(libs.turbine) }
    }
}
