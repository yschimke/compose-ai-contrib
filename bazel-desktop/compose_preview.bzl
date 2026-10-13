"""`bundle_preview` — pack a portable `compose-preview` bundle from Bazel outputs.

This is the Bazel-side answer to the portable-bundle ask (see
`../docs/portable-bundles.md`). The rule drives the shared producer
(`:bundle-producer` in the repo's Gradle build, packaged as a single runnable
`bundle-producer-all.jar`) over Bazel's compiled outputs:

  - the consumer module's bytecode (`module`'s `JavaInfo` output jars) is
    minimized to the classes reachable from the selected previews,
  - each reachable runtime jar (the module plus any additional `deps`) is
    recorded as a resolvable `ClasspathEntry.Maven` coordinate **with sha256**,
    recovered from `rules_jvm_external`'s pinned `maven_install.json` by basename
    (`resolution = "coordinates"`),
  - a runtime jar with no pin (a vendored `//third_party` jar, or any jar when
    `maven_install` is omitted / `embed = True`) is carried inside the bundle's
    `libs/` instead (`resolution = "embedded"` / `"mixed"`).

`producer = "bazel"` is stamped into `bundle.json`. Rendering (`previews/<id>.png`)
runs separately from this packaging action. By default the bundle carries a
stub cover plus a real `classpath[]`;
pass `--render <id>=<png>` via `render_files` to bake external renders.

The producer jar is staged by `./gradlew :bundle-producer:uberJar`.
The action uses Bazel's execution-platform Java runtime, with its files declared
as tools. It does not depend on java on PATH or the caller's shell environment.

"""

# Bazel 9 removed `JavaInfo` as an implicit `.bzl` global (it was autoloaded in
# Bazel 8), so load it explicitly from rules_java. bazel-apk pins Bazel 9 (see
# MODULE.bazel), where this is required.
load("@rules_java//java:defs.bzl", "JavaInfo")

def _bundle_preview_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.name + ".png")
    producer = ctx.file.producer_jar

    # Consumer module bytecode: the library's own output jars.
    module_info = ctx.attr.module[JavaInfo]
    module_jars = module_info.runtime_output_jars
    if not module_jars:
        fail("module must provide runtime output jars")
    if not ctx.attr.previews:
        fail("previews must contain at least one selected preview")

    # Reachable runtime classpath: the module plus additional deps, minus
    # the module's own output jars (those are minimized into classes/app.jar; we
    # don't want them re-listed as a dependency / embedded under libs/).
    module_paths = {jar.path: True for jar in module_jars}
    runtime_depset = depset(transitive = [module_info.transitive_runtime_jars] + [
        dep[JavaInfo].transitive_runtime_jars
        for dep in ctx.attr.deps
        if JavaInfo in dep
    ])
    runtime_jars = [jar for jar in runtime_depset.to_list() if jar.path not in module_paths]

    args = ctx.actions.args()
    args.add("-jar")
    args.add(producer.path)
    args.add("--backend", ctx.attr.backend)
    args.add("--variant", ctx.attr.variant)
    args.add("--out", out.path)
    args.add("--module-path", ctx.attr.module_path)
    args.add("--producer", "bazel")
    args.add("--produced-by", "compose-ai-contrib bazel producer")
    for jar in module_jars:
        args.add("--module-source", jar.path)
    for jar in runtime_jars:
        args.add("--jar", jar.path)
    for preview in ctx.attr.previews:
        args.add("--preview", preview)

    # Each pre-rendered PNG is baked in under its filename stem as the preview id
    # (`<id>.png` -> `--render <id>=<path>`). Empty until a Bazel render path exists.
    for png in ctx.files.render_files:
        args.add("--render", png.basename[:-len(".png")] + "=" + png.path)

    inputs = [producer] + module_jars + runtime_jars + ctx.files.render_files
    if ctx.file.maven_install:
        args.add("--maven-install", ctx.file.maven_install.path)
        inputs.append(ctx.file.maven_install)
    if ctx.attr.embed:
        args.add("--embed")

    java_runtime = ctx.toolchains["@bazel_tools//tools/jdk:runtime_toolchain_type"].java_runtime
    ctx.actions.run(
        executable = java_runtime.java_executable_exec_path,
        tools = java_runtime.files,
        arguments = [args],
        inputs = inputs,
        outputs = [out],
        mnemonic = "ComposePreviewBundle",
        progress_message = "Packing compose-preview bundle for %{label}",
    )
    return [DefaultInfo(files = depset([out]))]

bundle_preview = rule(
    implementation = _bundle_preview_impl,
    doc = "Packs a portable `compose-preview` bundle (`<name>.png` PNG+ZIP polyglot).",
    toolchains = ["@bazel_tools//tools/jdk:runtime_toolchain_type"],
    attrs = {
        "backend": attr.string(
            default = "desktop",
            values = ["desktop", "android"],
            doc = "Render backend recorded in the bundle.",
        ),
        "variant": attr.string(default = "desktop"),
        "module": attr.label(
            mandatory = True,
            providers = [JavaInfo],
            doc = "The consumer library target whose bytecode is minimized into `classes/app.jar`.",
        ),
        "deps": attr.label_list(
            providers = [JavaInfo],
            doc = "Additional runtime targets; the module closure is always included.",
        ),
        "previews": attr.string_list(
            mandatory = True,
            doc = "Selected previews as `id=fully.qualified.Class#functionName[@sourceFile]` " +
                  "(first entry is the cover).",
        ),
        "module_path": attr.string(
            mandatory = True,
            doc = "Module label recorded into `bundle.json` (e.g. `//app:bazel_sample_lib`).",
        ),
        "maven_install": attr.label(
            allow_single_file = [".json"],
            doc = "Pinned `maven_install.json` for coordinate+sha256 recovery. Omit to embed all deps.",
        ),
        "render_files": attr.label_list(
            allow_files = [".png"],
            doc = "Optional pre-rendered PNGs to bake in; each `<id>.png` is keyed by its stem.",
        ),
        "embed": attr.bool(
            default = False,
            doc = "Force every dep into `libs/` (offline `embedded` pack).",
        ),
        "producer_jar": attr.label(
            allow_single_file = [".jar"],
            default = ":bundle-producer-all.jar",
            doc = "The `bundle-producer-all.jar` built by `./gradlew :bundle-producer:uberJar`.",
        ),
    },
)


def _runtime_classpath_impl(ctx):
    jars = ctx.attr.module[JavaInfo].transitive_runtime_jars
    out = ctx.actions.declare_file(ctx.label.name + ".json")
    ctx.actions.write(out, json.encode({
        "moduleJars": [jar.path for jar in ctx.attr.module[JavaInfo].runtime_output_jars],
        "runtimeJars": [jar.path for jar in jars.to_list()],
    }))
    # Building this target must also materialize the bytecode the manifest names.
    return [DefaultInfo(files = depset([out], transitive = [jars]))]

runtime_classpath = rule(
    implementation = _runtime_classpath_impl,
    attrs = {"module": attr.label(mandatory = True, providers = [JavaInfo])},
    doc = "Exports execution-root-relative runtime jars for the render contract test.",
)
