package homer.app.runners.kubernetes

import homer.app.EnvConstants
import homer.app.infra.KillCause
import homer.app.runners.RemoteShell
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/** Owns the pod's process control: the process-group launch wrapper, pgid file, `setsid -w` probe, and grace/pod-wide kill paths. */
internal class PodProcessControl(
    private val execClient: KubernetesExecClient,
    private val namespace: String,
    private val podName: String,
    /** Slack added to the grace for the signalling exec's own bound. */
    private val signalExecSlackMs: Long,
) {
    private val pgidCounter = AtomicLong(0)

    /** The pod's `setsid -w` argv prefix, or none where it lacks one. Probed only when a grace is used. */
    private val setsidPrefix: List<String> by lazy { if (probeSetsidWait()) SETSID_WAIT else emptyList() }

    /**
     * The pod-side file this command's group leader records its pid (its pgid) in, under `HMR_WORK_DIR` or else a
     * relative `.homer`; null without [interruptGrace], and the launch is then unchanged (ADR-0108).
     */
    fun pgidFileFor(
        env: Map<String, String>,
        interruptGrace: Duration?,
    ): String? {
        if (interruptGrace == null) return null
        val dir = env[EnvConstants.WORK_DIR]?.takeIf { it.isNotBlank() } ?: FALLBACK_PGID_DIR
        return "$dir/homer-exec-${pgidCounter.incrementAndGet()}.pgid"
    }

    /**
     * [command] run in its own process group: a wrapper `sh`, under `setsid -w` where the pod has it, records its pid
     * to `$0` only when it leads its group, sources a non-null [envFile], and waits for the argv in a child, so a signal
     * death exits 128 + n; the command keeps its exit code, streams and stdin (ADR-0041).
     */
    fun groupLaunch(
        pgidFile: String,
        envFile: String?,
        command: List<String>,
    ): List<String> {
        val launch = if (envFile != null) "f=\$1; shift; " + RemoteShell.sourceThenRun("\$f") else "(exec \"\$@\")"
        // `trap :`, never `trap ''`, keeps the wrapper waiting through a group signal without the argv inheriting an ignore.
        val wrapper = "trap : INT TERM HUP; $PGID_RECORD$launch; exit \$?"
        return setsidPrefix + listOf("sh", "-c", wrapper, pgidFile) + listOfNotNull(envFile) + command
    }

    /**
     * Stops the pod's leftover command. Only on its own timeout, with a grace and a recorded pgid, is its group sent SIGINT
     * and SIGKILL after the grace; otherwise, or when that fails, the pod-wide `kill -KILL -1` runs at once (ADR-0108).
     */
    fun killPod(
        interruptGrace: Duration?,
        pgidFile: String?,
        kill: KillCause,
    ) {
        if (interruptGrace != null && pgidFile != null && kill.byTimeout && interruptGroupInPod(pgidFile, interruptGrace)) return
        runCatching { killInsidePod() }
    }

    /**
     * Whether the pod has util-linux's `setsid -w`, which waits for the child it forks. BusyBox's refuses `-w` and does not
     * wait, so such a pod launches without `setsid`: the exec'd `sh` already leads its own group there.
     */
    private fun probeSetsidWait(): Boolean {
        val probe = runCatching { execClient.start(namespace, podName, SETSID_WAIT + "true", stdin = false) }.getOrNull()
        if (probe == null) return false
        return try {
            probe.waitFor(PROBE_WAIT_MS, TimeUnit.MILLISECONDS) && probe.exitValue() == 0
        } catch (e: Exception) {
            logger.warn("Failed to probe setsid in pod {}", podName, e)
            false
        } finally {
            runCatching { probe.destroyForcibly() }
        }
    }

    /**
     * Runs one bounded exec that SIGINTs the process group recorded in [pgidFile], waits out
     * [grace], then SIGKILLs it. True only when a valid pgid was interrupted, so [killPod] falls
     * back to the pod-wide kill otherwise; a hung or failed exec is abandoned at its bound.
     */
    private fun interruptGroupInPod(
        pgidFile: String,
        grace: Duration,
    ): Boolean {
        val proc =
            runCatching {
                execClient.start(
                    namespace,
                    podName,
                    listOf("sh", "-c", interruptScript(grace), SIGNAL_SCRIPT_NAME, pgidFile),
                    stdin = false,
                )
            }.getOrNull() ?: return false
        return try {
            proc.waitFor(grace.inWholeMilliseconds + signalExecSlackMs, TimeUnit.MILLISECONDS) && proc.exitValue() == SIGNAL_OK
        } catch (e: Exception) {
            logger.warn("Failed to interrupt the command's process group in pod {}", podName, e)
            false
        } finally {
            runCatching { proc.destroyForcibly() }
        }
    }

    /**
     * The signalling exec's script: refuse the pgid file's value unless [PGID_GUARD] passes (ADR-0047 rule 2), SIGINT the
     * group, wait out the grace, SIGKILL it. A refusal or a failed kill exits non-zero, so [killPod] falls back pod-wide.
     */
    private fun interruptScript(grace: Duration): String =
        "p=\$(cat \"\$1\" 2>/dev/null); ${KubernetesShellExecutor.PGID_GUARD}; " +
            "kill -INT -\"\$p\" 2>/dev/null || exit ${KubernetesShellExecutor.SIGNAL_REJECTED}; sleep ${grace.inWholeSeconds}; " +
            "kill -KILL -\"\$p\" 2>/dev/null || exit ${KubernetesShellExecutor.SIGNAL_REJECTED}; exit $SIGNAL_OK"

    /** Kills every process in the pod's main container PID namespace. */
    fun killInsidePod() {
        // The sh builtin, as for the group kill: procps `kill` truncates a multi-digit negative pid (ADR-0041).
        val killProc =
            execClient.start(
                namespace,
                podName,
                listOf("sh", "-c", "kill -KILL -1"),
                stdin = false,
            )
        runCatching { killProc.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS) }
        runCatching { killProc.destroyForcibly() }
    }

    companion object {
        private const val KILL_WAIT_MS = 2_000L
        private const val PROBE_WAIT_MS = 5_000L
        private const val SIGNAL_OK = 0
        private const val SIGNAL_SCRIPT_NAME = "homer-signal"
        private const val FALLBACK_PGID_DIR = ".homer"

        private val SETSID_WAIT = listOf("setsid", "-w")

        /**
         * $0 is the pgid file: the wrapper records its pid there only when `/proc` shows it leads its group, before the
         * launch sources or runs anything; `g=` first, so a failed read cannot match an inherited `g`.
         */
        private const val PGID_RECORD =
            "p=\"\$0\"; d=\"\${p%/*}\"; mkdir -p \"\$d\" 2>/dev/null; " +
                "g=; read -r _ _ _ _ g _ 2>/dev/null < /proc/\$\$/stat; [ \"\$g\" = \"\$\$\" ] && echo \$\$ > \"\$p\" 2>/dev/null; "

        private val logger = LoggerFactory.getLogger(PodProcessControl::class.java)
    }
}
