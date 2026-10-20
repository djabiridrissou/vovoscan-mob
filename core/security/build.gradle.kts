plugins {
    id("vovoscan.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":core:common")) }
        androidMain.dependencies { implementation(libs.androidx.security.crypto) }
    }
}
