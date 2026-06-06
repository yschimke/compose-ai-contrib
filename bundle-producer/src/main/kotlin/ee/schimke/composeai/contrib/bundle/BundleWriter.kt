package ee.schimke.composeai.contrib.bundle

import io.github.classgraph.ClassGraph
import io.github.classgraph.ScanResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/**
 * Packs a portable preview bundle from non-Gradle (Amper / Bazel) build outputs. This is the
 * build-system-agnostic core the issue asks contrib to provide: it reuses the same ClassGraph
 * reachability closure the upstream `BundlePreviewTask.closureWalk` uses (over a flat
 * `moduleSources + dependencyJars` scan, no Gradle dependency) to inline only the consumer bytecode
 * and keep only the third-party deps actually reachable from the selected previews.
 *
 * The caller supplies, per dependency jar, either a Maven coordinate (`group:artifact:version[:type]`)
 * — recovered from Amper's m2 cache layout or Bazel's `maven_install.json` — or `null` for a
 * coordinate-less (vendored) jar. Coordinate deps are recorded as detached [ClasspathEntry.Maven]
 * (with a `sha256`); coordinate-less deps, and everything when [BundleInputs.embed] is set, are
 * carried inside `libs/` as [ClasspathEntry.Embedded]. The [BundleManifest.resolution] is derived
 * honestly from the result (`coordinates` / `embedded` / `mixed`).
 */
object BundleWriter {

  /** A runtime-classpath jar plus its recovered coordinate (`group:artifact:version[:type]`, or null). */
  data class DependencyInput(val jar: File, val coordinate: String?)

  data class BundleInputs(
    /** Selected previews; the first entry is the cover. Must be non-empty. */
    val previews: List<PreviewInfo>,
    /**
     * The consumer module's own bytecode: class directories (Amper `kotlin-output/`) and/or jars
     * (a Bazel `kt_*_library` output jar). Reachable classes are repacked into `classes/app.jar`.
     */
    val moduleSources: List<File>,
    /** Third-party runtime jars (driven through the closure walk; kept ones land in the classpath). */
    val dependencyJars: List<DependencyInput>,
    /** Optional baked render per preview id (`previewId -> png file`). Missing ids get no image. */
    val renderPngs: Map<String, File> = emptyMap(),
    /** Optional module resources directory, bundled wholesale into `classes/app.jar`. */
    val moduleResourcesDir: File? = null,
    val modulePath: String,
    val variant: String = "",
    val backend: String = "desktop",
    val producer: String,
    val producedBy: String,
    /** Force every coordinate dep into `libs/` (offline `embedded` pack). Coordinate-less deps embed regardless. */
    val embed: Boolean = false,
  )

  /** Summary of a written bundle, for logging / assertions. */
  data class Result(
    val outputFile: File,
    val resolution: String,
    val mavenEntries: Int,
    val embeddedEntries: Int,
    val moduleClassesKept: Int,
    val moduleClassesTotal: Int,
    val depsDropped: Int,
    val bakedPngs: Int,
    /**
     * Jars embedded **because no coordinate was recovered** (not the intentionally `--embed`'d
     * coordinate jars). These are the ones worth checking against a registry — a published one here
     * is a recovery miss, not a genuinely vendored jar. See [EmbeddedVerifier].
     */
    val embeddedWithoutCoordinate: List<File> = emptyList(),
  )

  private val json = Json {
    prettyPrint = true
    encodeDefaults = true
    classDiscriminator = "kind"
  }

