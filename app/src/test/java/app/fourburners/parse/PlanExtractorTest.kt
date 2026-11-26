package app.fourburners.parse

import app.fourburners.reference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanExtractorTest {
    @Test
    fun `reference plan decodes`() {
        val plan = reference()
        assertEquals(3, plan.recipes.size)
        assertEquals(18, plan.steps.size)
        assertEquals(7, plan.tools.size)
        val cIn = plan.steps.first { it.id == "c_in" }
        assertTrue(cIn.interrupt)
        assertEquals(listOf("c1", "c_pre"), cIn.after)
        assertEquals(2, plan.tools.first { it.id == "burners" }.count)
        assertEquals("clean", plan.tools.first { it.id == "sheetpan" }.initial)
    }
}
