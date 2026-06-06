package ee.schimke.composeai.contrib.bundle

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Recovers Maven coordinates (`group:artifact:version:type`) for a runtime jar from what the
 * non-Gradle build systems already record — the answer to the issue's Q2 ("can the driver recover
 * `maven_install` coordinates per jar, or only file paths?"). Two sources:
 *
 * - [recoverFromMavenLayout] — Amper resolves into a standard Maven-layout cache
 *   (`<root>/<group as path>/<artifact>/<version>/<artifact>-<version>.<ext>`), so the coordinate is
 *   read straight back from a resolved jar's path relative to the cache root.
 * - [MavenInstallIndex] — Bazel's `rules_jvm_external` pins every artifact in `maven_install.json`
 *   with its `group:artifact:version` and packaging; matching a runtime jar to its pin by basename
 *   recovers the coordinate (and, where present, the pinned `sha256`).
 *
 * A jar that resolves to neither (a vendored `//third_party` jar, an anonymous boot jar) has no
 * coordinate — the caller embeds it under `libs/` instead.
 */
object Coordinates {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }
  private val HEX_SHA256 = Regex("[0-9a-fA-F]{64}")

  /**
   * `group:artifact:version:type` for [jar] when it sits under one of [roots] in Maven layout, else
   * null. The group is the path components between the root and the `<artifact>/<version>/` tail; the
   * type is the file extension. Verifies the basename is `<artifact>-<version>.<ext>` so unrelated
   * files nested in the cache don't masquerade as artifacts.
   */
  fun recoverFromMavenLayout(jar: File, roots: List<File>): String? {
    val canonicalJar = jar.absoluteFile.normalize()
    for (root in roots) {
      val canonicalRoot = root.absoluteFile.normalize()
      val rel = relativeOrNull(canonicalRoot, canonicalJar) ?: continue
      val parts = rel.split('/').filter { it.isNotEmpty() }
      // Need at least group(1+) / artifact / version / file => >= 4 components.
      if (parts.size < 4) continue
      val file = parts.last()
      val version = parts[parts.size - 2]
      val artifact = parts[parts.size - 3]
      val group = parts.subList(0, parts.size - 3).joinToString(".")
      if (group.isEmpty()) continue
      val ext = file.substringAfterLast('.', "")
      if (ext.isEmpty()) continue
      val expectedStem = "$artifact-$version"
      if (file.substringBeforeLast('.') != expectedStem) continue
      return "$group:$artifact:$version:$ext"
    }
    return null
  }

  private fun relativeOrNull(root: File, child: File): String? {
    val rootPath = root.path.removeSuffix(File.separator)
    val childPath = child.path
    if (!childPath.startsWith(rootPath + File.separator)) return null
    return childPath.substring(rootPath.length + 1).replace(File.separatorChar, '/')
  }

  /** A coordinate index built from a parsed `maven_install.json`. */
  class MavenInstallIndex internal constructor(
    private val byFileName: Map<String, Pinned>,
  ) {
    /** `group:artifact:version:type` for a runtime jar matched by basename, or null. */
    fun coordinateFor(jar: File): String? = byFileName[jar.name]?.coordinate

    /** Pinned hex `sha256` for a runtime jar matched by basename, or null. */
    fun sha256For(jar: File): String? = byFileName[jar.name]?.sha256

    val size: Int get() = byFileName.size
  }

  internal data class Pinned(val coordinate: String, val sha256: String?)

  /**
   * Parse `rules_jvm_external`'s pinned `maven_install.json` (v2 format) into an index keyed by the
   * expected runtime-jar basename (`<artifact>-<version>.<type>`). Each `artifacts` entry carries the
   * version and per-packaging `shasums`; a sha is recorded only when it is valid 64-char hex so a
   * differently-encoded digest never becomes a guaranteed player-side mismatch. Unknown keys are
   * ignored, so a legacy/extended pin file still parses (yielding whatever it can express).
   */
  fun parseMavenInstall(jsonText: String): MavenInstallIndex {
    val parsed =
      runCatching { json.decodeFromString(MavenInstallFile.serializer(), jsonText) }.getOrNull()
        ?: return MavenInstallIndex(emptyMap())
    val byFileName = LinkedHashMap<String, Pinned>()
    for ((ga, entry) in parsed.artifacts) {
      val version = entry.version ?: continue
      val parts = ga.split(':')
      if (parts.size < 2) continue
      val group = parts[0]
      val artifact = parts[1]
      val shasums = entry.shasums ?: emptyMap()
      // Each packaging the pin records (jar / aar / …) maps to a runtime basename.
      val packagings = if (shasums.isNotEmpty()) shasums.keys else setOf("jar")
      for (type in packagings) {
        val fileName = "$artifact-$version.$type"
        val sha = shasums[type]?.takeIf { HEX_SHA256.matches(it) }
        byFileName[fileName] = Pinned(coordinate = "$group:$artifact:$version:$type", sha256 = sha)
      }
    }
    return MavenInstallIndex(byFileName)
  }

  @Serializable
  private data class MavenInstallFile(
    val artifacts: Map<String, MavenInstallArtifact> = emptyMap(),
  )

  @Serializable
  private data class MavenInstallArtifact(
    val version: String? = null,
    val shasums: Map<String, String>? = null,
  )
}
