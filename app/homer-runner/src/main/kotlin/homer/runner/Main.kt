package homer.runner

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import homer.app.infra.Dumpability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

// Lazy, so this file's class initialisation touches no logging backend before the JVM is hardened.
private val logger by lazy { LoggerFactory.getLogger("homer.runner") }

/**
 * `homer-runner`: registers this machine as a pull-mode runner instance and executes the runs it
 * claims. The token comes only from `HOMER_RUNNER_TOKEN`, never a flag, keeping it out of argv. CWE-522.
 */
class HomerRunnerCli(
    // Seams for tests (a test run must not leave a drain hook on its own JVM). Both are
    // declared before `harden` so a trailing lambda still binds to `harden`.
    private val addShutdownHook: (Thread) -> Unit = Runtime.getRuntime()::addShutdownHook,
    private val healthCheck: (Path) -> Unit = RunnerHealth::check,
    // Seam for tests; production uses Dumpability.harden's own defaults.
    private val harden: () -> Dumpability.Result = Dumpability::harden,
) : CliktCommand(name = "homer-runner") {
    private val hardening: Dumpability.Result

    init {
        // Runs as this command is constructed, before Clikt parses the command line or reads any
        // environment variable: the token is in this process's environment from `execve` onwards. CWE-526, ADR-0078.
        hardening = harden()
    }

    override fun help(context: Context) =
        """
        Homer external runner — claim runs from a Homer server and execute them here.

        homer-runner --url https://homer.internal --tags linux,gpu --concurrency 2 --root /var/homer-runner
        (token via HOMER_RUNNER_TOKEN)
        """.trimIndent()

    val url by option("--url", envvar = "HOMER_RUNNER_URL", help = "Homer base URL").required()
    val tags by option("--tags", envvar = "HOMER_RUNNER_TAGS", help = "Comma-separated tags this instance serves")
        .convert { value -> value.split(',').map(String::trim).filter(String::isNotEmpty) }
        .default(emptyList())
    val concurrency by option("--concurrency", envvar = "HOMER_RUNNER_CONCURRENCY", help = "Runs at once").int().default(1)
    val root by option(
        "--root",
        envvar = "HOMER_RUNNER_ROOT",
        help = "Directory for per-run workspaces",
    ).path(canBeFile = false).default(Path.of("/var/homer-runner"))
    val name by option("--name", envvar = "HOMER_RUNNER_NAME", help = "Instance name shown in Homer (default: hostname)")
    val drainTimeout by option(
        "--drain-timeout",
        envvar = "HOMER_RUNNER_DRAIN_TIMEOUT",
        help = "Seconds to finish in-flight runs on SIGTERM or a failed health check",
    ).long().default(600)

    override fun run() {
        // Fail closed before the token read; the hardening itself ran at construction.
        hardenOrRefuse()
        val token =
            currentContext.readEnvvar("HOMER_RUNNER_TOKEN")?.takeIf { it.isNotBlank() }
                ?: throw UsageError("set the runner domain token in HOMER_RUNNER_TOKEN; it is not accepted as a flag")
        val client =
            try {
                HomerClient(url, token)
            } catch (e: IllegalArgumentException) {
                echo(e.message, err = true)
                throw ProgramResult(2)
            }
        val options = RunnerOptions(name ?: hostName(), tags, concurrency, root.toAbsolutePath(), drainTimeout.seconds)
        val agent = RunnerAgent(client, options, healthCheck = healthCheck)
        addShutdownHook(
            Thread {
                runBlocking { agent.drain() }
            }.apply { name = "homer-runner-drain" },
        )
        val code =
            try {
                runBlocking(Dispatchers.Default) { agent.run() }
                0
            } catch (e: RegistrationRejectedException) {
                echo(e.message, err = true)
                2
            } catch (e: RunnerUnhealthyException) {
                echo("homer-runner cannot work on this host: ${e.message}", err = true)
                3
            } catch (e: Exception) {
                echo("homer-runner failed: ${e.message}", err = true)
                1
            } finally {
                client.close()
            }
        throw ProgramResult(code)
    }

    /** Reports the hardening decided at construction; a failed attempt refuses to start. */
    private fun hardenOrRefuse() {
        when (val result = hardening) {
            is Dumpability.Result.Hardened -> logger.info("homer-runner JVM is non-dumpable")
            is Dumpability.Result.Skipped -> logger.warn("homer-runner JVM left dumpable: {}", result.reason)
            is Dumpability.Result.Failed -> throw CliktError(hardeningRefusal(result))
        }
    }
}

/** The message a failed hardening refuses the runner with, at either gate. */
private fun hardeningRefusal(result: Dumpability.Result.Failed) = "homer-runner refuses to start: ${result.reason}"

/**
 * Default instance name: this host's name shaped to Homer's `^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$`, so an
 * untyped default never blocks startup. An explicit `--name` passes through for the server to validate.
 */
internal fun hostName(): String =
    runCatching { InetAddress.getLocalHost().hostName }
        .getOrElse { "homer-runner" }
        .let(::sanitizeInstanceName)

internal fun sanitizeInstanceName(raw: String): String {
    fun Char.isAllowedStart() = code < 128 && isLetterOrDigit()
    val mapped = raw.map { if (it.isAllowedStart() || it in "_.-") it else '-' }.joinToString("")
    val trimmed = mapped.dropWhile { !it.isAllowedStart() }.take(64)
    return trimmed.ifEmpty { "homer-runner" }
}

/**
 * The runner's entry point: hardens the JVM before Clikt parses the command line or reads any
 * environment variable, because the token is in this process's environment from `execve` onwards.
 * CWE-526, ADR-0078.
 */
internal fun runnerMain(
    args: Array<String>,
    harden: () -> Dumpability.Result = Dumpability::harden,
    // Seam for tests: they drive the parse, so an assertion never reaches Clikt's `exitProcess`.
    execute: (HomerRunnerCli, Array<String>) -> Unit = { cli, argv -> cli.main(argv) },
) {
    val hardening = harden()
    if (hardening is Dumpability.Result.Failed) {
        // Fail closed before Clikt parses: `--help` and a usage error end the process before
        // `run()`'s own gate, so the refusal must happen here, before the command exists. CWE-526, ADR-0078.
        throw CliktError(hardeningRefusal(hardening))
    }
    val cli = HomerRunnerCli(harden = { hardening })
    execute(cli, args)
}

fun main(args: Array<String>) = runnerMain(args)
