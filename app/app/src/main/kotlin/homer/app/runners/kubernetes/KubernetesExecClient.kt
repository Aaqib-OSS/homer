package homer.app.runners.kubernetes

import homer.app.infra.SecretRedactor
import homer.app.models.MIN_SCREENED_SECRET_LENGTH
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** client-java's exec exit value after a websocket failure; a close with no usable `v1.Status` leaves `-1` instead. */
internal const val EXEC_NO_EXIT_STATUS_SENTINEL = -1975219

/** Whether an exec exit value means no exit status arrived: client-java reports that as a negative value (ADR-0103). */
internal fun isNoExitStatus(exitValue: Int): Boolean = exitValue < 0

/**
 * Seam over io.kubernetes.client.Exec so KubernetesShellExecutor can
 * be unit-tested without a real cluster. Returning java.lang.Process
 * matches what the upstream client produces — its stdout/stderr/stdin
 * streams are wired to the remote pod via a websocket.
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
 * Masks every credential-length value of [env] raw and in the query-encoded form the exec URL gives it, since
 * that URL carries the argv (ADR-0047 rule 7).
 */
internal fun execUrlRedactor(env: Map<String, String>): SecretRedactor =
    SecretRedactor.from(env, env.keys, minNeedleLength = MIN_SCREENED_SECRET_LENGTH) +
        SecretRedactor.from(
            env.mapValues { (_, v) -> URLEncoder.encode(v, StandardCharsets.UTF_8) },
            env.keys,
            minNeedleLength = MIN_SCREENED_SECRET_LENGTH,
        )
