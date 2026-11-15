package homer.app.di

import homer.app.infra.ProcessJobRegistry
import homer.app.models.AppConfig
import homer.app.services.persistence.ContinuationRepository
import homer.app.services.persistence.CostRepository
import homer.app.services.persistence.DatabaseService
import homer.app.services.persistence.DispatchRepository
import homer.app.services.persistence.ProcessRepository
import homer.app.services.persistence.QuestionsRepository
import homer.app.services.persistence.ResultTokenRepository
import homer.app.services.persistence.RunMessagesRepository
import homer.app.services.persistence.ServerMetaRepository
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/**
 * The SQLite-backed durable state: the database handle, the live job registry and one repository
 * per durable aggregate. Split out of [appModule] so each subject's bindings stay in one file
 * (root CLAUDE.md §2); the boot loads it beside the other subject modules.
 */
internal val persistenceModule =
    module {
        // Persistence layer (SQLite-backed durable state). See ADR-0020.
        single {
            DatabaseService(
                databasePath = get<AppConfig>().databasePath,
                logger = get { parametersOf("homer.app.services.persistence.DatabaseService") },
            )
        }

        single { ProcessJobRegistry() }

        single { ProcessRepository(db = get(), jobRegistry = get()) }

        single { QuestionsRepository(db = get()) }

        single { RunMessagesRepository(db = get()) }

        single { DispatchRepository(db = get()) }

        single { ContinuationRepository(db = get()) }

        single { ResultTokenRepository(db = get()) }

        single { CostRepository(db = get()) }

        single {
            ServerMetaRepository(
                db = get(),
                logger = get { parametersOf("homer.app.services.persistence.ServerMetaRepository") },
            )
        }
    }
