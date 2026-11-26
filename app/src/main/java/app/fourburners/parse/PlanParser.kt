package app.fourburners.parse

import app.fourburners.model.Plan
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Lenient configuration shared by extraction, parsing and tests. */
val PlanJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

data class PlanError(val rule: Int, val message: String)

sealed interface ParseResult {
    data class Ok(val plan: Plan) : ParseResult
    data class Failed(val errors: List<PlanError>, val received: String) : ParseResult
}

object PlanParser {
    const val NOT_FOUND = "No cook plan found in the shared text."
    const val WRONG_VERSION = "This is not a cookplan version 1 plan."

    fun parse(text: String): ParseResult {
        fun fail(rule: Int, message: String) = ParseResult.Failed(listOf(PlanError(rule, message)), text)

        val obj = PlanExtractor.extract(text) ?: return fail(0, NOT_FOUND)
        val version = (obj["cookplan"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (version != 1) return fail(1, WRONG_VERSION)
        return try {
            ParseResult.Ok(PlanJson.decodeFromJsonElement(Plan.serializer(), obj))
        } catch (e: SerializationException) {
            fail(0, "The plan is malformed: ${describe(e)}.")
        } catch (e: IllegalArgumentException) {
            fail(0, "The plan is malformed: ${describe(e)}.")
        }
    }

    /** Turns kotlinx's exception text into "<field> <reason>" without leaking stack-trace style detail. */
    private fun describe(e: Exception): String {
        val msg = e.message.orEmpty()
        val path = Regex("path: (\\$\\S*)").find(msg)?.groupValues?.get(1)?.trimEnd('.', ',')
        val where = path?.removePrefix("$")?.removePrefix(".")?.takeIf { it.isNotEmpty() }
        val missing = Regex("Field '([^']+)' is required").find(msg)?.groupValues?.get(1)
        return when {
            missing != null -> "required field '$missing' is missing" + (where?.let { " in $it" } ?: "")
            where != null -> {
                val field = where.substringAfterLast('.').substringBefore('[')
                "'$field' has an invalid value (at $where)"
            }
            else -> "the structure does not match the cookplan format"
        }
    }
}
