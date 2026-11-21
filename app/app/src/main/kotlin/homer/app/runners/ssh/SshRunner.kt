package homer.app.runners.ssh

import homer.app.EnvConstants
import homer.app.models.RunnerConfig
import homer.app.models.ShellEnvNames
import homer.app.models.SshAuth
import homer.app.runners.RemotePathTranslator
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.core.CoreModuleProperties
import java.nio.file.Path
import java.time.Duration
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Runner backed by a remote host reached over SSH. `openSession` only connects and authenticates; the
 * [SshRunnerSession] primitives, driven by `AgentExecutor`'s sync policy, do the rest (ADR-0033).
 */
class SshRunner(
    override val alias: String,
    private val config: RunnerConfig.Ssh,
    private val client: SshClient,
    private val sessionFactory: (RunnerConfig.Ssh) -> ClientSession = { defaultConnect(client, it) },
    override val sysEnvBackedEnvNames: Set<String> = emptySet(),
) : Runner {
    override val isRemote: Boolean = true

    override val runnerEnv: Map<String, String> = config.env

    override suspend fun openSession(params: RunnerSessionParams): RunnerSession =
        withContext(Dispatchers.IO) {
            // Before any remote effect: every step sources the env from a shell file (ADR-0032).
            val invalid = ShellEnvNames.firstInvalid(params.env.keys + params.resultEnvNames)
            require(invalid == null) { "env name '$invalid' must be a shell identifier (${ShellEnvNames.PATTERN}) on ssh runner '$alias'" }
            val ws = params.workspace
            val relative = ws.serverRoot.relativize(ws.projectRoot).toString()
            val remoteProjectRoot = "${config.remoteWorkspaceRoot.trimEnd('/')}/$relative"
            val remoteAgentDir = "$remoteProjectRoot/.homer/agent"

            val session = sessionFactory(config)
            try {
                val translator =
                    RemotePathTranslator(
                        serverProjectRoot = ws.projectRoot.toString(),
                        remoteProjectRoot = remoteProjectRoot,
                        remoteAgentDir = remoteAgentDir,
                        apiUrl = config.homerApiUrl,
                    )
                // Pure string computation — safe before createWorkspace() runs.
                val remoteWorkdir = translator.overrides(params.env)[EnvConstants.WORK_DIR] ?: "$remoteProjectRoot/workdir"

                // Probe once whether the target can reap process groups (`setsid -w`,
                // util-linux). Where absent, fall back to close(true)-only teardown.
                val reapCapable = canReapProcessGroup(session)
                val reapDir = if (reapCapable) "$remoteProjectRoot/.homer/reap" else null
                val sync = WorkspaceSync(session, config.sftpTransferTimeoutSeconds.seconds)

                SshRunnerSession(
                    session = session,
                    shell =
                        SshShellExecutor(
                            session = session,
                            remoteWorkdir = remoteWorkdir,
                            sync = sync,
                            envDir = "$remoteProjectRoot/.homer/env",
                            maxOutputBytes = ws.maxOutputBytes,
                            reapProcessGroup = reapCapable,
                            reapDir = reapDir,
                        ),
                    sync = sync,
                    remoteWorkspaceRoot = config.remoteWorkspaceRoot,
                    remoteProjectRoot = remoteProjectRoot,
                    remoteWorkdir = remoteWorkdir,
                    translator = translator,
                    initialEnv = params.env,
                    syncExcludes = config.syncExcludes,
                    runnerAlias = alias,
                    extraUploads = config.extraUploads,
                )
            } catch (e: Throwable) {
                runCatching { session.close() }
                throw e
            }
        }

    /**
     * Whether the target runs `setsid -w` (util-linux), which [RemoteCommandBuilder.buildReaping] needs. Any
     * failure reads as `false`, so reaping is enabled only where it is known to work (ADR-0041).
     */
    private fun canReapProcessGroup(session: ClientSession): Boolean =
        runCatching {
            val ch = session.createExecChannel("command -v setsid >/dev/null 2>&1 && setsid -w true >/dev/null 2>&1")
            ch.use {
                it.open().verify(PROBE_TIMEOUT_MS)
                it.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), PROBE_TIMEOUT_MS)
                it.exitStatus == 0
            }
        }.getOrDefault(false)

    companion object {
        private const val PROBE_TIMEOUT_MS = 10_000L

        internal fun defaultConnect(
            client: SshClient,
            cfg: RunnerConfig.Ssh,
        ): ClientSession {
            client.serverKeyVerifier = sshServerKeyVerifier(cfg)
            val session =
                client
                    .connect(cfg.user, cfg.host, cfg.port)
                    .verify(cfg.connectTimeoutSeconds, TimeUnit.SECONDS)
                    .session
            when (val auth = cfg.auth) {
                is SshAuth.KeyFile -> {
                    SshKeyLoading.addKeyIdentity(session, auth.privateKeyPath, auth.passphrase)
                }

                is SshAuth.Agent -> {
                    // No identity is registered: auth relies on MINA's own ssh-agent discovery, unverified (ADR-0032).
                }
            }
            session.auth().verify(cfg.connectTimeoutSeconds, TimeUnit.SECONDS)
            return session
        }
    }
}

/**
 * The host-key check for [cfg]: `known_hosts` only (unknown hosts rejected) under
 * `strictHostKeyChecking`, which then requires `knownHostsPath`; otherwise any key.
 */
internal fun sshServerKeyVerifier(cfg: RunnerConfig.Ssh): ServerKeyVerifier =
    if (cfg.strictHostKeyChecking) {
        KnownHostsServerKeyVerifier(
            RejectAllServerKeyVerifier.INSTANCE,
            cfg.knownHostsPath?.let { Path.of(it) }
                ?: error("strictHostKeyChecking=true requires knownHostsPath (ssh runner host=${cfg.host})"),
        )
    } else {
        AcceptAllServerKeyVerifier.INSTANCE
    }

/** Apply the transport timing from [config] to [client]. See SERVER.md, "ssh runner". */
internal fun applySshClientTiming(
    client: SshClient,
    config: RunnerConfig.Ssh,
) {
    CoreModuleProperties.IDLE_TIMEOUT.set(
        client,
        config.idleTimeoutSeconds?.let { Duration.ofSeconds(it) } ?: Duration.ZERO,
    )
    // Zero means no heartbeats, i.e. heartbeatIntervalSeconds = null = disabled.
    CoreModuleProperties.HEARTBEAT_INTERVAL.set(
        client,
        config.heartbeatIntervalSeconds?.let { Duration.ofSeconds(it) } ?: Duration.ZERO,
    )
    // Disconnect a dead/stalling peer after KEEPALIVE_NO_REPLY_MAX unanswered
    // heartbeats — independent of the idle timeout (which is 0 by default). See SERVER.md.
    CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(client, KEEPALIVE_NO_REPLY_MAX)
}

/** Consecutive unanswered keepalives before the client disconnects the session. See SERVER.md. */
internal const val KEEPALIVE_NO_REPLY_MAX: Int = 3
