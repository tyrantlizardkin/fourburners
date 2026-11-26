package app.fourburners.engine

import app.fourburners.model.Kind
import app.fourburners.model.Plan
import app.fourburners.model.Session
import app.fourburners.model.Status
import app.fourburners.model.StepState
import app.fourburners.model.UndoPoint

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

    fun done(session: Session, stepId: String, nowMs: Long): Session {
        val current = advance(session, nowMs)
        if (nowMs < Simulator.recordedThrough(current)) return current
        val step = current.plan.steps.firstOrNull { it.id == stepId } ?: return current
        if (step.kind != Kind.ACTIVE || current.steps.getValue(stepId).status != Status.RUNNING) return current
        val completed = Simulator.completeActive(current, step, nowMs).copy(
            undo = UndoPoint(stepId, nowMs, current.steps, current.slipSec))
        return advance(completed, nowMs)
    }

    fun startEarly(session: Session, nowMs: Long): Session {
        val current = advance(session, nowMs)
        if (nowMs < Simulator.recordedThrough(current) || Simulator.hasRunningActive(current)) return current
        val next = Simulator.candidates(current).firstOrNull() ?: return current
        return advance(Simulator.startStep(current, next, nowMs).copy(undo = null), nowMs)
    }

    fun extend(session: Session, stepId: String, sec: Int, nowMs: Long): Session {
        val current = advance(session, nowMs)
        if (nowMs < Simulator.recordedThrough(current) || sec == 0) return current
        val step = current.plan.steps.firstOrNull { it.id == stepId } ?: return current
        val state = current.steps.getValue(stepId)
        val reopening = step.kind == Kind.PASSIVE && state.status == Status.DONE && sec > 0 &&
            nowMs - requireNotNull(state.endedAtMs) <= 60_000L
        if (state.status != Status.RUNNING && !reopening) return current
        if (step.kind == Kind.ACTIVE && sec < 0) return current

        var extra = state.extraSec.toLong() + sec
        if (sec < 0) {
            val remainingMs = Simulator.endMs(current, step) - nowMs
            if (remainingMs < 30_000L) return current
            // Use a ceiling so a fractional second can never shorten the timer below 30 s.
            val minimumExtra = (nowMs + 30_000L - requireNotNull(state.startedAtMs) + 999L) / 1000L - step.durationSec
            extra = maxOf(extra, minimumExtra)
        }
        if (extra !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || extra.toInt() == state.extraSec) return current

        var steps = current.steps
        if (reopening) {
            val dependents = descendants(current, stepId)
            if (dependents.any { current.steps.getValue(it).status == Status.DONE }) return current
            steps = steps.mapValues { (id, old) ->
                if (id in dependents) old.copy(status = Status.PENDING, startedAtMs = null,
                    endedAtMs = null, acknowledgedAtMs = null) else old
            }
        }
        steps = steps + (stepId to state.copy(extraSec = extra.toInt(), status = Status.RUNNING,
            endedAtMs = null, acknowledgedAtMs = null))
        return advance(current.copy(steps = steps, undo = null), nowMs)
    }

    fun acknowledge(session: Session, stepId: String, nowMs: Long): Session {
        val current = advance(session, nowMs)
        if (nowMs < Simulator.recordedThrough(current)) return current
        val step = current.plan.steps.firstOrNull { it.id == stepId } ?: return current
        val state = current.steps.getValue(stepId)
        if (step.kind != Kind.PASSIVE || state.status != Status.DONE || state.acknowledgedAtMs != null) return current
        val undo = current.undo?.let { point ->
            // Catch up the alternate history before a newer acknowledgement advances its event floor.
            val previous = advance(current.copy(steps = point.prevSteps,
                slipSec = point.prevSlipSec, undo = null), nowMs).steps
            point.copy(prevSteps = previous + (stepId to previous.getValue(stepId).copy(acknowledgedAtMs = nowMs)))
        }
        return current.copy(steps = current.steps + (stepId to state.copy(acknowledgedAtMs = nowMs)), undo = undo)
    }

    fun undo(session: Session, nowMs: Long): Session {
        val current = advance(session, nowMs)
        if (!canUndo(current, nowMs)) return current
        val point = requireNotNull(current.undo)
        return advance(current.copy(steps = point.prevSteps, slipSec = point.prevSlipSec, undo = null), nowMs)
    }

    fun canUndo(session: Session, nowMs: Long): Boolean {
        val current = advance(session, nowMs)
        val point = current.undo ?: return false
        if (nowMs < Simulator.recordedThrough(current) || nowMs - point.doneAtMs !in 0L..30_000L) return false
        return current.plan.steps.none { step ->
            val state = current.steps.getValue(step.id)
            step.kind == Kind.PASSIVE && state.startedAtMs == point.doneAtMs && state.status == Status.DONE &&
                point.prevSteps.getValue(step.id).status == Status.PENDING
        }
    }

    /** Include indirect dependents: none may remain started when their timer is reopened. */
    private fun descendants(session: Session, stepId: String): Set<String> {
        val ids = mutableSetOf(stepId)
        do {
            val size = ids.size
            session.plan.steps.filter { step -> step.after.any { it in ids } }.forEach { ids += it.id }
        } while (ids.size != size)
        return ids - stepId
    }
}
