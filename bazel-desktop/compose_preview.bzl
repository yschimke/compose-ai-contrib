"""`bundle_preview` — pack a portable `compose-preview` bundle from Bazel outputs.

This is the Bazel-side answer to the portable-bundle ask (see
`../docs/portable-bundles.md`). The rule drives the shared producer
(`:bundle-producer` in the repo's Gradle build, packaged as a single runnable
`bundle-producer-all.jar`) over Bazel's compiled outputs:

  - the consumer module's bytecode (`module`'s `JavaInfo` output jars) is
    minimized to the classes reachable from the selected previews,
  - each reachable runtime jar (`deps`' `JavaInfo.transitive_runtime_jars`) is
    recorded as a resolvable `ClasspathEntry.Maven` coordinate **with sha256**,
    recovered from `rules_jvm_external`'s pinned `maven_install.json` by basename
    (`resolution = "coordinates"`),
  - a runtime jar with no pin (a vendored `//third_party` jar, or any jar when
    `maven_install` is omitted / `embed = True`) is carried inside the bundle's
    `libs/` instead (`resolution = "embedded"` / `"mixed"`).

`producer = "bazel"` is stamped into `bundle.json`. Rendering (`previews/<id>.png`)
is out of scope under Bazel today — the Robolectric/Desktop render path isn't
invocable here — so the bundle carries a stub cover plus a real `classpath[]`;
pass `--render <id>=<png>` (via `render_pngs`) once a Bazel render lands.

The producer jar is built by the repo's Gradle:

    ./gradlew :bundle-producer:uberJar
    cp ../bundle-producer/build/libs/bundle-producer-all.jar bazel-apk/

and referenced through the `producer_jar` attribute (default
`:bundle-producer-all.jar`). It runs through the host `java` in a `run_shell`
action — the same pragmatic shape `bazel/compose_preview.bzl` uses for the
resources discover script — rather than taking a hermetic JVM toolchain dep for a
single driver invocation.
"""

# Bazel 9 removed `JavaInfo` as an implicit `.bzl` global (it was autoloaded in
# Bazel 8), so load it explicitly from rules_java. bazel-apk pins Bazel 9 (see
# MODULE.bazel), where this is required.
load("@rules_java//java:defs.bzl", "JavaInfo")

def _bundle_preview_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.name + ".png")
    producer = ctx.file.producer_jar

    # Consumer module bytecode: the library's own output jars.
    module_jars = []
    if JavaInfo in ctx.attr.module:
        module_jars = ctx.attr.module[JavaInfo].runtime_output_jars

    # Reachable runtime classpath: the transitive runtime jars of `deps`, minus
    # the module's own output jars (those are minimized into classes/app.jar; we
    # don't want them re-listed as a dependency / embedded under libs/).
    module_paths = {jar.path: True for jar in module_jars}
    runtime_depset = depset(transitive = [
        dep[JavaInfo].transitive_runtime_jars
        for dep in ctx.attr.deps
        if JavaInfo in dep
    ])
    runtime_jars = [jar for jar in runtime_depset.to_list() if jar.path not in module_paths]

    args = ctx.actions.args()
    args.add(producer.path)  # consumed as `java -jar "$@"` => java -jar <producer> <flags…>
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

    ctx.actions.run_shell(
        command = 'java -jar "$@"',
        arguments = [args],
        inputs = inputs,
        outputs = [out],
        mnemonic = "ComposePreviewBundle",
        progress_message = "Packing compose-preview bundle for %{label}",
        use_default_shell_env = True,
    )
    return [DefaultInfo(files = depset([out]))]

bundle_preview = rule(
    implementation = _bundle_preview_impl,
    doc = "Packs a portable `compose-preview` bundle (`<name>.png` PNG+ZIP polyglot).",
    attrs = {
        "module": attr.label(
            mandatory = True,
            providers = [JavaInfo],
            doc = "The consumer library target whose bytecode is minimized into `classes/app.jar`.",
        ),
        "deps": attr.label_list(
            providers = [JavaInfo],
            doc = "Targets whose `transitive_runtime_jars` form the candidate third-party classpath.",
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
