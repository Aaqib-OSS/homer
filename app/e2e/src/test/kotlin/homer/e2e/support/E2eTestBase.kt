package homer.e2e.support

import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll

/**
 * Base class for every E2E test: needs E2E_BASE_URL to point at a live server. Without it the
 * class fails where [E2e.required] (the E2E runner image), and is skipped elsewhere, so
 * `./gradlew check` (unit-test CI, local pre-commit) stays green without a running backend.
 */
abstract class E2eTestBase {
    companion object {
        @JvmStatic
        @BeforeAll
        fun requireLiveServer() {
            if (E2e.baseUrl.isNotBlank()) return
            if (E2e.required) fail<Unit>("E2E_BASE_URL not set, but E2E_REQUIRED is: the suite has no server to test")
            Assumptions.abort<Unit>("E2E_BASE_URL not set — black-box E2E suite skipped")
        }
    }
}
