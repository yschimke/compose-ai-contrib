package ee.schimke.composeai.contrib.bundle.modulefixture

import ee.schimke.composeai.contrib.bundle.depfixture.DepHelper
import ee.schimke.composeai.contrib.bundle.depfixture.DepHelper2

/**
 * Stands in for a consumer module's `@Preview` enclosing class: it references [DepHelper] and
 * [DepHelper2], so the closure walk must keep whichever dependency jars carry them.
 */
class SeedPreview {
  fun render(): String = DepHelper().greet() + DepHelper2().greet()
}

/**
 * A module class nothing reaches from the seed — the minimizer must drop it from `classes/app.jar`.
 */
class UnusedModuleClass {
  fun nothing(): Int = 0
}
