package ee.schimke.composeai.contrib.bundle

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmbeddedVerifierTest {

  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  private fun jar(name: String, content: String): File =
    tmp.newFile(name).apply { writeText(content) }

  @Test
  fun `flags an embedded jar that is actually published`() {
    val published = jar("mystery.jar", "these-bytes-are-on-central")
    val sha1 = EmbeddedVerifier.sha1Hex(published)
    val search = EmbeddedVerifier.ChecksumSearch { s ->
      if (s == sha1) "com.example:published:1.2.3" else null
    }

    val findings = EmbeddedVerifier.verify(listOf(published), search)

    assertThat(findings).hasSize(1)
    assertThat(findings[0].resolvable).isTrue()
    assertThat(findings[0].publishedAs).isEqualTo("com.example:published:1.2.3")
    assertThat(findings[0].checked).isTrue()
  }

  @Test
  fun `clears a genuinely vendored jar the registry does not know`() {
    val vendored = jar("vendored.jar", "internal-only-bytes")
    val search = EmbeddedVerifier.ChecksumSearch { null }

    val finding = EmbeddedVerifier.verify(listOf(vendored), search).single()

    assertThat(finding.resolvable).isFalse()
    assertThat(finding.publishedAs).isNull()
    assertThat(finding.checked).isTrue()
  }

  @Test
  fun `marks a jar unverified when the lookup itself fails`() {
    val jar = jar("offline.jar", "bytes")
    val search = EmbeddedVerifier.ChecksumSearch { error("network down") }

    val finding = EmbeddedVerifier.verify(listOf(jar), search).single()

    assertThat(finding.checked).isFalse()
    assertThat(finding.resolvable).isFalse()
  }
}
