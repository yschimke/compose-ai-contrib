package ee.schimke.composeai.contrib.bundle

import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

/**
 * Command-line front end for [BundleWriter], used by the Bazel `bundle_preview` rule (which shells
 * out to `java -cp <bundle-producer-and-deps> ee.schimke.composeai.contrib.bundle.BundleProducerCli`).
 * The Amper producer calls [BundleWriter] directly — it's already a JVM module — so this CLI exists
 * mainly for the Starlark/`genrule` path that can only pass file paths.
 *
 * Per-jar coordinate recovery (answering the issue's Q2) is layered, first match wins:
 *   1. an explicit `--coord <basename>=<group:artifact:version[:type]>` override,
 *   2. `--maven-install <maven_install.json>` matched by basename (Bazel's `rules_jvm_external` pins),
 *   3. `--maven-repo <dir>` Maven-layout path recovery (Amper's m2 cache / any local repo).
 * A jar that matches none has no coordinate and is embedded under `libs/`.
 *
 * ```
 * --out <bundle.png>                       output polyglot (required)
 * --module-path <path>                     module label/name recorded in bundle.json (required)
 * --producer <amper|bazel|gradle>          producer field (default: bazel)
 * --produced-by <string>                   diagnostics version string
 * --backend <desktop>                      render backend (default: desktop)
 * --variant <v>                            variant recorded in previews.json
 * --preview <id>=<class>#<fn>[@<src>]      a selected preview (repeatable; first = cover)
 * --previews-json <file>                   OR read selected previews from a discovery manifest
 * --module-source <dir|jar>                consumer module bytecode (repeatable, required)
 * --module-resources <dir>                 consumer module resources (optional)
 * --jar <path>                             a runtime-classpath jar (repeatable)
 * --maven-install <maven_install.json>     recover coordinates by basename (repeatable)
 * --maven-repo <dir>                       recover coordinates by Maven layout (repeatable)
 * --coord <basename>=<g:a:v[:type]>        explicit coordinate override (repeatable)
 * --embed                                  carry every coordinate dep in libs/ (offline pack)
 * --verify-embedded                        SHA-1 check each coordinate-less (embedded) jar against
 *                                          Maven Central; warn if it's actually published
 * --fail-on-resolvable-embed               with --verify-embedded, exit non-zero if any embedded jar
 *                                          turns out to be published (a recovery miss, not vendored)
 * ```
 */
object BundleProducerCli {

  @JvmStatic
  fun main(args: Array<String>) {
    val opts = ArgParser(args)

    val out = File(opts.required("--out"))
    val modulePath = opts.required("--module-path")
    val producer = opts.value("--producer") ?: PRODUCER_BAZEL
    val producedBy = opts.value("--produced-by") ?: "compose-ai-contrib bundle-producer"
    val backend = opts.value("--backend") ?: "desktop"
    val variant = opts.value("--variant") ?: ""
    val embed = opts.flag("--embed")

    val previews = collectPreviews(opts)
    if (previews.isEmpty()) fail("no previews selected — pass --preview or --previews-json")

    val moduleSources = opts.values("--module-source").map(::File)
    if (moduleSources.isEmpty()) fail("no --module-source given")
    val moduleResources = opts.value("--module-resources")?.let(::File)

    val mavenInstalls = opts.values("--maven-install").map { Coordinates.parseMavenInstall(File(it).readText()) }
    val mavenRepos = opts.values("--maven-repo").map(::File)
    val explicitCoords =
      opts.values("--coord").associate { spec ->
        val (name, coord) = spec.splitOnce('=') ?: fail("malformed --coord (expected name=coord): $spec")
        name to coord
      }

    val dependencyJars =
      opts.values("--jar").map(::File).filter { it.isFile }.map { jar ->
        val coord =
          explicitCoords[jar.name]
            ?: mavenInstalls.firstNotNullOfOrNull { it.coordinateFor(jar) }
            ?: Coordinates.recoverFromMavenLayout(jar, mavenRepos)
        BundleWriter.DependencyInput(jar = jar, coordinate = coord)
      }

    val renderPngs =
      opts.values("--render").mapNotNull { spec ->
        val (id, path) = spec.splitOnce('=') ?: return@mapNotNull null
        id to File(path)
      }.toMap()

    val result =
      BundleWriter.write(
        BundleWriter.BundleInputs(
          previews = previews,
          moduleSources = moduleSources,
          dependencyJars = dependencyJars,
          renderPngs = renderPngs,
          moduleResourcesDir = moduleResources,
          modulePath = modulePath,
          variant = variant,
          backend = backend,
          producer = producer,
          producedBy = producedBy,
          embed = embed,
        ),
        out,
      )

    System.err.println(
      "BundleProducerCli: wrote ${result.outputFile.absolutePath} (${result.outputFile.length()} bytes)\n" +
        "  producer:            $producer\n" +
        "  resolution:          ${result.resolution}\n" +
        "  previews baked:      ${result.bakedPngs} / ${previews.size}\n" +
        "  module classes kept: ${result.moduleClassesKept} / ${result.moduleClassesTotal}\n" +
        "  Maven coordinates:   ${result.mavenEntries}\n" +
        "  embedded deps:       ${result.embeddedEntries}\n" +
        "  deps dropped:        ${result.depsDropped} (no reachable classes)"
    )

    if (opts.flag("--verify-embedded")) {
      // Exactly the jars actually embedded *because coordinate recovery failed* — an intentionally
      // embedded coordinate jar (--embed) is the operator's choice, not a recovery miss.
      val resolvable =
        verifyEmbedded(
          result.embeddedWithoutCoordinate,
          failOnResolvable = opts.flag("--fail-on-resolvable-embed"),
        )
      if (resolvable) exitProcess(3)
    }
  }

