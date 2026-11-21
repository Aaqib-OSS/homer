package homer.app.di

import homer.app.config.ExecutionConfig
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.DescriptorOpen
import homer.app.infra.FileOperations
import homer.app.infra.InputSchemaValidator
import homer.app.infra.LocalFileOperations
import homer.app.infra.PasswordVerifier
import homer.app.infra.TextCompiler
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.kubernetes.RunnerStateSink
import homer.app.services.Authorizer
import homer.app.services.ProcessEventBroadcaster
import homer.app.services.ProcessManager
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.slf4j.LoggerFactory

/**
 * The cross-cutting Koin definitions: the per-class logger factory, the execution defaults and the ports
 * (an interface bound to its implementation, or a lambda-shaped sink) that no single subject owns. Every other
 * subject has its own module (`PersistenceWiring`, `TelemetryWiring`, ...), registered together at boot.
 */
val appModule =
    module {
        // Logger factory - provides logger for each class
        factory { (name: String) ->
            LoggerFactory.getLogger(name)
        }

        // Configuration
        single { ExecutionConfig() }

        single<TextCompiler> { DefaultTextCompiler() }

        // Eager, so DescriptorOpen's status line is logged at boot (ADR-0047, amendment 2026-11-21).
        single<FileOperations>(createdAtStart = true) {
            LocalFileOperations().also { DescriptorOpen.logStatus(get { parametersOf("homer.app.infra.DescriptorOpen") }) }
        }

        single { ProcessEventBroadcaster() }

        single { InputSchemaValidator() }

        single { PasswordVerifier() }

        single { Authorizer.from(get()) }

        single { ActiveSessionsRegistry() }

        single<RunnerStateSink> {
            val pm = get<ProcessManager>()
            RunnerStateSink { processId, update -> pm.setRunnerState(processId, update) }
        }
    }
