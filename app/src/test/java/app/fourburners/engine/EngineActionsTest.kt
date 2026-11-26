package app.fourburners.engine

import app.fourburners.T0
import app.fourburners.at
import app.fourburners.drive
import app.fourburners.model.*
import app.fourburners.planJson
import app.fourburners.reference
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class EngineActionsTest {
    private fun cook(until: Int, late: Map<String, Int> = emptyMap()) =
        drive(SessionEngine.start(reference(), T0), until, late)

    private fun projection(session: Session, now: Int) =
        SessionEngine.project(session, at(now)).associateBy { it.stepId }

    private fun active(id: String, duration: Int = 10, planned: Int = 0,
                       after: List<String> = emptyList()) =
        Step(id, kind = Kind.ACTIVE, title = id, durationSec = duration,
            plannedStartSec = planned, after = after)

    private fun passive(id: String, duration: Int, after: List<String> = emptyList()) =
        Step(id, kind = Kind.PASSIVE, title = id, durationSec = duration,
            plannedStartSec = 0, after = after)

    private fun start(vararg steps: Step, tools: List<Tool> = emptyList()) =
        SessionEngine.start(Plan(1, tools = tools, recipes = listOf(Recipe("r", "Recipe")),
            steps = steps.toList()), T0)

    @Test fun `on plan finishes at 3170 with slip zero`() {
        val session = cook(3170)
        assertTrue(SessionEngine.view(session, at(3170)).finished)
        assertEquals(0, session.slipSec)
        assertEquals(at(3170), session.steps.values.maxOf { it.endedAtMs!! })
    }

    @Test fun `late c1 slips plan timed work but not timers`() {
        val session = cook(760, mapOf("c1" to 180))
        assertEquals(180, session.slipSec)
        assertEquals(at(680), session.steps.getValue("r1").startedAtMs)
        assertEquals(at(740), session.steps.getValue("c_pre").endedAtMs)
        val future = projection(session, 760)
        assertEquals(2_100_000L, future.getValue("c2").endMs - future.getValue("c2").startMs)
        assertEquals(at(1055), future.getValue("r2").startMs)
    }

    @Test fun `c_in interrupts r1 without resetting it`() {
        val session = cook(760, mapOf("c1" to 180))
        val live = SessionEngine.view(session, at(760))
        assertEquals("r1", live.nowStepId)
        assertEquals(listOf("c_in"), live.interruptIds)
        assertEquals(at(680), session.steps.getValue("r1").startedAtMs)
    }

    @Test fun `roast follow up becomes Now regardless of slip`() {
        val session = cook(2870, mapOf("c1" to 60, "b3" to -120))
        assertEquals(60, session.slipSec)
        assertEquals(at(2870), session.steps.getValue("c2").endedAtMs)
        assertEquals("c3", SessionEngine.view(session, at(2870)).nowStepId)
    }

    @Test fun `free after b1 allows b_pot early`() {
        val session = SessionEngine.advance(cook(1535), at(1540))
        val free = SessionEngine.view(session, at(1540)).free!!
        assertEquals("b_pot", free.nextStepId)
        assertEquals(at(1790), free.untilMs)
        val early = SessionEngine.startEarly(session, at(1540))
        assertEquals("b_pot", SessionEngine.view(early, at(1540)).nowStepId)
        assertEquals(at(1540), early.steps.getValue("b_pot").startedAtMs)
        assertNull(early.undo)
        assertEquals(0, early.slipSec)
    }

    @Test fun `occupied tool blocks eligibility until extended timer ends`() {
        val uses = listOf(ToolUse("pot"))
        val session = start(passive("P", 600).copy(tools = uses),
            active("A", 60, 600).copy(tools = uses), tools = listOf(Tool("pot", "Pot")))
        val extended = SessionEngine.extend(session, "P", 120, at(300))
        assertNull(SessionEngine.view(extended, at(650)).nowStepId)
        assertEquals("A", SessionEngine.view(extended, at(720)).nowStepId)
    }

    @Test fun `extend moves timer and follow up`() {
        val extended = SessionEngine.extend(cook(1000), "c2", 60, at(1000))
        val future = projection(extended, 1000)
        assertEquals(at(2930), future.getValue("c2").endMs)
        assertEquals(at(2930), future.getValue("c3").startMs)
        assertNull(extended.undo)
    }

    @Test fun `negative timer extension leaves at least thirty seconds`() {
        val session = cook(640)
        val shortened = SessionEngine.extend(session, "c_pre", -600, at(640))
        assertEquals(at(670), projection(shortened, 640).getValue("c_pre").endMs)
        val almost = cook(720)
        assertEquals(almost, SessionEngine.extend(almost, "c_pre", -600, at(720)))
    }

    @Test fun `timer shortening rounds fractional milliseconds up to thirty seconds`() {
        val session = start(passive("P", 100))
        val now = at(20) + 1L
        val shortened = SessionEngine.extend(session, "P", -100, now)
        val end = SessionEngine.project(shortened, now).single().endMs
        assertEquals(at(51), end)
        assertTrue(end - now >= 30_000L)
        assertTrue(end - now < 31_000L)
    }

    @Test fun `recent timer reopens and returns follow up to pending`() {
        val session = cook(2890)
        assertEquals(Status.RUNNING, session.steps.getValue("c3").status)
        val reopened = SessionEngine.extend(session, "c2", 60, at(2890))
        assertEquals(Status.RUNNING, reopened.steps.getValue("c2").status)
        assertNull(reopened.steps.getValue("c2").endedAtMs)
        assertNull(reopened.steps.getValue("c2").acknowledgedAtMs)
        assertEquals(StepState(), reopened.steps.getValue("c3"))
        assertEquals(at(2930), projection(reopened, 2890).getValue("c2").endMs)
        val later = SessionEngine.advance(session, at(2940))
        assertEquals(later, SessionEngine.extend(later, "c2", 60, at(2940)))
    }

    @Test fun `reopening window includes sixty seconds and rejects finished dependents`() {
        val base = start(passive("P", 100), active("A", after = listOf("P")))
        val ended = SessionEngine.advance(base, at(160))
        assertEquals(Status.RUNNING, SessionEngine.extend(ended, "P", 120, at(160)).steps.getValue("P").status)
        assertEquals(ended, SessionEngine.extend(ended, "P", 120, at(161)))
        val completed = SessionEngine.done(base, "A", at(110))
        assertEquals(completed, SessionEngine.extend(completed, "P", 120, at(115)))
    }

    @Test fun `active extension projects slip immediately and measures it at done`() {
        val session = SessionEngine.extend(cook(1300), "b1", 60, at(1300))
        assertEquals(0, session.slipSec)
        val future = projection(session, 1300)
        assertEquals(at(1850), future.getValue("b_pot").startMs)
        assertEquals(at(2390), future.getValue("b2").startMs)
        assertEquals(at(2630), future.getValue("b3").startMs)
        assertEquals(at(2870), future.getValue("c3").startMs)
        assertEquals(60, SessionEngine.done(session, "b1", at(1595)).slipSec)
    }

    @Test fun `undo within thirty seconds restores prior state`() {
        val before = cook(739)
        val done = SessionEngine.done(before, "r1", at(740))
        assertTrue(SessionEngine.canUndo(done, at(765)))
        val restored = SessionEngine.undo(done, at(765))
        val expected = SessionEngine.advance(before, at(765))
        assertEquals(expected.steps, restored.steps)
        assertEquals(expected.slipSec, restored.slipSec)
        assertNull(restored.undo)
        assertTrue(SessionEngine.canUndo(done, at(770)))
        assertFalse(SessionEngine.canUndo(done, at(771)))
        assertEquals(done, SessionEngine.undo(done, at(771)))
    }

    @Test fun `undo is refused once a passive it started has ended`() {
        val session = start(active("A"), passive("P", 10, listOf("A")))
        val done = SessionEngine.done(session, "A", at(10))
        val advanced = SessionEngine.advance(done, at(25))
        assertFalse(SessionEngine.canUndo(done, at(25)))
        assertEquals(advanced, SessionEngine.undo(done, at(25)))
        val silenced = SessionEngine.acknowledge(done, "P", at(25))
        assertFalse(SessionEngine.canUndo(silenced, at(25)))
        assertEquals(silenced, SessionEngine.undo(silenced, at(25)))
    }

    @Test fun `double Done cannot finish the next step`() {
        val once = SessionEngine.done(SessionEngine.start(reference(), T0), "c_on", at(20))
        assertEquals(once, SessionEngine.done(once, "c_on", at(20)))
        assertEquals(Status.RUNNING, once.steps.getValue("c1").status)
    }

    @Test fun `restore after process death matches continuous advancement`() {
        val stored = cook(800)
        val restored = planJson.decodeFromString<Session>(planJson.encodeToString(stored))
        val continuous = (900..1400 step 100).fold(stored) { session, time ->
            SessionEngine.advance(session, at(time))
        }
        assertEquals(continuous, SessionEngine.advance(restored, at(1400)))
    }

    @Test fun `acknowledgement survives undo and does not replace undo point`() {
        val session = start(passive("P", 5), active("A"), active("B", planned = 10, after = listOf("A")))
        val done = SessionEngine.done(session, "A", at(10))
        val silenced = SessionEngine.acknowledge(done, "P", at(12))
        assertEquals(at(12), silenced.steps.getValue("P").acknowledgedAtMs)
        assertEquals(done.undo!!.doneAtMs, silenced.undo!!.doneAtMs)
        val undone = SessionEngine.undo(silenced, at(15))
        assertEquals(at(12), undone.steps.getValue("P").acknowledgedAtMs)
        assertEquals(Status.RUNNING, undone.steps.getValue("A").status)
        assertEquals(Status.PENDING, undone.steps.getValue("B").status)
    }

    @Test fun `acknowledging before undo preserves timer and follow up event times`() {
        val before = start(passive("P", 12), active("A"),
            active("I", after = listOf("P")).copy(interrupt = true))
        val done = SessionEngine.done(before, "A", at(10))
        val silenced = SessionEngine.acknowledge(done, "P", at(13))
        assertTrue(SessionEngine.canUndo(silenced, at(14)))
        val restored = planJson.decodeFromString<Session>(planJson.encodeToString(silenced))
        val undone = SessionEngine.undo(restored, at(14))
        val expected = SessionEngine.advance(before, at(14))
        assertEquals(at(12), undone.steps.getValue("P").endedAtMs)
        assertEquals(at(12), undone.steps.getValue("I").startedAtMs)
        assertEquals(at(13), undone.steps.getValue("P").acknowledgedAtMs)
        assertEquals(expected.steps + ("P" to expected.steps.getValue("P").copy(
            acknowledgedAtMs = at(13))), undone.steps)
        assertNull(undone.undo)
    }

    @Test fun `acknowledgement preserves other timer events in undo snapshot`() {
        val before = start(passive("P", 5), passive("Q", 12), active("A"),
            active("I", after = listOf("Q")).copy(interrupt = true))
        val done = SessionEngine.done(before, "A", at(10))
        val silenced = SessionEngine.acknowledge(done, "P", at(13))
        assertTrue(SessionEngine.canUndo(silenced, at(14)))
        val undone = SessionEngine.undo(silenced, at(14))
        val expected = SessionEngine.advance(before, at(14))
        assertEquals(at(12), undone.steps.getValue("Q").endedAtMs)
        assertEquals(at(12), undone.steps.getValue("I").startedAtMs)
        assertEquals(expected.steps + ("P" to expected.steps.getValue("P").copy(
            acknowledgedAtMs = at(13))), undone.steps)
    }

    @Test fun `invalid actions leave an advanced session unchanged`() {
        val session = cook(800)
        assertEquals(session, SessionEngine.done(session, "c2", at(800)))
        assertEquals(session, SessionEngine.done(session, "missing", at(800)))
        val busy = cook(900)
        assertEquals(busy, SessionEngine.startEarly(busy, at(900)))
        assertEquals(Status.RUNNING, busy.steps.getValue("r2").status)
        assertEquals(busy, SessionEngine.extend(busy, "r2", -60, at(900)))
        assertEquals(session, SessionEngine.extend(session, "r2", -60, at(800)))
        assertEquals(session, SessionEngine.extend(session, "c2", 0, at(800)))
        assertEquals(session, SessionEngine.extend(session, "missing", 60, at(800)))
        assertEquals(session, SessionEngine.acknowledge(session, "c2", at(800)))
        assertEquals(session, SessionEngine.acknowledge(session, "r1", at(800)))
        assertEquals(session, SessionEngine.acknowledge(session, "missing", at(800)))
    }

    @Test fun `early start skips work whose dependencies are not done`() {
        val session = start(passive("P", 600), active("A", planned = 600, after = listOf("P")),
            active("B", planned = 700))
        assertEquals("B", SessionEngine.view(SessionEngine.startEarly(session, at(300)), at(300)).nowStepId)
    }

    @Test fun `actions after clock rollback do not mutate recorded history`() {
        val session = cook(800)
        assertEquals(session, SessionEngine.done(session, "r2", at(100)))
        assertEquals(session, SessionEngine.extend(session, "c2", 60, at(100)))
        assertEquals(session, SessionEngine.startEarly(session, at(100)))
        assertEquals(session, SessionEngine.acknowledge(session, "c_pre", at(100)))
        assertFalse(SessionEngine.canUndo(session, at(100)))
        assertEquals(session, SessionEngine.undo(session, at(100)))
    }
}
