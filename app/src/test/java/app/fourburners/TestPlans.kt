package app.fourburners

import app.fourburners.model.Plan
import app.fourburners.model.Step
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
