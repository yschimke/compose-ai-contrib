package ee.schimke.composeai.contrib.bundle

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Contrib-side mirror of the upstream `compose-preview` bundle on-disk format
 * (`gradle-plugin/.../PreviewBundleFormat.kt` and the reader's copy in
 * `bundle-viewer/.../Schema.kt`). Field names, the `kind` discriminator, and the default values are
 * kept in lockstep with those definitions so a bundle this module writes opens in the upstream
 * `:bundle-viewer` / `compose-preview bundle open` exactly like a Gradle-produced one.
 *
 * Only the subset the Amper/Bazel producers emit is modelled here — the v5 `intermediateRepresentations`
 * and v6 `androidResources` fields are omitted (those producers capture no IR), and the upstream
 * reader supplies their defaults via `ignoreUnknownKeys`.
 *
 * The bundle is a **PNG + ZIP polyglot**: leading bytes are a valid cover PNG (every image viewer
 * shows it), trailing bytes a standard ZIP (`unzip foo.png` works). See [writePngZipPolyglot].
 *
 * ## ZIP layout
 * ```
 * bundle.json           — this [BundleManifest]
 * previews.json         — [PreviewManifest] filtered to the selected ids
 * previews/<id>.png     — one baked render per selected preview (absent when not rendered)
 * classes/app.jar       — minimized consumer-module bytecode (ClassGraph closure)
 * libs/<name>.jar       — third-party jars carried in-bundle (embedded / mixed mode only)
 * report.json           — [MinimizationReport]
 * ```
 */
@Serializable
data class BundleManifest(
  val schemaVersion: Int,
  /** Render backend the bundle targets. Desktop Compose for the contrib producers. */
  val backend: String,
  /** Selected preview ids (matches `previews.json[].id`). First entry = cover. */
  val previewIds: List<String>,
  /** Preview id whose PNG forms the polyglot's leading bytes. Usually `previewIds[0]`. */
  val coverPreviewId: String?,
  /**
   * Classpath in load order. First entry is always [ClasspathEntry.Module] for the inlined
   * `classes/app.jar`; the rest are [ClasspathEntry.Maven] coordinates the player resolves at open
   * time or [ClasspathEntry.Embedded] jars carried inside the bundle's `libs/`.
   */
  val classpath: List<ClasspathEntry>,
  /** Source module path that produced the bundle (Amper module name / Bazel label). */
  val modulePath: String,
  /** Producer-version string for diagnostics. */
  val producedBy: String,
  /**
   * Build system that produced the bundle: [PRODUCER_GRADLE], [PRODUCER_AMPER], or [PRODUCER_BAZEL].
   * Defaults to `gradle` so a v2 bundle (which omits the field) decodes as Gradle-produced.
   */
  val producer: String = PRODUCER_GRADLE,
  /**
   * How the player assembles the third-party classpath: [RESOLUTION_COORDINATES] (resolve
   * [ClasspathEntry.Maven] from the consumer's repos), [RESOLUTION_EMBEDDED] (everything reachable
   * carried in `libs/`), or [RESOLUTION_MIXED] (coordinate-less deps embedded, the rest referenced).
   * Defaults to [RESOLUTION_COORDINATES] for v2 back-compat.
   */
  val resolution: String = RESOLUTION_COORDINATES,
)

