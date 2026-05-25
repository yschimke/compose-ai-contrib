# Buck2 sample

Stepping-stone demo for compose-ai-tools under [Buck2], scoped to
**resources discovery** only (vectors and adaptive icons →
`resources.json`). The full Compose `@Preview` pipeline is tracked
separately in [#1037](https://github.com/yschimke/compose-ai-tools/issues/1037);
the seam this sample exercises is
[#1253](https://github.com/yschimke/compose-ai-tools/issues/1253).

Structural twin of [`../bazel/`](../bazel/) — same `_discover.sh`,
same `app/res/` tree, same `resources.json` output. The two samples
share a wire-format contract: building either should produce byte-
identical JSON (modulo the `module` label).

[Buck2]: https://buck2.build/

## What this proves

- The Gradle plugin's `resources.json` wire format is producible
  outside Gradle, under Buck2's action graph.
- A Buck2 `discover_resources` rule can drive the discovery step as a
  hermetic action with declared inputs (XML files) and a declared
  output (the manifest).
- The same CLI surface (eventually `compose-preview discover-resources`)
  works for Buck2, Bazel, and Amper — validating the spec across three
  non-Gradle build systems.

## What's stubbed

The `_discover` action today is a shell script ([`_discover.sh`](_discover.sh))
that hand-rolls a subset of the `ResourceDiscovery` logic from the
Gradle plugin. It produces a valid `resources.json` for `<vector>` /
`<adaptive-icon>` / `<animated-vector>` root tags only. The script is
a placeholder — once `compose-preview discover-resources` exists, the
rule swaps the executable and the script goes away. The **rule
interface** (`srcs`, `module`, `variant`, output `<name>.json`) is
the part this sample is committing to.

The **render** half (`renders/resources/<id>/<qualifiers>.png`) is
deliberately out of scope here. Rendering currently runs inside a
Robolectric `Test` task launched by the Gradle plugin, and unblocking
it under Buck2 needs its own design pass.

## Layout

```
contrib/buck2/
├── .buckconfig          # single root cell + Buck2's bundled prelude
├── .buckroot            # cell-root marker
├── BUCK                 # discover_resources target wiring
├── compose_preview.bzl  # rule definition (Buck2 Starlark)
├── _discover.sh         # placeholder discover action (identical to bazel/)
├── toolchains/BUCK      # toolchain cell stub (nothing to compile yet)
└── app/res/             # mirrors samples/android/src/main/res/
    ├── drawable/        # vectors + an animated-vector
    ├── drawable-night/  # qualifier variant of ic_compose_logo
    ├── mipmap-anydpi-v26/  # two adaptive-icons
    └── values/          # non-drawable XML; discover skips these
```

The `app/res/` tree is a verbatim copy of [`../bazel/app/res/`](../bazel/app/res/),
which itself mirrors `samples/android/src/main/res/` in upstream. Same
coverage of `<vector>`, `<animated-vector>`, `<adaptive-icon>`,
qualifier variants, and `<resources>` skip-files.

## Build

From this directory:

```
buck2 build //:app_resources --show-output
```

Outputs `buck-out/.../app_resources.json` matching the schema in the
Gradle plugin's `resources.json`. Inspect with:

```
cat $(buck2 build //:app_resources --show-simple-output)
```

Expected: one entry per `(base, name)` pair, each listing per-qualifier
source files under `sourceFiles`. The `captures` array is empty until
the render half lands.

To prove wire-format parity with the Bazel sample:

```
diff <(jq 'del(.module)' "$(buck2 build //:app_resources --show-simple-output)") \
     <(cd ../bazel && bazel build //:app_resources >/dev/null && jq 'del(.module)' bazel-bin/app_resources.json)
```

(The `module` field is the only intentional difference — Buck2 uses
`//samples/buck2:app`, Bazel uses `//samples/bazel:app`.)

## Applying to another Buck2 project

This sample is structured so the rule and its placeholder action drop
into any Buck2 cell:

1. Copy [`compose_preview.bzl`](compose_preview.bzl) and
   [`_discover.sh`](_discover.sh) into the consuming project (any
   directory inside the cell).
2. In a `BUCK` file alongside the project's `res/` tree, add:

   ```starlark
   load("@root//path/to:compose_preview.bzl", "discover_resources")

   export_file(name = "_discover.sh", src = "_discover.sh")

   discover_resources(
       name = "app_resources",
       srcs = glob(["res/**/*.xml"]),
       module = "//your/module:label",
       variant = "main",
   )
   ```

3. `buck2 build //path/to:app_resources` writes `app_resources.json`
   under `buck-out/`.

No prelude rules from `@prelude//...` are required — the rule shells
out via `ctx.actions.run` against the bundled bash. The `prelude` cell
in [`.buckconfig`](.buckconfig) uses Buck2's `bundled` external-cell
mechanism so consumers don't have to vendor the prelude either.

## Status

- [x] `.buckconfig` with single-cell + bundled prelude (no submodules)
- [x] `discover_resources` rule (placeholder shell action)
- [x] `app/res/` mirror of the Bazel sample for byte-diff parity
- [x] Opt-in CI workflow (`.github/workflows/buck2.yml`)
- [ ] `compose-preview discover-resources` CLI subcommand
- [ ] Swap `_discover.sh` for the real CLI binary
- [ ] `render_resources` rule (blocked on render-CLI extraction)

## See also

- [`../bazel/`](../bazel/) — twin sample under Bazel.
- [`../docs/bazel.md`](../docs/bazel.md) — design rationale for the
  rule shape; applies to Buck2 with minor syntactic differences
  (`attrs.list` vs `attr.label_list`, `declare_output` vs
  `declare_file`, `ctx.attrs` vs `ctx.attr`).
- [`buck2.build`](https://buck2.build/) — Buck2 documentation, including
  the [bundled prelude](https://buck2.build/docs/concepts/cells/#external-cells)
  mechanism used here.
