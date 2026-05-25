package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.system.exitProcess
import org.jetbrains.skia.EncodedImageFormat

/**
 * Standalone `java -cp` entry point invoked once per preview by the published
 * `ee.schimke.composeai.preview` Gradle plugin's `composePreviewRender` task. The plugin passes
 * `<className> <functionName> <widthPx> <heightPx> <density> <showBackground> <backgroundColor>
 * <outputFile>` plus a tail of optional args (`@PreviewParameter`, `@PreviewWrapper`,
 * pseudolocale, `@ScrollingPreview`, display filters) that this minimal renderer ignores — the
 * demo previews don't use any of them.
 */
fun main(args: Array<String>) {
  if (args.size < 8) {
    System.err.println(
      "Usage: DesktopRendererMain <className> <functionName> <widthPx> <heightPx> <density> " +
        "<showBackground> <backgroundColor> <outputFile> [...ignored]"
    )
    exitProcess(1)
  }
  val className = args[0]
  val functionName = args[1]
  val widthPx = args[2].toInt()
  val heightPx = args[3].toInt()
  val density = args[4].toFloat()
  val showBackground = args[5].toBoolean()
  val backgroundColor = args[6].toLong()
  val outputFile = File(args[7])

  try {
    renderSingleFrame(
      className = className,
      functionName = functionName,
      widthPx = widthPx,
      heightPx = heightPx,
      density = density,
      showBackground = showBackground,
      backgroundColor = backgroundColor,
      outputFile = outputFile,
    )
  } catch (t: Throwable) {
    System.err.println("Render failed for $className.$functionName: ${t.javaClass.name}: ${t.message}")
    t.printStackTrace()
    exitProcess(2)
  }
}

private fun renderSingleFrame(
  className: String,
  functionName: String,
  widthPx: Int,
  heightPx: Int,
  density: Float,
  showBackground: Boolean,
  backgroundColor: Long,
  outputFile: File,
) {
  val clazz = Class.forName(className)
  val composableMethod = clazz.getDeclaredComposableMethod(functionName)

  val scene = ImageComposeScene(width = widthPx, height = heightPx, density = Density(density))
  try {
    scene.setContent {
      CompositionLocalProvider(LocalInspectionMode provides true) {
        val bg =
          when {
            // Honour the explicit `@Preview(backgroundColor = ...)` value first; the value is
            // an ARGB long the plugin forwards verbatim. `0L` is the sentinel for "unset" — fall
            // through to `showBackground` or transparent.
            backgroundColor != 0L -> Color(backgroundColor.toInt())
            showBackground -> Color.White
            else -> Color.Transparent
          }
        Box(modifier = Modifier.fillMaxSize().background(bg)) { InvokeComposable(composableMethod) }
      }
    }

    // Render twice so any first-frame Compose effects (e.g. initial measure pass) settle before
    // we capture. The upstream renderer does the same; the second render is the canonical one.
    scene.render()
    val image = scene.render()

    val pngData =
      image.encodeToData(EncodedImageFormat.PNG)
        ?: throw IllegalStateException("Skia failed to encode the scene to PNG")
    outputFile.parentFile?.mkdirs()
    outputFile.writeBytes(pngData.bytes)
  } finally {
    scene.close()
  }
}

@Composable
private fun InvokeComposable(composableMethod: ComposableMethod) {
  composableMethod.invoke(currentComposer, null)
}
