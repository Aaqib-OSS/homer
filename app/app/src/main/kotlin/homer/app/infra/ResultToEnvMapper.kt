package homer.app.infra

import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import homer.app.models.CommandResult
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.SrcType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.slf4j.LoggerFactory
import kotlin.io.path.readText

class ResultToEnvMapper internal constructor(
    private val exitCode: Int,
    readStdOut: () -> String,
    readStdErr: () -> String,
) {
    constructor(result: CommandResult) : this(result.exitCode, { result.stdOut.readText() }, { result.stdErr.readText() })

    // LazyThreadSafetyMode.NONE: one mapper per CommandResult, mapToEnv called once
    // from a single coroutine. Don't share a mapper across threads.
    private val stdOutContent by lazy(mode = LazyThreadSafetyMode.NONE) { readStdOut() }
    private val stdErrContent by lazy(mode = LazyThreadSafetyMode.NONE) { readStdErr() }
    private val stdOutJsonPath by lazy(mode = LazyThreadSafetyMode.NONE) { parseJsonPathDocument(stdOutContent) }
    private val stdErrJsonPath by lazy(mode = LazyThreadSafetyMode.NONE) { parseJsonPathDocument(stdErrContent) }

    fun mapToEnv(resultEnvParams: List<ResultEnvParam>): Map<String, String> =
        buildMap {
            // After one regex param is shed, later ones are skipped unevaluated: the stall is one bounded match,
            // not one per param (ADR-0047 rule 4).
            var regexShed = false
            resultEnvParams.forEach { param ->
                if (regexShed && param.transformer is ResultTransformer.Regex) return@forEach
                val value =
                    when (val transformer = param.transformer) {
                        is ResultTransformer.JsonPath -> {
                            jsonPathValue(param, transformer)
                        }

                        is ResultTransformer.Regex -> {
                            regexValue(param, transformer) { regexShed = true }
                        }

                        ResultTransformer.Pass -> {
                            srcContent(param)
                        }
                    }

                value?.let { put(param.envName, it) }
            }
        }

    private fun srcContent(param: ResultEnvParam): String =
        when (param.src) {
            SrcType.STD_OUT -> stdOutContent
            SrcType.STD_ERR -> stdErrContent
            SrcType.RESULT_CODE -> exitCode.toString()
        } ?: ""

    private fun jsonPathValue(
        param: ResultEnvParam,
        transformer: ResultTransformer.JsonPath,
    ): String? {
        val jsonPath =
            when (param.src) {
                SrcType.STD_OUT -> stdOutJsonPath
                SrcType.STD_ERR -> stdErrJsonPath
                SrcType.RESULT_CODE -> null
            }
        return try {
            jsonPathText(jsonPath?.read<Any>(transformer.path))
        } catch (_: PathNotFoundException) {
            null
        }
    }

    private fun regexValue(
        param: ResultEnvParam,
        transformer: ResultTransformer.Regex,
        onShed: () -> Unit,
    ): String? {
        // An author-supplied pattern over attacker-influenceable content is a ReDoS vector, so the match is bounded;
        // a timeout or uncompilable pattern omits the value (ADR-0047 rule 4).
        val match = BoundedRegex.findOrOmit(transformer.regex, srcContent(param), param.envName, logger, onShed)
        return match?.let { matchResult ->
            if (transformer.group != null) {
                matchResult.groupValues[transformer.group]
            } else {
                matchResult.value
            }
        }
    }

    /**
     * Builds a JsonPath document from a command's stdout/stderr: single-document JSON as-is, or NDJSON lines
     * (Claude's `stream-json`) re-assembled into one array. Returns null when neither parses.
     */
    private fun parseJsonPathDocument(content: String): DocumentContext? {
        if (content.isBlank()) return null
        // A single JSON value parses strictly; NDJSON fails on the data after its first line's value, which
        // tells the two apart.
        if (runCatching { Json.parseToJsonElement(content) }.isSuccess) {
            return runCatching { JsonPath.parse(content) }.getOrNull()
        }
        return runCatching {
            val events =
                content
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .map { LlmOutputJson.parseToJsonElement(it) }
                    .toList()
            if (events.isEmpty()) null else JsonPath.parse(JsonArray(events).toString())
        }.getOrNull()
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(ResultToEnvMapper::class.java)
    }
}
