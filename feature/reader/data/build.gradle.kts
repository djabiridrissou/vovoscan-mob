plugins {
    id("vovoscan.kmp-library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.sqldelight")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:reader:domain"))
            implementation(project(":core:network"))
            implementation(project(":core:database"))
            implementation(project(":core:sources"))
            implementation(libs.okio)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies { implementation(project(":core:testing")) }
    }
}

// Tables et migrations propres à la feature ; `verifySqlDelightMigration` (dans check) les vérifie.
sqldelight {
    databases {
        create("ReaderDatabase") {
            packageName.set("com.vovoscan.feature.reader.data.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
