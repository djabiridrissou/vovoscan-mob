import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Architecture rules (clean architecture per feature), checked when each module is configured:
 * - a feature never depends on another feature, only on `core`;
 * - `domain` depends only on `:core:common` (pure Kotlin: no Ktor, no SQLDelight, no Koin);
 * - `data` sees its `domain` and `core`, never its `presentation`;
 * - `presentation` sees only its feature's `domain` and `:core:common`;
 * - `core` never depends on a feature.
 */
object ModuleGraph {
    private val feature = Regex("^:feature:([^:]+):([^:]+)$")

    fun violations(
        from: String,
        to: String,
    ): String? {
        if (from == to) return null
        val source = feature.find(from)
        val target = feature.find(to)
        if (from.startsWith(":core:") && target != null) return "$from -> $to: core depends on no feature"
        if (source == null) return null
        val (name, layer) = source.destructured
        if (target != null && target.groupValues[1] != name) return "$from -> $to: a feature does not depend on another"
        return when (layer) {
            "domain" ->
                if (to == ":core:common" || to == ":core:testing") null else "$from -> $to: domain depends only on :core:common"
            "data" ->
                if (to == ":feature:$name:presentation" || to == ":feature:$name:di") {
                    "$from -> $to: data sees neither presentation nor di"
                } else {
                    null
                }
            "presentation" ->
                if (to in setOf(":core:common", ":core:testing", ":feature:$name:domain")) {
                    null
                } else {
                    "$from -> $to: presentation sees only its domain and :core:common"
                }
            else -> null
        }
    }

    fun check(project: Project) {
        project.afterEvaluate {
            val found =
                configurations
                    .flatMap { configuration ->
                        configuration.dependencies.withType(ProjectDependency::class.java).mapNotNull { violations(path, it.path) }
                    }.distinct()
            if (found.isNotEmpty()) throw GradleException("Forbidden dependencies:\n" + found.joinToString("\n"))
        }
    }
}
