plugins {
    `kotlin-dsl`
}

// Tous les plugins Gradle du projet : les modules les appliquent par leur id, sans version.
dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.sqldelight.gradle.plugin)
    implementation(libs.skie.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
