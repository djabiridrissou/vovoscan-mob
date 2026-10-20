plugins {
    id("vovoscan.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:catalog:domain"))
            api(libs.androidx.lifecycle.viewmodel)
        }
        commonTest.dependencies { implementation(libs.turbine) }
    }
}
