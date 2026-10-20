// Module Kotlin Multiplatform : Android, iosArm64 et iosSimulatorArm64, tests communs sur la JVM (host tests Android).

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun version(name: String) = libs.findVersion(name).get().requiredVersion
fun library(name: String) = libs.findLibrary(name).get()

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.vovoscan." + path.removePrefix(":").replace(":", ".").replace("-", "")
        compileSdk = version("compileSdk").toInt()
        minSdk = version("minSdk").toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTest {}
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        // Pilote SQLDelight natif (SQLiter) : SQLite système, pour les binaires de test comme pour le framework.
        target.binaries.all { linkerOpts("-lsqlite3") }
    }

    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(library("kotlinx-coroutines-test"))
        }
    }
}

// Le code généré (SQLDelight, BuildConfig…) n'est pas vérifié.
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
    exclude { it.file.path.contains("/build/") }
}

ModuleGraph.check(project)
