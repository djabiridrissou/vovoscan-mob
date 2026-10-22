plugins {
    id("vovoscan.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:forum:domain"))
            api(libs.androidx.lifecycle.viewmodel)
        }
        commonTest.dependencies { implementation(libs.turbine) }
    }
}
