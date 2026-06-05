plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

dependencies {
  implementation(libs.classgraph)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

application {
  // CLI used by the non-Gradle drivers (the Bazel `bundle_preview` rule shells
  // out to it; the Amper producer calls the library directly). See
  // `BundleProducerCli`.
  mainClass.set("ee.schimke.composeai.contrib.bundle.BundleProducerCli")
}

// Single self-contained runnable jar so a Bazel `run_shell` action (or any
// non-Gradle driver) can invoke the producer with plain `java -jar` — no
// classpath assembly, no published artifact. Mirrors how `docs/bazel.md` drives
// the upstream `java -jar` CLIs. Built with `./gradlew :bundle-producer:uberJar`
// → `build/libs/bundle-producer-all.jar`.
val uberJar by tasks.registering(Jar::class) {
  archiveClassifier.set("all")
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  manifest { attributes["Main-Class"] = "ee.schimke.composeai.contrib.bundle.BundleProducerCli" }
  from(sourceSets.main.get().output)
  dependsOn(configurations.runtimeClasspath)
  from({
    configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
  })
  // Drop signature files from dependency jars — they'd fail verification once
  // repacked into a different archive.
  exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.named("assemble") { dependsOn(uberJar) }

kotlin {
  // JDK 17 to match the rest of the repo (the daemon runs on 17; keeping the
  // producer's class-file version aligned avoids surprises when its own output
  // is ever fed back through the same toolchain).
  jvmToolchain(17)
}