  fun write(inputs: BundleInputs, out: File): Result {
    require(inputs.previews.isNotEmpty()) { "bundle has no previews to pack" }
    val coverId = inputs.previews.first().id

    val moduleSources = inputs.moduleSources.filter { it.exists() }
    val depJars = inputs.dependencyJars.filter { it.jar.isFile && it.jar.name.endsWith(".jar") }
    val scanPaths = (moduleSources + depJars.map { it.jar }).map { it.absolutePath }

    val seedFqns = inputs.previews.map { it.className }.toSet()
    val closure = closureWalk(scanPaths, seedFqns)

    val moduleClassFqns = collectModuleClassFqns(moduleSources)
    val reachableModuleClasses = moduleClassFqns intersect closure.reachable
    val keptModuleClassFiles = packModuleClasses(moduleSources, reachableModuleClasses)
    val appJarBytes = buildJar(keptModuleClassFiles, inputs.moduleResourcesDir)

    val depDecisions = buildDepDecisions(depJars, closure.perElement)
    val assembled = assembleClasspath(depJars, depDecisions, embed = inputs.embed)

    val report =
      MinimizationReport(
        entryClassFqns = seedFqns.sorted(),
        reachableClassCount = closure.reachable.size,
        totalScannedClassCount = closure.totalScanned,
        moduleClasses =
          ModuleClassesStats(
            totalClasses = moduleClassFqns.size,
            reachableClasses = reachableModuleClasses.size,
            packedBytes = appJarBytes.size.toLong(),
          ),
        dependencies = depDecisions,
      )

    val manifest =
      BundleManifest(
        schemaVersion = BUNDLE_SCHEMA_VERSION,
        backend = inputs.backend,
        previewIds = inputs.previews.map { it.id },
        coverPreviewId = coverId,
        classpath = assembled.entries,
        modulePath = inputs.modulePath,
        producedBy = inputs.producedBy,
        producer = inputs.producer,
        resolution = assembled.resolution,
      )

    val previewManifest =
      PreviewManifest(
        module = inputs.modulePath,
        variant = inputs.variant,
        previews = inputs.previews,
      )

    val previewPngs = LinkedHashMap<String, ByteArray>()
    for (preview in inputs.previews) {
      val png = inputs.renderPngs[preview.id]?.takeIf { it.isFile && it.length() > 0 } ?: continue
      previewPngs[preview.id] = png.readBytes()
    }

    val zipBytes =
      buildZip(
        bundleJson = json.encodeToString(BundleManifest.serializer(), manifest),
        previewsJson = json.encodeToString(PreviewManifest.serializer(), previewManifest),
        appJar = appJarBytes,
        inlinedJars = assembled.inlinedJars,
        report = json.encodeToString(MinimizationReport.serializer(), report),
        previewPngs = previewPngs,
      )

    val coverPng = previewPngs[coverId] ?: STUB_GRAY_PNG
    writePngZipPolyglot(coverPng, zipBytes, out)

    return Result(
      outputFile = out,
      resolution = assembled.resolution,
      mavenEntries = assembled.entries.count { it is ClasspathEntry.Maven },
      embeddedEntries = assembled.entries.count { it is ClasspathEntry.Embedded },
      moduleClassesKept = reachableModuleClasses.size,
      moduleClassesTotal = moduleClassFqns.size,
      depsDropped = depDecisions.count { !it.kept },
      bakedPngs = previewPngs.size,
      embeddedWithoutCoordinate = assembled.coordinatelessEmbedded,
    )
  }

  // --- Closure walk (mirrors upstream BundlePreviewTask) -------------------------------------------

  private data class PerElementCount(val reachable: Int, val total: Int)

  private data class Closure(
    val reachable: Set<String>,
    val perElement: Map<String, PerElementCount>,
    val totalScanned: Int,
  )

  private fun closureWalk(scanPaths: List<String>, seed: Set<String>): Closure {
    if (scanPaths.isEmpty()) {
      return Closure(reachable = seed.toSet(), perElement = emptyMap(), totalScanned = 0)
    }
    ClassGraph()
      .enableAllInfo()
      .enableInterClassDependencies()
      .overrideClasspath(scanPaths)
      .ignoreParentClassLoaders()
      .scan()
      .use { scan ->
        val reachable = bfsReachable(scan, seed)
        val perElement = HashMap<String, IntArray>() // [reachable, total]
        for (ci in scan.allClasses) {
          val file = ci.classpathElementFile?.absolutePath ?: continue
          val counts = perElement.getOrPut(file) { IntArray(2) }
          counts[1]++
          if (ci.name in reachable) counts[0]++
        }
        return Closure(
          reachable = reachable,
          perElement = perElement.mapValues { (_, c) -> PerElementCount(c[0], c[1]) },
          totalScanned = scan.allClasses.size,
        )
      }
  }

