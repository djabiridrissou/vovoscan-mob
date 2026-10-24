plugins {
    id("vovoscan.android-app")
}

// Firebase (sealed pushes, docs/NOTIFICATIONS.md): -Pvovoscan.firebase.appId=… (and apiKey, projectId, senderId), or a
// local google-services.json next to this file (git-ignored; the Google Services plugin is not used), whose client for
// the build's package is taken. Without one the build has no Firebase: notifications stay in the app.
fun firebase(packageName: String): Map<String, String> {
    val props =
        listOf("appId", "apiKey", "projectId", "senderId").associateWith {
            providers.gradleProperty("vovoscan.firebase.$it").orNull
        }
    if (props.values.all { it != null }) return props.mapValues { it.value!! }
    val file = file("google-services.json")
    if (!file.exists()) return emptyMap()
    @Suppress("UNCHECKED_CAST")
    val json = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
    val project = json["project_info"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val client =
        (json["client"] as List<Map<String, Any?>>).firstOrNull { c ->
            ((c["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == packageName
        } ?: return emptyMap()

    @Suppress("UNCHECKED_CAST")
    val info = client["client_info"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val key = (client["api_key"] as List<Map<String, Any?>>).first()["current_key"].toString()
    return mapOf(
        "appId" to info["mobilesdk_app_id"].toString(),
        "apiKey" to key,
        "projectId" to project["project_id"].toString(),
        "senderId" to project["project_number"].toString(),
    )
}

fun com.android.build.api.dsl.ApplicationBuildType.firebaseFields(packageName: String) {
    val values = firebase(packageName)
    listOf(
        "appId" to "FIREBASE_APP_ID",
        "apiKey" to "FIREBASE_API_KEY",
        "projectId" to "FIREBASE_PROJECT_ID",
        "senderId" to "FIREBASE_SENDER_ID",
    ).forEach { (key, field) -> buildConfigField("String", field, "\"${values[key].orEmpty()}\"") }
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
            firebaseFields("com.vovoscan.app.dev")
            // -Pvovoscan.apiBaseUrl=…; defaults to the local API, as seen from the emulator.
            val apiBaseUrl = providers.gradleProperty("vovoscan.apiBaseUrl").getOrElse("http://10.0.2.2:8000/")
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            // The website (a circle's shared link): -Pvovoscan.webBaseUrl=…; the local web app, as seen from the emulator.
            val webBaseUrl = providers.gradleProperty("vovoscan.webBaseUrl").getOrElse("http://10.0.2.2:3000/")
            buildConfigField("String", "WEB_BASE_URL", "\"$webBaseUrl\"")
        }
        release {
            firebaseFields("com.vovoscan.app")
            val apiBaseUrl = providers.gradleProperty("vovoscan.releaseApiBaseUrl").getOrElse("https://vovoscan.yieldgrowth.org/")
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            val webBaseUrl = providers.gradleProperty("vovoscan.releaseWebBaseUrl").getOrElse("https://vovoscan.yieldgrowth.org/")
            buildConfigField("String", "WEB_BASE_URL", "\"$webBaseUrl\"")
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
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.window)
    // Sealed pushes: Firebase Cloud Messaging carries them, X25519 (Tink, every Android version) opens them; the key
    // pair is kept in Keystore-protected preferences.
    implementation(libs.firebase.messaging)
    implementation(libs.tink.android)
    implementation(libs.androidx.security.crypto)
    // A group's share link as a QR code (encoding only, no camera).
    implementation(libs.zxing.core)
    testImplementation(libs.kotlin.test.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
