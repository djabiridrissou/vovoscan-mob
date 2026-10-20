// Android application: Compose, with AGP's built-in Kotlin.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun version(name: String) = libs.findVersion(name).get().requiredVersion

android {
    compileSdk = version("compileSdk").toInt()
    defaultConfig {
        minSdk = version("minSdk").toInt()
        targetSdk = version("targetSdk").toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

// Generated code (SQLDelight, BuildConfig…) is not checked.
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
    exclude { it.file.path.contains("/build/") }
}

ModuleGraph.check(project)
