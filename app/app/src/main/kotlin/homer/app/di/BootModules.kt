package homer.app.di

/**
 * The whole graph the boot installs, in install order: [appModule] plus every subject module
 * beside it. `installKoin` loads exactly this list and `KoinGraphTest` verifies it, so a new
 * subject module is named here once — the test cannot be left behind.
 */
internal val bootModules =
    listOf(
        appModule,
        persistenceModule,
        telemetryModule,
        executionModule,
        servicesModule,
        processModule,
        hookModule,
    )
