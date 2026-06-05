package ee.schimke.composeai.contrib.bundle

import kotlinx.serialization.Serializable

/**
 * `previews.json` shape, mirroring the upstream reader's [PreviewManifest] / [PreviewInfo] /
 * [PreviewParams] (`bundle-viewer/.../Schema.kt`). The bundle writer filters this to the selected
 * previews and writes it into the bundle; the player reads `id` / `className` / `functionName` /
 * `params` to load and invoke each composable. `ignoreUnknownKeys` on both sides keeps extra fields
 * a richer discovery manifest carries (e.g. `captures`) harmless.
 */
@Serializable
data class PreviewManifest(
  val module: String = "",
  val variant: String = "",
  val previews: List<PreviewInfo>,
  val dataExtensionReports: Map<String, String> = emptyMap(),
)

@Serializable
data class PreviewInfo(
  val id: String,
  val functionName: String,
  val className: String,
  val sourceFile: String? = null,
  val params: PreviewParams = PreviewParams(),
)

@Serializable
data class PreviewParams(
  val name: String? = null,
  val device: String? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  val density: Float? = null,
  val fontScale: Float = 1.0f,
  val showSystemUi: Boolean = false,
  val showBackground: Boolean = false,
  val backgroundColor: Long = 0,
  val uiMode: Int = 0,
  val locale: String? = null,
  val group: String? = null,
  val wrapperClassName: String? = null,
  val previewParameterProviderClassName: String? = null,
  val previewParameterLimit: Int = Int.MAX_VALUE,
  val kind: String = "COMPOSE",
)
