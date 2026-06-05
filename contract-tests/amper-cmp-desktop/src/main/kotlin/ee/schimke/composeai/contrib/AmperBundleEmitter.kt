package ee.schimke.composeai.contrib

import ee.schimke.composeai.contrib.bundle.BundleWriter
import ee.schimke.composeai.contrib.bundle.Coordinates
import ee.schimke.composeai.contrib.bundle.PRODUCER_AMPER
import ee.schimke.composeai.contrib.bundle.PreviewInfo
import java.io.File

/**
 * Packs a portable preview bundle for the Amper Compose-Desktop fixture, answering the issue's Q1/Q2
 * for the Amper producer: it emits a real `bundle.json` with a `classpath[]` (not PNGs only), and it
 * recovers resolvable Maven coordinates for each reachable runtime jar from **Amper's m2 cache** —
 * a standard Maven-layout tree (`~/.cache/JetBrains/Amper/.m2.cache/<group>/<artifact>/<version>/…`)
 * — so the default pack is `resolution = "coordinates"` with a `sha256` per dep. A jar with no
 * recoverable coordinate (or [embed] = true) is carried in `libs/` instead.
 *
 * The reachability prune is the shared [BundleWriter] closure walk (ClassGraph over the Amper
 * `kotlin-output/` classes + the cache jars), so only deps the rendered preview actually reaches are
 * recorded — the same minimization the Gradle plugin does, with no Gradle dependency.
 */
object AmperBundleEmitter {

  /**
   * @param previews selected previews (first = cover), already rendered.
   * @param amperKotlinOutput the module's compiled-classes dir (`…/kotlin-output`).
   * @param m2CacheRoots Maven-layout roots to recover coordinates from and to scan for runtime jars.
   * @param renderPngs `previewId -> rendered png` (baked into the bundle for detached viewing).
   * @param out the `bundle.png` polyglot to write.
   * @param embed force an offline `embedded` pack (every dep carried in `libs/`).
   */
  fun emit(
    previews: List<PreviewInfo>,
    amperKotlinOutput: File,
    m2CacheRoots: List<File>,
    renderPngs: Map<String, File>,
    modulePath: String,
    variant: String,
    out: File,
    embed: Boolean,
  ): BundleWriter.Result {
    val existingRoots = m2CacheRoots.filter { it.isDirectory }
    val cacheJars = existingRoots.flatMap { collectJars(it) }
    val dependencyJars =
      cacheJars.map { jar ->
        BundleWriter.DependencyInput(
          jar = jar,
          coordinate = Coordinates.recoverFromMavenLayout(jar, existingRoots),
        )
      }

    return BundleWriter.write(
      BundleWriter.BundleInputs(
        previews = previews,
        moduleSources = listOf(amperKotlinOutput),
        dependencyJars = dependencyJars,
        renderPngs = renderPngs,
        modulePath = modulePath,
        variant = variant,
        backend = "desktop",
        producer = PRODUCER_AMPER,
        producedBy = "compose-ai-contrib amper producer",
        embed = embed,
      ),
      out,
    )
  }

  /** All `.jar` files under [root], skipping `-sources`/`-javadoc` sidecars. */
  private fun collectJars(root: File): List<File> =
    root
      .walkTopDown()
      .filter {
        it.isFile &&
          it.name.endsWith(".jar") &&
          !it.name.endsWith("-sources.jar") &&
          !it.name.endsWith("-javadoc.jar")
      }
      .toList()

  /**
   * Default Amper m2-cache roots, honouring an override system property
   * (`contrib.amperM2Cache`, path-separated). Just Amper's own cache by default — the closure walk
   * only keeps *reachable* jars, so a stray sibling repo would only cost scan time, but keeping the
   * root tight keeps the ClassGraph scan fast and the recovered coordinates unambiguous.
   */
  fun defaultM2CacheRoots(): List<File> {
    System.getProperty("contrib.amperM2Cache")?.let { return it.split(File.pathSeparator).map(::File) }
    val home = System.getProperty("user.home") ?: return emptyList()
    return listOf(File(home, ".cache/JetBrains/Amper/.m2.cache"))
  }
}
