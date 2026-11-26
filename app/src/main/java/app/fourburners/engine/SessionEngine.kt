package app.fourburners.engine

import app.fourburners.model.Kind
import app.fourburners.model.Plan
import app.fourburners.model.Session
import app.fourburners.model.Status
import app.fourburners.model.StepState

data class ProjectedStep(val stepId: String, val startMs: Long, val endMs: Long)
data class FreeInfo(val nextStepId: String?, val untilMs: Long?)
data class LiveState(
    val nowStepId: String?,
    val interruptIds: List<String>,
    val timerIds: List<String>,
    val free: FreeInfo?,
    val finished: Boolean,
)

object SessionEngine {
    fun start(plan: Plan, nowMs: Long): Session = advance(Session(plan, nowMs,
        plan.steps.associate { it.id to StepState() }, slipSec = 0), nowMs)

    fun advance(session: Session, nowMs: Long): Session =
        Simulator.run(session, nowMs, assumeDone = false, nowMs = nowMs)

    fun project(session: Session, nowMs: Long): List<ProjectedStep> {
        val advanced = advance(session, nowMs)
        val completed = Simulator.run(advanced, Long.MAX_VALUE, assumeDone = true,
            nowMs = maxOf(nowMs, Simulator.recordedThrough(advanced)))
        return completed.plan.steps.map { step ->
            val state = completed.steps.getValue(step.id)
            ProjectedStep(step.id, requireNotNull(state.startedAtMs) { "Unreachable step ${step.id}" },
                requireNotNull(state.endedAtMs) { "Unfinished step ${step.id}" })
        }
    }

    fun view(session: Session, nowMs: Long): LiveState {
        val current = advance(session, nowMs)
        val running = current.plan.steps.filter { current.steps.getValue(it.id).status == Status.RUNNING }
        val interrupts = running.filter { it.interrupt }
            .sortedBy { current.steps.getValue(it.id).startedAtMs }
        val now = running.firstOrNull { it.kind == Kind.ACTIVE && !it.interrupt } ?: interrupts.firstOrNull()
        val timers = running.filter { it.kind == Kind.PASSIVE }.sortedBy { Simulator.endMs(current, it) }
        val finished = current.steps.values.all { it.status == Status.DONE }
        val free = if (now == null && !finished) {
            val next = project(current, nowMs).filter { projected ->
                current.steps.getValue(projected.stepId).status == Status.PENDING &&
                    current.plan.steps.first { it.id == projected.stepId }.kind == Kind.ACTIVE
            }.minByOrNull { it.startMs }
            FreeInfo(next?.stepId, next?.startMs)
        } else null
        return LiveState(now?.id, interrupts.filter { it.id != now?.id }.map { it.id },
            timers.map { it.id }, free, finished)
    }

    fun elapsedSec(session: Session, nowMs: Long): Int =
        ((nowMs - session.startedAtMs).coerceAtLeast(0) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
