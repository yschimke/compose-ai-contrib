package ee.schimke.composeai.contrib.bundle

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.contrib.bundle.depfixture.DepHelper
import ee.schimke.composeai.contrib.bundle.modulefixture.SeedPreview
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundleWriterTest {

  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }

  private val seedPreview =
    PreviewInfo(
      id = "modulefixture.SeedPreview",
      className = "ee.schimke.composeai.contrib.bundle.modulefixture.SeedPreview",
      functionName = "render",
      sourceFile = "ModuleFixtures.kt",
    )

  /** Compiled-classes root holding the test fixtures (e.g. `build/classes/kotlin/test`). */
  private fun classesRoot(): File =
    File(SeedPreview::class.java.protectionDomain.codeSource.location.toURI())

  private fun copyClassInto(dir: File, fqn: String) {
    val rel = fqn.replace('.', '/') + ".class"
    val src = File(classesRoot(), rel)
    check(src.isFile) { "fixture class not compiled: $src" }
    val dest = File(dir, rel)
    dest.parentFile.mkdirs()
    src.copyTo(dest, overwrite = true)
  }

  private fun jarOf(jarFile: File, vararg fqns: String): File {
    ZipOutputStream(jarFile.outputStream()).use { zip ->
      for (fqn in fqns) {
        val rel = fqn.replace('.', '/') + ".class"
        val src = File(classesRoot(), rel)
        check(src.isFile) { "fixture class not compiled: $src" }
        zip.putNextEntry(java.util.zip.ZipEntry(rel))
        zip.write(src.readBytes())
        zip.closeEntry()
      }
    }
    return jarFile
  }

  private fun moduleDirWithFixtures(): File {
    val moduleDir = tmp.newFolder("module")
    copyClassInto(moduleDir, "ee.schimke.composeai.contrib.bundle.modulefixture.SeedPreview")
    copyClassInto(moduleDir, "ee.schimke.composeai.contrib.bundle.modulefixture.UnusedModuleClass")
    return moduleDir
  }

  private fun depJar(): File =
    jarOf(tmp.newFile("dep.jar"), "ee.schimke.composeai.contrib.bundle.depfixture.DepHelper")

  /** Bytes of every zip entry in the bundle (after stripping the leading cover PNG). */
  private fun zipEntries(bundle: File): Map<String, ByteArray> {
    val bytes = bundle.readBytes()
    val zipStart = if (isPng(bytes)) pngLength(bytes) else 0
    val out = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes, zipStart, bytes.size - zipStart)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        if (!entry.isDirectory) out[entry.name] = zin.readBytes()
        zin.closeEntry()
      }
    }
    return out
  }

  private fun manifestOf(entries: Map<String, ByteArray>): BundleManifest =
    json.decodeFromString(BundleManifest.serializer(), entries.getValue("bundle.json").decodeToString())

  private fun appJarClassNames(entries: Map<String, ByteArray>): Set<String> {
    val names = mutableSetOf<String>()
    ZipInputStream(ByteArrayInputStream(entries.getValue("classes/app.jar"))).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        if (entry.name.endsWith(".class")) names += entry.name
        zin.closeEntry()
      }
    }
    return names
  }

  @Test
  fun `coordinates mode emits a Maven entry with sha256 and a PNG+ZIP polyglot`() {
    val out = File(tmp.newFolder("out"), "bundle.png")

    val result =
      BundleWriter.write(
        BundleWriter.BundleInputs(
          previews = listOf(seedPreview),
          moduleSources = listOf(moduleDirWithFixtures()),
          dependencyJars = listOf(BundleWriter.DependencyInput(depJar(), "com.example:dep:1.0:jar")),
          modulePath = ":amper-cmp-desktop",
          variant = "desktop",
          producer = PRODUCER_AMPER,
          producedBy = "test",
          embed = false,
        ),
        out,
      )

    // Polyglot: leading bytes are a valid PNG (the stub cover — no render given).
    assertThat(isPng(out.readBytes())).isTrue()

    val entries = zipEntries(out)
    assertThat(entries.keys).containsAtLeast("bundle.json", "previews.json", "classes/app.jar", "report.json")
    assertThat(entries.keys.none { it.startsWith("libs/") }).isTrue()

    val manifest = manifestOf(entries)
    assertThat(manifest.schemaVersion).isEqualTo(BUNDLE_SCHEMA_VERSION)
    assertThat(manifest.producer).isEqualTo(PRODUCER_AMPER)
    assertThat(manifest.resolution).isEqualTo(RESOLUTION_COORDINATES)
    assertThat(manifest.previewIds).containsExactly(seedPreview.id)
    assertThat(manifest.coverPreviewId).isEqualTo(seedPreview.id)

    val module = manifest.classpath.filterIsInstance<ClasspathEntry.Module>().single()
    assertThat(module.path).isEqualTo("classes/app.jar")
    val maven = manifest.classpath.filterIsInstance<ClasspathEntry.Maven>().single()
    assertThat(maven.group).isEqualTo("com.example")
    assertThat(maven.artifact).isEqualTo("dep")
    assertThat(maven.version).isEqualTo("1.0")
    assertThat(maven.type).isEqualTo("jar")
    assertThat(maven.sha256).matches("[0-9a-f]{64}")

    // Minimization: the seed's class is kept, the unreachable module class is dropped.
    val appClasses = appJarClassNames(entries)
    assertThat(appClasses).contains("ee/schimke/composeai/contrib/bundle/modulefixture/SeedPreview.class")
    assertThat(appClasses)
      .doesNotContain("ee/schimke/composeai/contrib/bundle/modulefixture/UnusedModuleClass.class")

    assertThat(result.mavenEntries).isEqualTo(1)
    assertThat(result.embeddedEntries).isEqualTo(0)
  }

  @Test
  fun `embed mode carries the dep in libs and reports embedded resolution`() {
    val out = File(tmp.newFolder("out"), "bundle.png")

    val result =
      BundleWriter.write(
        BundleWriter.BundleInputs(
          previews = listOf(seedPreview),
          moduleSources = listOf(moduleDirWithFixtures()),
          dependencyJars = listOf(BundleWriter.DependencyInput(depJar(), "com.example:dep:1.0:jar")),
          modulePath = "//app:preview",
          producer = PRODUCER_BAZEL,
          producedBy = "test",
          embed = true,
        ),
        out,
      )

    val entries = zipEntries(out)
    assertThat(entries.keys.any { it.startsWith("libs/") && it.endsWith(".jar") }).isTrue()

    val manifest = manifestOf(entries)
    assertThat(manifest.producer).isEqualTo(PRODUCER_BAZEL)
    assertThat(manifest.resolution).isEqualTo(RESOLUTION_EMBEDDED)
    val embedded = manifest.classpath.filterIsInstance<ClasspathEntry.Embedded>().single()
    assertThat(embedded.inlinedAs).startsWith("libs/")
    assertThat(manifest.classpath.filterIsInstance<ClasspathEntry.Maven>()).isEmpty()

    assertThat(result.embeddedEntries).isEqualTo(1)
    assertThat(result.mavenEntries).isEqualTo(0)
  }

  @Test
  fun `a coordinate-less vendored jar is embedded even without embed mode`() {
    val out = File(tmp.newFolder("out"), "bundle.png")

    BundleWriter.write(
      BundleWriter.BundleInputs(
        previews = listOf(seedPreview),
        moduleSources = listOf(moduleDirWithFixtures()),
        // coordinate = null mirrors a vendored //third_party jar with no resolvable GAV.
        dependencyJars = listOf(BundleWriter.DependencyInput(depJar(), coordinate = null)),
        modulePath = "//app:preview",
        producer = PRODUCER_BAZEL,
        producedBy = "test",
        embed = false,
      ),
      out,
    )

    val manifest = manifestOf(zipEntries(out))
    assertThat(manifest.resolution).isEqualTo(RESOLUTION_EMBEDDED)
    assertThat(manifest.classpath.filterIsInstance<ClasspathEntry.Embedded>()).hasSize(1)
  }

  @Test
  fun `mixed mode when one dep has a coordinate and another does not`() {
    val out = File(tmp.newFolder("out"), "bundle.png")
    // Two separate jars carrying two distinct reachable classes: one resolvable, one vendored.
    val resolvable = jarOf(tmp.newFile("resolvable.jar"), "ee.schimke.composeai.contrib.bundle.depfixture.DepHelper")
    val vendored = jarOf(tmp.newFile("vendored.jar"), "ee.schimke.composeai.contrib.bundle.depfixture.DepHelper2")

    BundleWriter.write(
      BundleWriter.BundleInputs(
        previews = listOf(seedPreview),
        moduleSources = listOf(moduleDirWithFixtures()),
        dependencyJars =
          listOf(
            BundleWriter.DependencyInput(resolvable, "com.example:dep:1.0:jar"),
            BundleWriter.DependencyInput(vendored, coordinate = null),
          ),
        modulePath = "//app:preview",
        producer = PRODUCER_BAZEL,
        producedBy = "test",
        embed = false,
      ),
      out,
    )

    val manifest = manifestOf(zipEntries(out))
    assertThat(manifest.resolution).isEqualTo(RESOLUTION_MIXED)
    assertThat(manifest.classpath.filterIsInstance<ClasspathEntry.Maven>()).hasSize(1)
    assertThat(manifest.classpath.filterIsInstance<ClasspathEntry.Embedded>()).hasSize(1)
  }

  @Test
  fun `an unreachable dep is dropped from the classpath`() {
    val out = File(tmp.newFolder("out"), "bundle.png")
    // A jar the seed never references (contains only UnusedModuleClass-like content): use a class
    // not on the reachability path. DepHelper is reached; an extra jar of an unrelated class is not.
    val unrelated = jarOf(tmp.newFile("unrelated.jar"), "ee.schimke.composeai.contrib.bundle.modulefixture.UnusedModuleClass")

    val result =
      BundleWriter.write(
        BundleWriter.BundleInputs(
          previews = listOf(seedPreview),
          moduleSources = listOf(moduleDirWithFixtures()),
          dependencyJars =
            listOf(
              BundleWriter.DependencyInput(depJar(), "com.example:dep:1.0:jar"),
              BundleWriter.DependencyInput(unrelated, "com.example:unrelated:9.9:jar"),
            ),
          modulePath = ":m",
          producer = PRODUCER_AMPER,
          producedBy = "test",
        ),
        out,
      )

    val manifest = manifestOf(zipEntries(out))
    // Only the reached dep is recorded; the unrelated one is dropped (no reachable classes).
    assertThat(manifest.classpath.filterIsInstance<ClasspathEntry.Maven>().map { it.artifact })
      .containsExactly("dep")
    assertThat(result.depsDropped).isEqualTo(1)
  }

  // --- PNG polyglot helpers (mirror the upstream extractZipBytes detection) ----------------------

  private fun isPng(bytes: ByteArray): Boolean {
    val sig = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    if (bytes.size < sig.size) return false
    for (i in sig.indices) if (bytes[i] != sig[i]) return false
    return true
  }

  private fun pngLength(bytes: ByteArray): Int {
    var offset = 8
    while (offset < bytes.size) {
      val length =
        ((bytes[offset].toInt() and 0xff) shl 24) or
          ((bytes[offset + 1].toInt() and 0xff) shl 16) or
          ((bytes[offset + 2].toInt() and 0xff) shl 8) or
          (bytes[offset + 3].toInt() and 0xff)
      val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
      offset += 4 + 4 + length + 4
      if (type == "IEND") return offset
    }
    error("truncated PNG: IEND not found")
  }
}
