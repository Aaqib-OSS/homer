package homer.app.models

/**
 * Shortest secret the artifact-stem screen and the run-tag guards match as a substring; a shorter
 * one would match nearly any value and refuse it. Telemetry and tail redaction screen every
 * non-empty secret. ADR-0047, ADR-0060.
 */
const val MIN_SCREENED_SECRET_LENGTH = 8
