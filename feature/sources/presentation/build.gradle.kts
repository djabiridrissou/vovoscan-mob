plugins {
    id("vovoscan.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:sources:domain"))
            api(libs.androidx.lifecycle.viewmodel)
        }
        commonTest.dependencies { implementation(libs.turbine) }
    }
}
