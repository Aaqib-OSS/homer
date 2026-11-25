package homer.app.infra

import homer.app.exceptions.MalformedLlmOutputException
import homer.app.models.LlmEvent
import homer.app.models.LlmUsage
import homer.app.models.Translation
import homer.app.models.gateSessionId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.inputStream

/**
 * Shared lenient JSON reader for LLM harness output, used by every harness adapter's typed decode
 * and by the readers in this file so they agree on leniency.
 */
val LlmOutputJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

/**
 * Splits NDJSON stdout into one [JsonObject] per non-blank line; throws [MalformedLlmOutputException]
 * on the first line that is not a JSON object. The caller owns the empty-output check. Prefer the
 * [Sequence] overload with `Path.useLines` for large output (ADR-0047 rules 4-5).
 */
fun parseNdjson(lines: Sequence<String>): List<JsonObject> =
    lines
        .filter { it.isNotBlank() }
        .map { line ->
            val element =
                try {
                    LlmOutputJson.parseToJsonElement(line)
                } catch (e: SerializationException) {
                    throw MalformedLlmOutputException("stdout line is not valid JSON (${e.message})", e)
                }
            if (element !is JsonObject) {
                throw MalformedLlmOutputException("stdout line is not a JSON object")
            }
            element
        }.toList()

fun parseNdjson(output: String): List<JsonObject> = parseNdjson(output.lineSequence())

/**
 * At most [maxBytes] bytes of the file as a line [Sequence], read lazily; the last line may be
 * a partial read. [cut] is meaningful only once [lines] has been consumed.
 */
class BoundedLines(
    path: Path,
    maxBytes: Long,
) : Closeable {
    // CWE-400: the bound sits under the decoder, so no line is buffered past it (ADR-0047 rule 4).
    private val capped = CappedInputStream(path.inputStream(), maxBytes)
    private val reader = capped.bufferedReader()

    /** True once the read stopped at the bound; read after consuming [lines]. */
    val cut: Boolean
        get() = capped.cut

    val lines: Sequence<String> = generateSequence { reader.readLine() }

    override fun close() {
        reader.close()
    }
}

/**
 * Ends after [maxBytes] bytes of [inner]; [cut] is true once a read past them found more. Truncates for a
 * lenient reader, where the runners' `BoundedInputStream` fails a transfer instead.
 */
private class CappedInputStream(
    private val inner: InputStream,
    maxBytes: Long,
) : InputStream() {
    private var remaining = maxBytes
    private var probed = false

    var cut = false
        private set

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        if (remaining <= 0) return probeEnd()
        val n = inner.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    private fun probeEnd(): Int {
        if (!probed) {
            probed = true
            cut = inner.read() >= 0
        }
        return -1
    }

    override fun close() = inner.close()
}

/**
 * What the lenient parse recovered: the parsed events, plus how many lines it skipped, which
 * surfaces on `telemetry.llm.malformed_events`.
 */
data class LenientParse(
    val events: List<JsonObject>,
    val skippedLines: Long,
    /**
     * True when the parse stopped at the caller's [parseNdjsonLenient] `maxEvents` cap —
     * lines after it were neither parsed nor counted.
     */
    val truncated: Boolean = false,
)

/**
 * Lenient sibling of [parseNdjson]: blank, unparseable and non-object lines are skipped and
 * counted in [LenientParse.skippedLines]. [maxEvents] bounds the list (CWE-400) and
 * sets [LenientParse.truncated].
 */
