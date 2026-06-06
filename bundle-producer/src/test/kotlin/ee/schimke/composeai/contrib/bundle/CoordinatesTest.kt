package ee.schimke.composeai.contrib.bundle

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoordinatesTest {

  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  @Test
  fun `recovers coordinate from maven layout path`() {
    val root = tmp.newFolder("m2")
    val jar = File(root, "org/jetbrains/compose/desktop/desktop-jvm-linux-x64/1.7.0/desktop-jvm-linux-x64-1.7.0.jar")
    jar.parentFile.mkdirs()
    jar.writeText("not really a jar")

    val coord = Coordinates.recoverFromMavenLayout(jar, listOf(root))

    assertThat(coord).isEqualTo("org.jetbrains.compose.desktop:desktop-jvm-linux-x64:1.7.0:jar")
  }

  @Test
  fun `returns null when the basename does not match artifact-version`() {
    val root = tmp.newFolder("m2")
    val jar = File(root, "org/example/foo/1.0/some-unrelated-name.jar")
    jar.parentFile.mkdirs()
    jar.writeText("x")

    assertThat(Coordinates.recoverFromMavenLayout(jar, listOf(root))).isNull()
  }

  @Test
  fun `returns null for a jar outside every repo root`() {
    val root = tmp.newFolder("m2")
    val outside = tmp.newFile("loose-1.0.jar")

    assertThat(Coordinates.recoverFromMavenLayout(outside, listOf(root))).isNull()
  }

  @Test
  fun `parses maven_install pins into coordinates and hex sha256`() {
    val sha = "a".repeat(64)
    val json =
      """
      {
        "artifacts": {
          "androidx.compose.ui:ui": { "shasums": { "aar": "$sha" }, "version": "1.7.5" },
          "org.jetbrains.kotlin:kotlin-stdlib": { "shasums": { "jar": "$sha" }, "version": "2.1.0" }
        },
        "version": "2"
      }
      """.trimIndent()

    val index = Coordinates.parseMavenInstall(json)

    assertThat(index.size).isEqualTo(2)
    assertThat(index.coordinateFor(File("/x/ui-1.7.5.aar")))
      .isEqualTo("androidx.compose.ui:ui:1.7.5:aar")
    assertThat(index.sha256For(File("/x/ui-1.7.5.aar"))).isEqualTo(sha)
    assertThat(index.coordinateFor(File("/x/kotlin-stdlib-2.1.0.jar")))
      .isEqualTo("org.jetbrains.kotlin:kotlin-stdlib:2.1.0:jar")
  }

  @Test
  fun `drops a non-hex sha but still recovers the coordinate`() {
    val json =
      """
      { "artifacts": { "g:a": { "shasums": { "jar": "not-hex" }, "version": "1.0" } } }
      """.trimIndent()

    val index = Coordinates.parseMavenInstall(json)

    assertThat(index.coordinateFor(File("a-1.0.jar"))).isEqualTo("g:a:1.0:jar")
    assertThat(index.sha256For(File("a-1.0.jar"))).isNull()
  }

  @Test
  fun `tolerates malformed maven_install json`() {
    assertThat(Coordinates.parseMavenInstall("{ not json").size).isEqualTo(0)
  }
}
