package homer.app

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Clears the process-wide [isDraining] flag after each test: a test that leaves its `testApplication` block fires
 * `ApplicationStopping`, which sets the flag, and a later `/readyz` assertion in this JVM would see 503. Apply to every
 * class that boots `configureServer` (`GlobalRateLimitProbeExemptionTest` clears it in its own setup instead).
 */
class ResetDrainingExtension : AfterEachCallback {
    override fun afterEach(context: ExtensionContext) {
        isDraining.set(false)
    }
}
