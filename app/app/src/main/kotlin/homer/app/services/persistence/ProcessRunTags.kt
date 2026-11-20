package homer.app.services.persistence

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select

// Run-time subject tags declared through the callback route after a run's INSERT; the
// `fromInputs` tags are written by [ProcessRepository.obtainProcessId] itself. See ADR-0060.

/**
 * Appends a run-time tag from the callback route; an exact duplicate collapses on the `(process_id, tag)`
 * key. The per-run ceiling, enforced in the write's transaction, counts only tags whose key is outside
 * [fromInputsKeys]. See ADR-0060.
 */
suspend fun ProcessRepository.addRuntimeTag(
    processId: Int,
    tag: String,
    maxTags: Int,
    fromInputsKeys: Set<String> = emptySet(),
): ProcessRepository.RuntimeTagResult =
    query {
        // CWE-459: a late callback can land after the record purge and no foreign key refuses it, so
        // the row must exist in this transaction or the tag would outlive its run. See ADR-0060.
        if (!Processes.select(Processes.id).where { Processes.id eq processId }.any()) {
            return@query ProcessRepository.RuntimeTagResult(written = false, count = 0)
        }
        val existing =
            ProcessTags
                .select(ProcessTags.tag)
                .where { ProcessTags.processId eq processId }
                .map { it[ProcessTags.tag] }
                .toSet()
        val runtimeCount = existing.count { it.substringBefore(':') !in fromInputsKeys }
        if (tag in existing) {
            return@query ProcessRepository.RuntimeTagResult(written = true, count = runtimeCount)
        }
        if (runtimeCount >= maxTags) {
            return@query ProcessRepository.RuntimeTagResult(written = false, count = runtimeCount)
        }
        ProcessTags.insert {
            it[ProcessTags.processId] = processId
            it[ProcessTags.tag] = tag
        }
        ProcessRepository.RuntimeTagResult(written = true, count = runtimeCount + 1)
    }

/** Every tag on a run, in tag order (ORDER BY, served by the (process_id, tag) primary key). */
suspend fun ProcessRepository.getTags(processId: Int): List<String> = read { tagsForTx(processId) }
