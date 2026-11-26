package app.fourburners.parse

import app.fourburners.model.Ingredient
import app.fourburners.model.Kind
import app.fourburners.model.Plan
import app.fourburners.model.Recipe
import app.fourburners.model.Step
import app.fourburners.model.Tool
import app.fourburners.model.ToolUse
import app.fourburners.reference
import app.fourburners.referenceEdited
import app.fourburners.referenceText
import app.fourburners.withStep
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanValidatorTest {
    private fun errors(plan: Plan) = PlanValidator.validate(plan)

    private fun assertRule(rule: Int, plan: Plan): PlanError {
        val errs = errors(plan)
        val hit = errs.firstOrNull { it.rule == rule }
        assertTrue("expected rule $rule in $errs", hit != null)
        return hit!!
    }

    private fun ref() = reference()

    private fun recipe(id: String) = Recipe(id = id, name = "Recipe $id")

    private fun Plan.recipeEdit(id: String, change: (Recipe) -> Recipe) =
        copy(recipes = recipes.map { if (it.id == id) change(it) else it })

    private fun Plan.addIngredient(i: Ingredient) = recipeEdit("c") { it.copy(ingredients = it.ingredients + i) }

    // Rule 2
    @Test fun `rule 2 five recipes`() {
        assertRule(2, ref().copy(recipes = ref().recipes + recipe("x") + recipe("y")))
    }

    @Test fun `rule 2 zero steps`() {
        assertRule(2, ref().copy(steps = emptyList()))
    }

    @Test fun `rule 2 zero recipes`() {
        assertRule(2, ref().copy(recipes = emptyList()))
    }

    @Test fun `rule 2 too many steps`() {
        val s = ref().steps.first()
        assertRule(2, ref().copy(steps = (1..81).map { s.copy(id = "s$it") }))
    }

    @Test fun `rule 2 31 tools`() {
        assertRule(2, ref().copy(tools = (1..31).map { Tool("t$it", "Tool $it") }))
    }

    // Rule 3
    @Test fun `rule 3 duplicate step id`() {
        val p = ref()
        assertRule(3, p.copy(steps = p.steps + p.steps.first()))
    }

    @Test fun `rule 3 duplicate recipe id and tool id`() {
        val p = ref()
        assertRule(3, p.copy(recipes = p.recipes + p.recipes.first()))
        assertRule(3, p.copy(tools = p.tools + p.tools.first()))
    }

    @Test fun `rule 3 unknown recipe`() {
        assertRule(3, ref().withStep("c1") { it.copy(recipe = "zz") })
    }

    @Test fun `rule 3 unknown after`() {
        assertRule(3, ref().withStep("c1") { it.copy(after = listOf("nope")) })
    }

    @Test fun `rule 3 duplicate ingredient id in one recipe`() {
        assertRule(3, ref().recipeEdit("c") { it.copy(ingredients = it.ingredients + it.ingredients.first()) })
    }

    // Rule 4
    @Test fun `rule 4 cycle`() {
        assertRule(4, ref().withStep("r1") { it.copy(after = listOf("r2")) })
    }

    @Test fun `rule 4 self cycle`() {
        assertRule(4, ref().withStep("r1") { it.copy(after = listOf("r1")) })
    }

    // Rule 5
    @Test fun `rule 5 duration zero`() {
        assertRule(5, ref().withStep("c1") { it.copy(durationSec = 0) })
    }

    @Test fun `rule 5 duration too long`() {
        assertRule(5, ref().withStep("c1") { it.copy(durationSec = 21601) })
    }

    @Test fun `rule 5 negative start`() {
        assertRule(5, ref().withStep("c_on") { it.copy(plannedStartSec = -1) })
    }

    // Rule 6
    @Test fun `rule 6 starts before predecessor ends`() {
        val e = assertRule(6, ref().withStep("c_in") { it.copy(plannedStartSec = 700) })
        assertTrue(e.message, e.message.contains("Chicken into the oven") && e.message.contains("Oven preheats"))
    }

    // Rule 7
    @Test fun `rule 7 cook overlap`() {
        val e = assertRule(7, ref().withStep("r1") { it.copy(plannedStartSec = 400) })
        assertTrue(e.message, e.message.contains("Chop onion") && e.message.contains("Season thighs"))
    }

    @Test fun `rule 7 interrupt step may overlap`() {
        // c_in is an interrupt step, so overlapping the active c1 (20-500) is not a rule 7 error
        val q = ref().withStep("c_in") { it.copy(plannedStartSec = 300) }
        assertTrue(errors(q).none { it.rule == 7 })
    }

    // Rule 8
    @Test fun `rule 8 interrupt on passive`() {
        assertRule(8, ref().withStep("c2") { it.copy(interrupt = true) })
    }

    @Test fun `rule 8 interrupt on long active`() {
        assertRule(8, ref().withStep("c_in") { it.copy(durationSec = 121) })
    }

    // Rule 9
    @Test fun `rule 9 empty ingredient name`() {
        assertRule(9, ref().addIngredient(Ingredient(name = "")))
    }

    @Test fun `rule 9 non positive amount and grams`() {
        assertRule(9, ref().addIngredient(Ingredient(name = "x", amount = 0.0)))
        assertRule(9, ref().addIngredient(Ingredient(name = "x", amount = 1.0, grams = 0.0)))
    }

    @Test fun `rule 9 grams without amount`() {
        assertRule(9, ref().addIngredient(Ingredient(name = "x", grams = 5.0)))
    }

    @Test fun `rule 9 sixty one ingredients`() {
        assertRule(9, ref().recipeEdit("c") { it.copy(ingredients = (1..61).map { n -> Ingredient(name = "i$n") }) })
    }

    @Test fun `rule 9 servings zero`() {
        assertRule(9, ref().recipeEdit("c") { it.copy(servings = 0.0) })
    }

    // Rule 10
    @Test fun `rule 10 uses ingredient of another recipe`() {
        val e = assertRule(10, ref().withStep("c1") { it.copy(uses = listOf("rice")) })
        assertTrue(e.message, e.message.contains("rice"))
    }

    @Test fun `rule 10 uses on chore`() {
        assertRule(10, ref().withStep("c1") { it.copy(recipe = null, uses = listOf("thighs")) })
    }

    // Rule 11
    @Test fun `rule 11 unknown tool`() {
        assertRule(11, ref().withStep("c1") { it.copy(tools = listOf(ToolUse("wok"))) })
    }

    @Test fun `rule 11 count zero`() {
        val p = ref()
        assertRule(11, p.copy(tools = p.tools.map { if (it.id == "board") it.copy(count = 0) else it }))
    }

    @Test fun `rule 11 needs on pool tool`() {
        assertRule(11, ref().withStep("c1") { it.copy(tools = listOf(ToolUse("burners", needs = "clean"))) })
    }

    // Rule 12
    @Test fun `rule 12 capacity exceeded`() {
        val x = Step(
            id = "x", recipe = null, kind = Kind.PASSIVE, title = "Extra", durationSec = 60,
            plannedStartSec = 900, tools = listOf(ToolUse("burners")),
        )
        val moved = ref().withStep("b_pot") { it.copy(plannedStartSec = 900) }
        val e = assertRule(12, moved.copy(steps = moved.steps + x))
        assertTrue(e.message, e.message.contains("Burners") && e.message.contains("0:15"))
        assertTrue(e.message, e.message.contains("needed by 3 steps") && e.message.contains("only 2"))
    }

    @Test fun `rule 12 single count wording`() {
        val p = ref().withStep("c2") { it.copy(plannedStartSec = 740) } // overlaps c_in on the oven
        val e = assertRule(12, p)
        assertTrue(e.message, e.message.contains("there is only 1:"))
    }

    // Rule 13
    @Test fun `rule 13 state mismatch`() {
        val p = ref().withStep("c_in") {
            it.copy(tools = listOf(ToolUse("oven", needs = "450°F"), ToolUse("sheetpan")))
        }
        val e = assertRule(13, p)
        for (s in listOf("Oven", "425°F", "450°F", "Chicken into the oven", "0:12")) {
            assertTrue("$s in ${e.message}", e.message.contains(s))
        }
    }

    @Test fun `rule 13 message shape`() {
        val p = ref().withStep("r1") { it.copy(tools = listOf(ToolUse("board", needs = "wet", leaves = "onion"))) }
        assertEquals("Cutting board is 'clean' at 0:08, but 'Chop onion' needs it 'wet'.", assertRule(13, p).message)
    }

    // Accepts
    @Test fun `accepts reference`() {
        assertEquals(emptyList<PlanError>(), errors(ref()))
    }

    @Test fun `accepts plan without tools and ingredients`() {
        val p = ref()
        val bare = p.copy(
            tools = emptyList(),
            recipes = p.recipes.map { it.copy(ingredients = emptyList()) },
            steps = p.steps.map { it.copy(uses = emptyList(), tools = emptyList()) },
        )
        assertEquals(emptyList<PlanError>(), errors(bare))
    }

    // Robustness
    @Test fun `schedule rules skipped when references are broken`() {
        val p = ref().withStep("c_in") { it.copy(plannedStartSec = 700, tools = listOf(ToolUse("wok"))) }
        val rules = errors(p).map { it.rule }
        assertTrue(rules.toString(), 11 in rules && 6 !in rules && 7 !in rules && 12 !in rules && 13 !in rules)
    }

    @Test fun `never throws on malformed plans`() {
        val p = ref()
        errors(p.withStep("r1") { it.copy(after = listOf("r2", "zzz", "r1")) })
        errors(Plan(cookplan = 1, recipes = emptyList(), steps = emptyList()))
        errors(p.copy(steps = p.steps + p.steps))
        errors(p.copy(tools = p.tools + p.tools))
        errors(p.copy(tools = p.tools.map { it.copy(count = -3) }))
    }

    @Test fun `errors are ordered by rule`() {
        val p = ref().withStep("c1") { it.copy(durationSec = 0, recipe = "zz") }
            .copy(recipes = ref().recipes.map { it.copy(servings = 0.0) })
        val rules = errors(p).map { it.rule }
        assertEquals(rules.sorted(), rules)
    }

    // Parser wiring
    @Test fun `parser fails with validator errors`() {
        val text = referenceEdited { m -> m["steps"] = JsonArray(emptyList()) }
        val r = PlanParser.parse(text)
        assertTrue(r.toString(), r is ParseResult.Failed && r.errors.any { it.rule == 2 })
    }

    @Test fun `parser accepts reference`() {
        assertTrue(PlanParser.parse(referenceText()) is ParseResult.Ok)
    }
}
