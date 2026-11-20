package homer.app.runners.kubernetes

import org.slf4j.LoggerFactory
import io.kubernetes.client.Exec as UpstreamExec

/**
 * Production implementation of KubernetesExecClient that delegates to
 * the upstream io.kubernetes.client.Exec helper. The seam interface
 * keeps KubernetesShellExecutor unit-testable without a real cluster.
 */
class KubernetesApiExecClient(
    private val upstream: UpstreamExec,
) : KubernetesExecClient {
    override fun start(
        namespace: String,
        podName: String,
        argv: List<String>,
        stdin: Boolean,
    ): Process =
        upstream
            .newExecutionBuilder(namespace, podName, argv.toTypedArray())
            .setStdin(stdin)
            .setTty(false)
            // The client's default prints the stack trace, whose text can quote the exec URL (ADR-0047 rule 7).
            .setOnUnhandledError { logger.warn("Exec channel to pod {} failed: {}", podName, unhandledErrorLine(argv, it)) }
            .execute()

    private companion object {
        private val logger = LoggerFactory.getLogger(KubernetesApiExecClient::class.java)
    }
}

/** [error]'s class and one-line message, without the exec URL and with the argv's leading `env K=V…` values masked. */
internal fun unhandledErrorLine(
    argv: List<String>,
    error: Throwable,
): String {
    val assignments = if (argv.firstOrNull() == "env") argv.drop(1).takeWhile { '=' in it } else emptyList()
    val env = assignments.withIndex().associate { (i, assignment) -> "$i" to assignment.substringAfter('=') }
    val message = error.message.orEmpty().replace(EXEC_URL, "[exec URL]")
    return execUrlRedactor(env).redact("${error.javaClass.simpleName}: $message").toOneLine()
}

private val EXEC_URL = Regex("""\S*[?&]command=\S*""")
