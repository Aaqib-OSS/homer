package homer.app.infra

import com.jayway.jsonpath.Configuration

/**
 * Text form of a JsonPath-selected value, shared by every transformer. Objects and arrays are
 * re-serialized as JSON, scalars keep their text, and a JSON null or absent path yields null.
 */
internal fun jsonPathText(value: Any?): String? =
    when (value) {
        null -> {
            null
        }

        is String -> {
            value
        }

        is Map<*, *>, is Collection<*>, is Array<*> -> {
            Configuration.defaultConfiguration().jsonProvider().toJson(value)
        }

        else -> {
            value.toString()
        }
    }