/** Discriminator field `kind`, values: `module`, `maven`, `project`, `embedded`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface ClasspathEntry {
  /** The minimized consumer-module jar inlined inside the bundle (`classes/app.jar`). */
  @Serializable
  @SerialName("module")
  data class Module(val path: String) : ClasspathEntry

  /**
   * A Maven coordinate the player resolves (and, with [sha256], verifies) at open time. The
   * canonical way a bundle carries a third-party dependency — bytes stay detached.
   */
  @Serializable
  @SerialName("maven")
  data class Maven(
    val group: String,
    val artifact: String,
    val version: String,
    /** Packaging the player resolves: `"jar"` (pure JVM) or `"aar"` (Android). */
    val type: String,
    /** Lowercase hex SHA-256 of the resolved artifact's bytes; null = unverifiable. */
    val sha256: String? = null,
  ) : ClasspathEntry

  /** Project-local dep with no Maven coordinate, inlined alongside the consumer jar. */
  @Serializable
  @SerialName("project")
  data class Project(val path: String, val inlinedAs: String) : ClasspathEntry

  /**
   * A third-party jar carried **inside** the bundle's `libs/` — no coordinate, no resolution, no
   * network. Emitted by `embedded` / `mixed` packs and by producers that can't express resolvable
   * coordinates (a vendored Bazel `//third_party` jar). See [ClasspathEntry.Maven] for the
   * detached-by-coordinate alternative.
   */
  @Serializable
  @SerialName("embedded")
  data class Embedded(val inlinedAs: String) : ClasspathEntry
}

/**
 * Diagnostic record describing the minimization, written as `report.json`. Mirrors the upstream
 * shape so existing tooling that reads a bundle's report keeps working.
 */
@Serializable
data class MinimizationReport(
  val entryClassFqns: List<String>,
  val reachableClassCount: Int,
  val totalScannedClassCount: Int,
  val moduleClasses: ModuleClassesStats,
  val dependencies: List<DependencyDecision>,
)

@Serializable
data class ModuleClassesStats(
  val totalClasses: Int,
  val reachableClasses: Int,
  val packedBytes: Long,
)

@Serializable
data class DependencyDecision(
  val sourcePath: String,
  /** `group:artifact:version[:type]` when known; null for coordinate-less (embedded) deps. */
  val coordinate: String?,
  val projectPath: String?,
  val totalClasses: Int,
  val reachableClasses: Int,
  val originalBytes: Long,
  /** `true` when the dep contributed at least one reachable class (and is in the classpath). */
  val kept: Boolean,
)

/** [BundleManifest.producer] values. */
const val PRODUCER_GRADLE: String = "gradle"

const val PRODUCER_AMPER: String = "amper"

const val PRODUCER_BAZEL: String = "bazel"

/** [BundleManifest.resolution] values. */
const val RESOLUTION_COORDINATES: String = "coordinates"

const val RESOLUTION_EMBEDDED: String = "embedded"

const val RESOLUTION_MIXED: String = "mixed"

/**
 * Schema version stamped into [BundleManifest.schemaVersion]. The contrib producers emit at most v4
 * features — [ClasspathEntry.Embedded] (v3) and [ClasspathEntry.Maven.sha256] (v4) — so they stamp
 * v4. A v4 reader gets everything; a newer (v5/v6) reader still opens it (the higher-version fields
 * default), and a v2 reader opening a `coordinates` bundle works because the new entries/fields are
 * additive.
 */
const val BUNDLE_SCHEMA_VERSION: Int = 4

/** Well-known directory holding one rendered PNG per selected preview (`previews/<id>.png`). */
const val BUNDLE_PREVIEWS_DIR: String = "previews"

/**
 * Writes a PNG + ZIP polyglot: [coverPng] verbatim, then [zipBytes] verbatim. ZIP's
 * End-Of-Central-Directory is found from EOF (inside the zip) and PNG's chunk loop ends at IEND
 * (inside the cover), so the raw concatenation satisfies both formats. Mirrors the upstream writer.
 */
fun writePngZipPolyglot(coverPng: ByteArray, zipBytes: ByteArray, out: File) {
  out.parentFile?.mkdirs()
  out.outputStream().use { stream ->
    stream.write(coverPng)
    stream.write(zipBytes)
  }
}

/**
 * 1×1 gray PNG used as the cover when no rendered PNG is available (e.g. a Bazel bundle produced
 * without the render path). `file(1)` still reports PNG and viewers show a neutral pixel.
 */
val STUB_GRAY_PNG: ByteArray by lazy {
  val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
  img.setRGB(0, 0, 0x808080)
  val baos = ByteArrayOutputStream()
  ImageIO.write(img, "png", baos)
  baos.toByteArray()
}
