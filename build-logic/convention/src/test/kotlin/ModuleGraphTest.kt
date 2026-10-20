import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModuleGraphTest {
    @Test
    fun `a feature does not depend on another`() {
        assertNotNull(ModuleGraph.violations(":feature:library:data", ":feature:catalog:domain"))
    }

    @Test
    fun `domain stays pure Kotlin`() {
        assertNull(ModuleGraph.violations(":feature:catalog:domain", ":core:common"))
        assertNotNull(ModuleGraph.violations(":feature:catalog:domain", ":core:network"))
    }

    @Test
    fun `data sees its domain and core, not its presentation`() {
        assertNull(ModuleGraph.violations(":feature:catalog:data", ":feature:catalog:domain"))
        assertNull(ModuleGraph.violations(":feature:catalog:data", ":core:database"))
        assertNotNull(ModuleGraph.violations(":feature:catalog:data", ":feature:catalog:presentation"))
    }

    @Test
    fun `presentation sees only its domain`() {
        assertNull(ModuleGraph.violations(":feature:catalog:presentation", ":feature:catalog:domain"))
        assertNotNull(ModuleGraph.violations(":feature:catalog:presentation", ":feature:catalog:data"))
    }

    @Test
    fun `core depends on no feature`() {
        assertNotNull(ModuleGraph.violations(":core:network", ":feature:auth:domain"))
    }
}
