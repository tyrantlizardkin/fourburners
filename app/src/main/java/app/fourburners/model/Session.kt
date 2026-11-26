package app.fourburners.model

import kotlinx.serialization.Serializable

@Serializable
enum class Status { PENDING, RUNNING, DONE }

@Serializable
data class StepState(
    val status: Status = Status.PENDING,
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val extraSec: Int = 0,
    val acknowledgedAtMs: Long? = null,
)

@Serializable
data class UndoPoint(
    val stepId: String,
    val doneAtMs: Long,
    val prevSteps: Map<String, StepState>,
    val prevSlipSec: Int,
)

@Serializable
data class Session(
    val plan: Plan,
    val startedAtMs: Long,
    val steps: Map<String, StepState>,
    val slipSec: Int,
    val undo: UndoPoint? = null,
)
