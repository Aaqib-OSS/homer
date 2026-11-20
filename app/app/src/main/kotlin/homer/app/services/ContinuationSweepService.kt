package homer.app.services

import homer.app.infra.PeriodicSweep
import org.slf4j.Logger

/**
 * Each tick expires PENDING continuations past their TTL, re-fires the due retries of continuations and
 * hooks, prunes their settled rows, and reaps expired result tokens. See ADR-0023, ADR-0025, ADR-0107.
 */
internal class ContinuationSweepService(
    private val continuationService: ContinuationService,
    private val resultTokenService: ResultTokenService,
    private val hookService: HookService,
    intervalSec: Int,
    logger: Logger,
) : PeriodicSweep(intervalSec, logger, "Continuation sweep failed: {}") {
    override suspend fun sweep() {
        continuationService.sweepExpired()
        // After expiry, so a continuation past its TTL lapses instead of
        // burning one more fire attempt on the way out.
        continuationService.sweepRetries()
        continuationService.pruneSettled()
        hookService.sweepRetries()
        hookService.pruneSettled()
        resultTokenService.sweepExpired()
    }
}
