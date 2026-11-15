package homer.app.di

import homer.app.executors.CommandExecutorFactory
import homer.app.executors.DefaultLockManager
import homer.app.executors.DefaultParallelRunsManager
import homer.app.executors.LangGraphExecutor
import homer.app.executors.LockManager
import homer.app.executors.ParallelRunsManager
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
 * The Execution layer's wiring: the workspace factory, the lock and slot gates, the runner
 * registry and its startup sweep, and the process/command executors. Split out of [appModule] so
 * each subject's bindings stay in one file (root CLAUDE.md §2); the boot loads it beside the
 * other subject modules. The load-bearing comments on the cycle-breaking lambdas and on the
 * `applicationScope` are moved with their definitions (ADR-0023, ADR-0030).
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
            // Stable per-instance id scopes the k8s startup sweep to this instance's
            // pods. Resolved once, only when a kubernetes runner is configured, so a
            // non-k8s deployment never touches server_meta. configureServer calls
            // migrate() before the first injection, and building this registry is
            // reachable from the AgentsService scan — the first injection — so the
            // read is safe however early the registry is built. See ADR-0020.
            val k8sInstanceId =
                if (appConfig.runners.values.any { it is RunnerConfig.Kubernetes }) {
                    get<ServerMetaRepository>().getOrCreateInstanceId()
                } else {
                    ""
                }
            buildRunnerRegistry(appConfig, stateSink, tracer, k8sInstanceId)
        }

        single {
            ProcessFactory(
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
            // Shared scope for all background coroutines that must drain on
            // shutdown (ProcessExecutor.coroutineScope.launch jobs and the LLM
            // cost settlements of ADR-0089).
            // SupervisorJob so one failed child doesn't cancel its siblings.
            // Audit P0 #3.
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }

        single {
            ProcessExecutor(
                telemetryService = get<TelemetryService>(),
                processManager = get<ProcessManager>(),
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
                        // Resolved lazily — breaks the DI cycle RunAgentExecutor →
                        // AgentRunStarter → ProcessFactory → CommandExecutorFactory →
                        // RunAgentExecutor. See ADR-0030.
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
