package homer.app.runners.ssh

import java.nio.file.Files
import java.nio.file.Path

/** The `setsid -w` launcher [RemoteCommandBuilder.buildReaping] puts in front of its wrapper `sh -c`. */
private const val SETSID_LAUNCHER = "exec setsid -w sh -c "

/** The same one-shot probe SshRunner gates the reaping form on: `setsid -w` must be util-linux's. */
internal fun setsidCanFormGroup(): Boolean =
    runCatching {
        val process = ProcessBuilder("setsid", "-w", "true").start()
        process.inputStream.readBytes()
        process.errorStream.readBytes()
        process.waitFor() == 0
    }.getOrDefault(false)

/**
 * The argv that runs a built reaping [command] as sshd does: the login shell leads its own session, so the
 * `exec setsid -w` it reaches is a group leader, forks the wrapper and waits for it. Needs [setsidCanFormGroup].
 */
internal fun underLoginSession(command: String): List<String> = listOf("setsid", "/bin/sh", "-c", command)

/**
 * The argv that runs a built reaping [command]'s wrapper script under [shell] without `setsid`, on any host:
 * the process started is the wrapper itself, so its exit code is the wrapper's own.
 */
internal fun wrapperAlone(
    command: String,
    shell: String = "/bin/sh",
): List<String> {
    require(SETSID_LAUNCHER in command) { "not a reaping command: $command" }
    return listOf("/bin/sh", "-c", command.replace(SETSID_LAUNCHER, "exec $shell -c "))
}

/** The POSIX shells on this host the wrapper must behave the same under, one path per distinct binary. */
internal fun wrapperShells(): List<String> =
    listOf("/bin/sh", "/bin/dash", "/usr/bin/dash", "/bin/bash", "/opt/homebrew/bin/bash")
        .filter { Files.isExecutable(Path.of(it)) }
        .distinctBy { Path.of(it).toRealPath() }
