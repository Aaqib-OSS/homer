package homer.app.runners.kubernetes

import homer.app.runners.ssh.wrapperShells
import java.nio.file.Files
import java.nio.file.Path

/** A [PodProcessControl] whose "pod" answers the `setsid -w` probe locally, recording each probe argv in [probes]. */
internal fun podControl(
    hasSetsidWait: Boolean,
    probes: MutableList<List<String>> = mutableListOf(),
): PodProcessControl =
    PodProcessControl(
        { _, _, argv, _ ->
            synchronized(probes) { probes += argv }
            ProcessBuilder(if (hasSetsidWait) "true" else "false").start()
        },
        "homer",
        "pod-1",
        signalExecSlackMs = 0,
    )

/** A built group launch with no `setsid` prefix, its wrapper run by [shell] instead of the pod's `sh`. */
internal fun underShell(
    argv: List<String>,
    shell: List<String>,
): List<String> {
    require(argv.first() == "sh") { "not a launch without setsid: $argv" }
    return shell + argv.drop(1)
}

/** The POSIX shells on this host a pod's `sh` may be: the ssh wrapper's shells, and BusyBox ash where installed. */
internal fun podShells(): List<List<String>> =
    wrapperShells().map { listOf(it) } +
        listOf("/bin/busybox", "/usr/bin/busybox").filter { Files.isExecutable(Path.of(it)) }.take(1).map { listOf(it, "ash") }
