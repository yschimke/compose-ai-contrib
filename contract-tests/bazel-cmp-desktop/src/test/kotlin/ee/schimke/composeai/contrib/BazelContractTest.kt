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

/** Renders the actual Bazel-built CMP UI; missing artifacts fail instead of silently skipping. */
class BazelContractTest {

  @get:Rule val tempDir: TemporaryFolder = TemporaryFolder()

  @Test
  fun `bazel-built classes drive a real render via RenderSession`() {
    val fixtureDir = File(requireSysProp("contrib.bazelFixtureDir"))
    val executionRoot = File(requireSysProp("contrib.bazelExecutionRoot"))
    val manifest = File(fixtureDir, "bazel-bin/runtime_classpath.json")
    check(manifest.isFile) { "Build //:runtime_classpath in bazel-desktop first: $manifest" }
    val runtime = Json.parseToJsonElement(manifest.readText()) as JsonObject
    fun jars(key: String): List<String> =
      (runtime.getValue(key) as JsonArray).map {
        File(executionRoot, it.jsonPrimitive.content).absolutePath.also { path ->
          check(File(path).isFile) { "Bazel runtime jar missing: $path" }
        }
      }
    val moduleJars = jars("moduleJars")
    val runtimeJars = jars("runtimeJars")
    check(moduleJars.isNotEmpty()) { "Bazel module output jars missing" }

    val rendererClasspath =
      requireSysProp("contrib.rendererClasspath")
        .split(File.pathSeparator)
        .filter { it.isNotEmpty() }
    val classpath = rendererClasspath + runtimeJars

    val moduleDir = tempDir.newFolder("bazel-module")
    val renderOutputDir = File(moduleDir, "build/compose-previews/renders").apply { mkdirs() }
    val historyDir = File(moduleDir, ".compose-preview-history").apply { mkdirs() }
    val previewsJsonPath = File(moduleDir, "build/compose-previews/previews.json")
    previewsJsonPath.parentFile.mkdirs()
    previewsJsonPath.writeText(handAuthoredPreviewsJson(modulePath = "//:app"))

    val descriptor =
      DaemonLaunchBuilder.build(
        modulePath = "//:app",
        variant = "desktop",
        mainClass = "ee.schimke.composeai.daemon.DaemonMain",
        classpath = classpath,
        jvmArgs = listOf("-ea", "-Xmx1024m"),
        systemProperties =
          linkedMapOf(
            "user.home" to moduleDir.absolutePath,
            "java.awt.headless" to "true",
            "composeai.daemon.protocolVersion" to "1",
            "composeai.daemon.modulePath" to "//:app",
            "composeai.daemon.moduleId" to "//:app",
            "composeai.daemon.moduleProjectDir" to moduleDir.absolutePath,
            "composeai.daemon.workspaceRoot" to fixtureDir.absolutePath,
            "composeai.daemon.previewsJsonPath" to previewsJsonPath.absolutePath,
            "composeai.harness.previewsManifest" to previewsJsonPath.absolutePath,
            "composeai.render.outputDir" to renderOutputDir.absolutePath,
            "composeai.daemon.historyDir" to historyDir.absolutePath,
            "composeai.daemon.userClassDirs" to moduleJars.joinToString(File.pathSeparator),
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
          workspaceRoot = fixtureDir,
          workspaceName = "compose-ai-contrib",
          logSink = { line -> System.err.println("[bazel-daemon] $line") },
        )
      )
      .use { session ->
        assertThat(session.modulePath).isEqualTo("//:app")
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
        val image = javax.imageio.ImageIO.read(png) ?: error("Invalid PNG: $png")
        assertThat(image.width).isGreaterThan(1)
        assertThat(image.height).isGreaterThan(1)
        val colors = mutableSetOf<Int>()
        for (y in 0 until image.height) {
          for (x in 0 until image.width) colors += image.getRGB(x, y)
        }
        assertWithMessage("Greeting should contain text, not just a blank background")
          .that(colors.size).isGreaterThan(1)
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
                put("className", JsonPrimitive("com.example.bazeldesktop.GreetingKt"))
                put("sourceFile", JsonPrimitive("app/src/main/kotlin/com/example/bazeldesktop/Greeting.kt"))
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
