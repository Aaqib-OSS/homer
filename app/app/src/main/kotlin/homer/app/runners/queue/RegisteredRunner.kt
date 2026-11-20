package homer.app.runners.queue

import homer.app.models.DEFAULT_RUNNER_CONCURRENCY
import homer.app.runners.Runner

/** A configured runner with its claim-queue facets (`tags`, `concurrency`). */
data class RegisteredRunner(
    val runner: Runner,
    val tags: Set<String> = emptySet(),
    val concurrency: Int = DEFAULT_RUNNER_CONCURRENCY,
)
