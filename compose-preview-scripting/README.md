# `compose-preview-scripting`

Standalone `compose-preview-scripting <path>` binary. Compiles and evaluates a
`*.composepreview.kts` script against the set of previews rendered out of a
Gradle project rooted at the current working directory.

Lifted from
[`yschimke/compose-ai-tools` PR #1375](https://github.com/yschimke/compose-ai-tools/pull/1375)
(`examples/scripting/`), which carved the scripting host out of `:cli` and proved
the published API surface (`preview-data-api` + `gradle-preview-driver` +
`data-a11y-core`) was expressive enough to build features against. This repo is
the published home — upstream's `examples/scripting/` is the reference template
and gets deleted once #1375 lands.

## Build

```bash
./gradlew :compose-preview-scripting:installDist
./compose-preview-scripting/build/install/compose-preview-scripting/bin/compose-preview-scripting \
  path/to/your.composepreview.kts
```

The binary walks up from cwd for a `gradlew`, opens a `GradlePreviewDriver`
rooted there, renders every preview module with `--with-extension a11y`,
annotates each result with the per-preview accessibility entry, then evaluates
the script. Any accumulated `fail(...)` messages surface on stderr and the
process exits `2`.

## DSL

`*.composepreview.kts` scripts inherit `ComposePreviewScript`. The DSL surface:

```kotlin
for (ui in previews().filter { it.module == ":app" && it.id.startsWith("Home") }) {
  if (ui.a11y.hasErrors) fail("a11y errors on ${ui.id}: ${ui.a11y.errors.size}")
}
```

- `previews(): List<RenderedPreview>` — every rendered preview, id-sorted.
- `show(id: String): RenderedPreview` — lookup; throws with available ids on miss.
- `fail(message: String)` — accumulating; does not abort the script body.

Each `RenderedPreview` exposes the published `PreviewResult` fields (`id`,
`module`, `pngPath`, `sha256`, `captures`, …) plus typed sub-handles like
`ui.a11y` decoded off `dataExtensions["a11y"]`.

## Version pin

This module depends on `ee.schimke.composeai:preview-data-api` and
`ee.schimke.composeai:gradle-preview-driver` — both introduced by
[PR #1375](https://github.com/yschimke/compose-ai-tools/pull/1375) and published
since `composeai` 0.11.15. The version pinned in
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml) (`composeai`) resolves
them from Maven Central; bump that pin in lockstep with upstream releases.

## Wire-format note

The a11y mirror types (`AccessibilityFinding` / `AccessibilityEntry` /
`AccessibilityReport`) come from `preview-data-api`'s deprecated v1 mirror.
When the compose-preview wire format bumps to v2:

- The mirror types disappear from `preview-data-api`.
- This module switches to `data-a11y-core`'s `ee.schimke.composeai.renderer`
  types, which are the canonical shape.
- The `dataExtensions["a11y"]` payload bumps to
  `compose-preview-data-a11y/v2`; `A11Y_PAYLOAD_SCHEMA_V1` gets renamed.

Until v2 lands, the mirror is the contract.
