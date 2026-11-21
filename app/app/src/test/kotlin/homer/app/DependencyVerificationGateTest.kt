package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Guard for Gradle dependency verification (CWE-494, CWE-345, ADR-0115): it keeps out of the repository a per-artifact
 * exemption or a key trusted for every group, a committed private key, and a build file that turns verification off
 * or rewrites the metadata; it also fails when the version catalog moves ahead of the metadata.
 */
class DependencyVerificationGateTest {
    private val mainMetadata = "app/gradle/verification-metadata.xml"
    private val mainKeyring = "app/gradle/verification-keyring.keys"
    private val buildSrcMetadata = "app/buildSrc/gradle/verification-metadata.xml"
    private val buildSrcKeyring = "app/buildSrc/gradle/verification-keyring.keys"

    /** Directories the repository walk below skips: VCS metadata, dependency and build output. */
    private val skippedDirs = setOf(".git", "node_modules", "build", ".gradle", ".next")

    /**
     * The files a build runs from, none of which may weaken verification — discovered, not
     * enumerated, so a new workflow, Dockerfile or `gradle.properties` (any of them may invoke
     * Gradle) is scanned without this test having to learn about it.
     */
    private fun buildFiles(): List<String> =
        repoRoot
            .walkTopDown()
            .onEnter { it.name !in skippedDirs }
            .filter { it.isFile }
            .map { it.relativeTo(repoRoot).path }
            .filter { path ->
                val name = path.substringAfterLast('/')
                name == "gradle.properties" || name == "Dockerfile" ||
                    (path.startsWith(".github/workflows/") && (name.endsWith(".yml") || name.endsWith(".yaml")))
            }.sorted()
            .toList()

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
                    "checksum check. Add the key to the keyring by full fingerprint instead (ADR-0115).",
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

    /**
     * Every spelling of turning verification down: the property, the CLI flag and the
     * `GRADLE_DEPENDENCY_VERIFICATION` variable, any separator, quote, mode and capitalisation (`=Lenient` fails too).
     */
    private val weakened =
        Regex(
            """(gradle_)?dependency[-._]verification\s*[=\s:]\s*["']?(lenient|off)\b""",
            RegexOption.IGNORE_CASE,
        )

    @Test
    fun `the weakening guard recognizes every spelling of lenient and off`() {
        val weakenedForms =
            listOf(
                "dependency-verification=lenient",
                "dependency-verification=off",
                "org.gradle.dependency-verification=off",
                "dependency.verification=off",
                "dependency_verification=off",
                "GRADLE_DEPENDENCY_VERIFICATION=lenient",
                "GRADLE_DEPENDENCY_VERIFICATION: off",
                "GRADLE_DEPENDENCY_VERIFICATION: \"off\"",
                "--dependency-verification lenient",
                "--dependency-verification=\"off\"",
                "dependency-verification=LENIENT",
                "dependency-verification=Off",
            )
        for (form in weakenedForms) {
            assertTrue(
                weakened.containsMatchIn(form),
                "The weakening guard does not recognize `$form`: a build file or workflow could turn " +
                    "verification down in a form the guard misses (CWE-494).",
            )
        }
        assertTrue(
            !weakened.containsMatchIn("dependency-verification=strict"),
            "The weakening guard rejects strict mode, which is the setting every build file should " +
                "carry: point the guard back at lenient and off only.",
        )
    }

    @Test
    fun `no build file or workflow weakens dependency verification`() {
        val files = buildFiles()
        // An empty walk passes the loop vacuously: a regression in `skippedDirs`, the name filter or the repo-root
        // resolution would switch the guard off for every build file while CI stays green.
        assertTrue(files.isNotEmpty(), "buildFiles() discovered no build file, workflow or Dockerfile to scan.")
        for (sentinel in listOf("app/gradle.properties", "app/Dockerfile", ".github/workflows/ci.yml")) {
            assertTrue(
                sentinel in files,
                "buildFiles() no longer discovers `$sentinel`, so the weakening guard no longer scans it.",
            )
        }
        for (path in files) {
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
                    "Regenerate per the Build commands section of app/CLAUDE.md.",
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
