package ee.schimke.composeai.contrib.bundle

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.contrib.bundle.modulefixture.SeedPreview
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the [BundleProducerCli] end-to-end (the path the Bazel `bundle_preview` rule drives),
 * including `--maven-install` coordinate recovery — so the producer the Bazel rule shells out to is
 * actually covered by CI even though a full Bazel build is opt-in / known-fragile.
 */
class BundleProducerCliTest {

  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }

  private fun classesRoot(): File =
    File(SeedPreview::class.java.protectionDomain.codeSource.location.toURI())

  private fun moduleDir(): File {
    val dir = tmp.newFolder("module")
    for (fqn in
      listOf(
        "ee.schimke.composeai.contrib.bundle.modulefixture.SeedPreview",
        "ee.schimke.composeai.contrib.bundle.modulefixture.UnusedModuleClass",
      )) {
      val rel = fqn.replace('.', '/') + ".class"
      File(classesRoot(), rel).copyTo(File(dir, rel).apply { parentFile.mkdirs() }, overwrite = true)
    }
    return dir
  }

  /** A dep jar named `<artifact>-<version>.jar` so `maven_install` basename matching applies. */
  private fun depJar(fqn: String, name: String): File {
    val jar = tmp.newFile(name)
    ZipOutputStream(jar.outputStream()).use { zip ->
      val rel = fqn.replace('.', '/') + ".class"
      zip.putNextEntry(java.util.zip.ZipEntry(rel))
      zip.write(File(classesRoot(), rel).readBytes())
      zip.closeEntry()
    }
    return jar
  }

  private fun manifestOf(bundle: File): BundleManifest {
    val bytes = bundle.readBytes()
    // strip leading PNG cover
    var offset = 8
    while (offset < bytes.size) {
      val len =
        ((bytes[offset].toInt() and 0xff) shl 24) or
          ((bytes[offset + 1].toInt() and 0xff) shl 16) or
          ((bytes[offset + 2].toInt() and 0xff) shl 8) or
          (bytes[offset + 3].toInt() and 0xff)
      val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
      offset += 12 + len
      if (type == "IEND") break
    }
    ZipInputStream(ByteArrayInputStream(bytes, offset, bytes.size - offset)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        if (entry.name == "bundle.json") {
          return json.decodeFromString(BundleManifest.serializer(), zin.readBytes().decodeToString())
        }
        zin.closeEntry()
      }
    }
    error("bundle.json not found")
  }

  @Test
  fun `cli recovers a coordinate from maven_install and writes a bazel bundle`() {
    val out = File(tmp.newFolder("out"), "bundle.png")
    val dep = depJar("ee.schimke.composeai.contrib.bundle.depfixture.DepHelper", "dep-1.0.jar")
    val sha = "b".repeat(64)
    val mavenInstall =
      tmp.newFile("maven_install.json").apply {
        writeText(
          """{ "artifacts": { "com.example:dep": { "shasums": { "jar": "$sha" }, "version": "1.0" } } }"""
        )
      }

    BundleProducerCli.main(
      arrayOf(
        "--out", out.absolutePath,
        "--module-path", "//app:preview",
        "--producer", "bazel",
        "--module-source", moduleDir().absolutePath,
        "--jar", dep.absolutePath,
        "--maven-install", mavenInstall.absolutePath,
        "--preview",
        "GreetingKt.Greeting=ee.schimke.composeai.contrib.bundle.modulefixture.SeedPreview#render",
      )
    )

    assertThat(out.isFile).isTrue()
    val manifest = manifestOf(out)
    assertThat(manifest.producer).isEqualTo(PRODUCER_BAZEL)
    assertThat(manifest.resolution).isEqualTo(RESOLUTION_COORDINATES)
    val maven = manifest.classpath.filterIsInstance<ClasspathEntry.Maven>().single()
    assertThat(maven.group).isEqualTo("com.example")
    assertThat(maven.artifact).isEqualTo("dep")
    assertThat(maven.version).isEqualTo("1.0")
    // The CLI hashes the on-disk jar, so sha256 is the actual artifact hash (present, 64 hex).
    assertThat(maven.sha256).matches("[0-9a-f]{64}")
  }

  @Test
  fun `cli embeds a vendored jar with no recoverable coordinate`() {
    val out = File(tmp.newFolder("out"), "bundle.png")
    val vendored = depJar("ee.schimke.composeai.contrib.bundle.depfixture.DepHelper", "vendored.jar")

    BundleProducerCli.main(
      arrayOf(
        "--out", out.absolutePath,
        "--module-path", "//app:preview",
        "--producer", "bazel",
        "--module-source", moduleDir().absolutePath,
        "--jar", vendored.absolutePath,
        "--preview",
        "GreetingKt.Greeting=ee.schimke.composeai.contrib.bundle.modulefixture.SeedPreview#render",
      )
    )

    val manifest = manifestOf(out)
    assertThat(manifest.resolution).isEqualTo(RESOLUTION_EMBEDDED)
    assertThat(manifest.classpath.filterIsInstance<ClasspathEntry.Embedded>()).hasSize(1)
  }
}
