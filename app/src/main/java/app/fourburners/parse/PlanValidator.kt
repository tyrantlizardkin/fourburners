package app.fourburners.parse

import app.fourburners.model.Kind
import app.fourburners.model.Plan
import app.fourburners.model.Step
import app.fourburners.model.Tool

/**
 * Checks a decoded [Plan] against spec section 5.3 (rule 1 is checked by [PlanParser]).
 * Returns every error found, ordered by rule number. Never throws, however malformed the plan is.
 */
object PlanValidator {
    private const val MAX_RECIPES = 4
    private const val MAX_STEPS = 80
    private const val MAX_TOOLS = 30
    private const val MAX_DURATION_SEC = 6 * 3600
    private const val MAX_INTERRUPT_SEC = 120
    private const val MAX_INGREDIENTS = 60

    fun validate(plan: Plan): List<PlanError> {
        val structural = buildList {
            addAll(limits(plan))
            addAll(duplicatesAndReferences(plan))
            addAll(cycles(plan))
            addAll(stepNumbers(plan))
            addAll(toolReferences(plan))
        }
        val other = interruptRules(plan) + ingredientRules(plan) + usesRules(plan)
        // The schedule rules assume well-formed references, so they only run on a structurally sound plan.
        val schedule = if (structural.isEmpty()) {
            afterOrder(plan) + oneCook(plan) + toolCapacity(plan) + toolState(plan)
        } else {
            emptyList()
        }
        return (structural + other + schedule).sortedBy { it.rule }
    }

    // Rule 2
    private fun limits(plan: Plan): List<PlanError> = buildList {
        if (plan.recipes.size !in 1..MAX_RECIPES) {
            add(PlanError(2, "The plan has ${plan.recipes.size} recipes, but it must have 1 to $MAX_RECIPES."))
        }
        if (plan.steps.size !in 1..MAX_STEPS) {
            add(PlanError(2, "The plan has ${plan.steps.size} steps, but it must have 1 to $MAX_STEPS."))
        }
        if (plan.tools.size > MAX_TOOLS) {
            add(PlanError(2, "The plan has ${plan.tools.size} tools, but it can have at most $MAX_TOOLS."))
        }
    }

    // Rule 3
    private fun duplicatesAndReferences(plan: Plan): List<PlanError> = buildList {
        duplicates(plan.steps.map { it.id }).forEach { add(PlanError(3, "Step id '$it' is used more than once.")) }
        duplicates(plan.recipes.map { it.id }).forEach { add(PlanError(3, "Recipe id '$it' is used more than once.")) }
        duplicates(plan.tools.map { it.id }).forEach { add(PlanError(3, "Tool id '$it' is used more than once.")) }
        for (r in plan.recipes) {
            duplicates(r.ingredients.mapNotNull { it.id }).forEach {
                add(PlanError(3, "Ingredient id '$it' is used more than once in '${r.name}'."))
            }
        }
        val recipeIds = plan.recipes.map { it.id }.toSet()
        val stepIds = plan.steps.map { it.id }.toSet()
        for (s in plan.steps) {
            if (s.recipe != null && s.recipe !in recipeIds) {
                add(PlanError(3, "Step '${s.title}' names an unknown recipe '${s.recipe}'."))
            }
            for (a in s.after.filter { it !in stepIds }) {
                add(PlanError(3, "Step '${s.title}' comes after an unknown step '$a'."))
            }
        }
    }

    private fun duplicates(ids: List<String>): List<String> =
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()

