package homer.app.infra

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.ServerSocket
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `.homer/bin` helpers keep tokens and request bodies off curl's argv (CWE-214): a stub `curl`
 * records argv, the `-K` config and the stdin body, so each case checks where every value went.
 */
class HomerHelperScriptsArgvTest {
    /** One curl call site: [helper] run with [args], [stdin] and [env] must request [url] with [body]. */
    data class Case(
        val helper: String,
        val args: List<String>,
        val stdin: String,
        val response: String,
        val url: String,
        val body: String?,
        val env: Map<String, String> = emptyMap(),
    ) {
        override fun toString() = "$helper ${args.firstOrNull().orEmpty()}"
    }

    @TempDir
    lateinit var dir: Path

    private data class Run(
        val exit: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun script(helper: String): Path =
        Paths.get(
            checkNotNull(javaClass.classLoader.getResource("scripts/$helper.sh")) {
                "scripts/$helper.sh missing from resources"
            }.toURI(),
        )

    private fun writeStubCurl() {
        val stub = dir.resolve("curl")
        stub.writeText(
            """
            #!/bin/sh
            echo call >> "${'$'}STUB_DIR/calls"
            printf '%s\n' "${'$'}@" > "${'$'}STUB_DIR/argv"
            prev=""
            out=/dev/stdout
            for a in "${'$'}@"; do
                [ "${'$'}prev" = "-K" ] && cat "${'$'}a" > "${'$'}STUB_DIR/config"
                [ "${'$'}prev" = "--data-binary" ] && [ "${'$'}a" = "@-" ] && cat > "${'$'}STUB_DIR/body"
                [ "${'$'}prev" = "-o" ] && out="${'$'}a"
                prev="${'$'}a"
            done
            printf '%s' "${'$'}STUB_RESPONSE" > "${'$'}out"
            """.trimIndent() + "\n",
        )
        stub.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    /** Runs [helper]; stdin stays open unless [stdin] is given, so a helper that read it would hang. */
    private fun run(
        helper: String,
        args: List<String>,
        stdin: String? = null,
        response: String = "",
        env: Map<String, String> = emptyMap(),
        apiUrl: String = "http://homer.test",
        stubCurl: Boolean = true,
    ): Run {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        assumeTrue(Paths.get("/bin/sh").exists(), "POSIX sh required")
        if (stubCurl) writeStubCurl()
        val pb = ProcessBuilder(listOf("/bin/sh", script(helper).toString()) + args)
        pb.environment().clear()
        pb.environment()["PATH"] = "$dir:/usr/bin:/bin"
        pb.environment()["STUB_DIR"] = dir.toString()
        pb.environment()["STUB_RESPONSE"] = response
        pb.environment()["HMR_API_URL"] = apiUrl
        pb.environment()["HMR_CALLBACK_TOKEN"] = CALLBACK_TOKEN
        pb.environment().putAll(env)
        val process = pb.start()
        if (stdin != null) process.outputStream.use { it.write(stdin.toByteArray()) }
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        process.outputStream.close()
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "$helper must not hang")
        return Run(
            process.exitValue(),
            process.inputStream.readBytes().decodeToString(),
            process.errorStream.readBytes().decodeToString(),
        )
    }

    private fun recorded(name: String): String? = dir.resolve(name).takeIf { it.exists() }?.readText()

    private fun curlCalls(): Int =
        dir
            .resolve("calls")
            .takeIf { it.exists() }
            ?.readLines()
            ?.size ?: 0

    @ParameterizedTest
    @MethodSource("cases")
    fun `the helper sends the token, URL and body to curl outside argv`(case: Case) {
        val result = run(case.helper, case.args, case.stdin.ifEmpty { null }, case.response, case.env)

        assertEquals(0, result.exit, "stderr=${result.stderr}")
        val argv = checkNotNull(recorded("argv")) { "curl was not called" }
        for (secret in listOfNotNull(TOKEN_MARKER, RESULT_TOKEN, PARAM_SECRET, case.body?.takeIf { it.isNotEmpty() })) {
            assertFalse(secret in argv, "curl argv must not carry '$secret': $argv")
        }
        assertTrue("--globoff\n" in argv, "curl must not glob the URL: $argv")
        assertEquals(
            "url = \"${case.url}\"\nheader = \"Authorization: Bearer $ESCAPED_CALLBACK_TOKEN\"\n",
            recorded("config"),
        )
        assertEquals(case.body, recorded("body"))
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `a callback token with a line break is refused before curl runs`(case: Case) {
        val result =
            run(
                case.helper,
                case.args,
                case.stdin.ifEmpty { null },
                case.response,
                case.env + ("HMR_CALLBACK_TOKEN" to "tok\noutput = \"/tmp/x\""),
            )

        assertEquals(2, result.exit, "stderr=${result.stderr}")
        assertTrue("contains a control character" in result.stderr, result.stderr)
        assertEquals(0, curlCalls(), "a refused config must not reach curl")
    }

    @Test
    fun `a result token with a line break is refused before curl runs`() {
        val result = run("homer-result", emptyList(), env = mapOf("HMR_RESULT_TOKEN" to "rt\noutput = \"/tmp/x\""))

        assertEquals(2, result.exit, "stderr=${result.stderr}")
        assertTrue("contains a control character" in result.stderr, result.stderr)
        assertEquals(0, curlCalls(), "a refused config must not reach curl")
    }

    @Test
    fun `a question id with a carriage return is refused before curl runs`() {
        val result = run("homer-ask", listOf("check", "q\r1"))

        assertEquals(2, result.exit, "stderr=${result.stderr}")
        assertTrue("contains a control character" in result.stderr, result.stderr)
        assertEquals(0, curlCalls(), "a refused config must not reach curl")
    }

    @Test
    fun `ask-and-wait refuses a control character in the returned id once, without polling`() {
        val result = run("homer-ask", listOf("ask-and-wait", "Ship it?"), response = """{"id":"q\u000d1"}""")

        assertEquals(2, result.exit, "stderr=${result.stderr}")
        assertTrue("the question id contains a control character" in result.stderr, result.stderr)
        assertEquals(1, curlCalls(), "only the ask reaches curl; the refusal is not retried")
    }

    @Test
    fun `homer-result --out writes the body to the file with the token off argv`() {
        val out = dir.resolve("r.txt")
        val result =
            run(
                "homer-result",
                listOf("--out", out.toString()),
                response = "the result",
                env = mapOf("HMR_RESULT_TOKEN" to RESULT_TOKEN),
            )

        assertEquals(0, result.exit, "stderr=${result.stderr}")
        assertEquals("", result.stdout)
        assertEquals("the result", out.readText())
        val argv = checkNotNull(recorded("argv")) { "curl was not called" }
        assertTrue("-o\n$out\n" in argv, "curl must write to the --out file: $argv")
        assertFalse(RESULT_TOKEN in argv || TOKEN_MARKER in argv, "curl argv must not carry a token: $argv")
        assertEquals(
            "url = \"$API/results/$RESULT_TOKEN\"\nheader = \"Authorization: Bearer $ESCAPED_CALLBACK_TOKEN\"\n",
            recorded("config"),
        )
    }

    @Test
    fun `homer-result still accepts the deprecated --token flag and warns on stderr`() {
        val result = run("homer-result", listOf("--token", RESULT_TOKEN), response = "the result")

        assertEquals(0, result.exit, "stderr=${result.stderr}")
        assertEquals("the result", result.stdout)
        assertTrue("--token is deprecated" in result.stderr, result.stderr)
        assertTrue("url = \"$API/results/$RESULT_TOKEN\"" in recorded("config").orEmpty())
    }

    @Test
    fun `homer-result without HMR_RESULT_TOKEN exits 2 at once, without reading stdin`() {
        val result = run("homer-result", listOf("--out", "r.txt"))

        assertEquals(2, result.exit)
        assertTrue("HMR_RESULT_TOKEN is not set" in result.stderr, result.stderr)
        assertEquals(0, curlCalls(), "no token means no request")
    }

    @Test
    fun `every helper carries the same homer_curl`() {
        val blocks =
            HELPERS.associateWith { helper ->
                val text = script(helper).readText()
                checkNotNull(Regex("""(?ms)^homer_curl\(\) \{\n.*?^}\n""").find(text)) {
                    "$helper.sh has no homer_curl() block"
                }.value
            }

        assertEquals(1, blocks.values.toSet().size, "homer_curl differs between helpers: ${blocks.keys}")
    }

    @Test
    fun `real curl reads the fd 3 config and requests a braced id once, unglobbed`() {
        assumeTrue(Paths.get("/usr/bin/curl").exists(), "curl required")
        val requests = Collections.synchronizedList(mutableListOf<List<String>>())
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        requests += generateSequence { reader.readLine() }.takeWhile { line -> line.isNotEmpty() }.toList()
                        val body = """{"status":"ANSWERED","answer":"ok"}"""
                        it.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                        )
                    }
                }
            }

            val result = run("homer-ask", listOf("check", "{a,b}"), apiUrl = "http://127.0.0.1:${server.localPort}", stubCurl = false)

            assertEquals(0, result.exit, "stderr=${result.stderr}")
            assertEquals("ok\n", result.stdout)
        }
        val request = requests.single()
        assertEquals("GET /api/v1/callback/questions/{a,b}?waitSec=0 HTTP/1.1", request.first())
        assertTrue("Authorization: Bearer $CALLBACK_TOKEN" in request, "request headers: $request")
    }

    companion object {
        private const val API = "http://homer.test/api/v1/callback"
        private const val TOKEN_MARKER = "cb-secret-343"

        // `"` and `\` must reach curl escaped per its config syntax.
        private const val CALLBACK_TOKEN = "$TOKEN_MARKER\"q\\b"
        private const val ESCAPED_CALLBACK_TOKEN = "$TOKEN_MARKER\\\"q\\\\b"
        private const val RESULT_TOKEN = "rt-secret-343"
        private const val PARAM_SECRET = "param-secret-343"
        private val HELPERS = listOf("homer-ask", "homer-dispatch", "homer-inbox", "homer-join", "homer-result", "homer-tag")

        @JvmStatic
        fun cases(): List<Case> =
            listOf(
                Case(
                    "homer-ask",
                    listOf("ask", "Ship it?"),
                    "",
                    """{"id":"q1"}""",
                    "$API/questions",
                    """{"text":"Ship it?","options":[]}""",
                ),
                Case(
                    "homer-ask",
                    listOf("check", "q1"),
                    "",
                    """{"status":"ANSWERED","answer":"yes"}""",
                    "$API/questions/q1?waitSec=0",
                    null,
                ),
                Case(
                    "homer-ask",
                    listOf("ask-and-dispatch", "Ship it?", "--agent", "handler", "--param", "key=$PARAM_SECRET"),
                    "",
                    """{"dispatchId":1,"questionId":"q1"}""",
                    "$API/dispatches",
                    """{"text":"Ship it?","options":[],"agent":"handler","answerParam":"answer","params":{"key":"$PARAM_SECRET"}}""",
                ),
                Case(
                    "homer-ask",
                    listOf("ask-and-dispatch-group", "--question", "Ship it?", "--agent", "handler", "--param", "key=$PARAM_SECRET"),
                    "",
                    """{"dispatchId":1,"questionIds":["q1"]}""",
                    "$API/dispatch-groups",
                    """{"questions":[{"text":"Ship it?","options":[]}],"agent":"handler","answerParam":"answer",""" +
                        """"params":{"key":"$PARAM_SECRET"}}""",
                ),
                Case(
                    "homer-dispatch",
                    listOf("--agent", "worker", "--param", "key=$PARAM_SECRET"),
                    "",
                    """{"processId":5}""",
                    "$API/runs",
                    """{"agent":"worker","params":{"key":"$PARAM_SECRET"}}""",
                ),
                Case(
                    "homer-join",
                    emptyList(),
                    """{"subs":[{"agent":"worker","params":{"key":"$PARAM_SECRET"}}]}""",
                    """{"processIds":[5]}""",
                    "$API/joins",
                    """{"subs":[{"agent":"worker","params":{"key":"$PARAM_SECRET"}}]}""",
                ),
                Case("homer-inbox", emptyList(), "", "{\"messages\":[]}\n200", "$API/messages/take", null),
                Case(
                    "homer-result",
                    emptyList(),
                    "",
                    "the result",
                    "$API/results/$RESULT_TOKEN",
                    null,
                    env = mapOf("HMR_RESULT_TOKEN" to RESULT_TOKEN),
                ),
                Case("homer-tag", listOf("k:v"), "", "{}\n200", "$API/tags", """{"tag":"k:v"}"""),
            )
    }
}
