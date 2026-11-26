package app.fourburners.engine

import app.fourburners.model.Kind
import app.fourburners.model.Session
import app.fourburners.model.Status
import app.fourburners.model.Step

/** The same event loop catches up real timers and predicts a cook with assumed Done presses. */
internal object Simulator {
    fun run(session: Session, untilMs: Long, assumeDone: Boolean, nowMs: Long): Session {
        var current = session
        var eventMs = recordedThrough(session)
        if (untilMs < eventMs) return session

        while (true) {
            // Ends release tools before dependents or other eligible work can start.
            for (step in current.plan.steps) {
                val state = current.steps.getValue(step.id)
                if (state.status != Status.RUNNING) continue
                val end = endMs(current, step)
                if (step.kind == Kind.PASSIVE && end <= eventMs) {
                    current = current.copy(steps = current.steps + (step.id to
                        state.copy(status = Status.DONE, endedAtMs = eventMs)))
                } else if (assumeDone && step.kind == Kind.ACTIVE &&
                    maxOf(end, nowMs) <= eventMs) {
                    current = completeActive(current, step, eventMs)
                }
            }
            current = startReady(current, eventMs)

            val events = current.plan.steps.mapNotNull { step ->
                val state = current.steps.getValue(step.id)
                when {
                    state.status == Status.RUNNING && step.kind == Kind.PASSIVE -> endMs(current, step)
                    state.status == Status.RUNNING && assumeDone -> maxOf(endMs(current, step), nowMs)
                    state.status == Status.PENDING && step.kind == Kind.ACTIVE && !step.interrupt &&
                        !hasRunningActive(current) && dependenciesDone(current, step) && toolsFree(current, step) ->
                        eligibleAtMs(current, step)
                    else -> null
                }
            }
            val nextMs = events.filter { it > eventMs }.minOrNull() ?: return current
            if (nextMs > untilMs) return current
            eventMs = nextMs
        }
    }

    /** Latest persisted event; rollback never rewrites history or starts work before that event. */
    fun recordedThrough(session: Session): Long = session.steps.values.fold(session.startedAtMs) { latest, state ->
        maxOf(latest, state.startedAtMs ?: latest, state.endedAtMs ?: latest,
            state.acknowledgedAtMs ?: latest)
    }

    fun endMs(session: Session, step: Step): Long {
        val state = session.steps.getValue(step.id)
        return requireNotNull(state.startedAtMs) + (step.durationSec.toLong() + state.extraSec) * 1000L
    }

    fun dependenciesDone(session: Session, step: Step): Boolean =
        step.after.all { session.steps.getValue(it).status == Status.DONE }

    fun toolsFree(session: Session, step: Step): Boolean = step.tools.all { use ->
        val count = session.plan.tools.first { it.id == use.id }.count
        val occupied = session.plan.steps.count { other ->
            session.steps.getValue(other.id).status == Status.RUNNING && other.tools.any { it.id == use.id }
        }
        occupied < count
    }

    fun hasRunningActive(session: Session): Boolean = session.plan.steps.any {
        it.kind == Kind.ACTIVE && session.steps.getValue(it.id).status == Status.RUNNING
    }

    /** Stable sorting keeps plan order as the last tie-breaker. */
    fun candidates(session: Session): List<Step> = session.plan.steps.filter {
        it.kind == Kind.ACTIVE && !it.interrupt && session.steps.getValue(it.id).status == Status.PENDING &&
            dependenciesDone(session, it) && toolsFree(session, it)
    }.sortedWith(compareBy<Step> { passiveDependencyEnd(session, it) ?: Long.MAX_VALUE }
        .thenBy { it.plannedStartSec })

    fun eligibleAtMs(session: Session, step: Step): Long =
        if (passiveDependencyEnd(session, step) != null) recordedThrough(session)
        else session.startedAtMs + (step.plannedStartSec.toLong() + session.slipSec) * 1000L

    /** Shared by projected completions and the real Done action. */
    fun completeActive(session: Session, step: Step, nowMs: Long): Session {
        val state = session.steps.getValue(step.id)
        val slip = if (step.interrupt) session.slipSec else maxOf(session.slipSec,
            SessionEngine.elapsedSec(session, nowMs) - step.plannedStartSec - step.durationSec)
        return session.copy(steps = session.steps + (step.id to
            state.copy(status = Status.DONE, endedAtMs = nowMs)), slipSec = slip)
    }

    private fun passiveDependencyEnd(session: Session, step: Step): Long? = step.after
        .filter { id -> session.plan.steps.first { it.id == id }.kind == Kind.PASSIVE }
        .maxOfOrNull { requireNotNull(session.steps.getValue(it).endedAtMs) }

    private fun startReady(session: Session, nowMs: Long): Session {
        var current = session
        do {
            val before = current
            for (step in current.plan.steps) {
                if (current.steps.getValue(step.id).status == Status.PENDING &&
                    (step.kind == Kind.PASSIVE || step.interrupt) && dependenciesDone(current, step)) {
                    current = startStep(current, step, nowMs)
                }
            }
            if (!hasRunningActive(current)) {
                candidates(current).firstOrNull { eligibleAtMs(current, it) <= nowMs }?.let {
                    current = startStep(current, it, nowMs)
                }
            }
        } while (current != before)
        return current
    }

    fun startStep(session: Session, step: Step, nowMs: Long): Session = session.copy(
        steps = session.steps + (step.id to session.steps.getValue(step.id).copy(
            status = Status.RUNNING, startedAtMs = nowMs, endedAtMs = null)))
}
