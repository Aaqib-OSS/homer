package homer.architecture

/**
 * JUnit tag of a rule that still has findings: it runs in `architectureReportOnly`, which never fails the build.
 * Delete the tag once the rule reports 0 (ADR-0082).
 */
internal const val REPORT_ONLY = "report-only"