fun parseNdjsonLenient(
    lines: Sequence<String>,
    maxEvents: Int? = null,
): LenientParse {
    val events = ArrayList<JsonObject>()
    var skipped = 0L
    var truncated = false
    for (line in lines) {
        if (maxEvents != null && events.size >= maxEvents) {
            truncated = true
            break
        }
        if (line.isBlank()) continue
        val obj =
            try {
                LlmOutputJson.parseToJsonElement(line) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
        if (obj == null) skipped++ else events.add(obj)
    }
    return LenientParse(events, skipped, truncated)
}

/**
 * An attempt's raw NDJSON translated through one harness adapter. [raw] is the strict-parsed
 * line list; [malformedPreview] is up to 5 truncated raw lines the adapter flagged.
 */
data class TranslatedStream(
    val events: List<LlmEvent>,
    val raw: List<JsonObject>,
    val ignored: Long,
    val malformed: Long,
    val malformedPreview: List<String>,
)

fun translateStream(
    raw: List<JsonObject>,
    translate: (JsonObject) -> Translation,
): TranslatedStream {
    val events = ArrayList<LlmEvent>()
    var ignored = 0L
    var malformed = 0L
    val preview = ArrayList<String>()
    for (line in raw) {
        when (val t = translate(line)) {
            is Translation.Events -> {
                events.addAll(t.events)
            }

            Translation.Ignored -> {
                ignored++
            }

            is Translation.Malformed -> {
                malformed++
                if (preview.size < MALFORMED_PREVIEW_LINES) preview.add(line.toString().take(RAW_EVENT_PREVIEW_LENGTH))
            }
        }
    }
    return TranslatedStream(events, raw, ignored, malformed, preview)
}

/** What the lenient accounting scan could recover from an attempt's stdout. */
data class LlmAccountingScan(
    val sessionId: String?,
    val cliCostUsd: Double?,
    /** True once a turn-ending event was seen: separates a killed attempt from a turn end with no usable cost. */
    val resultSeen: Boolean,
    /** Distinct model-call ids seen, capped at 10 000; the expected count for the re-poll (ADR-0061). */
    val modelCalls: Int,
    val usage: LlmUsage?,
)

private const val MAX_MODEL_CALLS = 10_000
private const val MALFORMED_PREVIEW_LINES = 5
private const val RAW_EVENT_PREVIEW_LENGTH = 500

/**
 * Lenient per-attempt scan: unparseable lines are skipped; cost is the highest reported and usage
 * the sum over turns (ADR-0074). Reads only the events [translate] produces, and gates `sessionId`
 * with [gateSessionId] whatever the adapter returns (ADR-0061).
 */
fun scanLlmAccounting(
    lines: Sequence<String>,
    translate: (JsonObject) -> Translation,
): LlmAccountingScan {
    val totals = AccountingTotals()
    for (line in lines) {
        if (line.isBlank()) continue
        val obj =
            try {
                LlmOutputJson.parseToJsonElement(line) as? JsonObject ?: continue
            } catch (_: SerializationException) {
                continue
            }
        val events = (translate(obj) as? Translation.Events)?.events.orEmpty()
        for (e in events) totals.add(e)
    }
    return totals.toScan()
}

/** The running totals [scanLlmAccounting] folds an attempt's events into, in stream order. */
private class AccountingTotals {
    private var sessionId: String? = null
    private var cost: Double? = null
    private var resultSeen = false
    private var usage: LlmUsage? = null
    private val messageIds = HashSet<String>()

    fun add(e: LlmEvent) {
        when (e) {
            is LlmEvent.SessionStart -> {
                if (sessionId == null) sessionId = gateSessionId(e.sessionId)
            }

            is LlmEvent.AssistantMessage -> {
                e.messageId?.let { if (messageIds.size < MAX_MODEL_CALLS) messageIds.add(it) }
            }

            is LlmEvent.ToolCall -> {
                e.messageId?.let { if (messageIds.size < MAX_MODEL_CALLS) messageIds.add(it) }
            }

            is LlmEvent.TurnEnd -> {
                resultSeen = true
                // CWE-682: cost is cumulative, so a lower or negative figure is never a refund.
                e.costUsd?.takeIf { it >= 0.0 }?.let { c -> cost = maxOf(cost ?: c, c) }
                // CWE-345: a negative token count in agent-written stdout would hide real usage.
                val turn = e.usage.nonNegative()
                usage = usage?.plus(turn) ?: turn
            }

            is LlmEvent.ToolResult, is LlmEvent.TransientError -> {}
        }
    }

    fun toScan() = LlmAccountingScan(sessionId, cost, resultSeen, messageIds.size, usage)
}