  /**
   * BFS over ClassGraph's inter-class dependency map. Kotlin top-level functions live on `FooKt` and
   * their generated peers (`ComposableSingletons$FooKt`, `FooKt$lambda-1`) share the prefix, so we
   * seed every class whose name equals or is `$`-suffixed from a seed FQN.
   */
  private fun bfsReachable(scan: ScanResult, seed: Set<String>): Set<String> {
    if (seed.isEmpty()) return emptySet()
    val depMap = scan.classDependencyMap
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    for (entry in seed) {
      for (ci in scan.allClasses) {
        if (ci.name == entry || ci.name.startsWith("$entry$")) {
          if (visited.add(ci.name)) queue += ci.name
        }
      }
    }
    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      val ci = scan.getClassInfo(current) ?: continue
      val deps = depMap[ci] ?: continue
      for (dep in deps) {
        if (visited.add(dep.name)) queue += dep.name
      }
    }
    return visited
  }

  // --- Module class packing ------------------------------------------------------------------------

  /** Every class FQN defined by the module's own sources (class dirs and/or jars). */
  private fun collectModuleClassFqns(sources: List<File>): Set<String> {
    val result = mutableSetOf<String>()
    for (source in sources) {
      if (source.isDirectory) {
        source
          .walkTopDown()
          .filter { it.isFile && it.name.endsWith(".class") }
          .forEach { f ->
            val rel = f.relativeTo(source).path.replace(File.separatorChar, '/')
            result += rel.removeSuffix(".class").replace('/', '.')
          }
      } else if (source.isFile && source.name.endsWith(".jar")) {
        forEachJarEntry(source) { name, _ ->
          if (name.endsWith(".class")) result += name.removeSuffix(".class").replace('/', '.')
        }
      }
    }
    return result
  }

  /** Reachable module `.class` bytes keyed by their posix path inside `classes/app.jar`. */
  private fun packModuleClasses(sources: List<File>, reachable: Set<String>): Map<String, ByteArray> {
    val result = LinkedHashMap<String, ByteArray>()
    for (source in sources) {
      if (source.isDirectory) {
        source
          .walkTopDown()
          .filter { it.isFile && it.name.endsWith(".class") }
          .forEach { f ->
            val rel = f.relativeTo(source).path.replace(File.separatorChar, '/')
            val fqn = rel.removeSuffix(".class").replace('/', '.')
            if (fqn in reachable && rel !in result) result[rel] = f.readBytes()
          }
      } else if (source.isFile && source.name.endsWith(".jar")) {
        forEachJarEntry(source) { name, bytes ->
          if (name.endsWith(".class")) {
            val fqn = name.removeSuffix(".class").replace('/', '.')
            if (fqn in reachable && name !in result) result[name] = bytes()
          }
        }
      }
    }
    return result
  }

  // --- Dependency decisions + classpath assembly ---------------------------------------------------

  private fun buildDepDecisions(
    jars: List<DependencyInput>,
    perElement: Map<String, PerElementCount>,
  ): List<DependencyDecision> = jars.map { dep ->
    val totals = perElement[dep.jar.absolutePath]
    val reachable = totals?.reachable ?: 0
    DependencyDecision(
      sourcePath = dep.jar.absolutePath,
      coordinate = dep.coordinate,
      projectPath = null,
      totalClasses = totals?.total ?: 0,
      reachableClasses = reachable,
      originalBytes = dep.jar.length(),
      kept = reachable > 0,
    )
  }

  private data class AssembledClasspath(
    val entries: List<ClasspathEntry>,
    val inlinedJars: Map<String, File>,
    val resolution: String,
    /** Embedded jars that had no recovered coordinate (the registry-check suspects). */
    val coordinatelessEmbedded: List<File>,
  )

  private fun assembleClasspath(
    jars: List<DependencyInput>,
    deps: List<DependencyDecision>,
    embed: Boolean,
  ): AssembledClasspath {
    val byPath = jars.associateBy { it.jar.absolutePath }
    val entries = mutableListOf<ClasspathEntry>(ClasspathEntry.Module(path = "classes/app.jar"))
    val inlinedJars = LinkedHashMap<String, File>()
    val coordinatelessEmbedded = mutableListOf<File>()
    val seenJarNames = mutableMapOf<String, Int>()
    var mavenReferenced = 0
    var mavenEmbedded = 0

    fun dedupeJarName(name: String): String {
      val count = seenJarNames.getOrDefault(name, 0)
      seenJarNames[name] = count + 1
      return if (count == 0) name else name.removeSuffix(".jar") + "-$count.jar"
    }

    for (dep in deps) {
      if (!dep.kept) continue
      val src = byPath[dep.sourcePath]?.jar
      val coord = dep.coordinate
      when {
        // Coordinate dep, default mode: reference detached, with a content hash for verification.
        coord != null && !embed -> {
          entries += parseMavenCoord(coord, src)
          mavenReferenced++
        }
        // Coordinate dep, embed mode: carry the jar in libs/ (fall back to a coordinate if missing).
        coord != null -> {
          if (src != null) {
            val inlined = "libs/${dedupeJarName(src.name)}"
            inlinedJars[inlined] = src
            entries += ClasspathEntry.Embedded(inlinedAs = inlined)
            mavenEmbedded++
          } else {
            entries += parseMavenCoord(coord, src = null)
            mavenReferenced++
          }
        }
        // Coordinate-less (vendored) dep: must be embedded — there's nothing to resolve.
        else -> {
          val name = src?.name ?: File(dep.sourcePath).name
          val inlined = "libs/${dedupeJarName(name)}"
          if (src != null) {
            inlinedJars[inlined] = src
            coordinatelessEmbedded += src
          }
          entries += ClasspathEntry.Embedded(inlinedAs = inlined)
          mavenEmbedded++
        }
      }
    }

    val resolution =
      when {
        mavenEmbedded > 0 && mavenReferenced > 0 -> RESOLUTION_MIXED
        mavenEmbedded > 0 -> RESOLUTION_EMBEDDED
        else -> RESOLUTION_COORDINATES
      }
    return AssembledClasspath(
      entries = entries,
      inlinedJars = inlinedJars,
      resolution = resolution,
      coordinatelessEmbedded = coordinatelessEmbedded,
    )
  }

  /**
   * Parse `group:artifact:version[:type]` into a [ClasspathEntry.Maven], hashing [src] when present
   * so the detached coordinate is verifiable. Defaults the packaging to `jar`.
   */
  private fun parseMavenCoord(coord: String, src: File?): ClasspathEntry.Maven {
    val parts = coord.split(':')
    require(parts.size >= 3) { "malformed Maven coordinate: $coord" }
    return ClasspathEntry.Maven(
      group = parts[0],
      artifact = parts[1],
      version = parts[2],
      type = parts.getOrNull(3)?.ifBlank { "jar" } ?: "jar",
      sha256 = src?.let { sha256Hex(it) },
    )
  }

  // --- Zip / jar building --------------------------------------------------------------------------

  private fun buildJar(classes: Map<String, ByteArray>, resourcesDir: File?): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      classes.forEach { (path, bytes) -> zip.writeFile(path, bytes) }
      if (resourcesDir != null && resourcesDir.isDirectory) {
        resourcesDir
          .walkTopDown()
          .filter { it.isFile }
          .forEach { f ->
            val rel = f.relativeTo(resourcesDir).path.replace(File.separatorChar, '/')
            if (rel !in classes) zip.writeFile(rel, f.readBytes())
          }
      }
    }
    return baos.toByteArray()
  }

  private fun buildZip(
    bundleJson: String,
    previewsJson: String,
    appJar: ByteArray,
    inlinedJars: Map<String, File>,
    report: String,
    previewPngs: Map<String, ByteArray>,
  ): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      zip.writeFile("bundle.json", bundleJson.toByteArray(Charsets.UTF_8))
      zip.writeFile("previews.json", previewsJson.toByteArray(Charsets.UTF_8))
      previewPngs.forEach { (id, bytes) -> zip.writeFile("$BUNDLE_PREVIEWS_DIR/$id.png", bytes) }
      zip.writeFile("classes/app.jar", appJar)
      inlinedJars.forEach { (path, file) -> zip.writeFile(path, file.readBytes()) }
      zip.writeFile("report.json", report.toByteArray(Charsets.UTF_8))
    }
    return baos.toByteArray()
  }

  /** Pin every entry to the DOS-epoch floor so the bundle is byte-reproducible across runs. */
  private fun ZipOutputStream.writeFile(path: String, bytes: ByteArray) {
    val entry = ZipEntry(path)
    entry.time = ZIP_DOS_EPOCH_MS
    putNextEntry(entry)
    write(bytes)
    closeEntry()
  }

  // --- Helpers -------------------------------------------------------------------------------------

  /** Visit each entry of [jar]; `bytes()` lazily reads the current entry's bytes. */
  private inline fun forEachJarEntry(jar: File, action: (name: String, bytes: () -> ByteArray) -> Unit) {
    ZipInputStream(jar.inputStream().buffered()).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        if (!entry.isDirectory) action(entry.name) { zin.readBytes() }
        zin.closeEntry()
      }
    }
  }

  private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private val ZIP_DOS_EPOCH_MS: Long =
    java.util.GregorianCalendar(1980, java.util.Calendar.JANUARY, 1, 0, 0, 0).timeInMillis
}
