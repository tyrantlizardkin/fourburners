package app.fourburners.engine

import app.fourburners.T0
import app.fourburners.at
import app.fourburners.model.Kind
import app.fourburners.model.Plan
import app.fourburners.model.Recipe
import app.fourburners.model.Session
import app.fourburners.model.Status
import app.fourburners.model.Step
import app.fourburners.model.StepState
import app.fourburners.model.Tool
import app.fourburners.model.ToolUse
import app.fourburners.reference
import org.junit.Assert.*
import org.junit.Test

class EngineScheduleTest {
    private fun afterOvenOn(): Session {
        val started = SessionEngine.start(reference(), T0)
        return started.copy(steps = started.steps + ("c_on" to
            StepState(Status.DONE, T0, at(20))))
    }

    @Test fun `start runs c_on immediately`() {
        val session = SessionEngine.start(reference(), T0)
        assertEquals("c_on", SessionEngine.view(session, T0).nowStepId)
        assertEquals(T0, session.steps.getValue("c_on").startedAtMs)
        assertEquals(0, session.slipSec)
    }

    @Test fun `on-plan projection ends at 3170`() {
        val started = SessionEngine.start(reference(), T0)
        val projection = SessionEngine.project(started, T0)
        val byId = projection.associateBy { it.stepId }
        assertEquals(started.plan.steps.map { it.id }, projection.map { it.stepId })
        assertEquals(at(3170), byId.getValue("c4").endMs)
        assertEquals(at(500), byId.getValue("r1").startMs)
        assertEquals(at(770), byId.getValue("c2").startMs)
        assertEquals(at(2330), byId.getValue("b2").startMs)
        assertEquals(at(2930), byId.getValue("r6").startMs)
        assertEquals(Status.RUNNING, started.steps.getValue("c_on").status)
    }

    @Test fun `passives wait for actual user completion`() {
        val session = SessionEngine.advance(SessionEngine.start(reference(), T0), at(5))
        assertEquals(Status.RUNNING, session.steps.getValue("c_on").status)
        assertEquals(Status.PENDING, session.steps.getValue("c_pre").status)
    }

    @Test fun `passives start and finish by themselves`() {
        val advanced = SessionEngine.advance(afterOvenOn(), at(900))
        assertEquals(at(20), advanced.steps.getValue("c_pre").startedAtMs)
        assertEquals(at(740), advanced.steps.getValue("c_pre").endedAtMs)
        assertEquals(Status.DONE, advanced.steps.getValue("c_pre").status)
        assertEquals(Status.RUNNING, advanced.steps.getValue("c1").status)
    }

    @Test fun `advance is idempotent and composable`() {
        val session = afterOvenOn()
        val direct = SessionEngine.advance(session, at(900))
        assertEquals(direct, SessionEngine.advance(SessionEngine.advance(session, at(300)), at(900)))
        assertEquals(direct, SessionEngine.advance(direct, at(900)))
    }

    @Test fun `overrunning active projects its end at now`() {
        val session = SessionEngine.advance(afterOvenOn(), at(590))
        val projection = SessionEngine.project(session, at(590)).associateBy { it.stepId }
        assertEquals(at(20), session.steps.getValue("c1").startedAtMs)
        assertEquals(at(590), projection.getValue("c1").endMs)
        assertEquals(at(590), projection.getValue("r1").startMs)
    }

    @Test fun `clock moved backwards does not throw or un-finish`() {
        val session = SessionEngine.advance(afterOvenOn(), at(900))
        val back = SessionEngine.advance(session, at(100))
        assertEquals(session, back)
        val projected = SessionEngine.project(session, at(100)).associateBy { it.stepId }
        session.steps.filterValues { it.status == Status.DONE }.forEach { (id, state) ->
            assertEquals(state.endedAtMs, projected.getValue(id).endMs)
        }
        assertEquals(0, SessionEngine.elapsedSec(session, T0 - 5000))
        assertEquals(0, SessionEngine.elapsedSec(session, T0 + 999))
        assertEquals(1, SessionEngine.elapsedSec(session, T0 + 1000))
    }

    @Test fun `passive without dependencies starts at session start regardless of planned time`() {
        val plan = smallPlan(Step("P", kind = Kind.PASSIVE, title = "Timer", durationSec = 60,
            plannedStartSec = 500))
        val session = SessionEngine.advance(SessionEngine.start(plan, T0), at(90))
        assertEquals(T0, session.steps.getValue("P").startedAtMs)
        assertEquals(at(60), session.steps.getValue("P").endedAtMs)
        assertTrue(SessionEngine.view(session, at(90)).finished)
        assertNull(SessionEngine.view(session, at(90)).free)
    }

