plugins {
    `kotlin-dsl`
}

// Every Gradle plugin of the project: modules apply them by id, without a version.
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
