plugins {
    id("vovoscan.android-app")
}

android {
    namespace = "com.vovoscan.app"
    defaultConfig {
        applicationId = "com.vovoscan.app"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            // -Pvovoscan.apiBaseUrl=… ; par défaut l'API lancée en local, vue depuis l'émulateur.
            val apiBaseUrl = providers.gradleProperty("vovoscan.apiBaseUrl").getOrElse("http://10.0.2.2:8000/")
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        }
        release {
            val apiBaseUrl = providers.gradleProperty("vovoscan.releaseApiBaseUrl").getOrElse("https://vovoscan.yieldgrowth.org/")
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
