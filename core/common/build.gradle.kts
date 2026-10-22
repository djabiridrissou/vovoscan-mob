plugins {
    id("vovoscan.kmp-library")
}

/*
 * The UI catalogues (i18n/<locale>.json, flat `namespace.key` objects shared with the web app, i18n/SPEC.md) built into
 * the code as strings: every platform reads them the same way, with no resource loading.
 */
val generateCatalogues by tasks.registering {
    val input = layout.projectDirectory.dir("i18n")
    val output = layout.buildDirectory.dir("generated/i18n/kotlin")
    inputs.dir(input)
    outputs.dir(output)
    doLast {
        fun chunks(text: String): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            text.forEach { ch ->
                when {
                    ch == '\\' -> current.append("\\\\")
                    ch == '"' -> current.append("\\\"")
                    ch == '$' -> current.append("\\$")
                    ch.code in 0x20..0x7e -> current.append(ch)
                    else -> current.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                }
                if (current.length >= 4000) {
                    out += current.toString()
                    current.clear()
                }
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }
        val files =
            input.asFile
                .listFiles()
                .orEmpty()
                .filter { it.extension == "json" }
                .sortedBy { it.name }
        val entries =
            files.joinToString("\n") { file ->
                val parts = chunks(file.readText()).joinToString(",\n") { "                    \"$it\"" }
                "            \"${file.nameWithoutExtension}\" to\n                listOf(\n$parts,\n                ).joinToString(\"\"),"
            }
        val dir = output.get().asFile.resolve("com/vovoscan/core/common/i18n")
        dir.mkdirs()
        dir.resolve("CatalogueSources.kt").writeText(
            "// Generated from i18n/*.json by :core:common:generateCatalogues. Do not edit.\n" +
                "package com.vovoscan.core.common.i18n\n\ninternal object CatalogueSources {\n" +
                "    val json: Map<String, String> =\n        mapOf(\n$entries\n        )\n}\n",
        )
    }
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(generateCatalogues)
            dependencies {
                api(libs.kotlinx.coroutines.core)
            }
        }
    }
}

// Android lint reads the generated sources too.
tasks.configureEach { if (name.startsWith("lint") || name.contains("LintModel")) dependsOn(generateCatalogues) }
