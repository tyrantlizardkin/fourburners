package app.fourburners.parse

import java.util.BitSet
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/** Finds the cookplan JSON object inside a whole shared chat message (spec 5.4). Never repairs JSON. */
object PlanExtractor {
    private const val KEY = "cookplan"
    private val fence = Regex("```[A-Za-z0-9_+-]*[ \t]*\r?\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)

    fun extract(text: String): JsonObject? {
        if (!text.contains(KEY)) return null
        for (m in fence.findAll(text)) {
            parseCandidate(m.groupValues[1])?.let { return it }
        }
        return balancedObjects(text).firstNotNullOfOrNull { parseCandidate(text.substring(it.first, it.last + 1)) }
    }

    private fun parseCandidate(s: String): JsonObject? {
        if (!s.contains(KEY)) return null
        val obj = try {
            PlanJson.parseToJsonElement(s) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return obj?.takeIf { KEY in it }
    }

    /**
     * Every balanced {...} span, ordered by start. A '{' outside a string that is visited by an
     * earlier scan is already resolved (its own scan would behave identically), so each scan
     * only starts at braces not yet seen; this keeps unbalanced-brace input linear.
     */
    private fun balancedObjects(text: String): List<IntRange> {
        val spans = ArrayList<IntRange>()
        val seen = BitSet(text.length)
        var i = text.indexOf('{')
        while (i >= 0) {
            if (!seen.get(i)) scan(text, i, seen, spans)
            i = text.indexOf('{', i + 1)
        }
        spans.sortBy { it.first }
        return spans
    }

    private fun scan(text: String, start: Int, seen: BitSet, out: MutableList<IntRange>) {
        val stack = ArrayList<Int>()
        var inString = false
        var j = start
        while (j < text.length) {
            val c = text[j]
            if (inString) {
                if (c == '\\') j++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> { stack.add(j); seen.set(j) }
                '}' -> {
                    out.add(stack.removeAt(stack.size - 1)..j)
                    if (stack.isEmpty()) return
                }
            }
            j++
        }
    }
}
