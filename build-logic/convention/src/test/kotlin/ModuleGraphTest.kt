import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModuleGraphTest {
    @Test
    fun `une feature ne dépend pas d'une autre`() {
        assertNotNull(ModuleGraph.violations(":feature:library:data", ":feature:catalog:domain"))
    }

    @Test
    fun `domain reste du Kotlin pur`() {
        assertNull(ModuleGraph.violations(":feature:catalog:domain", ":core:common"))
        assertNotNull(ModuleGraph.violations(":feature:catalog:domain", ":core:network"))
    }

    @Test
    fun `data voit son domain et core, pas sa presentation`() {
        assertNull(ModuleGraph.violations(":feature:catalog:data", ":feature:catalog:domain"))
        assertNull(ModuleGraph.violations(":feature:catalog:data", ":core:database"))
        assertNotNull(ModuleGraph.violations(":feature:catalog:data", ":feature:catalog:presentation"))
    }

    @Test
    fun `presentation ne voit que son domain`() {
        assertNull(ModuleGraph.violations(":feature:catalog:presentation", ":feature:catalog:domain"))
        assertNotNull(ModuleGraph.violations(":feature:catalog:presentation", ":feature:catalog:data"))
    }

    @Test
    fun `core ne dépend d'aucune feature`() {
        assertNotNull(ModuleGraph.violations(":core:network", ":feature:auth:domain"))
    }
}