    // Rule 4
    private fun cycles(plan: Plan): List<PlanError> {
        val byId = plan.steps.associateBy { it.id }
        val deps = byId.mapValues { (_, s) -> s.after.filter { it in byId }.toSet() }
        val left = byId.keys.toMutableSet()
        // Peel steps with nothing left before them, then steps with nothing left after them: a cycle remains.
        var changed = true
        while (changed) {
            changed = false
            val dependents = left.flatMap { id -> deps.getValue(id).filter { it in left } }.toSet()
            val peel = left.filter { id -> deps.getValue(id).none { it in left } || id !in dependents }
            if (peel.isNotEmpty()) {
                left.removeAll(peel.toSet())
                changed = true
            }
        }
        if (left.isEmpty()) return emptyList()
        val titles = left.joinToString(", ") { "'${byId.getValue(it).title}'" }
        return listOf(PlanError(4, "These steps wait on each other in a circle: $titles."))
    }

    // Rule 5
    private fun stepNumbers(plan: Plan): List<PlanError> = buildList {
        for (s in plan.steps) {
            if (s.durationSec <= 0 || s.durationSec > MAX_DURATION_SEC) {
                add(PlanError(5, "Step '${s.title}' lasts ${s.durationSec} seconds, but it must be 1 to $MAX_DURATION_SEC."))
            }
            if (s.plannedStartSec < 0) {
                add(PlanError(5, "Step '${s.title}' is planned to start at ${s.plannedStartSec} seconds, before the session begins."))
            }
        }
    }

    // Rule 8
    private fun interruptRules(plan: Plan): List<PlanError> = buildList {
        for (s in plan.steps.filter { it.interrupt }) {
            if (s.kind == Kind.PASSIVE) {
                add(PlanError(8, "Step '${s.title}' is passive, so it cannot be an interrupt."))
            } else if (s.durationSec > MAX_INTERRUPT_SEC) {
                add(PlanError(8, "Step '${s.title}' is an interrupt but lasts ${s.durationSec} seconds; the limit is $MAX_INTERRUPT_SEC."))
            }
        }
    }

    // Rule 9
    private fun ingredientRules(plan: Plan): List<PlanError> = buildList {
        for (r in plan.recipes) {
            if (r.servings != null && r.servings <= 0) {
                add(PlanError(9, "Recipe '${r.name}' has servings of ${r.servings}, but it must be more than 0."))
            }
            if (r.ingredients.size > MAX_INGREDIENTS) {
                add(PlanError(9, "Recipe '${r.name}' has ${r.ingredients.size} ingredients, but the limit is $MAX_INGREDIENTS."))
            }
            for (i in r.ingredients) {
                val label = if (i.name.isBlank()) "An ingredient in '${r.name}'" else "Ingredient '${i.name}' in '${r.name}'"
                if (i.name.isBlank()) add(PlanError(9, "$label has no name."))
                if (i.amount != null && i.amount <= 0) add(PlanError(9, "$label has an amount of ${i.amount}, but it must be more than 0."))
                if (i.grams != null && i.grams <= 0) add(PlanError(9, "$label has ${i.grams} grams, but it must be more than 0."))
                if (i.grams != null && i.amount == null) add(PlanError(9, "$label gives grams but no amount."))
            }
        }
    }

    // Rule 10
    private fun usesRules(plan: Plan): List<PlanError> = buildList {
        val recipes = plan.recipes.associateBy { it.id }
        for (s in plan.steps.filter { it.uses.isNotEmpty() }) {
            val recipe = s.recipe?.let { recipes[it] }
            if (s.recipe == null) {
                add(PlanError(10, "Step '${s.title}' has no recipe, so it cannot use ingredients."))
            } else if (recipe != null) {
                val known = recipe.ingredients.mapNotNull { it.id }.toSet()
                for (u in s.uses.filter { it !in known }) {
                    add(PlanError(10, "Step '${s.title}' uses ingredient '$u', which is not in '${recipe.name}'."))
                }
            }
        }
    }

