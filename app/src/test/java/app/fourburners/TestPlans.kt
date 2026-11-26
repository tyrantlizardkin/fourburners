package app.fourburners

import app.fourburners.model.Plan
import app.fourburners.model.Step
import app.fourburners.model.Kind
import app.fourburners.model.Session
import app.fourburners.model.Status
import app.fourburners.engine.SessionEngine
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val planJson: Json get() = app.fourburners.parse.PlanJson

const val T0 = 1_000_000_000_000L
fun at(sec: Int): Long = T0 + sec * 1000L

/** Drives Done presses and intervening timer events against the same fake wall clock. */
fun drive(start: Session, untilSec: Int, late: Map<String, Int> = emptyMap()): Session {
    val untilMs = at(untilSec)
    var current = start
    var nowMs = maxOf(start.startedAtMs, start.steps.values.flatMap {
        listOfNotNull(it.startedAtMs, it.endedAtMs, it.acknowledgedAtMs)
    }.maxOrNull() ?: start.startedAtMs)
    while (nowMs <= untilMs) {
        current = SessionEngine.advance(current, nowMs)
        val next = current.plan.steps.mapNotNull { step ->
            val state = current.steps.getValue(step.id)
            if (state.status != Status.RUNNING) null else {
                val end = requireNotNull(state.startedAtMs) +
                    (step.durationSec.toLong() + state.extraSec +
                        if (step.kind == Kind.ACTIVE) late.getOrDefault(step.id, 0) else 0) * 1000L
                step to maxOf(nowMs, end)
            }
        }.minByOrNull { it.second }
        val pendingStart = SessionEngine.view(current, nowMs).free?.untilMs
        val eventMs = listOfNotNull(next?.second, pendingStart).minOrNull() ?: break
        if (eventMs > untilMs) break
        nowMs = eventMs
        current = SessionEngine.advance(current, nowMs)
        if (next != null && next.second == nowMs && next.first.kind == Kind.ACTIVE) {
            current = SessionEngine.done(current, next.first.id, nowMs)
        }
    }
    return SessionEngine.advance(current, untilMs)
}

fun referenceText(): String = File("src/main/assets/sample-plan.json").readText()

fun reference(): Plan = planJson.decodeFromString(referenceText())

/** Decodes the reference, lets [edit] mutate a copy of the top-level map, returns re-encoded JSON. */
fun referenceEdited(edit: (MutableMap<String, JsonElement>) -> Unit): String {
    val map = planJson.parseToJsonElement(referenceText()).jsonObject.toMutableMap()
    edit(map)
    return planJson.encodeToString(JsonElement.serializer(), JsonObject(map))
}

/** Copy of this plan with the step [id] transformed by [change]. */
fun Plan.withStep(id: String, change: (Step) -> Step): Plan =
    copy(steps = steps.map { if (it.id == id) change(it) else it })

/** The reference plan's step with [id], with [overrides] keys replaced/added. */
fun stepJson(id: String, vararg overrides: Pair<String, JsonElement>): JsonObject {
    val steps = planJson.parseToJsonElement(referenceText()).jsonObject["steps"]!!.jsonArray
    val step = steps.first { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject
    return JsonObject(step + overrides)
}
