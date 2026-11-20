package homer.app.runners.kubernetes

import homer.app.EnvConstants
import homer.app.infra.KillCause
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/** Owns the pod's process control: the `setsid` launch wrapper, pgid file, probe, and interrupt-grace/pod-wide kill paths. */
internal class PodProcessControl(
    private val execClient: KubernetesExecClient,
    private val namespace: String,
    private val podName: String,
    /** Slack added to the grace for the signalling exec's own bound. */
    private val signalExecSlackMs: Long,
) {
    private val pgidCounter = AtomicLong(0)

    /** The pod's `setsid` argv prefix, or null where it has none. Probed only when a grace is used. */
    private val setsidPrefix: List<String>? by lazy { probeSetsid() }

    /**
     * The pod-side file this command's group leader records its pid (its pgid) in, under `HMR_WORK_DIR` or else a
     * relative `.homer`; null without [interruptGrace] or a pod `setsid`, and the launch is then unchanged (ADR-0108).
     */
    fun pgidFileFor(
        env: Map<String, String>,
        interruptGrace: Duration?,
    ): String? {
        if (interruptGrace == null || setsidPrefix == null) return null
        val dir = env[EnvConstants.WORK_DIR]?.takeIf { it.isNotBlank() } ?: FALLBACK_PGID_DIR
        return "$dir/homer-exec-${pgidCounter.incrementAndGet()}.pgid"
    }

    /**
     * [command] wrapped to lead its own process group: under `setsid` an inner `sh` records its pid, the group id, to `$0`
     * and `exec`s the argv, so the command keeps its pid, exit code, streams and stdin (ADR-0041).
     */
    fun setsidLaunch(
        pgidFile: String,
        command: List<String>,
    ): List<String> = setsidPrefix.orEmpty() + listOf("sh", "-c", PGID_SCRIPT, pgidFile) + command

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
     * The pod's `setsid` prefix, or null where it has none. util-linux's needs `-w` to wait for a forked child, or the exec
     * channel could close before the command ends; BusyBox's takes no `-w` and waits itself.
     */
    private fun probeSetsid(): List<String>? = SETSID_FORMS.firstOrNull { probeRuns(it) }

    private fun probeRuns(prefix: List<String>): Boolean {
        val probe = runCatching { execClient.start(namespace, podName, prefix + "true", stdin = false) }.getOrNull()
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

        /** `setsid` forms to probe, most preferred first. */
        private val SETSID_FORMS = listOf(listOf("setsid", "-w"), listOf("setsid"))

        /** $0 is the pgid file, $@ the argv: record the group leader's pid, then exec the argv in place. */
        private const val PGID_SCRIPT =
            "p=\"\$0\"; d=\"\${p%/*}\"; mkdir -p \"\$d\" 2>/dev/null; echo \$\$ > \"\$p\" 2>/dev/null; exec \"\$@\""

        private val logger = LoggerFactory.getLogger(PodProcessControl::class.java)
    }
}
