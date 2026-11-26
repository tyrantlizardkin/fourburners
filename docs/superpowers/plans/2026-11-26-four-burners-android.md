# Four Burners Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the native Android app "Four Burners", which imports a `cookplan` v1 JSON plan and runs it live: one current task, unattended timers that ring like alarms, and what comes next.

**Architecture:** Single-activity Compose app. Three pure-Kotlin packages (`model`, `parse`, `engine`) hold all behavior; the engine is one event-driven simulator used both to catch real state up to the wall clock (`advance`) and to predict the rest of the cook (`project`). A single `SessionController` is the only place that applies an engine action, persists the result and resyncs alarms; the UI, notification actions and boot receiver all go through it.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Navigation Compose, `kotlinx.serialization`, `ViewModel` + `StateFlow`, JUnit 4. `minSdk` 26. No DI framework, no Room, no DataStore (plain app-private JSON files), no network.

**Spec:** `plans/four-burners-android-spec.md` (source of truth). Also `plans/sample-plan.json`, `plans/mockups/README.md`, `plans/brand/README.md`. Section numbers below (§) refer to the spec.

## How this plan is organized

The spec fixes six milestones (§3) with a **mandatory stop for review after each**. This plan has one part per milestone.

- **Milestone 1 is written to step level.** Everything else rests on it and its behavior is fully pinned by §13.
- **Milestones 2 to 6 are written to task level:** files, interfaces, decisions, tests and verification per task. They are executable as written; at each review stop, re-read the next part against the code that actually exists and adjust names before starting.

At the end of every milestone: `./gradlew assembleDebug` and `./gradlew test` pass, commit, and append to `docs/milestones.md` a note listing each thing verified and how (unit test, emulator, physical device, or not verified). Then stop.

## Ten-day schedule

One session per day, about 1,500 lines a day (estimated total about 15,000). Each day ends with a green build and its work committed that day. Days that end a milestone also end with the milestone note and the review stop; the next day does not start until the review is done.

| Day | Tasks | What exists at the end | Est. lines |
|---|---|---|---|
| 1 | 1–4 | SDK installed, project builds, model, parser, validator | 1,500 |
| 2 | 5–6 | Simulator, projection, all engine actions | 1,550 |
| 3 | 7–9 | Tool and ingredient queries, alert logic, debug screen. **Milestone 1 review** | 1,000 |
| 4 | 10–13 | Stores, formatting, theme and navigation, Empty and Import, share/URL/paste | 2,150 |
| 5 | 14–16 | Live, Full schedule, Done, lifecycle. **Milestone 2 review** | 1,550 |
| 6 | 17–21 | Alarms, ringing, notifications, boot. Needs a physical device. **Milestone 3 review** | 1,500 |
| 7 | 22–24 | Recipe card with scaling and grams, Kitchen. **Milestone 4 review** | 1,800 |
| 8 | 25–27 | Volume buttons, undo bar, timer sheet, clock times. **Milestone 5 review** | 1,100 |
| 9 | 28–29 | Motion tokens and every animation in §11.1 | 1,700 |
| 10 | 30–33 | Haptics, hold gestures, brand assets, gallery, finish pass, README. **Final review** | 1,300 |

Days 4 and 7 are the heaviest; if either runs long, its last task moves to the next morning rather than being rushed. Day 6 is the one most likely to slip, because the alarm checks have to be done on a real phone.

## Global Constraints

- App name **Four Burners** (launcher label, notifications, share target). The format stays `cookplan`: do not rename the JSON key or URL scheme.
- Application id and root package: `app.fourburners`.
- `minSdk` 26; `targetSdk`/`compileSdk` latest stable. AGP, Kotlin, Compose BOM, serialization: look up current stable versions when scaffolding; do not pin from memory.
- No `INTERNET` permission in the merged manifest.
- `model`, `parse`, `engine` (and the pure `recipe` and `format` packages this plan adds) contain no `android.*` or `androidx.*` imports.
- The engine never reads the clock; every function takes `nowMs`.
- Do not silently deviate from the §2 decisions table. If one is unworkable, stop and say so.
- Dark theme only, portrait only. Colors, type, radii (8, 12, 14, 24 dp) and springs exactly as §11.
- Copy: sentence case, no exclamation marks, no emoji. Real characters (°, ×, ½, en dash). Durations under a minute read "20 s".
- Live screen: no tappable target under 56 dp, Done 76 dp high and ≥ 230 dp wide, no swipe/drag/long-press-only routes.
- Brand SVGs are converted, never redrawn, recolored or animated.

## Decisions this plan makes where the spec is silent

Flagged so the reviewer can veto them before Milestone 1 starts.

1. **Validator returns every error, not the first.** The §13 tool-capacity test (move `b_pot` to 900) also breaks rule 7 (overlaps `r2`), so tests assert "contains an error for rule N".
2. **`Session` gains one field, `undo: UndoPoint? = null`.** §7.1 has no place to keep what `undo()` restores. Additive; everything else in §7.1 is verbatim.
3. **One cook, including interrupts.** A new non-interrupt active step does not start while any active step (interrupt or not) is RUNNING. An interrupt becomes RUNNING the moment its `after` steps are DONE, whatever else is running, and does not wait for tools.
4. **Now** is the RUNNING non-interrupt active step if there is one, otherwise the earliest-started RUNNING interrupt. Other RUNNING interrupts are bars.
5. **`extend` on a timer that ended ≤ 60 s ago** reopens it: its dependents that started because of it go back to PENDING. Refused if any dependent is already DONE.
6. **Negative `extend`** is clamped so at least 30 s remain; refused if under 30 s already remain.
7. **Refused or meaningless actions** (`done` on a step that is not RUNNING, `undo` after 30 s) return the session unchanged. This also makes double presses harmless.
8. **Per-recipe scale** lives in the settings file keyed by recipe id and is cleared when the plan is replaced or closed (§9.8 says "with the plan", §12 says "separately from the session").
9. **Storage is two app-private JSON files** written atomically (`session.json`, `settings.json`). §2 allows this; it keeps `BootReceiver` synchronous.
10. **Project lives at the repo root**; `plans/` stays as the spec folder and is committed with the scaffold.

## Review Focus

Inputs the spec implies but its test list does not exercise. Each has a test in the task named.

1. **Prose with braces before the plan, and braces inside JSON strings** ("use a {large} pan", a `detail` containing `}`): extraction must skip non-plan candidates and respect string literals. → Task 3.
2. **Wrong types and missing required fields** (`"duration_sec": "480"`, a step with no `title`): a readable "Plan not loaded" error naming the field, never a crash. → Task 3.
3. **Double press of Done / volume down:** the second `done` on the same step is a no-op and must not complete the next step. → Task 6.
4. **Device clock moved backwards mid-session** (`nowMs` earlier than a stored timestamp): `advance` and `project` return without throwing and without un-finishing steps. → Task 5.
5. **Corrupt or unreadable `session.json`** (truncated write, older shape): app opens on Empty, file is discarded, no crash loop. → Task 10.

---

# Milestone 1: Core

