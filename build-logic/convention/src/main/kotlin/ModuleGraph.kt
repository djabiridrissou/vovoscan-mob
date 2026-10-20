import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Règles d'architecture (clean architecture par feature), vérifiées à la configuration de chaque module :
 * - une feature ne dépend jamais d'une autre feature, seulement de `core` ;
 * - `domain` ne dépend que de `:core:common` (Kotlin pur : ni Ktor, ni SQLDelight, ni Koin) ;
 * - `data` voit son `domain` et `core`, jamais sa `presentation` ;
 * - `presentation` ne voit que le `domain` de sa feature et `:core:common` ;
 * - `core` ne dépend jamais d'une feature.
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
        if (from.startsWith(":core:") && target != null) return "$from -> $to : core ne dépend d'aucune feature"
        if (source == null) return null
        val (name, layer) = source.destructured
        if (target != null && target.groupValues[1] != name) return "$from -> $to : une feature ne dépend pas d'une autre"
        return when (layer) {
            "domain" ->
                if (to == ":core:common" || to == ":core:testing") null else "$from -> $to : domain ne dépend que de :core:common"
            "data" ->
                if (to == ":feature:$name:presentation" || to == ":feature:$name:di") {
                    "$from -> $to : data ne voit ni presentation ni di"
                } else {
                    null
                }
            "presentation" ->
                if (to in setOf(":core:common", ":core:testing", ":feature:$name:domain")) {
                    null
                } else {
                    "$from -> $to : presentation ne voit que son domain et :core:common"
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
            if (found.isNotEmpty()) throw GradleException("Dépendances interdites :\n" + found.joinToString("\n"))
        }
    }
}
