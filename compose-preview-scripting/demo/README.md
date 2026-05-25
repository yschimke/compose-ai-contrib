# `compose-preview-scripting` demo

Tiny Compose Desktop fixture that exercises the `compose-preview-scripting`
binary against a normal `@Preview` set. Three previews
(`GreetingPreview`, `RedBoxPreview`, `BlueBoxPreview`) in
[`src/main/kotlin/demo/Previews.kt`](src/main/kotlin/demo/Previews.kt);
three example `.composepreview.kts` scripts in [`scripts/`](scripts/).

## Run

```bash
# Build the scripting binary once.
./gradlew :compose-preview-scripting:installDist

# Then drive the demo previews from inside the demo dir. The binary walks up
# from cwd for a `gradlew`, opens the build via Tooling API, runs the plugin's
# `composePreviewRenderAll` task, and evaluates the script against the
# resulting `PreviewResult`s.
cd compose-preview-scripting/demo
../build/install/compose-preview-scripting/bin/compose-preview-scripting \
  scripts/list.composepreview.kts
```

## Scripts

- [`list.composepreview.kts`](scripts/list.composepreview.kts) — iterate
  `previews()`, print id / module / png path / sha256 for each.
- [`inspect.composepreview.kts`](scripts/inspect.composepreview.kts) —
  `show("...")` lookup by id, dump per-capture detail.
- [`audit-boxes.composepreview.kts`](scripts/audit-boxes.composepreview.kts) —
  filter previews, assert with `fail(...)` (accumulating; the script
  body keeps going after each failure so a real audit reports every
  offender in one pass).

## Iterating

The script is just Kotlin against the `ComposePreviewScript` DSL
(`previews()`, `show(id)`, `fail(message)`). Edit a `.composepreview.kts`
file, rerun the binary — the second invocation is fast because Gradle's
build cache short-circuits the render task when sources and plugin
classpath haven't changed:

```
> Task :compose-preview-scripting:demo:composePreviewDiscover UP-TO-DATE
> Task :compose-preview-scripting:demo:composePreviewRender UP-TO-DATE
```

So the inner loop is just: tweak script → rerun → see new output.

## Rendered output

Each preview's PNG is on disk at the `pngPath` the script reads:

```
build/compose-previews/renders/GreetingPreview_Greeting.png
build/compose-previews/renders/RedBoxPreview_Red_Box.png
build/compose-previews/renders/BlueBoxPreview_Blue_Box.png
```
