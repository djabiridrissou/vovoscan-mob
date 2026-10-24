plugins {
    id("vovoscan.kmp-library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.sqldelight")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:notifications:domain"))
            implementation(project(":core:network"))
            implementation(project(":core:database"))
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies { implementation(project(":core:testing")) }
    }
}

// Tables and migrations owned by the feature; `verifySqlDelightMigration` (part of check) verifies them.
sqldelight {
    databases {
        create("NotificationsDatabase") {
            packageName.set("com.vovoscan.feature.notifications.data.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
