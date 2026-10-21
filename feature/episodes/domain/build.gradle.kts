plugins {
    id("vovoscan.kmp-library")
}

/*
 * The shared player vectors (fixtures/framing.json, mix.json, captions.json: the same files the backend and the old
 * web player test against, so every player matches the MP4 export) and a timeline (fixtures/timeline.json, from the
 * backend's tests), turned into Kotlin strings: commonTest reads them the same way on the JVM and on iOS.
 */
val generateVectors by tasks.registering {
    val input = layout.projectDirectory.dir("src/commonTest/fixtures")
    val output = layout.buildDirectory.dir("generated/vectors/kotlin")
    inputs.dir(input)
    outputs.dir(output)
    doLast {
        fun literal(text: String): List<String> {
            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            var count = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val pair = c.isHighSurrogate() && i + 1 < text.length
                val piece = if (pair) text.substring(i, i + 2) else c.toString()
                piece.forEach { ch ->
                    when {
                        ch == '\\' -> current.append("\\\\")
                        ch == '"' -> current.append("\\\"")
                        ch == '$' -> current.append("\\$")
                        ch.code in 0x20..0x7e -> current.append(ch)
                        else -> current.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    }
                }
                i += piece.length
                count += piece.length
                if (count >= 4000) {
                    chunks += current.toString()
                    current.clear()
                    count = 0
                }
            }
            if (current.isNotEmpty()) chunks += current.toString()
            return chunks
        }
        val files =
            input.asFile
                .listFiles()
                .orEmpty()
                .filter { it.extension == "json" }
                .sortedBy { it.name }
        val body =
            files.joinToString("\n") { file ->
                val parts = literal(file.readText()).joinToString(",\n") { "            \"$it\"" }
                "    val ${file.nameWithoutExtension}: String =\n        listOf(\n$parts,\n        ).joinToString(\"\")\n"
            }
        val dir = output.get().asFile.resolve("com/vovoscan/feature/episodes/domain/player")
        dir.mkdirs()
        dir.resolve("Vectors.kt").writeText(
            "// Generated from src/commonTest/fixtures by :feature:episodes:domain:generateVectors. Do not edit.\n" +
                "package com.vovoscan.feature.episodes.domain.player\n\ninternal object Vectors {\n$body}\n",
        )
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":core:common")) }
        commonTest.dependencies { implementation(libs.kotlinx.serialization.json) }
        commonTest { kotlin.srcDir(generateVectors) }
    }
}

// Android lint reads the test sources too, the generated vectors included.
tasks.configureEach { if (name.startsWith("lint") || name.contains("LintModel")) dependsOn(generateVectors) }
