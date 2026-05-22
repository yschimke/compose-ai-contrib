package ee.schimke.composeai.contrib

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import ee.schimke.composeai.daemon.protocol.RenderTier
import ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilder
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end proof that the published `ee.schimke.composeai:*` artifacts drive an Amper-built
 * module's `@Preview` through `RenderSession` without any Gradle involvement on the consumer
 * side. This is a Maven-Central-consuming equivalent of the upstream
 * `render-session/subprocess`'s `AmperContractTest` — same shape, three differences:
 *
 * 1. Renderer classpath comes from a system property (`contrib.rendererClasspath`) populated by
 *    the `rendererRuntime` configuration in `build.gradle.kts`, rather than from a reference
 *    `daemon-launch.json` produced by a Gradle plugin.
 * 2. The descriptor is built with `DaemonLaunchBuilder.build(...)` + `.encode(...)` — proving
 *    the published builder library produces a wire-compatible descriptor.
 * 3. The Amper fixture lives at a path passed via `contrib.amperFixtureDir` so the test can run
 *    from any working directory.
 *
 * **Self-skip:** the test exits cleanly (no failure) when the Amper fixture outputs are missing,
 * so a clean checkout passes. CI builds the fixture (`./amper build`) before running the test.
 */
class AmperContractTest {

  @get:Rule val tempDir: TemporaryFolder = TemporaryFolder()

  @Test
  fun `amper-built classes drive a real render via RenderSession`() {
    val amperFixtureDir = File(requireSysProp("contrib.amperFixtureDir"))
    val amperKotlinOutput =
      File(
        amperFixtureDir,
        "build/artifacts/CompiledJvmArtifact/amper-cmp-desktopjvm/kotlin-output",
      )

    if (!File(amperFixtureDir, "amper").canExecute()) {
      System.err.println(
        "[AmperContractTest] skipping — `${amperFixtureDir.path}/amper` wrapper missing or not executable"
      )
      return
    }
    val classFiles = amperKotlinOutput.listFiles { f -> f.extension == "class" }
    if (classFiles == null || classFiles.isEmpty()) {
      System.err.println(
        "[AmperContractTest] skipping — Amper outputs missing at $amperKotlinOutput. " +
          "Run `cd ${amperFixtureDir.path} && AMPER_JAVA_OPTIONS=\"…trustStore=…\" ./amper build` first."
      )
      return
    }

    val rendererClasspath =
      requireSysProp("contrib.rendererClasspath")
        .split(File.pathSeparator)
        .filter { it.isNotEmpty() }
    val classpath = rendererClasspath + amperKotlinOutput.absolutePath

    val moduleDir = tempDir.newFolder("amper-module")
    val renderOutputDir = File(moduleDir, "build/compose-previews/renders").apply { mkdirs() }
    val historyDir = File(moduleDir, ".compose-preview-history").apply { mkdirs() }
    val previewsJsonPath = File(moduleDir, "build/compose-previews/previews.json")
    previewsJsonPath.parentFile.mkdirs()
    previewsJsonPath.writeText(handAuthoredPreviewsJson(modulePath = ":amper-cmp-desktop"))

    val descriptor =
      DaemonLaunchBuilder.build(
        modulePath = ":amper-cmp-desktop",
        variant = "desktop",
        mainClass = "ee.schimke.composeai.daemon.DaemonMain",
        classpath = classpath,
        jvmArgs = listOf("-ea", "-Xmx1024m"),
        systemProperties =
          linkedMapOf(
            "composeai.daemon.protocolVersion" to "1",
            "composeai.daemon.modulePath" to ":amper-cmp-desktop",
            "composeai.daemon.moduleId" to ":amper-cmp-desktop",
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

    val target = "GreetingKt.Greeting"

    SubprocessRenderSessions.open(
        RenderSessionConfig(
          descriptorPath = descriptorFile,
          workspaceRoot = amperFixtureDir,
          workspaceName = "compose-ai-contrib",
          logSink = { line -> System.err.println("[amper-daemon] $line") },
        )
      )
      .use { session ->
        assertThat(session.modulePath).isEqualTo(":amper-cmp-desktop")
        assertThat(session.initializeResult.daemonVersion).isNotEmpty()

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
            val queueAck = session.renderNow(previewIds = listOf(target), tier = RenderTier.FULL)
            assertWithMessage("renderNow should accept Greeting, got rejected=${queueAck.rejected}")
              .that(queueAck.rejected.map { it.id })
              .doesNotContain(target)

            assertWithMessage("daemon should emit renderFinished for $target within 60s")
              .that(latch.await(60, TimeUnit.SECONDS))
              .isTrue()
          }

        val params = finishedParams.get() ?: error("renderFinished notification missing payload")
        val pngPath =
          params["pngPath"]?.jsonPrimitive?.contentOrNull
            ?: error("renderFinished params missing pngPath: $params")
        val png = File(pngPath)
        assertWithMessage("rendered PNG must exist on disk: $pngPath").that(png.isFile).isTrue()
        assertThat(png.length()).isGreaterThan(0L)
      }
  }

  private fun requireSysProp(name: String): String =
    System.getProperty(name)
      ?: error("System property '$name' must be set by the Gradle test task")

  private val previewsJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  private fun handAuthoredPreviewsJson(modulePath: String): String =
    previewsJson.encodeToString(
      JsonObject.serializer(),
      buildJsonObject {
        put("module", JsonPrimitive(modulePath))
        put("variant", JsonPrimitive("desktop"))
        put(
          "previews",
          JsonArray(
            listOf(
              buildJsonObject {
                put("id", JsonPrimitive("GreetingKt.Greeting"))
                put("functionName", JsonPrimitive("Greeting"))
                put("className", JsonPrimitive("GreetingKt"))
                put("sourceFile", JsonPrimitive("src/Greeting.kt"))
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
                        put("renderOutput", JsonPrimitive("renders/GreetingKt.Greeting.png"))
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
}