## File structure

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew(.bat), .gitignore
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/assets/sample-plan.json          copy of plans/sample-plan.json
app/src/main/java/app/fourburners/
  model/Plan.kt          Plan, Recipe, Ingredient, Tool, Step, ToolUse, Kind
  model/Session.kt       Session, StepState, Status, UndoPoint
  parse/PlanExtractor.kt find the JSON object in shared text (§5.4)
  parse/PlanValidator.kt rules 2–13 (§5.3)
  parse/PlanParser.kt    extract → decode → validate; ParseResult, PlanError
  engine/Simulator.kt    internal event loop shared by advance and project
  engine/SessionEngine.kt public actions and queries (§7)
  engine/Alerts.kt       ringing set, ring cadence, alarm times (§8 logic)
  ui/DebugScreen.kt      dumps engine state for the sample plan
  MainActivity.kt
app/src/test/java/app/fourburners/
  TestPlans.kt           reference(), JSON mutation helpers, drive()
  parse/PlanExtractorTest.kt, PlanValidatorTest.kt
  engine/EngineScheduleTest.kt, EngineActionsTest.kt, EngineQueriesTest.kt, AlertsTest.kt
  PurityTest.kt
```

### Task 1: Toolchain and project scaffold

This machine has JDK 17 (`JAVA_HOME`) and 21, and `adb`, but **no Android SDK and no Gradle**.

**Files:** Create the Gradle files, manifest, `MainActivity.kt`, `.gitignore`, `app/src/main/assets/sample-plan.json`, `app/src/test/java/app/fourburners/PurityTest.kt`.

**Interfaces:** Produces a building project with package `app.fourburners`; later tasks add to it.

- [ ] **Step 1:** Install the Android command-line tools to `%LOCALAPPDATA%\Android\Sdk`, then with `sdkmanager` install `platform-tools`, the latest stable `platforms;android-NN` and matching `build-tools`. Write `local.properties` with `sdk.dir` (gitignored).
- [ ] **Step 2:** Look up the current stable AGP, the Gradle version it requires, Kotlin, the Compose BOM and `kotlinx-serialization-json`. Record them in `gradle/libs.versions.toml`. Generate the Gradle wrapper for that Gradle version.
- [ ] **Step 3:** Scaffold a single-module Compose app: `applicationId`/`namespace` `app.fourburners`, `minSdk` 26, label "Four Burners", one `singleTop` portrait `MainActivity` showing the text "Four Burners". Plugins: Android application, Kotlin Android, Compose compiler, Kotlin serialization. Test dependency: JUnit 4. No `INTERNET` permission.
- [ ] **Step 4:** Copy `plans/sample-plan.json` to `app/src/main/assets/sample-plan.json`.
- [ ] **Step 5:** Write `PurityTest`:

```kotlin
@Test fun `pure packages have no android imports`() {
    val roots = listOf("model", "parse", "engine", "recipe", "format")
        .map { File("src/main/java/app/fourburners/$it") }.filter { it.exists() }
    val bad = roots.flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        .filter { f -> f.readLines().any { l -> l.startsWith("import android") } }
    assertEquals(emptyList<File>(), bad)
}
```

- [ ] **Step 6:** Run `./gradlew assembleDebug test`. Expected: BUILD SUCCESSFUL, 1 test passed.
- [ ] **Step 7:** Commit scaffold together with the untracked `plans/` folder and this plan: `chore: scaffold Android project and add spec package`.

### Task 2: Model

**Files:** Create `model/Plan.kt`, `model/Session.kt`, `TestPlans.kt`. Test: `parse/PlanExtractorTest.kt` (first test only).

**Interfaces — Produces** (all `@Serializable`; JSON names via `@SerialName` where they differ):

```kotlin
enum class Kind { @SerialName("active") ACTIVE, @SerialName("passive") PASSIVE }
data class Tool(val id: String, val name: String, val count: Int = 1, val initial: String = "clean")
data class Ingredient(val id: String? = null, val name: String, val amount: Double? = null,
                      val unit: String? = null, val grams: Double? = null, val note: String? = null)
data class Recipe(val id: String, val name: String, val short: String? = null,   // UI falls back to name
                  val servings: Double? = null, val ingredients: List<Ingredient> = emptyList(),
                  val notes: String? = null)
data class ToolUse(val id: String, val needs: String? = null, val leaves: String? = null)
data class Step(val id: String, val recipe: String? = null, val kind: Kind, val title: String,
                val detail: String? = null,
                @SerialName("duration_sec") val durationSec: Int,
                @SerialName("planned_start_sec") val plannedStartSec: Int,
                val after: List<String> = emptyList(), val uses: List<String> = emptyList(),
                val tools: List<ToolUse> = emptyList(), val interrupt: Boolean = false,
                @SerialName("timer_label") val timerLabel: String? = null,
                @SerialName("max_hold_sec") val maxHoldSec: Int? = null)
data class Plan(val cookplan: Int, val title: String? = null, val tools: List<Tool> = emptyList(),
                val recipes: List<Recipe>, val steps: List<Step>)

enum class Status { PENDING, RUNNING, DONE }
data class StepState(val status: Status = Status.PENDING, val startedAtMs: Long? = null,
                     val endedAtMs: Long? = null, val extraSec: Int = 0,
                     val acknowledgedAtMs: Long? = null)
data class UndoPoint(val stepId: String, val doneAtMs: Long,
                     val prevSteps: Map<String, StepState>, val prevSlipSec: Int)
data class Session(val plan: Plan, val startedAtMs: Long, val steps: Map<String, StepState>,
                   val slipSec: Int, val undo: UndoPoint? = null)
```

`TestPlans.kt` produces: `val planJson: Json` (`ignoreUnknownKeys = true; isLenient = true`), `fun referenceText(): String` (reads `src/main/assets/sample-plan.json`), `fun reference(): Plan`, `fun referenceEdited(edit: (MutableMap<String, JsonElement>) -> Unit): String` for building invalid variants, and `fun stepJson(id: String, vararg overrides: Pair<String, JsonElement>): JsonObject`.

- [ ] **Step 1:** Write the failing test `reference plan decodes`: `reference()` has 3 recipes, 18 steps, 7 tools; step `c_in` has `interrupt == true` and `after == listOf("c1","c_pre")`; tool `burners` has `count == 2`; tool `sheetpan` has `initial == "clean"`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests "*PlanExtractorTest*"`. Expected: compile failure.
- [ ] **Step 3:** Write the model and `TestPlans.kt` as above.
- [ ] **Step 4:** Run again. Expected: PASS.
- [ ] **Step 5:** Commit `feat: plan and session model`.

### Task 3: Extraction and parsing (§5.4)

**Files:** Create `parse/PlanExtractor.kt`, `parse/PlanParser.kt`. Test: `parse/PlanExtractorTest.kt`.

**Interfaces — Produces:**

