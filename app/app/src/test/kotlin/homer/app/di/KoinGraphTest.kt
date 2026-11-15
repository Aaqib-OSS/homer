package homer.app.di

import homer.app.executors.QuestionExpiry
import homer.app.executors.QuestionRegistration
import homer.app.executors.RunMessageExpiry
import homer.app.executors.RunRecords
import homer.app.executors.RunWorkRecords
import homer.app.executors.StepRecords
import homer.app.executors.SubRunStarter
import homer.app.executors.llm.CostRecorder
import homer.app.executors.llm.LlmStepTelemetry
import homer.app.models.AppConfig
import homer.app.models.MaintenanceConfig
import homer.app.models.McpServerConfig
import homer.app.models.QuestionsConfig
import homer.app.models.RunMessagesConfig
import homer.app.models.SessionSpendConfig
import homer.app.runners.ConfiguredRunners
import homer.app.runners.RunnerLookup
import homer.app.runners.kubernetes.ActiveRunIds
import homer.app.runners.queue.RegisteredRunner
import homer.app.services.persistence.ProcessRecordReads
import org.junit.jupiter.api.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.test.verify.verify
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * The graph the boot installs, checked as one graph.
 *
 * `installKoin` is the only place the seven modules are named, and nothing outside a real server
 * boot builds them, so a binding that moves into the wrong subject module — or is lost between two
 * of them — would otherwise surface only as a startup crash in production. `verify()` reflects on
 * every definition's constructor and fails on a parameter type no definition provides, and
 * [EXPECTED_TYPES] catches what reflection cannot: a definition dropped whole, or registered twice.
 */
class KoinGraphTest {
    @OptIn(KoinInternalApi::class, KoinExperimentalAPI::class)
    @Test
    fun `every definition of the boot's graph resolves, exactly once`() {
        val modules = listOf(appModule, persistenceModule, telemetryModule, executionModule, servicesModule, processModule, hookModule)

        module { includes(appModule, persistenceModule, telemetryModule, executionModule, servicesModule, processModule, hookModule) }
            .verify(extraTypes = EXTRA_TYPES)
            .verify()

        val types = modules.flatMap { it.registeredTypes() }
        assertEquals(EXPECTED_TYPES.sorted(), types.sorted())
        assertEquals(
            listOf(named("applicationScope")),
            modules.flatMap { it.definitions() }.mapNotNull { it.qualifier },
        )
    }

    private companion object {
        /**
         * Types a definition's constructor names that no definition in the graph provides, and that
         * `verify()` therefore cannot see resolved. Every entry is a claim that the type cannot come
         * from the graph — not a way to silence a missing binding — and each is one of:
         *
         * - `AppConfig`, which `installKoin` registers itself, beside the modules;
         * - a value a definition builds inline (a config section, a lambda, a `Path`);
         * - a port whose concrete service is passed through an explicit `get<ConcreteService>()`,
         *   so the port's own name appears in no definition's index.
         */
        val EXTRA_TYPES =
            listOf(
                AppConfig::class,
                Boolean::class,
                Map::class,
                Path::class,
                // Config sections read out of `AppConfig` and passed inline.
                MaintenanceConfig::class,
                McpServerConfig::class,
                QuestionsConfig::class,
                RunMessagesConfig::class,
                SessionSpendConfig::class,
                CostRecorder::class,
                LlmStepTelemetry::class,
                // Ports implemented by a service registered under its own type: `ProcessManager`
                // for the run, question, message and step records; `RunnerRegistry` for the
                // configured runners and the runner lookup; `AgentSubRunStarter` for the sub-run hop.
                ActiveRunIds::class,
                ConfiguredRunners::class,
                ProcessRecordReads::class,
                QuestionExpiry::class,
                QuestionRegistration::class,
                RegisteredRunner::class,
                RunMessageExpiry::class,
                RunnerLookup::class,
                RunRecords::class,
                RunWorkRecords::class,
                StepRecords::class,
                SubRunStarter::class,
            )

        /**
         * Every definition of the seven modules, by primary type: one line per `single`/`factory`
         * the graph must hold. Adding a binding means adding it here — the test fails otherwise,
         * which is the point: a dropped or duplicated definition is otherwise silent.
         */
        val EXPECTED_TYPES =
            listOf(
                // appModule
                "Logger",
                "ExecutionConfig",
                "TextCompiler",
                "FileOperations",
                "ProcessEventBroadcaster",
                "InputSchemaValidator",
                "PasswordVerifier",
                "Authorizer",
                "ActiveSessionsRegistry",
                "RunnerStateSink",
                // persistenceModule
                "DatabaseService",
                "ProcessJobRegistry",
                "ProcessRepository",
                "QuestionsRepository",
                "RunMessagesRepository",
                "DispatchRepository",
                "ContinuationRepository",
                "ResultTokenRepository",
                "CostRepository",
                "ServerMetaRepository",
                // telemetryModule
                "TelemetryService",
                "LangfuseClient",
                "LlmTelemetryService",
                "LlmCostResolver",
                "CostMeter",
                "CooldownGate",
                // executionModule
                "WorkspaceConfigFactory",
                "LockManager",
                "ParallelRunsManager",
                "SweepStatus",
                "KubernetesRunnerStartupSweep",
                "RunnerRegistry",
                "ProcessFactory",
                "AgentRunStarter",
                "CoroutineScope",
                "ProcessExecutor",
                "RunAgentExecutor",
                "LangGraphExecutor",
                "RunnerCmdExecutor",
                "CommandExecutorFactory",
                // servicesModule
                "AgentsService",
                "AgentInputResolver",
                "AgentFilesService",
                "GraphInspectRunner",
                "LangGraphInspectService",
                "GraphStateRunner",
                "LangGraphRunStatusService",
                "MaintenanceModeService",
                "StartupReconciliation",
                "McpToolFactory",
                "QuestionsService",
                "RunMessagesService",
                "DispatchGroupService",
                "DispatchService",
                "DispatchSweepService",
                "ContinuationService",
                "ResultTokenService",
                "ContinuationSweepService",
                "QuestionsWorkspaceSetup",
                "QuestionsMcpToolFactory",
                "LogTailService",
                "LlmEventStreamService",
                "ArtifactService",
                "CronSchedulerService",
                "WorkspacePurgeService",
                // processModule
                "ProcessManager",
                // hookModule
                "HookRepository",
                "HookService",
            )
    }
}

/** One entry per definition, whatever a definition binds its type under. */
@OptIn(KoinInternalApi::class)
private fun Module.definitions() = mappings.values.distinct().map { it.beanDefinition }

@OptIn(KoinInternalApi::class)
private fun Module.registeredTypes() = definitions().mapNotNull { it.primaryType.simpleName }
