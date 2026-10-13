package ee.schimke.composeai.contrib

import ee.schimke.composeai.contrib.bundle.BundleWriter
import ee.schimke.composeai.contrib.bundle.Coordinates
import ee.schimke.composeai.contrib.bundle.EmbeddedVerifier
import ee.schimke.composeai.contrib.bundle.PRODUCER_AMPER
import ee.schimke.composeai.contrib.bundle.PreviewInfo
import java.io.File

/**
 * Packs a portable preview bundle for the Amper Compose-Desktop fixture, answering the issue's Q1/Q2
 * for the Amper producer: it emits a real `bundle.json` with a `classpath[]` (not PNGs only), and it
 * recovers resolvable Maven coordinates for each reachable runtime jar from **Amper's m2 cache** —
 * a standard Maven-layout tree (`~/.cache/JetBrains/Kotlin/.m2.cache/<group>/<artifact>/<version>/…`)
 * — so the default pack is `resolution = "coordinates"` with a `sha256` per dep. A jar with no
 * recoverable coordinate (or [embed] = true) is carried in `libs/` instead.
 *
 * The reachability prune is the shared [BundleWriter] closure walk (ClassGraph over the Amper
 * `kotlin-output/` classes + the fixture's resolved runtime jars), so only deps the rendered preview actually reaches are
 * recorded — the same minimization the Gradle plugin does, with no Gradle dependency.
 */
object AmperBundleEmitter {

  /**
   * @param previews selected previews (first = cover), already rendered.
   * @param amperKotlinOutput the module's compiled-classes dir (`…/kotlin-output`).
   * @param runtimeClasspathFile newline-separated paths captured from the fixture's Kotlin Toolchain run task.
   * @param m2CacheRoots Maven-layout roots used only to recover coordinates.
   * @param renderPngs `previewId -> rendered png` (baked into the bundle for detached viewing).
   * @param out the `bundle.png` polyglot to write.
   * @param embed force an offline `embedded` pack (every dep carried in `libs/`).
   */
  fun emit(
    previews: List<PreviewInfo>,
    amperKotlinOutput: File,
    runtimeClasspathFile: File,
    m2CacheRoots: List<File>,
    renderPngs: Map<String, File>,
    modulePath: String,
    variant: String,
    out: File,
    embed: Boolean,
    verifyEmbedded: Boolean = false,
  ): BundleWriter.Result {
    val existingRoots = m2CacheRoots.filter { it.isDirectory }
    val runtimeJars = readRuntimeJars(runtimeClasspathFile)
    val dependencyJars =
      runtimeJars.map { jar ->
        BundleWriter.DependencyInput(
          jar = jar,
          coordinate = Coordinates.recoverFromMavenLayout(jar, existingRoots),
        )
      }

    val result =
      BundleWriter.write(
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

    // Coordinate recovery is best-effort: jars outside a known Maven cache stay embedded.
    if (verifyEmbedded && result.embeddedWithoutCoordinate.isNotEmpty()) {
      val findings =
        EmbeddedVerifier.verify(
          result.embeddedWithoutCoordinate,
          EmbeddedVerifier.MavenCentralChecksumSearch(),
        )
      for (f in findings.filter { it.resolvable }) {
        System.err.println(
          "AmperBundleEmitter: WARNING — embedded ${f.jar.name} is published as ${f.publishedAs}; " +
            "this is a coordinate-recovery miss, not a vendored jar."
        )
      }
    }

    return result
  }

  /** The runtime classpath is the source of truth; cache roots only identify Maven coordinates. */
  fun readRuntimeJars(classpathFile: File): List<File> {
    require(classpathFile.isFile) {
      "Missing Kotlin Toolchain runtime classpath: $classpathFile. Run the fixture's classpath export first."
    }
    val entries = classpathFile.readLines().filter(String::isNotBlank)
    require(entries.isNotEmpty()) { "Empty Kotlin Toolchain runtime classpath: $classpathFile" }
    val jars = entries.map(::File).filter { it.extension == "jar" }.distinctBy { it.absoluteFile.normalize().path }
    require(jars.isNotEmpty()) { "No runtime jars in Kotlin Toolchain classpath: $classpathFile" }
    jars.forEach { require(it.isFile) { "Missing runtime jar from Kotlin Toolchain classpath: $it" } }
    return jars
  }

  /**
   * Default Amper m2-cache roots, honouring an override system property
   * (`contrib.amperM2Cache`, path-separated). Kotlin Toolchain's current cache plus the legacy
   * Amper cache by default. These roots are only used for coordinate recovery, never scanned.
   */
  fun defaultM2CacheRoots(): List<File> {
    System.getProperty("contrib.amperM2Cache")?.let { return it.split(File.pathSeparator).map(::File) }
    val home = System.getProperty("user.home") ?: return emptyList()
    val sharedCache = System.getenv("KOTLIN_SHARED_CACHE_DIR") ?: System.getenv("AMPER_SHARED_CACHE_DIR")
    if (!sharedCache.isNullOrBlank()) return listOf(File(sharedCache, ".m2.cache"))
    val os = System.getProperty("os.name", "").lowercase()
    val root = when {
      os.contains("mac") -> File(home, "Library/Caches/JetBrains/Kotlin")
      os.contains("win") -> File(System.getenv("LOCALAPPDATA") ?: home, "JetBrains/Kotlin")
      else -> File(System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", "JetBrains/Kotlin")
    }
    return listOf(File(root, ".m2.cache"), File(home, ".cache/JetBrains/Amper/.m2.cache"))
  }
}
