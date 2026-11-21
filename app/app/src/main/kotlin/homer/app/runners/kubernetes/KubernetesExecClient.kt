package homer.app.runners.kubernetes

import homer.app.infra.SecretRedactor
import homer.app.models.MIN_SCREENED_SECRET_LENGTH
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Whether an exec exit value means no exit status arrived: client-java reports that as a negative value (ADR-0103). */
internal fun isNoExitStatus(exitValue: Int): Boolean = exitValue < 0

/**
 * Seam over io.kubernetes.client.Exec, so KubernetesShellExecutor is testable without a cluster. The returned [Process]'s
 * streams are wired to the pod over the exec websocket, as the upstream client's are.
 */
fun interface KubernetesExecClient {
    fun start(
        namespace: String,
        podName: String,
        argv: List<String>,
        stdin: Boolean,
    ): Process
}

/**
 * Masks every credential-length value of [env], raw and query-encoded, in text that may quote it. The exec URL no
 * longer carries env values, so this is defence in depth (ADR-0047 rule 7).
 */
internal fun execUrlRedactor(env: Map<String, String>): SecretRedactor =
    SecretRedactor.from(env, env.keys, minNeedleLength = MIN_SCREENED_SECRET_LENGTH) +
        SecretRedactor.from(
            env.mapValues { (_, v) -> URLEncoder.encode(v, StandardCharsets.UTF_8) },
            env.keys,
            minNeedleLength = MIN_SCREENED_SECRET_LENGTH,
        )

/** [text] with each exec URL replaced by `[exec URL]`: the URL carries the argv, env values of any length included. */
internal fun withoutExecUrl(text: String): String = text.replace(EXEC_URL, "[exec URL]")

private val EXEC_URL = Regex("""\S*[?&]command=\S*""")