    // Rule 11
    private fun toolReferences(plan: Plan): List<PlanError> = buildList {
        val tools = plan.tools.associateBy { it.id }
        for (t in plan.tools.filter { it.count < 1 }) {
            add(PlanError(11, "Tool '${t.name}' has a count of ${t.count}, but it must be at least 1."))
        }
        for (s in plan.steps) {
            for (use in s.tools) {
                val tool = tools[use.id]
                if (tool == null) {
                    add(PlanError(11, "Step '${s.title}' uses an unknown tool '${use.id}'."))
                } else if (tool.count > 1 && (use.needs != null || use.leaves != null)) {
                    add(PlanError(11, "Step '${s.title}' gives a state for '${tool.name}', which has ${tool.count} units and carries no state."))
                }
            }
        }
    }

    // Rule 6
    private fun afterOrder(plan: Plan): List<PlanError> = buildList {
        val byId = plan.steps.associateBy { it.id }
        for (s in plan.steps) {
            for (a in s.after.mapNotNull { byId[it] }) {
                if (s.plannedStartSec < end(a)) {
                    add(PlanError(6, "Step '${s.title}' starts at ${clock(s.plannedStartSec)}, before '${a.title}' ends at ${clock(end(a))}."))
                }
            }
        }
    }

    // Rule 7
    private fun oneCook(plan: Plan): List<PlanError> = buildList {
        val hands = plan.steps.filter { it.kind == Kind.ACTIVE && !it.interrupt }.sortedBy { it.plannedStartSec }
        for ((i, a) in hands.withIndex()) {
            for (b in hands.drop(i + 1).takeWhile { it.plannedStartSec < end(a) }) {
                add(
                    PlanError(
                        7,
                        "Steps '${a.title}' (${clock(a.plannedStartSec)} to ${clock(end(a))}) and " +
                            "'${b.title}' (${clock(b.plannedStartSec)} to ${clock(end(b))}) overlap, but one cook cannot do both.",
                    ),
                )
            }
        }
    }

    /** A step's occupancy of one tool: starts and ends are processed in time order, ends first at a tie. */
    private class Use(val step: Step, val time: Int, val isEnd: Boolean)

    private fun usesOf(plan: Plan, tool: Tool): List<Use> =
        plan.steps.filter { s -> s.tools.any { it.id == tool.id } }
            .flatMap { listOf(Use(it, it.plannedStartSec, false), Use(it, end(it), true)) }
            .sortedWith(compareBy<Use> { it.time }.thenByDescending { it.isEnd })

    // Rule 12
    private fun toolCapacity(plan: Plan): List<PlanError> = buildList {
        for (tool in plan.tools) {
            val active = mutableListOf<Step>()
            var overloaded = false
            for (u in usesOf(plan, tool)) {
                if (u.isEnd) {
                    active.remove(u.step)
                    if (active.size <= tool.count) overloaded = false
                    continue
                }
                active.add(u.step)
                if (active.size > tool.count && !overloaded) {
                    overloaded = true
                    val names = active.joinToString(", ") { "'${it.title}'" }
                    val verb = if (tool.count == 1) "there is only 1" else "there are only ${tool.count}"
                    add(PlanError(12, "${tool.name} is needed by ${active.size} steps at ${clock(u.time)} but $verb: $names."))
                }
            }
        }
    }

    // Rule 13
    private fun toolState(plan: Plan): List<PlanError> = buildList {
        for (tool in plan.tools.filter { it.count == 1 }) {
            var state = tool.initial
            for (u in usesOf(plan, tool)) {
                val use = u.step.tools.first { it.id == tool.id }
                if (u.isEnd) {
                    use.leaves?.let { state = it }
                } else if (use.needs != null && use.needs != state) {
                    add(PlanError(13, "${tool.name} is '$state' at ${clock(u.time)}, but '${u.step.title}' needs it '${use.needs}'."))
                }
            }
        }
    }

    private fun end(s: Step) = s.plannedStartSec + s.durationSec

    /** Seconds as h:mm, floored to the minute. */
    private fun clock(seconds: Int): String {
        val minutes = Math.floorDiv(seconds, 60)
        return "%d:%02d".format(minutes / 60, minutes % 60)
    }
}
