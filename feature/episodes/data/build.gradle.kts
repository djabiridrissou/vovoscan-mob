plugins {
    id("vovoscan.kmp-library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.sqldelight")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:episodes:domain"))
            implementation(project(":core:network"))
            implementation(project(":core:database"))
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies { implementation(project(":core:testing")) }
    }
}

// Tables owned by the feature (last known episode and watch per chapter); `verifySqlDelightMigration` (part of check) verifies them.
sqldelight {
    databases {
        create("EpisodesDatabase") {
            packageName.set("com.vovoscan.feature.episodes.data.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
