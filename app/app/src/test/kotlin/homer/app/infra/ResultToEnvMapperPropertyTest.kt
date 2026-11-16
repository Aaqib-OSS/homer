package homer.app.infra

import homer.app.fixtures.TestFixtures
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.SrcType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ResultToEnvMapper.mapToEnv]'s env-naming contract over generated parameter lists and captures
 * (ADR-0047 rules 2 and 8): the captured value is attacker-influenceable, so the mapper may only
 * ever write the env names it was handed — never invent, rename or re-target one — and a name it
 * emits is never one the host or Homer owns. The example cases live in `ResultToEnvMapperTest`;
 * these drive the combinations its author did not list (root CLAUDE.md §6).
 *
 * The generated parameters carry the names `AgentInputChecks` admits (its pattern, not a reserved
 * name), which is the precondition production guarantees before a run starts; the last test pins
 * that precondition so a generator drifting into rejected names cannot make the property vacuous.
 */
class ResultToEnvMapperPropertyTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var stdOutFile: Path
    private lateinit var stdErrFile: Path

    @BeforeEach
    fun setup() {
        stdOutFile = tmp.resolve("stdout.txt")
        stdErrFile = tmp.resolve("stderr.txt")
    }

    /** The env-name rule of `AgentInputChecks.checkCommandBindings` for a result capture. */
    private val envNamePattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** Validated names, plus spellings one character away from a reserved one (which stay legal). */
    private val envNames: Arb<String> =
        Arb.bind(
            Arb.element("R", "HMRX_", "PATHX", "LDX", "JAVAX", "LCX", "path"),
            Arb.list(Arb.element('a', 'Z', '0', '_'), 0..6),
        ) { prefix, tail -> prefix + tail.joinToString("") }

    /** Well-formed JsonPath expressions: jayway throws on a malformed one, and load-time validation rejects it. */
    private val jsonPaths: Arb<String> =
        Arb.bind(
            Arb.list(Arb.element("a", "b", "c"), 1..3),
            Arb.element("", "[0]", "[1]"),
        ) { segments, index -> "$." + segments.joinToString(".") + index }

    /** Linear patterns only (no nested quantifier): a shed match would just slow the property down. */
    private val regexes: Arb<ResultTransformer.Regex> =
        Arb.bind(Arb.list(Arb.element("a", "b", "1", "ok", "x y", "\\d+"), 1..3), Arb.boolean()) { parts, grouped ->
            val body = parts.joinToString("|")
            if (grouped) {
                ResultTransformer.Regex(regex = "($body)", group = 1)
            } else {
                ResultTransformer.Regex(regex = body)
            }
        }

    private val transformers: Arb<ResultTransformer> =
        Arb.bind(Arb.element("pass", "jsonPath", "regex"), jsonPaths, regexes) { kind, path, regex ->
            when (kind) {
                "jsonPath" -> ResultTransformer.JsonPath(path)
                "regex" -> regex
                else -> ResultTransformer.Pass
            }
        }

    private val params: Arb<List<ResultEnvParam>> =
        Arb.list(
            Arb.bind(envNames, Arb.element(SrcType.STD_OUT, SrcType.STD_ERR, SrcType.RESULT_CODE), transformers) {
                name,
                src,
                transformer,
                ->
                ResultEnvParam(src = src, envName = name, transformer = transformer)
            },
            0..5,
        )

    /** A capture: a JSON document with an optional tail, so parsing succeeds and fails across the run. */
    private val captures: Arb<String> =
        Arb.bind(
            Arb.bind(Arb.list(Arb.element("a", "b", "c"), 0..3), Arb.list(Arb.element("1", "x", "true"), 0..3)) { keys, values ->
                buildJsonObject { keys.zip(values).forEach { (key, value) -> put(key, value) } }.toString()
            },
            Arb.element("", "not json", "a b c", "42\n"),
        ) { document, tail -> document + tail }

    private data class Capture(
        val exitCode: Int,
        val stdOut: String,
        val stdErr: String,
    )

    private val captures3: Arb<Capture> = Arb.bind(Arb.int(0, 255), captures, captures) { exit, out, err -> Capture(exit, out, err) }

    private fun mapper(capture: Capture): ResultToEnvMapper {
        Files.writeString(stdOutFile, capture.stdOut)
        Files.writeString(stdErrFile, capture.stdErr)
        val result = TestFixtures.mockCommandResult(exitCode = capture.exitCode, stdOut = stdOutFile, stdErr = stdErrFile)
        return ResultToEnvMapper(result)
    }

    @Test
    fun `the generated parameter names are the ones the agent validator admits`() {
        runTest {
            checkAll(200, envNames) { name ->
                assertTrue(envNamePattern.matches(name), "'$name' must match the validator's env-name pattern")
                assertFalse(ReservedEnvNames.isReserved(name), "'$name' must not be a reserved name")
            }
        }
    }

    @Test
    fun `every emitted key is a name the mapper was given and none is reserved`() {
        runTest {
            checkAll(300, params, captures3) { paramList, capture ->
                val env = mapper(capture).mapToEnv(paramList)
                val given = paramList.map { it.envName }.toSet()

                assertTrue(env.keys.all { it in given }, "mapToEnv emitted a key it was not given: ${env.keys - given}")
                val reserved = env.keys.filter { ReservedEnvNames.isReserved(it) }
                assertTrue(reserved.isEmpty(), "mapToEnv emitted a reserved env name: $reserved")
            }
        }
    }

    @Test
    fun `a Pass param returns its captured source verbatim`() {
        runTest {
            checkAll(200, params, captures3) { paramList, capture ->
                val env = mapper(capture).mapToEnv(paramList)
                // A name used by more than one param is overwritten by the later one; skip those.
                val unique = paramList.groupBy { it.envName }.filterValues { it.size == 1 }.keys

                paramList.filter { it.transformer is ResultTransformer.Pass && it.envName in unique }.forEach { param ->
                    val expected =
                        when (param.src) {
                            SrcType.STD_OUT -> capture.stdOut
                            SrcType.STD_ERR -> capture.stdErr
                            SrcType.RESULT_CODE -> capture.exitCode.toString()
                        }
                    assertEquals(expected, env[param.envName], "a Pass param must return its source verbatim")
                }
            }
        }
    }
}
