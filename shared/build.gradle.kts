// Parapluie : assemble core et features pour les deux applications, et exporte vers iOS
// le framework « Shared » (SKIE : Flow -> AsyncSequence, suspend -> async, sealed -> enum).

import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("vovoscan.kmp-library")
    id("co.touchlab.skie")
}

val features = listOf("auth", "catalog", "library", "reader", "sources")

kotlin {
    val xcframework = XCFramework("Shared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            binaryOption("bundleId", "com.vovoscan.shared")
            export(project(":core:common"))
            features.forEach { name ->
                export(project(":feature:$name:domain"))
                export(project(":feature:$name:presentation"))
            }
            xcframework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            features.forEach { name ->
                api(project(":feature:$name:domain"))
                api(project(":feature:$name:presentation"))
                implementation(project(":feature:$name:di"))
            }
            implementation(project(":core:network"))
            implementation(project(":core:security"))
            implementation(project(":core:database"))
            implementation(project(":core:sources"))
            api(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(project(":core:testing"))
            implementation(libs.koin.test)
        }
    }
}
