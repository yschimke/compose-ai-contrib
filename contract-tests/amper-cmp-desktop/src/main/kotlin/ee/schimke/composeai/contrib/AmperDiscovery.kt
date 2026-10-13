package ee.schimke.composeai.contrib

import ee.schimke.composeai.discovery.PreviewDiscovery
import java.io.File
import kotlinx.serialization.json.Json

/** Scan the compiled consumer bytecode using the same published API as the Gradle plugin. */
internal fun discoverAmperPreviews(fixture: File, classes: File, module: String, dependencies: List<String>): String {
  val outcome = PreviewDiscovery.discover(
    PreviewDiscovery.Input(
      classDirs = listOf(classes),
      dependencyJars = dependencies.map(::File).filter { it.isFile && it.extension == "jar" },
      sourceFiles = File(fixture, "src").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList(),
      moduleName = module,
      variantName = "desktop",
      projectDirectory = fixture,
      failOnEmpty = true,
    )
  )
  require(outcome is PreviewDiscovery.Outcome.Success) { "Preview discovery failed for $fixture: $outcome" }
  return Json { encodeDefaults = true; prettyPrint = true }.encodeToString(outcome.manifest)
}
