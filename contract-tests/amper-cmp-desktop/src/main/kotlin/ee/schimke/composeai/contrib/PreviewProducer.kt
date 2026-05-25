package ee.schimke.composeai.contrib

import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilder
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Standalone producer that renders the Amper Desktop fixture's `@Preview`
 * functions and emits a wire-compatible `_previews.json` envelope for the
 * upstream `apply` composite action's `skip-render: true` mode
 * (yschimke/compose-ai-tools#1476).
 *
 * Mirrors `AmperContractTest` end-to-end — same `DaemonLaunchBuilder` +
 * `SubprocessRenderSessions` setup, same `renderFinished` capture — but
 * writes the rendered `pngPath` + `sha256` into the envelope shape
 * `lib/compare-previews.py` expects (`{previews: [{module, id, functionName,
 * sourceFile, captures: [{pngPath, sha256, renderOutput}]}]}`) instead of
 * asserting on them.
 *
 * Inputs (system properties, mirror the contract test's pattern):
 *   contrib.amperFixtureDir        — absolute path to amper-cmp-desktop/
 *   contrib.rendererClasspath      — path-separated renderer-side jars
 *   contrib.previewsOutputFile     — absolute path to write _previews.json
 *                                    (default: `_previews.json` in cwd)
 *
 * Exits non-zero on render failure — no self-skip behavior (unlike the test,
 * which tolerates a clean checkout). CI must run `./amper build` first.
 */
object PreviewProducer {
  private const val MODULE_PATH = ":amper-cmp-desktop"
  private const val VARIANT = "desktop"
  private const val PREVIEW_ID = "GreetingKt.Greeting"
  private const val CLASS_NAME = "GreetingKt"
  private const val FUNCTION_NAME = "Greeting"
  private const val SOURCE_FILE = "src/Greeting.kt"

  @JvmStatic
  fun main(args: Array<String>) {
    val amperFixtureDir = File(requireSysProp("contrib.amperFixtureDir"))
    val amperKotlinOutput =
      File(
        amperFixtureDir,
        "build/artifacts/CompiledJvmArtifact/amper-cmp-desktopjvm/kotlin-output",
      )
    val classFiles = amperKotlinOutput.listFiles { f -> f.extension == "class" }
    require(classFiles != null && classFiles.isNotEmpty()) {
      "Amper outputs missing at $amperKotlinOutput — run `./amper build` in $amperFixtureDir first."
    }

    val rendererClasspath =
      requireSysProp("contrib.rendererClasspath")
        .split(File.pathSeparator)
        .filter { it.isNotEmpty() }
    val classpath = rendererClasspath + amperKotlinOutput.absolutePath

    // Per-invocation scratch dir for the daemon's notion of a "module" —
    // mirrors the contract test's @TemporaryFolder use. Lives under
    // java.io.tmpdir; the PNG path captured into the envelope is absolute,
    // so consumers downstream don't care that this dir is ephemeral.
    val workDir = Files.createTempDirectory("amper-preview-producer-").toFile()
    val moduleDir = File(workDir, "amper-module").apply { mkdirs() }
    val renderOutputDir = File(moduleDir, "build/compose-previews/renders").apply { mkdirs() }
    val historyDir = File(moduleDir, ".compose-preview-history").apply { mkdirs() }
    val previewsJsonPath = File(moduleDir, "build/compose-previews/previews.json")
    previewsJsonPath.parentFile.mkdirs()
    previewsJsonPath.writeText(handAuthoredPreviewsJson(MODULE_PATH))

    val descriptor =
      DaemonLaunchBuilder.build(
        modulePath = MODULE_PATH,
        variant = VARIANT,
        mainClass = "ee.schimke.composeai.daemon.DaemonMain",
        classpath = classpath,
        jvmArgs = listOf("-ea", "-Xmx1024m"),
        systemProperties =
          linkedMapOf(
            "composeai.daemon.protocolVersion" to "1",
            "composeai.daemon.modulePath" to MODULE_PATH,
            "composeai.daemon.moduleId" to MODULE_PATH,
            "composeai.daemon.moduleProjectDir" to moduleDir.absolutePath,
            "composeai.daemon.workspaceRoot" to amperFixtureDir.absolutePath,
            "composeai.daemon.previewsJsonPath" to previewsJsonPath.absolutePath,
            "composeai.harness.previewsManifest" to previewsJsonPath.absolutePath,
            "composeai.render.outputDir" to renderOutputDir.absolutePath,
            "composeai.daemon.historyDir" to historyDir.absolutePath,
            "composeai.daemon.userClassDirs" to amperKotlinOutput.absolutePath,
            "composeai.daemon.idleTimeoutMs" to "5000",
          ),
        workingDirectory = moduleDir.absolutePath,
        manifestPath = previewsJsonPath.absolutePath,
      )

    val descriptorFile = File(moduleDir, "build/compose-previews/daemon-launch.json")
    descriptorFile.writeText(DaemonLaunchBuilder.encode(descriptor))

    val pngPath = renderOne(descriptorFile, amperFixtureDir, PREVIEW_ID)
    val png = File(pngPath)
    require(png.isFile && png.length() > 0L) {
      "Rendered PNG missing or empty: $pngPath"
    }
    val sha = sha256(png)

    val envelope =
      buildEnvelope(
        modulePath = MODULE_PATH,
        previewId = PREVIEW_ID,
        className = CLASS_NAME,
        functionName = FUNCTION_NAME,
        sourceFile = SOURCE_FILE,
        pngPath = png.absolutePath,
        sha256 = sha,
      )

    val outFile = File(System.getProperty("contrib.previewsOutputFile", "_previews.json"))
    outFile.absoluteFile.parentFile?.mkdirs()
    outFile.writeText(prettyJson.encodeToString(JsonObject.serializer(), envelope))
    System.err.println(
      "PreviewProducer: wrote ${outFile.absolutePath} " +
        "(${png.length()} byte PNG, sha256=${sha.take(12)}…)"
    )
  }

  /** Spawns the daemon, fires one `renderNow`, waits for `renderFinished`, returns `pngPath`. */
  private fun renderOne(descriptorFile: File, workspaceRoot: File, target: String): String =
    SubprocessRenderSessions.open(
        RenderSessionConfig(
          descriptorPath = descriptorFile,
          workspaceRoot = workspaceRoot,
          workspaceName = "compose-ai-contrib",
          logSink = { line -> System.err.println("[amper-daemon] $line") },
        )
      )
      .use { session ->
        val finishedParams = AtomicReference<JsonObject?>(null)
        val latch = CountDownLatch(1)
        session
          .onNotification { method, params ->
            if (method == "renderFinished" && params != null) {
              val id = params["id"]?.jsonPrimitive?.contentOrNull
              if (id == target) {
                finishedParams.set(params)
                latch.countDown()
              }
            }
          }
          .use {
            val ack = session.renderNow(previewIds = listOf(target), tier = RenderTier.FULL)
            require(target !in ack.rejected.map { r -> r.id }) {
              "renderNow rejected $target: rejected=${ack.rejected}"
            }
            require(latch.await(60, TimeUnit.SECONDS)) {
              "Daemon did not emit renderFinished for $target within 60s"
            }
          }
        val params = finishedParams.get() ?: error("renderFinished missing payload")
        params["pngPath"]?.jsonPrimitive?.contentOrNull
          ?: error("renderFinished missing pngPath: $params")
      }

  private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

  private fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(8192)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  private fun buildEnvelope(
    modulePath: String,
    previewId: String,
    className: String,
    functionName: String,
    sourceFile: String,
    pngPath: String,
    sha256: String,
  ): JsonObject = buildJsonObject {
    put("schema", JsonPrimitive("compose-previews/v1"))
    put("module", JsonPrimitive(modulePath))
    put("variant", JsonPrimitive(VARIANT))
    put(
      "previews",
      JsonArray(
        listOf(
          buildJsonObject {
            put("id", JsonPrimitive(previewId))
            put("module", JsonPrimitive(modulePath))
            put("className", JsonPrimitive(className))
            put("functionName", JsonPrimitive(functionName))
            put("sourceFile", JsonPrimitive(sourceFile))
            put(
              "captures",
              JsonArray(
                listOf(
                  buildJsonObject {
                    put("renderOutput", JsonPrimitive("renders/${previewId}.png"))
                    put("pngPath", JsonPrimitive(pngPath))
                    put("sha256", JsonPrimitive(sha256))
                    put("cost", JsonPrimitive(1.0f))
                  }
                )
              ),
            )
          }
        )
      ),
    )
  }

  // Pre-rendered manifest the daemon reads to know what previews exist.
  // Identical shape to the one AmperContractTest hand-authors — copying
  // here keeps the producer self-contained rather than reaching across
  // src/main ↔ src/test.
  private fun handAuthoredPreviewsJson(modulePath: String): String =
    prettyJson.encodeToString(
      JsonObject.serializer(),
      buildJsonObject {
        put("module", JsonPrimitive(modulePath))
        put("variant", JsonPrimitive(VARIANT))
        put(
          "previews",
          JsonArray(
            listOf(
              buildJsonObject {
                put("id", JsonPrimitive(PREVIEW_ID))
                put("functionName", JsonPrimitive(FUNCTION_NAME))
                put("className", JsonPrimitive(CLASS_NAME))
                put("sourceFile", JsonPrimitive(SOURCE_FILE))
                put(
                  "params",
                  buildJsonObject {
                    put("density", JsonPrimitive(2.625f))
                    put("fontScale", JsonPrimitive(1.0f))
                    put("showSystemUi", JsonPrimitive(false))
                    put("showBackground", JsonPrimitive(true))
                    put("backgroundColor", JsonPrimitive(0xFFFFFFFFL))
                    put("uiMode", JsonPrimitive(0))
                    put("previewParameterLimit", JsonPrimitive(Int.MAX_VALUE))
                    put("kind", JsonPrimitive("COMPOSE"))
                  },
                )
                put(
                  "captures",
                  JsonArray(
                    listOf(
                      buildJsonObject {
                        put("renderOutput", JsonPrimitive("renders/${PREVIEW_ID}.png"))
                        put("cost", JsonPrimitive(1.0f))
                      }
                    )
                  ),
                )
                put("dataProducts", JsonArray(emptyList()))
                put("targets", JsonArray(emptyList()))
              }
            )
          ),
        )
      },
    )

  private fun requireSysProp(name: String): String =
    System.getProperty(name)
      ?: error("System property '$name' must be set (passed by Gradle `run` task or -D flag)")
}
