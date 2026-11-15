package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Guard for Gradle dependency verification (CWE-494, CWE-345). Both Gradle builds (`app` and its
 * `buildSrc`) verify every resolved artifact against a committed SHA-256 checksum and, where the
 * repository publishes a signature, against a PGP key trusted by full fingerprint in
 * `verification-metadata.xml` and carried in a committed public keyring.
 *
 * The guard keeps the three ways the gate could be quietly undone out of the repository: a
 * per-artifact exemption (`ignored-key`) or a key trusted for every group, a private key committed
 * with the keyring, and a build file, workflow or Dockerfile that turns verification off or
 * rewrites the metadata mid-build. It also fails when the version catalog moves ahead of the
 * metadata, which is what a dependency bump without regeneration looks like.
 *
 * See ADR-0114.
 */
class DependencyVerificationGateTest {
    private val mainMetadata = "app/gradle/verification-metadata.xml"
    private val mainKeyring = "app/gradle/verification-keyring.keys"
    private val buildSrcMetadata = "app/buildSrc/gradle/verification-metadata.xml"
    private val buildSrcKeyring = "app/buildSrc/gradle/verification-keyring.keys"

    /** The files a build runs from, none of which may weaken verification. */
    private val buildFiles =
        listOf(
            "app/gradle.properties",
            "app/Dockerfile",
            "app/e2e/Dockerfile",
            ".github/workflows/ci.yml",
            ".github/workflows/nightly.yml",
        )

    /**
     * Plugin ids the convention plugin applies from a module dependency rather than from its
     * marker: Gradle resolves the module, so the metadata records no `<id>.gradle.plugin` artifact.
     * Each maps to the catalog module that carries it.
     */
    private val moduleAppliedPlugins = mapOf("org.jetbrains.kotlinx.kover" to "kover-gradle-plugin")

    private fun read(path: String): String {
        val file = File(repoRoot, path)
        assertTrue(
            file.isFile,
            "$path is missing. Strict dependency verification is the gate that keeps a swapped or " +
                "tampered artifact out of the build (CWE-494); commit it rather than deleting the guard.",
        )
        return file.readText()
    }

    /** Every `<trusted-key>` element of [metadata], self-closing or with `<trusting>` children. */
    private fun trustedKeys(metadata: String): List<String> =
        Regex("""<trusted-key\b[^>]*/>|<trusted-key\b[^>]*>.*?</trusted-key>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(metadata)
            .map { it.value }
            .toList()

    @Test
    fun `both gradle builds verify checksums and signatures`() {
        for (path in listOf(mainMetadata, buildSrcMetadata)) {
            val metadata = read(path)
            assertTrue(
                metadata.contains("<verify-metadata>true</verify-metadata>"),
                "$path does not enable `verify-metadata`: the module metadata (the `.pom` / `.module` " +
                    "that decides which artifacts a dependency pulls in) would go unverified (CWE-494).",
            )
            assertTrue(
                metadata.contains("<verify-signatures>true</verify-signatures>"),
                "$path does not enable `verify-signatures`: an artifact whose repository publishes a " +
                    "signature would be accepted on its checksum alone, so a key compromise or a " +
                    "rewritten checksum list would pass (CWE-345).",
            )
        }
    }

    @Test
    fun `no artifact is exempted from verification and no key is trusted wholesale`() {
        for (path in listOf(mainMetadata, buildSrcMetadata)) {
            val metadata = read(path)
            assertTrue(
                !metadata.contains("<ignored-key"),
                "$path ignores a signing key, which downgrades every artifact that key signs to a " +
                    "checksum check. Add the key to the keyring by full fingerprint instead (ADR-0114).",
            )
            val keys = trustedKeys(metadata)
            assertTrue(keys.isNotEmpty(), "$path trusts no key at all, so no signature could ever verify.")
            for (key in keys) {
                val restrictions = Regex("""(?:group|name)="([^"]*)"""").findAll(key).map { it.groupValues[1] }.toList()
                assertTrue(
                    restrictions.isNotEmpty(),
                    "$path trusts a key for every artifact: a key trusted without a `group` / `name` " +
                        "restriction can sign for any dependency in the build (CWE-345). Element: $key",
                )
                for (restriction in restrictions) {
                    assertTrue(
                        restriction.any { it.isLetterOrDigit() },
                        "$path restricts trust with the pattern `$restriction`, which matches every " +
                            "group: it is a blanket trust with extra steps (CWE-345). Element: $key",
                    )
                }
            }
        }
    }

    @Test
    fun `the committed keyrings hold public keys only`() {
        for (path in listOf(mainKeyring, buildSrcKeyring)) {
            val keyring = read(path)
            assertTrue(
                keyring.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----"),
                "$path holds no public key: verification would then have to fetch each key from a key " +
                    "server at build time, which the keyless CI runners do not reach (ADR-0084).",
            )
            assertTrue(
                !keyring.contains("PRIVATE KEY"),
                "$path holds private key material. A private key in the repository can sign artifacts " +
                    "for every key it is trusted for, so the signature check would prove nothing (CWE-321).",
            )
        }
    }

    @Test
    fun `no build file or workflow weakens dependency verification`() {
        val weakened = Regex("""dependency[-.]verification\s*[=\s]\s*(lenient|off)\b""")
        for (path in buildFiles) {
            val text = read(path)
            val lenient = weakened.find(text)?.value
            assertTrue(
                lenient == null,
                "$path sets `$lenient`. Lenient mode logs a verification failure and lets the build " +
                    "continue, and `off` skips verification entirely: both turn the gate into a report " +
                    "nobody reads (CWE-494). Strict is the default — remove the setting.",
            )
            assertTrue(
                !text.contains("--write-verification-metadata"),
                "$path rewrites the verification metadata during a build. That accepts whatever the " +
                    "repository served in that run, so a compromised artifact is trusted by the very " +
                    "build that downloads it (CWE-494). Regenerate by hand and commit the diff.",
            )
        }
    }

    @Test
    fun `every plugin in the version catalog is recorded in verification metadata`() {
        val catalog = read("app/gradle/libs.versions.toml")
        val versions = versionSection(catalog)
        val metadata = read(mainMetadata) + read(buildSrcMetadata)
        val plugins = Regex("""id\s*=\s*"([^"]+)"\s*,\s*version\.ref\s*=\s*"([^"]+)"""").findAll(catalog)
        assertTrue(
            plugins.any(),
            "No plugin with a `version.ref` in app/gradle/libs.versions.toml: this guard has lost its " +
                "input and must be pointed at the catalog's current shape.",
        )
        for (plugin in plugins) {
            val (id, ref) = plugin.destructured
            val version = versions[ref]
            assertTrue(version != null, "app/gradle/libs.versions.toml references undefined version `$ref` for plugin `$id`.")
            val module = moduleAppliedPlugins[id]
            val recorded =
                if (module == null) {
                    metadata.contains("""name="$id.gradle.plugin-$version.pom"""")
                } else {
                    metadata.contains("""name="$module-$version.""")
                }
            assertTrue(
                recorded,
                "Plugin `$id` is not recorded at version $version in the verification metadata: a bump " +
                    "was committed without regenerating it, so the next build fails verification (or, " +
                    "worse, the metadata is edited by hand to whatever the build downloaded). " +
                    "Regenerate per app/CLAUDE.md § Build commands.",
            )
        }
    }

    /** The `[versions]` entries of [catalog] as a name → version map. */
    private fun versionSection(catalog: String): Map<String, String> {
        val section = catalog.substringAfter("[versions]").substringBefore("\n[")
        return Regex("""^([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .findAll(section)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
