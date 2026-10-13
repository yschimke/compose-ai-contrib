package ee.schimke.composeai.contrib

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AmperBundleEmitterTest {
  @get:Rule val tempDir = TemporaryFolder()

  @Test
  fun `only the selected runtime version is packed even when the cache contains another`() {
    val cache = tempDir.newFolder("cache")
    val selected = File(cache, "example/lib/2/lib-2.jar").apply { parentFile.mkdirs(); writeBytes(byteArrayOf()) }
    File(cache, "example/lib/1/lib-1.jar").apply { parentFile.mkdirs(); writeBytes(byteArrayOf()) }
    val output = tempDir.newFolder("classes")
    val classpath = tempDir.newFile("runtime-classpath.txt").apply {
      writeText(listOf(output, selected, selected).joinToString("\n", postfix = "\n") { it.absolutePath })
    }

    assertThat(AmperBundleEmitter.readRuntimeJars(classpath)).containsExactly(selected)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `missing classpath entry fails instead of producing an incomplete bundle`() {
    val classpath = tempDir.newFile("runtime-classpath.txt").apply {
      writeText(File(tempDir.root, "missing.jar").absolutePath)
    }
    AmperBundleEmitter.readRuntimeJars(classpath)
  }
}
