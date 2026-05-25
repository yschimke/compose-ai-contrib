"""Buck2 rules wrapping `compose-preview` for Android resource discovery.

Mirror of `bazel/compose_preview.bzl`: same `_discover.sh` hermetic action,
same `resources.json` wire format. Until the `compose-preview
discover-resources` CLI subcommand lands (compose-ai-tools#1253), the
rule shells out to the placeholder script. When the CLI ships, the
rule swaps to invoke `java -jar` and the script disappears — the
`srcs`/`module`/`variant`/`<name>.json` interface stays put.

Drop-in for other Buck2 projects:
  1. Copy `compose_preview.bzl` and `_discover.sh` into the project.
  2. `load("//path/to:compose_preview.bzl", "discover_resources")` from
     a `BUCK` file alongside the project's `res/` tree.
  3. Glob the resource XML and pass `module` + `variant`. Output is
     `<bazel-bin equivalent>/<name>.json`.
"""

def _discover_resources_impl(ctx: AnalysisContext) -> list[Provider]:
    out = ctx.actions.declare_output(ctx.attrs.name + ".json")
    cmd = cmd_args(
        "bash",
        ctx.attrs._discover,
        out.as_output(),
        ctx.attrs.module,
        ctx.attrs.variant,
    )
    cmd.add(ctx.attrs.srcs)
    ctx.actions.run(
        cmd,
        category = "compose_preview_discover_resources",
        identifier = ctx.attrs.name,
    )
    return [DefaultInfo(default_output = out)]

discover_resources = rule(
    impl = _discover_resources_impl,
    doc = """Walks `srcs` (Android `res/` XML files) and writes a
    `<name>.json` manifest matching the `resources.json` schema produced
    by the Gradle plugin's `discoverAndroidResources` task. See
    compose-ai-tools#1253 for the wire-format spec.""",
    attrs = {
        "srcs": attrs.list(
            attrs.source(),
            default = [],
            doc = "Android resource XML files. Directory layout " +
                  "(`drawable/`, `drawable-night/`, `mipmap-anydpi-v26/`) " +
                  "is parsed from the file paths.",
        ),
        "module": attrs.string(
            doc = "Logical module name written into `resources.json`. " +
                  "Matches the Gradle plugin's per-module manifest.",
        ),
        "variant": attrs.string(
            default = "main",
            doc = "Variant label (Gradle parity). Sample uses 'main'.",
        ),
        "_discover": attrs.source(
            default = "root//:_discover.sh",
            doc = "Placeholder hermetic action. Swapped for the " +
                  "`compose-preview discover-resources` CLI when it lands.",
        ),
    },
)
