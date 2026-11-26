package app.fourburners.parse

import app.fourburners.reference
import app.fourburners.referenceEdited
import app.fourburners.referenceText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    private fun ok(text: String): ParseResult.Ok {
        val r = PlanParser.parse(text)
        assertTrue("expected Ok but was $r", r is ParseResult.Ok)
        return r as ParseResult.Ok
    }

    private fun failed(text: String): ParseResult.Failed {
        val r = PlanParser.parse(text)
        if (r !is ParseResult.Failed) fail("expected Failed but was $r")
        return r as ParseResult.Failed
    }

    /** Reference JSON with step [id] modified by [edit]. */
    private fun editedStep(id: String, edit: (MutableMap<String, JsonElement>) -> Unit): String =
        referenceEdited { top ->
            top["steps"] = JsonArray(top["steps"]!!.jsonArray.map { el ->
                if (el.jsonObject["id"]!!.jsonPrimitive.content == id) {
                    JsonObject(el.jsonObject.toMutableMap().also(edit))
                } else el
            })
        }

    private val NL = System.lineSeparator()
    private val notFound = "No cook plan found in the shared text."

    @Test
    fun `fenced block inside prose`() {
        val text = """Here is your plan:
```json
${referenceText()}
```
Enjoy!"""
        assertEquals(18, ok(text).plan.steps.size)
    }

    @Test
    fun `bare json`() {
        ok(referenceText())
    }

    @Test
    fun `no plan`() {
        val f = failed("dinner at 7?")
        assertEquals(listOf(PlanError(0, notFound)), f.errors)
        assertEquals("dinner at 7?", f.received)
    }

    @Test
    fun `two code blocks, only second is a plan`() {
        val text = """```json
{"a":1}
```
and
```json
${referenceText()}
```"""
        ok(text)
    }

    @Test
    fun `prose with braces before the plan`() {
        ok("use a {large} pan" + NL + referenceText())
    }

    @Test
    fun `brace inside a json string`() {
        val json = editedStep("c1") { it["detail"] = JsonPrimitive("Skin } side { up") }
        val plan = ok("Sure thing, here you go:" + NL + json + NL + "Let me know!").plan
        assertEquals("Skin } side { up", plan.steps.first { it.id == "c1" }.detail)
    }

    @Test
    fun `cookplan 2`() {
        val f = failed(referenceEdited { it["cookplan"] = JsonPrimitive(2) })
        assertEquals(1, f.errors.single().rule)
    }

    @Test
    fun `wrong type`() {
        val f = failed(editedStep("c1") { it["duration_sec"] = JsonPrimitive("eight minutes") })
        val e = f.errors.single()
        assertEquals(0, e.rule)
        assertTrue(e.message, e.message.contains("duration_sec"))
    }

    @Test
    fun `missing required field`() {
        val f = failed(editedStep("c1") { it.remove("title") })
        val e = f.errors.single()
        assertEquals(0, e.rule)
        assertTrue(e.message, e.message.contains("title"))
    }

    @Test
    fun `broken json is not repaired`() {
        val broken = referenceText().trimEnd().removeSuffix("}")
        val f = failed(broken)
        assertEquals(listOf(PlanError(0, notFound)), f.errors)
    }

    @Test(timeout = 5000)
    fun `many unbalanced braces before the plan`() {
        val prose = buildString {
            while (length < 200_000) append("lorem ipsum { dolor sit amet ")
        }
        ok(prose + NL + referenceText())
    }
}