```kotlin
data class PlanError(val rule: Int, val message: String)   // rule 0 = not found / malformed
sealed interface ParseResult {
    data class Ok(val plan: Plan) : ParseResult
    data class Failed(val errors: List<PlanError>, val received: String) : ParseResult
}
object PlanExtractor { fun extract(text: String): JsonObject? }
object PlanParser { fun parse(text: String): ParseResult }   // Task 4 adds the validator call
```

- [ ] **Step 1:** Write failing tests in `PlanExtractorTest`:
  - `fenced block inside prose` → `Ok`, 18 steps.
  - `bare json` → `Ok`.
  - `no plan` (`"dinner at 7?"`) → `Failed`, single error, rule 0, message `"No cook plan found in the shared text."`.
  - `two code blocks, only second is a plan` (first is ```` ```json {"a":1} ``` ````) → `Ok`.
  - `prose with braces before the plan` (`"use a {large} pan\n" + referenceText()`) → `Ok`. *(Review Focus 1)*
  - `brace inside a json string` (reference with `c1.detail = "Skin } side { up"`, unfenced, prose around it) → `Ok`, detail preserved. *(Review Focus 1)*
  - `cookplan 2` → `Failed`, rule 1.
  - `wrong type` (`c1.duration_sec = "eight minutes"`) → `Failed`, rule 0, message contains `duration_sec`. *(Review Focus 2)*
  - `missing required field` (remove `title` from `c1`) → `Failed`, rule 0, message contains `title`. *(Review Focus 2)*
  - `broken json is not repaired` (reference with the final `}` removed) → `Failed`, rule 0.
