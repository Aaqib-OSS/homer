package homer.app.di

import homer.app.executors.CommandExecutorFactory
import homer.app.executors.DefaultLockManager
import homer.app.executors.DefaultParallelRunsManager
import homer.app.executors.LangGraphExecutor
import homer.app.executors.LockManager
import homer.app.executors.ParallelRunsManager
import homer.app.executors.ProcessDeps
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.executors.RunAgentExecutor
import homer.app.executors.RunnerCmdExecutor
import homer.app.infra.TelemetryService
import homer.app.infra.WorkspaceConfigFactory
import homer.app.models.AppConfig
import homer.app.models.RunnerConfig
import homer.app.runners.RunnerRegistry
import homer.app.runners.kubernetes.KubernetesRunnerStartupSweep
import homer.app.runners.kubernetes.RunnerStateSink
import homer.app.runners.kubernetes.SweepStatus
import homer.app.services.AgentRunStarter
import homer.app.services.AgentSubRunStarter
import homer.app.services.ContinuationService
import homer.app.services.CostMeter
import homer.app.services.LlmTelemetryService
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import homer.app.services.RunMessagesService
import homer.app.services.persistence.ServerMetaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The Execution layer's wiring: workspace factory, lock and slot gates, runner registry and its startup sweep,
 * process and command executors. Split out of [appModule] so each subject's bindings stay in one file; the
 * cycle-breaking lambdas and `applicationScope` carry their own comments (ADR-0023, ADR-0030).
 */
internal val executionModule =
    module {
        single {
            val config = get<AppConfig>()
            WorkspaceConfigFactory(
                workspaceRoot = config.workspaceRootPath,
                globalEnv = config.globalEnv,
                textCompiler = get(),
                parentEnvAllowlist = config.parentEnvAllowlist,
                hostEnvSupplier = { System.getenv() },
                maxOutputBytes = config.server.maxOutputBytes,
                sysEnvBackedGlobalEnvNames = config.sysEnvBackedGlobalEnvNames,
                sysEnvBackedRunnerEnvNames = config.sysEnvBackedRunnerEnvNames,
            )
        }

        single<LockManager> {
            DefaultLockManager(get<ProcessManager>(), get { parametersOf("homer.app.executors.DefaultLockManager") })
        }

        single<ParallelRunsManager> {
            DefaultParallelRunsManager()
        }

        single { SweepStatus() }

        single {
            KubernetesRunnerStartupSweep(
                runners = get<RunnerRegistry>(),
                activeRuns = get<ProcessManager>(),
                status = get(),
            )
        }

        single<RunnerRegistry> {
            val appConfig = get<AppConfig>()
            val stateSink = get<RunnerStateSink>()
            val tracer = get<TelemetryService>().tracer
            // Stable per-instance id scoping the k8s startup sweep; read from server_meta only when a
            // kubernetes runner is configured, safe however early because migrate() runs first. See ADR-0020.
            val k8sInstanceId =
                if (appConfig.runners.values.any { it is RunnerConfig.Kubernetes }) {
                    get<ServerMetaRepository>().getOrCreateInstanceId()
                } else {
                    ""
                }
            buildRunnerRegistry(appConfig, stateSink, tracer, k8sInstanceId)
        }

        single {
            ProcessDeps(
                workspaceConfigFactory = get(),
                executorFactory = get(),
                telemetryService = get<TelemetryService>(),
                lockManager = get(),
                parallelRunsManager = get(),
                config = get(),
                processManager = get<ProcessManager>(),
                runnerRegistry = get(),
                activeSessions = get(),
                questionsService = get<QuestionsService>(),
                questionsWorkspaceSetup = get(),
            )
        }

        single { ProcessFactory(get()) }

        single {
            AgentRunStarter(
                serverId = homer.app.serverId,
                processExecutor = get(),
                processFactory = get(),
                telemetryService = get<TelemetryService>(),
                costMeter = get(),
                maxInvocationDepth = get<AppConfig>().server.maxAgentInvocationDepth,
                cooldownGate = get(),
                maintenanceGate = get(),
                // The parent-liveness read behind the maintenance bypass: a start whose
                // server-derived parent is still `WORKING` is admitted. ADR-0092.
                processReads = get<ProcessManager>(),
            )
        }

        single<CoroutineScope>(named("applicationScope")) {
            // Background coroutines that must drain on shutdown (process jobs, ADR-0089 cost settlements);
            // SupervisorJob keeps one failed child from cancelling its siblings.
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }

        single {
            ProcessExecutor(
                telemetryService = get<TelemetryService>(),
                processManager = get<ProcessManager>(),
                // The stored form's size comes from the manager that writes the row, so the cap
                // and the persisted bytes are one measurement. See ADR-0047 rule 4.
                runInputBytes = get<ProcessManager>(),
                maxInputBytes = get<AppConfig>().server.maxInputBytes,
                questionsService = get<QuestionsService>(),
                runMessages = get<RunMessagesService>(),
                coroutineScope = get<CoroutineScope>(named("applicationScope")),
                logger = get { parametersOf("homer.app.executors.ProcessExecutor") },
                // Resolved at call time, not construction, to break the cycle
                // ContinuationService → AgentRunStarter → ProcessExecutor. See ADR-0023.
                onTerminal = { pid, status -> get<ContinuationService>().onProcessTerminal(pid, status) },
            )
        }

        single {
            RunAgentExecutor(
                subRuns =
                    AgentSubRunStarter(
                        // Lazy: breaks the DI cycle RunAgentExecutor → AgentRunStarter → ProcessFactory →
                        // CommandExecutorFactory → RunAgentExecutor. See ADR-0030.
                        agentRunStarter = { get() },
                        agentsService = get(),
                        authorizer = get(),
                        processManager = get(),
                    ),
                agentInputResolver = get(),
                textCompiler = get(),
            )
        }

        single { LangGraphExecutor(fileOperations = get(), appConfig = get()) }

        single {
            RunnerCmdExecutor(
                runners = get<RunnerRegistry>(),
                activeSessions = get(),
                fileOperations = get(),
            )
        }

        single {
            CommandExecutorFactory(
                get(),
                get<LlmTelemetryService>(),
                get(),
                get<ProcessManager>(),
                get<CostMeter>(),
                settlementScope = get<CoroutineScope>(named("applicationScope")),
                llmCostResolver = get(),
                runAgentExecutor = get<RunAgentExecutor>(),
                langGraphExecutor = get<LangGraphExecutor>(),
                runnerCmdExecutor = get<RunnerCmdExecutor>(),
                requireClaudeCodeVersion = get<AppConfig>().server.requireClaudeCodeVersion,
                claudeDropSystemSubtypes = get<AppConfig>().server.claudeDropSystemSubtypes.toSet(),
            )
        }
    }