    @Test fun `ends release tools before starts at the same instant`() {
        val pot = listOf(ToolUse("pot"))
        val plan = smallPlan(
            Step("P", kind = Kind.PASSIVE, title = "Timer", durationSec = 600,
                plannedStartSec = 0, tools = pot),
            Step("A", kind = Kind.ACTIVE, title = "Use pot", durationSec = 60,
                plannedStartSec = 600, tools = pot),
        ).copy(tools = listOf(Tool("pot", "Pot")))
        val before = SessionEngine.advance(SessionEngine.start(plan, T0), at(599))
        assertNull(SessionEngine.view(before, at(599)).nowStepId)
        assertEquals("A", SessionEngine.view(before, at(599)).free?.nextStepId)
        assertEquals(at(600), SessionEngine.view(before, at(599)).free?.untilMs)
        val after = SessionEngine.advance(before, at(600))
        assertEquals(at(600), after.steps.getValue("P").endedAtMs)
        assertEquals(at(600), after.steps.getValue("A").startedAtMs)
    }

    @Test fun `timer follow-ups win ties and use latest passive dependency end`() {
        val plan = smallPlan(
            Step("P1", kind = Kind.PASSIVE, title = "Earlier timer", durationSec = 10, plannedStartSec = 0),
            Step("P2", kind = Kind.PASSIVE, title = "Later timer", durationSec = 20, plannedStartSec = 0),
            Step("work", kind = Kind.ACTIVE, title = "Busy", durationSec = 30, plannedStartSec = 0),
            Step("planned", kind = Kind.ACTIVE, title = "Plan", durationSec = 10, plannedStartSec = 5),
            Step("both", kind = Kind.ACTIVE, title = "Both timers", durationSec = 10,
                plannedStartSec = 20, after = listOf("P1", "P2")),
            Step("early", kind = Kind.ACTIVE, title = "First timer", durationSec = 10,
                plannedStartSec = 10, after = listOf("P1")),
        )
        val projected = SessionEngine.project(SessionEngine.start(plan, T0), T0).associateBy { it.stepId }
        assertEquals(at(30), projected.getValue("early").startMs)
        assertEquals(at(40), projected.getValue("both").startMs)
        assertEquals(at(50), projected.getValue("planned").startMs)
    }

    @Test fun `pooled tools allow the available unit`() {
        val burner = listOf(ToolUse("burner"))
        val plan = smallPlan(
            Step("P", kind = Kind.PASSIVE, title = "Timer", durationSec = 600,
                plannedStartSec = 0, tools = burner),
            Step("A", kind = Kind.ACTIVE, title = "Cook", durationSec = 60,
                plannedStartSec = 0, tools = burner),
        ).copy(tools = listOf(Tool("burner", "Burners", count = 2)))
        assertEquals("A", SessionEngine.view(SessionEngine.start(plan, T0), T0).nowStepId)
    }

    @Test fun `interrupt coexists with Now then becomes Now and blocks the next active`() {
        val plan = smallPlan(
            Step("P", kind = Kind.PASSIVE, title = "Timer", durationSec = 10, plannedStartSec = 0),
            Step("work", kind = Kind.ACTIVE, title = "Busy", durationSec = 30, plannedStartSec = 0),
            Step("interrupt", kind = Kind.ACTIVE, title = "Timer follow-up", durationSec = 20,
                plannedStartSec = 10, after = listOf("P"), interrupt = true),
            Step("next", kind = Kind.ACTIVE, title = "Next", durationSec = 10, plannedStartSec = 30),
        )
        val started = SessionEngine.advance(SessionEngine.start(plan, T0), at(15))
        val during = SessionEngine.view(started, at(15))
        assertEquals("work", during.nowStepId)
        assertEquals(listOf("interrupt"), during.interruptIds)
        assertEquals(T0, started.steps.getValue("work").startedAtMs)
        val done = started.copy(steps = started.steps + ("work" to
            started.steps.getValue("work").copy(status = Status.DONE, endedAtMs = at(30))))
        val after = SessionEngine.advance(done, at(40))
        assertEquals("interrupt", SessionEngine.view(after, at(40)).nowStepId)
        assertTrue(SessionEngine.view(after, at(40)).interruptIds.isEmpty())
        assertEquals(Status.PENDING, after.steps.getValue("next").status)
    }

    private fun smallPlan(vararg steps: Step) = Plan(1, recipes = listOf(Recipe("r", "Recipe")),
        steps = steps.toList())
}
