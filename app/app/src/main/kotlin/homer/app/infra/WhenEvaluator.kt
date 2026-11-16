package homer.app.infra

import homer.app.models.When
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

/**
 * Evaluates a [When] predicate by shelling out to `bash -c <expr>` per expression;
 * per-expression timeout is fixed at 5 s.
 * Exit codes: 0 → true, 1 → false, ≥2 → eval error ([Result.EvalError]; the caller
 * fails the agent — a silent skip would mask predicate bugs). A timeout also yields
 * [Result.EvalError] with exit code `-1`; a genuine caller cancellation is re-thrown
 * unchanged. [When.Operator.ALL] is AND, [When.Operator.ANY] is OR, short-circuiting.
 */
class WhenEvaluator(
    private val shell: ShellExecutor,
    private val artifactsDir: Path,
) {
    sealed class Result {
        data object Run : Result()

        data class Skip(
            val reason: String,
        ) : Result()

        data class EvalError(
            val expression: String,
            val exitCode: Int,
            val stderrSummary: String,
        ) : Result()
    }

    suspend fun evaluate(
        clause: When?,
        env: Map<String, String>,
        commandIndex: Int,
        // A caller evaluating more than one predicate per command (an LLM step's escalate tiers,
        // ADR-0079) passes its own stem so the artifacts don't overwrite the command's `when` ones.
        artifactStem: String = "when_$commandIndex",
    ): Result {
        if (clause == null) return Result.Run

        clause.expressions.forEachIndexed { i, expr ->
            val outPath = artifactsDir.resolve("${artifactStem}_$i.out")
            val errPath = artifactsDir.resolve("${artifactStem}_$i.err")

            val res =
                try {
                    shell.cmd(
                        command = listOf("bash", "-c", expr),
                        stdOut = outPath,
                        stdErr = errPath,
                        stdIn = null,
                        env = env,
                        timeout = EVAL_TIMEOUT,
                    )
                } catch (e: CancellationException) {
                    // Executors signal their own timeout with this class; a still-active caller
                    // means timeout, an inactive one a real kill (see class KDoc).
                    if (!currentCoroutineContext().isActive) throw e
                    return Result.EvalError(
                        expression = expr,
                        exitCode = -1,
                        stderrSummary = "predicate timed out after $EVAL_TIMEOUT",
                    )
                }

            when (res.exitCode) {
                // 0 = true; ALL: continue to next expression
                0 -> {
                    if (clause.operator == When.Operator.ANY) return Result.Run
                }

                // 1 = false; ANY: continue to next expression
                1 -> {
                    if (clause.operator == When.Operator.ALL) {
                        return Result.Skip("when.all expression #${i + 1} false: $expr")
                    }
                }

                // ≥2 = eval error
                else -> {
                    return Result.EvalError(
                        expression = expr,
                        exitCode = res.exitCode,
                        stderrSummary = readErrSummary(errPath),
                    )
                }
            }
        }

        return when (clause.operator) {
            When.Operator.ALL -> Result.Run

            // all expressions true
            When.Operator.ANY -> Result.Skip("when.any: no expression evaluated true")
        }
    }

    private fun readErrSummary(errPath: Path): String =
        try {
            if (Files.exists(errPath)) {
                val text = errPath.readText()
                val tail = if (text.length <= ERR_TAIL_BYTES) text else text.takeLast(ERR_TAIL_BYTES)
                tail.trim()
            } else {
                ""
            }
        } catch (_: java.io.IOException) {
            ""
        }

    companion object {
        private val EVAL_TIMEOUT = 5.seconds
        private const val ERR_TAIL_BYTES = 200
    }
}
