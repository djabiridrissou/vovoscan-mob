plugins {
    id("vovoscan.kmp-library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.sqldelight")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:account:domain"))
            implementation(project(":core:network"))
            implementation(project(":core:database"))
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies { implementation(project(":core:testing")) }
    }
}

// Tables owned by the feature (never a provider secret); `verifySqlDelightMigration` (part of check) verifies them.
sqldelight {
    databases {
        create("AccountDatabase") {
            packageName.set("com.vovoscan.feature.account.data.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
