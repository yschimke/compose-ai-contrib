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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
 * **Local skip:** JUnit reports a skipped case when fixture outputs are missing,
 * so a clean checkout passes. CI builds both fixtures and requires their outputs.
 */
@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
class AmperContractTest(private val fixture: String, private val className: String, private val functionName: String) {
  companion object {
    @JvmStatic
    @org.junit.runners.Parameterized.Parameters(name = "{0}")
    fun fixtures() = listOf(
      arrayOf("amper-cmp-desktop", "GreetingKt", "Greeting"),
      arrayOf("amper-compose-example", "MainKt", "App"),
    )
  }


  @get:Rule val tempDir: TemporaryFolder = TemporaryFolder()

  @Test
  fun `amper-built classes drive a real render via RenderSession`() {
    val amperFixtureDir = File(File(requireSysProp("contrib.amperFixtureDir")).parentFile, fixture)
    val amperKotlinOutput =
      File(
        amperFixtureDir,
        "build/artifacts/CompiledJvmArtifact/${fixture}jvm/kotlin-output",
      )

    val built = amperKotlinOutput.walkTopDown().any { it.isFile && it.extension == "class" }
    if (System.getProperty("contrib.requireAmperOutputs", "false").toBoolean()) {
      assertWithMessage("Missing outputs for $fixture: run ./kotlin build in $amperFixtureDir")
        .that(built).isTrue()
    }
    org.junit.Assume.assumeTrue("Build $fixture with ./kotlin build before rendering", built)

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
    val manifest = discoverAmperPreviews(amperFixtureDir, amperKotlinOutput, ":$fixture", rendererClasspath)
    previewsJsonPath.writeText(manifest)
    val discovered = Json.parseToJsonElement(manifest).jsonObject["previews"]!!.jsonArray.single().jsonObject
    assertThat(discovered["className"]!!.jsonPrimitive.content).isEqualTo(className)
    assertThat(discovered["functionName"]!!.jsonPrimitive.content).isEqualTo(functionName)
    val target = discovered["id"]!!.jsonPrimitive.content

    val descriptor =
      DaemonLaunchBuilder.build(
        modulePath = ":$fixture",
        variant = "desktop",
        mainClass = "ee.schimke.composeai.daemon.DaemonMain",
        classpath = classpath,
        jvmArgs = listOf("-ea", "-Xmx1024m"),
        systemProperties =
          linkedMapOf(
            "composeai.daemon.protocolVersion" to "1",
            "composeai.daemon.modulePath" to ":$fixture",
            "composeai.daemon.moduleId" to ":$fixture",
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


    SubprocessRenderSessions.open(
        RenderSessionConfig(
          descriptorPath = descriptorFile,
          workspaceRoot = amperFixtureDir,
          workspaceName = "compose-ai-contrib",
          logSink = { line -> System.err.println("[amper-daemon] $line") },
        )
      )
      .use { session ->
        assertThat(session.modulePath).isEqualTo(":$fixture")
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
        val decoded = javax.imageio.ImageIO.read(png) ?: error("Not a valid PNG: $pngPath")
        assertThat(decoded.width).isGreaterThan(0)
        assertThat(decoded.height).isGreaterThan(0)
        val colors = mutableSetOf<Int>()
        for (y in 0 until decoded.height) for (x in 0 until decoded.width) colors += decoded.getRGB(x, y)
        assertWithMessage("$target must render UI, not a uniform placeholder").that(colors.size).isGreaterThan(1)
      }
  }

  private fun requireSysProp(name: String): String =
    System.getProperty(name)
      ?: error("System property '$name' must be set by the Gradle test task")

}
