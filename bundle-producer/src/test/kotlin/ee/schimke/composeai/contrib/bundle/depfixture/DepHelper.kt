package ee.schimke.composeai.contrib.bundle.depfixture

/**
 * Stands in for a third-party dependency class. Lives in its own package so [BundleWriterTest] can
 * pack it into a separate "dependency jar" (apart from the module sources) and assert the closure
 * walk keeps it because the seed preview reaches it.
 */
class DepHelper {
  fun greet(): String = "hi from dep"
}

/** A second distinct dependency class, so a test can give the seed two reachable deps in two jars. */
class DepHelper2 {
  fun greet(): String = "hi from dep2"
}
