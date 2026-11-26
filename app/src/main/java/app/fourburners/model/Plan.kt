package app.fourburners.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Kind {
    @SerialName("active") ACTIVE,
    @SerialName("passive") PASSIVE,
}

@Serializable
data class Tool(val id: String, val name: String, val count: Int = 1, val initial: String = "clean")

@Serializable
data class Ingredient(
    val id: String? = null,
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    val grams: Double? = null,
    val note: String? = null,
)

@Serializable
data class Recipe(
    val id: String,
    val name: String,
    val short: String? = null, // UI falls back to name
    val servings: Double? = null,
    val ingredients: List<Ingredient> = emptyList(),
    val notes: String? = null,
)

@Serializable
data class ToolUse(val id: String, val needs: String? = null, val leaves: String? = null)

@Serializable
data class Step(
    val id: String,
    val recipe: String? = null,
    val kind: Kind,
    val title: String,
    val detail: String? = null,
    @SerialName("duration_sec") val durationSec: Int,
    @SerialName("planned_start_sec") val plannedStartSec: Int,
    val after: List<String> = emptyList(),
    val uses: List<String> = emptyList(),
    val tools: List<ToolUse> = emptyList(),
    val interrupt: Boolean = false,
    @SerialName("timer_label") val timerLabel: String? = null,
    @SerialName("max_hold_sec") val maxHoldSec: Int? = null,
)

@Serializable
data class Plan(
    val cookplan: Int,
    val title: String? = null,
    val tools: List<Tool> = emptyList(),
    val recipes: List<Recipe>,
    val steps: List<Step>,
)