  /**
   * Warn for any [jars] we're embedding that Maven Central actually publishes (so they should be
   * detached coordinates, not bundled bytes). Returns true if any was found published — the caller
   * decides whether that's fatal. Best-effort: an unreachable registry yields "unverified", never an
   * error.
   */
  private fun verifyEmbedded(jars: List<File>, failOnResolvable: Boolean): Boolean {
    if (jars.isEmpty()) return false
    val findings = EmbeddedVerifier.verify(jars, EmbeddedVerifier.MavenCentralChecksumSearch())
    val resolvable = findings.filter { it.resolvable }
    val unverified = findings.filter { !it.checked }
    for (f in resolvable) {
      System.err.println(
        "BundleProducerCli: WARNING — embedded ${f.jar.name} is published as ${f.publishedAs}; " +
          "record it as a Maven coordinate instead of embedding it."
      )
    }
    if (unverified.isNotEmpty()) {
      System.err.println(
        "BundleProducerCli: could not verify ${unverified.size} embedded jar(s) against the registry " +
          "(offline / endpoint unreachable) — left embedded."
      )
    }
    if (resolvable.isNotEmpty() && failOnResolvable) {
      System.err.println(
        "BundleProducerCli: --fail-on-resolvable-embed — ${resolvable.size} embedded jar(s) are " +
          "actually published; failing."
      )
      return true
    }
    return false
  }

  private val json = Json { ignoreUnknownKeys = true }

  private fun collectPreviews(opts: ArgParser): List<PreviewInfo> {
    opts.value("--previews-json")?.let { path ->
      val manifest = json.decodeFromString(PreviewManifest.serializer(), File(path).readText())
      return manifest.previews
    }
    return opts.values("--preview").map { spec ->
      // <id>=<className>#<functionName>[@<sourceFile>]
      val (id, rest) = spec.splitOnce('=') ?: fail("malformed --preview (expected id=class#fn): $spec")
      val srcSplit = rest.splitOnce('@')
      val classAndFn = srcSplit?.first ?: rest
      val sourceFile = srcSplit?.second
      val (className, functionName) =
        classAndFn.splitOnce('#') ?: fail("malformed --preview (expected class#fn): $spec")
      PreviewInfo(
        id = id,
        className = className,
        functionName = functionName,
        sourceFile = sourceFile,
      )
    }
  }

  private fun fail(message: String): Nothing {
    System.err.println("BundleProducerCli: $message")
    exitProcess(2)
  }

  private fun String.splitOnce(delimiter: Char): Pair<String, String>? {
    val idx = indexOf(delimiter)
    if (idx < 0) return null
    return substring(0, idx) to substring(idx + 1)
  }

  /** Minimal repeated-flag arg parser (no third-party dep). */
  private class ArgParser(args: Array<String>) {
    private val singles = mutableMapOf<String, String>()
    private val multi = mutableMapOf<String, MutableList<String>>()
    private val flags = mutableSetOf<String>()

    init {
      var i = 0
      while (i < args.size) {
        val arg = args[i]
        if (!arg.startsWith("--")) {
          i++
          continue
        }
        val next = args.getOrNull(i + 1)
        if (next == null || next.startsWith("--")) {
          flags += arg
          i++
        } else {
          singles[arg] = next
          multi.getOrPut(arg) { mutableListOf() } += next
          i += 2
        }
      }
    }

    fun value(name: String): String? = singles[name]

    fun values(name: String): List<String> = multi[name] ?: emptyList()

    fun flag(name: String): Boolean = name in flags || name in singles

    fun required(name: String): String =
      singles[name] ?: run {
        System.err.println("BundleProducerCli: missing required $name")
        exitProcess(2)
      }
  }
}