- [ ] **Step 2:** Run. Expected: FAIL (unresolved references).
- [ ] **Step 3:** Implement `PlanExtractor.extract`. Candidates in order: the content of each ``` fenced block, then each balanced `{...}` substring found by scanning from every `{` with a depth counter that ignores braces inside double-quoted strings (honoring `\"`). Return the first candidate that parses to a `JsonObject` containing the key `cookplan`.
- [ ] **Step 4:** Implement `PlanParser.parse`: extract (null → rule 0 "No cook plan found…"); `cookplan` not the number 1 → rule 1 `"This is not a cookplan version 1 plan."`; `decodeFromJsonElement<Plan>` wrapped so a `SerializationException` becomes rule 0 `"The plan is malformed: <field and reason>."`. `received` is always the original text.
- [ ] **Step 5:** Run. Expected: PASS.
- [ ] **Step 6:** Commit `feat: plan extraction and parsing`.

### Task 4: Validator (§5.3)

**Files:** Create `parse/PlanValidator.kt`; modify `parse/PlanParser.kt` (call it after decode; non-empty → `Failed`). Test: `parse/PlanValidatorTest.kt`.

**Interfaces — Produces:** `object PlanValidator { fun validate(plan: Plan): List<PlanError> }`. Returns all errors, ordered by rule number. Messages name steps by **title**, tools by **name**, and times as `h:mm` floored to the minute, matching the spec's example (900 s → `0:15`). If rules 2–5 or 11 produce errors, skip the schedule rules 6, 7, 12, 13 (they assume well-formed references).

- [ ] **Step 1:** Write failing tests, one per rule, each asserting `validate(...)` contains an error with that `rule`:
  - 2: five recipes; zero steps; 31 tools.
  - 3: duplicate step id; step with `recipe: "zz"`; `after: ["nope"]`; duplicate ingredient id within one recipe.
  - 4: `r1.after = ["r2"]` (r2 already after r1).
  - 5: `duration_sec` 0; `duration_sec` 21601; `planned_start_sec` -1.
  - 6: `c_in.planned_start_sec = 700` (before `c_pre` ends at 740).
  - 7: `r1.planned_start_sec = 400` (overlaps `c1`).
  - 8: `interrupt: true` on `c2` (passive); `c_in.duration_sec = 121`.
  - 9: ingredient `name: ""`; `amount: 0`; `grams` without `amount`; 61 ingredients; `servings: 0`.
  - 10: `c1.uses = ["rice"]`; a `recipe: null` step with `uses`.
  - 11: step tool `id: "wok"`; tool `count: 0`; `needs` on `burners`.
  - 12: `b_pot.planned_start_sec = 900` plus a new passive step `x` (`recipe: null`, 900–960, tools `burners`) → error rule 12 whose message contains `"Burners"` and `"0:15"`.
  - 13: `c_in` oven `needs: "450°F"` → error rule 13 whose message contains `"Oven"`, `"425°F"`, `"450°F"`, `"Chicken into the oven"` and `"0:12"`.
  - Accepts: `reference()` → empty list. A plan with no `tools` and no `ingredients` (reference with both stripped, plus `uses` and step `tools` removed) → empty list.
- [ ] **Step 2:** Run `--tests "*PlanValidatorTest*"`. Expected: FAIL.
- [ ] **Step 3:** Implement. Rule 12: sweep per tool over planned `[start, end)` intervals, processing ends before starts at the same second. Rule 13: per single-count tool, sort that tool's uses by planned start; walk them applying `leaves` at planned end (ends before starts at the same second); compare `needs` at planned start. Message shape for 13 is fixed by the spec: `"Cutting board is 'raw chicken' at 0:31, but 'Trim the beans' needs it 'clean'."`
- [ ] **Step 4:** Run. Expected: PASS, and Task 3's tests still pass.
- [ ] **Step 5:** Commit `feat: plan validator with tool capacity and state rules`.

### Task 5: Simulator, `advance`, `project`, `view` (§7.1–7.5)

**Completed 2026-11-26:** `bb471c5`. Debug build and 68 unit tests passed; separate spec and code-quality reviews passed. Twelve scheduling tests cover the reference projection, timer catch-up, clock rollback, event ordering, tool capacity, and interrupts.

**Files:** Create `engine/Simulator.kt`, `engine/SessionEngine.kt`. Test: `engine/EngineScheduleTest.kt`; extend `TestPlans.kt`.

**Interfaces — Produces:**

```kotlin
data class ProjectedStep(val stepId: String, val startMs: Long, val endMs: Long)
data class FreeInfo(val nextStepId: String?, val untilMs: Long?)
data class LiveState(val nowStepId: String?, val interruptIds: List<String>,
                     val timerIds: List<String>,      // RUNNING passive, by end time
                     val free: FreeInfo?, val finished: Boolean)
object SessionEngine {
    fun start(plan: Plan, nowMs: Long): Session            // all PENDING, slip 0, then advance
    fun advance(session: Session, nowMs: Long): Session    // idempotent catch-up to nowMs
    fun project(session: Session, nowMs: Long): List<ProjectedStep>   // plan order
    fun view(session: Session, nowMs: Long): LiveState
    fun elapsedSec(session: Session, nowMs: Long): Int     // floor, never negative
}
```

Test support in `TestPlans.kt`: `const val T0 = 1_000_000_000_000L`; `fun at(sec: Int) = T0 + sec * 1000L`; `fun drive(start: Session, untilSec: Int, late: Map<String, Int> = emptyMap()): Session`, which repeatedly advances to the next moment an active step is due to be marked done (its `startedAtMs + durationSec + extraSec + late[id]`), calls `done`, and stops once the next one is after `untilSec` (a done due exactly at `untilSec` is applied), returning the session advanced to `at(untilSec)`. (`drive` needs `done` from Task 6; in this task use `project` and `advance` only.)

**The algorithm** (the signatures do not determine it). `Simulator.run(session, untilMs, assumeDone: Boolean, nowMs)` loops:

1. Find the earliest event time `e`:
   - end of a RUNNING passive: `startedAtMs + (durationSec + extraSec) * 1000`;
   - if no active step is RUNNING: the moment the best PENDING non-interrupt active with all `after` DONE and all tools free becomes eligible. That is `startedAtMs + (plannedStartSec + slipSec) * 1000`, or immediately if any step in its `after` is passive;
   - only when `assumeDone`: assumed end of each RUNNING active, `max(startedAtMs + (durationSec + extraSec) * 1000, nowMs)`.
2. If none, or `e > untilMs`, stop.
3. Apply at `e`, **ends first, then starts**: mark due passives DONE with `endedAtMs = e`; (when `assumeDone`) mark due actives DONE via the same code path `done` uses, including the slip update; then repeat until nothing changes: start every PENDING passive whose `after` are all DONE; set RUNNING every PENDING interrupt whose `after` are all DONE; if no active is RUNNING, choose the Now step among eligible steps (timer follow-ups first, by latest passive-`after` end ascending; then `plannedStartSec`; then plan order) and set it RUNNING with `startedAtMs = e`.

`advance = run(untilMs = nowMs, assumeDone = false)`. `project` = `advance`, then `run(untilMs = Long.MAX_VALUE, assumeDone = true)`, reading each step's start and end. A tool is occupied by any RUNNING step listing it, up to the tool's `count`. A session is finished when every step is DONE. Event times never go backwards: if `nowMs` is before an already-recorded timestamp, nothing is un-done and no event fires.

- [x] **Step 1:** Write failing tests:
  - `start runs c_on immediately`: `view(start(reference(), T0), T0).nowStepId == "c_on"`.
  - `on-plan projection ends at 3170`: `project(start(reference(), T0), T0)` gives `c4.endMs == at(3170)`, `r1.startMs == at(500)`, `c2.startMs == at(770)`, `b2.startMs == at(2330)`, `r6.startMs == at(2930)`.
  - `passives start and finish by themselves`: `start` at T0, then `advance` to `at(5)`: `c_on` RUNNING, `c_pre` PENDING.
  - `advance is idempotent and composable`: for the session with `c_on` hand-marked DONE at `at(20)`, `advance(advance(s, at(300)), at(900)) == advance(s, at(900))`.
  - `overrunning active projects its end at now`: session with `c1` RUNNING since `at(20)`; at `at(590)` (90 s over) `project` gives `c1.endMs == at(590)` and `r1.startMs == at(590)`.
  - `clock moved backwards does not throw or un-finish` *(Review Focus 4)*: take a session advanced to `at(900)`, call `advance(s, at(100))` and `project(s, at(100))`: no exception, every step that was DONE is still DONE, `elapsedSec(s, T0 - 5000) == 0`.
- [x] **Step 2:** Run `--tests "*EngineScheduleTest*"`. Expected: FAIL.
- [x] **Step 3:** Implement `Simulator` and the five `SessionEngine` functions.
- [x] **Step 4:** Run. Expected: PASS.
- [x] **Step 5:** Commit `feat: session simulator, advance and projection`.

### Task 6: Actions (§7.3, 7.4, 7.8)

**Completed 2026-11-26:** `0e400f7`, with undo/acknowledgement history corrections in `b4c1f84` and `8f1944d`. Debug build and 92 unit tests passed (24 action tests, 12 scheduling tests, 56 existing tests). Regression tests preserve timer ends and follow-up starts across Silence, undo, and serialization; unrelated timer completions do not block undo, while timers started by Done still do. The undo guard below clarifies the source spec's causal rule using the saved pre-Done state. Verified by JVM tests and debug APK build; no emulator or physical-device checks were performed for this pure-Kotlin scope.

**Files:** Modify `engine/SessionEngine.kt`, `TestPlans.kt` (`drive`). Test: `engine/EngineActionsTest.kt`.

**Interfaces — Produces** (each calls `advance(session, nowMs)` first and returns a new `Session`):

```kotlin
fun done(session: Session, stepId: String, nowMs: Long): Session
fun startEarly(session: Session, nowMs: Long): Session
fun extend(session: Session, stepId: String, sec: Int, nowMs: Long): Session
fun acknowledge(session: Session, stepId: String, nowMs: Long): Session
fun undo(session: Session, nowMs: Long): Session
fun canUndo(session: Session, nowMs: Long): Boolean
```

Rules beyond the spec text: see "Decisions" 3–7. `done` stores `UndoPoint(stepId, nowMs, steps before, slip before)`; `startEarly`, `extend` and another `done` replace or clear it; `acknowledge` keeps it and also applies the acknowledgement to `prevSteps`. `undo` is allowed when `nowMs - doneAtMs <= 30_000` and no passive step that was PENDING in `prevSteps` and has `startedAtMs == doneAtMs` is DONE (a timer already RUNNING before Done must not block undo); it restores `prevSteps` and `prevSlipSec`, then advances. Slip is only updated by non-interrupt active steps: `max(slipSec, elapsedAtDone - (plannedStartSec + durationSec))`.

- [x] **Step 1:** Write failing tests (all on `reference()` unless stated):
  - `on plan finishes at 3170 with slip 0`: `drive(start, 3170)` → `view.finished`, `slipSec == 0`, max `endedAtMs == at(3170)`.
  - `late c1 slips plan-timed steps only`: `drive(start, 760, late = mapOf("c1" to 180))` → `slipSec == 180`, `r1.startedAtMs == at(680)`, `c_pre.endedAtMs == at(740)`; projected `c2.endMs - c2.startMs == 2_100_000`; projected `r2.startMs == at(1055)`.
  - `c_in interrupts a running r1`: same session at `at(760)` → `view.nowStepId == "r1"`, `view.interruptIds == listOf("c_in")`, `r1.startedAtMs` still `at(680)`.
  - `c3 becomes Now at the roast timer end regardless of slip`: drive with `late = mapOf("c1" to 60, "b3" to -120)` so nothing is running when `c2` ends → at `c2.endedAtMs`, `view.nowStepId == "c3"`.
  - `free after b1, startEarly makes b_pot Now`: `drive(start, 1535)` then at `at(1540)`: `view.free!!.nextStepId == "b_pot"`, `view.free.untilMs == at(1790)`; after `startEarly`, `nowStepId == "b_pot"`.
  - `occupied tool blocks eligibility` (custom plan from §13: pot count 1, passive P 0–600 on it, active A on it planned 600, no `after`): `extend(P, 120)` at `at(300)`; at `at(650)` `view.nowStepId == null`; at `at(720)` it is `"A"`.
  - `extend moves a timer and its follow-up`: at `at(1000)` `extend("c2", 60)` → projected `c2.endMs == at(2930)`, `c3.startMs == at(2930)`.
  - `negative extend keeps 30 s`: with `c_pre` at 100 s remaining, `extend("c_pre", -600)` → ends 30 s after now; with 20 s remaining, unchanged.
  - `extend reopens a timer ended under 60 s ago`: `c2` ended at `at(2870)`, `c3` RUNNING; at `at(2890)` `extend("c2", 60)` → `c2` RUNNING ending `at(2930)`, `c3` PENDING. At `at(2940)` instead → unchanged.
  - `extend on an active step moves later steps at once, slip measured at done`: during `b1`, `extend("b1", 60)` → projected `b_pot.startMs == at(1850)`, `b2.startMs == at(2390)`, `b3.startMs == at(2630)`, `c3.startMs == at(2870)` (the roast is untouched); `done("b1")` at `at(1595)` → `slipSec == 60`.
  - `undo within 30 s restores`: `done("r1")` at `at(740)`, `undo` at `at(765)` → `steps` and `slipSec` equal those of `advance(before, at(765))` and `undo == null`; `canUndo` at `at(771)` is false and `undo` returns the session unchanged.
  - `undo refused once a passive it started has ended` (custom plan: active A, then passive P of 10 s after A): `done(A)`, `undo` 15 s later → unchanged.
  - `double done is a no-op` *(Review Focus 3)*: `done("c_on", at(20))` twice in a row → second result equals the first; `c1` is RUNNING, not DONE.
  - `restore after process death`: `drive(start, 800)` then `advance(s, at(1400))` in one jump equals advancing through `at(900)`, `at(1000)`, … `at(1400)` in 100 s hops.
- [x] **Step 2:** Run `--tests "*EngineActionsTest*"`. Expected: FAIL.
- [x] **Step 3:** Implement the six functions and `drive`.
- [x] **Step 4:** Run the whole engine package. Expected: PASS.
- [x] **Step 5:** Commit `feat: engine actions with slip, interrupts and undo`.

### Task 7: Tool and ingredient queries (§7.6, 7.7)

**Files:** Modify `engine/SessionEngine.kt`. Test: `engine/EngineQueriesTest.kt`.

**Interfaces — Produces:**

```kotlin
data class ToolNow(val tool: Tool, val state: String, val occupiedBy: List<String>, val freeAtMs: Long?)
enum class IngredientState { IDLE, IN_USE, DONE }
fun toolStates(session: Session, nowMs: Long): List<ToolNow>          // plan order
fun toolWarning(session: Session, stepId: String): String?
fun ingredientStates(session: Session, recipeId: String): Map<String, IngredientState>  // keyed by ingredient id
```

Pool tools (`count > 1`) report `state == ""`. Warning copy: `"Cutting board is 'raw chicken'. This step expects 'clean'."`

- [ ] **Step 1:** Write failing tests:
  - `tool states at 800 s` (on plan): oven `state "425°F"`, `occupiedBy ["c2"]`, `freeAtMs == at(2870)`; sheet pan `"raw chicken"`; board `"onion"`; blanching pot `"clean"`, `occupiedBy` empty, `freeAtMs == null`.
  - `tool warning after starting early` (custom plan from §13: P passive 600 s; A after P, planned 600, board `needs "clean"`; B no `after`, planned 700, board `leaves "raw chicken"`): `startEarly` during P runs B; `done(B)`; when P ends A is Now and `toolWarning(s, "A")` contains the board's name, `'raw chicken'` and `'clean'`. `toolWarning` is null for every Now step of an on-plan reference run.
  - `chicken ingredients`: all five IN_USE while `c1` RUNNING, all DONE after.
  - `bean ingredients`: `beans` IN_USE during `b1`, IDLE after, IN_USE during `b2` and `b3`, DONE after `b3`; `salt` DONE after `b_pot`; `lemon` IDLE until `b3` starts.
- [ ] **Step 2:** Run. Expected: FAIL. **Step 3:** Implement. **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit `feat: tool and ingredient state queries`.

### Task 8: Alert logic (§8.1–8.3, logic only)

**Files:** Create `engine/Alerts.kt`. Test: `engine/AlertsTest.kt`.

**Interfaces — Produces:**

```kotlin
data class Ring(val stepId: String, val endedAtMs: Long, val followUpId: String?,
                val followUpIsInterrupt: Boolean, val holdPassed: Boolean)
enum class AlarmKind { TIMER_END, HOLD_WARNING, RING_RESUME }
data class AlarmAt(val stepId: String, val atMs: Long, val kind: AlarmKind)
object Alerts {
    fun followUp(plan: Plan, passiveId: String): Step?        // first active step with it in `after`
    fun rings(step: Step, plan: Plan): Boolean                // false: no follow-up and maxHoldSec >= 300
    fun ringing(session: Session, nowMs: Long): List<Ring>    // by endedAtMs
    fun quietlyEnded(session: Session, nowMs: Long): List<String>  // ended, non-ringing kind, unacknowledged
    fun soundOn(session: Session, nowMs: Long): Boolean
    fun alarms(session: Session, nowMs: Long): List<AlarmAt>  // everything AlarmManager must hold
}
```

A step is in `ringing` when: passive, DONE, `rings(...)`, `acknowledgedAtMs == null`, and its follow-up is not DONE. `soundOn`: true for the first 180 s after the oldest unacknowledged end, then true for the first 10 s of each following minute; also true for 30 s from `endedAtMs + maxHoldSec` when the follow-up is still not DONE, **even if acknowledged**. `alarms` returns a `TIMER_END` for every RUNNING passive, a `HOLD_WARNING` for every ended passive with `maxHoldSec` whose follow-up is not DONE and whose hold moment is in the future, and the next `RING_RESUME` minute boundary if anything is ringing past 180 s.

- [ ] **Step 1:** Write failing tests:
  - `finished roast rings until acknowledged`: at `at(2875)` `ringing` has `c2` with `followUpId "c3"`, `followUpIsInterrupt true`; after `acknowledge("c2")` it is empty.
  - `done(c3) stops it`; `extend(c2, 60) stops it`.
  - `rest timer never rings`: after `c4` ends, `ringing` is empty and `quietlyEnded == listOf("c4")`.
  - `cadence`: for a ring ended at `E`, `soundOn` is true at `E+179 s`, false at `E+185 s`, true at `E+245 s` (4:05 → inside the 10 s window at 4:00–4:10), false at `E+255 s`.
  - `hold warning resumes sound after acknowledge`: `c2` acknowledged, `c3` not done; `soundOn` true at `E+125 s` (hold is 120 s), false at `E+155 s`. Without the acknowledge, the `c2` ring has `holdPassed == true` at `E+125 s` and false at `E+100 s`.
  - `alarms on plan at 800 s`: exactly one `TIMER_END` for `c2` at `at(2870)`.
- [ ] **Step 2:** Run. Expected: FAIL. **Step 3:** Implement. **Step 4:** Run. Expected: PASS.
- [ ] **Step 5:** Commit `feat: alert logic for ringing set and alarm times`.

### Task 9: Debug screen and Milestone 1 close

**Files:** Create `ui/DebugScreen.kt`; modify `MainActivity.kt`; create `docs/milestones.md`.

- [ ] **Step 1:** `DebugScreen` loads `assets/sample-plan.json` through `PlanParser.parse`, starts a session at launch time, and each second shows monospace text: elapsed, slip, `view(...)`, each step's status and projected start/end, `toolStates`. A "Done" button calls `done` on the Now step. State is in memory only.
- [ ] **Step 2:** Run `./gradlew clean assembleDebug test`. Expected: BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 3:** Install on a device or emulator if one is available (`adb devices`); otherwise record "debug screen: not verified on device".
- [ ] **Step 4:** Write the Milestone 1 note in `docs/milestones.md`. Commit `feat: debug engine screen; milestone 1`. **Stop for review.**

---

# Milestone 2: Plain screens

Empty, Import, Live, Full schedule, Done, with persistence. No custom animation. Install the emulator (`sdkmanager "emulator" "system-images;android-NN;google_apis;x86_64"`, create an AVD) at the start if no physical device is attached; every UI task is checked there.

### Task 10: Stores and controller

**Files:** Create `data/SessionStore.kt`, `data/Settings.kt`, `data/SessionController.kt`, `data/Clock.kt`. Test: `data/SessionStoreTest.kt`, `data/SessionControllerTest.kt` (JVM, temp directories).

**Interfaces — Produces:**

```kotlin
fun interface Clock { fun nowMs(): Long }
fun interface AlarmGateway { fun sync(session: Session?, nowMs: Long) }   // no-op until Milestone 3
class SessionStore(dir: File) { fun load(): Session?; fun save(s: Session); fun clear() }
data class SettingsData(val showClockTimes: Boolean = false, val gramsMode: Boolean = false,
    val trackProgress: Boolean = true, val volumeButtons: Boolean = true,
    val reduceMotion: Boolean = false, val volumeHintShown: Boolean = false,
    val scales: Map<String, Double> = emptyMap())
class Settings(dir: File) { val data: StateFlow<SettingsData>; fun update(f: (SettingsData) -> SettingsData) }
class SessionController(store: SessionStore, alarms: AlarmGateway, clock: Clock) {
    val session: StateFlow<Session?>
    fun start(plan: Plan); fun end()
    fun act(action: (Session, Long) -> Session)   // apply, save, alarms.sync
    fun refresh()                                  // advance to now; save and sync only if changed
    val stale: Boolean                             // start in the future or > 12 h old (§12)
}
```

Both stores write to a temp file and rename. `java.io.File` only, so they are JVM-testable.

Tests: round-trip of a mid-cook session; `act` saves and calls `sync` once per action; `refresh` after 10 simulated minutes equals continuous running; `stale` at 13 h and at −1 min; **corrupt file** *(Review Focus 5)*: `session.json` containing `{"plan":` → `load()` returns null, the file is deleted, and a second `load()` is also null without throwing.

### Task 11: Formatting

**Files:** Create `format/Times.kt`. Test: `format/TimesTest.kt`.

**Produces:** `offset(sec): String`, h:mm floored to the minute, for step times and elapsed (2330 → "0:38"); `total(sec): String`, h:mm rounded half up, for plan length (3170 → "0:53"); `duration(sec): String` ("20 s", "8 min"); `remaining(sec)`: number + unit pair ("7","min left" / "40","s left" / "1","min over"); `countdown(sec)`: "mm:ss"; `clock(ms, zone, is24h)`. Tests pin: 3170 → "53 min" total; 2165 → "36 min" hands-on; 20 → "20 s"; 675 → "11 min"; 2490 → "42 min" (round half up).

### Task 12: Theme, fonts, navigation shell

**Files:** `ui/theme/Color.kt`, `Type.kt`, `Shape.kt`, `Theme.kt`; `res/font/*`; `ui/AppViewModel.kt`; `ui/AppNav.kt`; modify `MainActivity.kt`.

- All §11 tokens as named constants; recipe accents by index; a complete Material 3 dark `ColorScheme` so no default purple shows (§11.4).
- Download IBM Plex Sans (500, 700), Inter (400, 500, 600) and IBM Plex Mono (400) TTFs from their official releases (all SIL OFL) into `res/font/`; add their licenses under `licenses/`. Tabular figures on every time and amount style.
- `AppViewModel(controller, settings, clock)` exposes `StateFlow`s and a one-second ticker active only while Live, Schedule or Kitchen is visible. Navigation Compose routes: `empty`, `import`, `live`, `schedule`, `done` (later: `kitchen`, `recipe/{id}`, `gallery`). Start route from stored state: session finished → `done`; session present → `live`; else `empty`.
- Edge-to-edge with correct insets.

Verify: app launches to Empty on the emulator in the app palette.

### Task 13: Empty and Import screens, and the three ways in (§6, §9.1, §9.2)

**Files:** `ui/EmptyScreen.kt`, `ui/ImportScreen.kt`, `ui/ImportIntent.kt`; modify manifest and `MainActivity.kt`. Test: `ui/ImportIntentTest.kt` for the URL decoding (pure function).

- Manifest: `ACTION_SEND` `text/plain`; `ACTION_VIEW` scheme `cookplan` host `import`. Handle `onCreate` and `onNewIntent`.
- `decodeLink(d: String?, j: String?): String?`: `d` is base64url without padding of gzip of UTF-8 JSON; `j` is URL-decoded JSON. Tests: round-trip of the reference plan through both; garbage `d` → null. Cap decompressed size at 1 MB.
- Empty: copy and buttons per §9.1 ("Paste plan" reads the clipboard; "Load sample" reads the asset). The lockup itself arrives in Milestone 6; use the text "Four Burners" in the display font until then.
- Import: the seven items of §9.2 in order; "ready at" refreshes every 30 s; error state with first 300 characters and total length; all validator errors listed in the specifics card, first one as the headline.
- Replacing: with a session in progress, show "A cook is in progress (0:20 of 0:53). Replace it?" with Replace and Keep.
- Recipe chips are inert until Milestone 4.

Verify on emulator: `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "<prose + plan>"` opens Import showing 3 recipes, 53 min, 18 steps (acceptance 2); a broken plan shows the error state; `cookplan://import?j=...` works.

### Task 14: Live screen (§9.3, plain)

**Files:** `ui/LiveScreen.kt`, `ui/live/TopBlock.kt`, `InterruptBar.kt`, `TimerRow.kt`, `ThenList.kt`, `engine/Then.kt`. Test: `engine/ThenTest.kt`.

- `Then.items(session, nowMs): List<ThenItem>` (pure): next projected items after Now, with a free gap of ≥ 120 s as an item ("Free for 7 min"). Tests on the on-plan reference run: at 300 s (during `c1`) the items are `r1` then `c_in`; at 1300 s (during `b1`) the first item is the 255 s gap, "Free for 4 min", then `b_pot`.
- Layout exactly per §9.3 and §10 target sizes: top block, interrupt bars, Free state, timer rows, Then, bottom row ("Adjust" opens an empty sheet with only the clock-times switch and a plain "End session" button until Milestone 5/6; "Kitchen" hidden until Milestone 4), `FLAG_KEEP_SCREEN_ON`.
- Title auto-sizes 50 → 30 sp, three lines, never truncated.
- Countdown text reads time inside its own small composable so a tick does not recompose the screen (§11.4).
- Buttons: Done → `done(now)`, +1 min → `extend(now, 60)`, Start next early → `startEarly`. Timer rows are not yet tappable.
- When the session becomes finished, navigate to Done.

Verify on emulator: load sample, press Done through the first steps, watch `c_pre` appear as a timer, set the device clock forward to reach the Free state and an interrupt.

### Task 15: Full schedule and Done (§9.5, §9.10)

**Files:** `ui/ScheduleScreen.kt`, `ui/DoneScreen.kt`, `engine/Summary.kt`. Test: `engine/SummaryTest.kt`.

- Schedule rows from `project`, all states per §9.5 (DONE at 45% with actual start, RUNNING timers with remaining time, Now outline, dashed free gaps, final "Everything is ready" row).
- `Summary.of(session)`: took, planned, difference, hands-busy and free seconds. Test: on-plan reference → took 3170, planned 3170, difference 0, hands busy 2165.
- Done: "Close" clears session and scales, returns to Empty.

### Task 16: Lifecycle and Milestone 2 close

- `controller.refresh()` on `ON_START`; stale session → dialog offering to discard (§12).
- Register for `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED` to refresh labels (§9.9).
- Verify on emulator: start the sample, `adb shell am kill app.fourburners`, relaunch → same state (process death); run the sample to the end by marking steps on schedule → Done screen (acceptance 3); `aapt dump permissions` on the APK shows no `INTERNET` (acceptance 4).
- Milestone note, commit, **stop for review.**

---

# Milestone 3: Alerts

### Task 17: Manifest and permission rules

Before writing anything, read the current Android docs for exact alarms and foreground-service types for the `targetSdk` in use (§8.1 requires this), and record the conclusions in `docs/milestones.md`: which exact-alarm permission applies at each API level, which foreground-service type an alarm-ringing service may declare, and whether it may be started from an exact-alarm broadcast. Then declare: `POST_NOTIFICATIONS`, `USE_EXACT_ALARM` (33+), `SCHEDULE_EXACT_ALARM` (`maxSdkVersion` 32), `RECEIVE_BOOT_COMPLETED`, `VIBRATE`, the foreground-service permissions found above, and the receivers and service. If the docs rule out ringing from a foreground service on the target API, stop and report: §2 makes ring-until-acknowledged non-negotiable.

### Task 18: AlarmScheduler and receivers

**Files:** `alarm/AlarmScheduler.kt` (implements `AlarmGateway`), `alarm/TimerReceiver.kt`, `alarm/BootReceiver.kt`; wire into `SessionController` construction in an `Application` subclass (`App.kt`) so receivers get the same controller.

- `sync`: cancel everything previously set (stable request codes from a persisted list), then set one exact alarm per `Alerts.alarms(session, now)` entry via `setAlarmClock`, falling back to `setExactAndAllowWhileIdle` when exact alarms are not permitted.
- `TimerReceiver`: on any alarm, `controller.refresh()`, then hand off to `RingingService` and `Notifications`. Also receives the notification actions `DONE`, `SILENCE`, `PLUS_ONE` and maps them to `done(followUp)`, `acknowledge(step)`, `extend(step, 60)`.
- `BootReceiver`: load, `refresh()`, `sync`.
- Test (JVM, fake `AlarmGateway` recording calls): after each of `done`, `extend`, `undo`, `end`, the fake holds exactly `Alerts.alarms(...)`.

### Task 19: RingingService and notifications (§8.2, §8.3)

**Files:** `alarm/RingingService.kt`, `alarm/Notifications.kt`.

- Service: looping default alarm sound on `USAGE_ALARM`, repeating vibration, started when `Alerts.ringing` is non-empty; a one-second loop turns sound on and off from `Alerts.soundOn`; stops itself when `ringing` is empty and no hold window is active. One sound however many timers ring.
- Channels: "Timers" (high) and "Session" (low). Timer-ended notification per ringing step: title = follow-up title, else "<timer label> done"; text = recipe name; actions Done (interrupt follow-ups only), Silence, +1 min; body opens Live; shown on the lock screen. `quietlyEnded` steps get one normal notification with the default sound, then are acknowledged. Hold warning: "<title> has been waiting 3 min." Ongoing notification with a chronometer to the nearest timer end while backgrounded.

### Task 20: In-app ringing and permission prompts (§8.4)

- Live: a ringing timer appears as an interrupt bar with Silence (64 dp) beside Done; border toggles between 1.5 and 3 dp each second (plain, no spring yet); hold line "Waiting 3 min. Planned hold was 1 min." in Amber.
- Request `POST_NOTIFICATIONS` at first session start. If it or exact alarms are denied, show the one-line warning with Allow.

### Task 21: Milestone 3 close

Manual checks on a **physical device** (emulator audio and Doze behavior are not sufficient): timer rings with the screen off and keeps ringing past 3 minutes into the once-a-minute pattern; Silence and Done work from the lock screen; reboot mid-session and the next timer still fires; two timers ending close together give one sound and two notifications. Record each as device-verified or not verified. Commit, **stop for review.**

---

# Milestone 4: Reference screens

### Task 22: Recipe card data, scaling, grams (§9.7, §9.8)

**Files:** `recipe/RecipeCard.kt`, `recipe/Amounts.kt`. Test: `recipe/RecipeCardTest.kt`, `recipe/AmountsTest.kt`.

**Produces:**

```kotlin
data class CardStep(val step: Step, val ingredients: List<Ingredient>)
data class RecipeCardData(val recipe: Recipe, val handsOnSec: Int, val totalSec: Int,
                          val steps: List<CardStep>, val alsoNeeded: List<Ingredient>)
fun recipeCard(plan: Plan, recipeId: String): RecipeCardData   // topological, ties by planned start
object Amounts {
    fun format(x: Double): String                               // ⅛ ¼ ⅓ ⅜ ½ ⅝ ⅔ ¾ ⅞, else ≤ 2 decimals
    fun scaled(amount: Double, unit: String?, factor: Double): Double   // rounding only when factor != 1
    fun grams(ing: Ingredient, factor: Double): Double?         // null → show as written
    fun roundGrams(g: Double): Double
    fun stepServings(current: Double, base: Double?, delta: Int): Double  // ±0.5, min 0.5, max 4×base (or ×4)
}
```

Tests are exactly the "Recipe card, scaling, grams" list in §13: step order r1…r6, 11 min hands on, 42 min total; `format` 1.5 → "1½", 2.75 → "2¾", 0.5 → "½", 8 → "8", 1.2 → "1.2"; recipe `r` 4 → 6: "2¼ cup", "3 tbsp", "1½", "4⅛ cup"; stepper clamps at 0.5 and 16; grams at 6 servings: 415, 42, 1800, 680, lemon null with "¾"; `roundGrams` 4.3 → 4.5, 37.4 → 37, 417 → 415. Plus: an amount that would round to 0 shows the smallest increment (0.25 tsp × 0.125 → "¼"); "LBS", "Ounces", "kilogram" match as mass units.

### Task 23: Recipe screen

**Files:** `ui/RecipeScreen.kt` and pieces under `ui/recipe/`.

Header, controls row, collapsible "All ingredients (5)", step rail, "Also needed", notes, the Amber scaled-amounts line, the Grams footnote; progress display driven by `ingredientStates`, step status and `project`, behind the "Track progress" switch. Read-only. Wire entry points: Import chips, Live overline, Live timer rows' recipe (via the timer sheet in Milestone 5; for now the Schedule chips and overline), Schedule chips. Stepper uses plain taps; hold-to-repeat arrives in Milestone 6.

Verify on emulator against acceptance 9, before, during and after a session.

### Task 24: Kitchen screen (§9.6)

**Files:** `ui/KitchenScreen.kt`, `engine/ToolChanges.kt`. Test: `engine/ToolChangesTest.kt`.

`ToolChanges.upcoming(session, nowMs, limit = 3)`: the next tool state changes from the projection (a step's `leaves` at its projected end). Test: on plan at 800 s → rice pot → "rice and stock" at 1235, blanching pot → "heating water" at 1850, blanching pot → "boiling water" at 2330. Rows per §9.6; pool tools read "1 of 2 in use". Show the Live "Kitchen" button only when the plan has tools. Acceptance 10. Milestone note, commit, **stop for review.**

---

# Milestone 5: Kitchen input and clock times

### Task 25: Volume buttons (§10)

`MainActivity.dispatchKeyEvent` forwards volume keys to the ViewModel only while Live is the current route and the setting is on; consumes both down and up events. Pure reducer `ui/live/VolumeKeys.kt`: `fun onVolume(key, live: LiveState, ringing: List<Ring>): VolumeAction` with tests: down with a ring → `Silence(stepId)`; down again (now acknowledged, follow-up running) → `Done(followUp)`; down otherwise → `Done(now)`; up → `Extend(now, 60)`; up in Free → `StartEarly`; ignore key repeats (`repeatCount > 0`). The 6 s first-time hint, tracked by `volumeHintShown`. Acceptance 8: drive the whole sample with only the volume keys on the emulator.

### Task 26: Undo bar and timer sheet

Undo bar above the bottom row while `canUndo` is true: "Marked done: Chop onion", 56 dp Undo. Timer sheet on tapping a timer row: −1 min, +1 min, +5 min at 72 dp, new end time above, auto-closes 4 s after the last press, link to the recipe card.

### Task 27: Adjust sheet and clock times (§9.4, §9.9)

Adjust sheet with the three switches and "End session" (plain confirm dialog for now; hold-to-end is Milestone 6). `showClockTimes` applied on Live (overline, timer rows "until 7:11 PM", Then), Schedule, Kitchen, Import, recipe cards, Done; toggled from the Live overline's right side, Adjust and Import. Countdown stays mm:ss. Verify by toggling on each screen and by changing the device time zone. Milestone note, commit, **stop for review.**

---

# Milestone 6: Motion, haptics, hold gestures, finish

### Task 28: Motion tokens and reduced motion (§11.1)

`ui/theme/Motion.kt`: `press` (0.8, 600), `arrive` (0.55, 350), `settle` (1.0, 300), fade specs 120 ms out / 200 ms in, and `LocalReduceMotion` (system animator scale 0 or the setting) that swaps every spec for a 120 ms fade. A lint-style unit test greps `ui/` for `tween(` and `spring(` outside `Motion.kt` and fails on any hit.

### Task 29: Animations

One sub-commit per bullet of §11.1, each added to the gallery in the same commit: press scale and tinted ripples; Done transition with the shared "Then" → title element; timer start, end and travel into the interrupt bar; ringing pulse; rolling digits (per-digit, 120 ms, no bounce); +1 min bump; over-time pill fade; Free dash drift; recipe card growth and drawn check; stepper roll and staggered cross-fade; clock-time flip; screen transitions with predictive back; Done tiles. `SharedTransitionLayout` wraps the nav host.

### Task 30: Haptics and hold gestures (§11.2, §11.3)

`ui/Haptics.kt` maps the seven events of §11.2 to the constants available in the Compose version in use, falling back to `HapticFeedbackConstants`. `Modifier.holdToRepeat` (400 ms recognize, 300 ms repeats, 120 ms after five) on +1 min and the stepper, with the reject shake at limits. Hold-to-end: 1.2 s Rose fill, drain on early release, "Hold to end" for 2 s after a tap, TalkBack custom action with a confirmation dialog (acceptance 11). Hold the Live overline's recipe name to peek the recipe card as a half sheet. Every hold target has a non-hold route and a custom accessibility action.

### Task 31: Brand assets (§11.5)

Convert the SVGs in `plans/brand/` to vector drawables with the Android tooling's own converter (`Svg2Vector` from `com.android.tools:sdk-common`, run from a throwaway Gradle task; do not hand-edit path data). If the converter reports unsupported features or the output does not match the `preview-*.png` files, stop and report rather than simplify. Adaptive icon (foreground from `four-burners-icon.svg` scaled into the safe zone, background `#1E2529`, monochrome layer from the light shapes of `four-burners-icon-mono.svg`), themed splash from `four-burners-mark-app.svg`, lockup on Empty at about 190 dp.

### Task 32: Motion gallery and finish pass

Debug-only `gallery` route reached by holding the logo on Empty, one Replay row per animation. Then walk §11.4 line by line: no stock theme showing, no shadows, edge-to-edge with the status bar taking the top block's color on Live, radius set, tabular figures, plurals resources, copy rules, font scale 1.3 with no clipping on every screen, layout-inspector check that a countdown tick recomposes only its own scope. Verify acceptance 12 with "Reduce motion" on.

### Task 33: README and final acceptance

Replace the root `README.md`: what the app is, build and test commands, how to send a plan (pointing at `plans/sender-instructions.txt`), and the four manual checks from §13. Run all twelve §14 acceptance criteria from a clean checkout and record each in `docs/milestones.md` with how it was verified. Commit, **stop for final review.**

---

## Self-review notes

- **Spec coverage:** §5 → Tasks 2–4; §6 → 13; §7 → 5–7; §8 → 8, 17–21; §9.1–9.2 → 13; §9.3 → 14, 20, 26; §9.4 → 27, 30; §9.5, §9.10 → 15; §9.6 → 24; §9.7–9.8 → 22–23; §9.9 → 16, 27; §10 → 14, 25, 26; §11 → 12, 28–32; §12 → 10, 16; §13 → tests in 3–8, 22, plus manual checks in 21, 25, 33; §14 → 33.
- **Not covered on purpose:** everything in §15.
- **Known risk:** the brand SVGs are woodcut artwork and may not survive `Svg2Vector` cleanly (Task 31 stops and reports). The foreground-service rules for ringing depend on the `targetSdk` current at build time (Task 17 stops and reports).
