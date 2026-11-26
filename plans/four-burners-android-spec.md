# Four Burners — Android app spec

Spec for Claude Code. The app is called **Four Burners** (launcher label, notifications, share target). The plan format keeps the name `cookplan`; do not rename the JSON key or the URL scheme. Build a native Android app that runs a multi-recipe cooking session in real time: one current task, the timers running unattended, and what comes next. Plans are produced outside the app (by Claude, in chat) and sent in as JSON.

## 1. Summary

- **What it is:** a single-purpose live cooking screen for two or three recipes cooked at once by one cook, usable with wet or greasy hands.
- **Recipe cards:** each recipe in the plan can also be opened on its own, with its ingredients listed under the step that uses them.
- **Kitchen state:** the plan names the tools it uses and what state each step leaves them in, and the app can show what every tool is holding right now.
- **What it is not:** a recipe manager, a scheduler, or a recipe browser. The app does not compute the plan. It executes one and adapts to how fast the cook actually goes. It only knows the recipes in the current plan.
- **How plans get in:** Android share sheet (primary), custom URL scheme (secondary), paste (fallback).
- **Deliverable:** an Android Studio project that builds a debug APK with `./gradlew assembleDebug`, with unit tests passing under `./gradlew test`.

## 2. Decisions already made

Do not silently deviate from these. If one turns out to be unworkable, stop and say so.

| Decision | Choice | Reason |
|---|---|---|
| Who schedules | The sender supplies planned start times. The app only simulates forward from real progress. | Keeps the app deterministic and small. |
| Plan quality | The app rejects invalid plans. It cannot tell a slow plan from a good one; the cook judges that. | Accepted limit. |
| Sessions | One active session at a time. Importing a new plan asks before replacing. | Matches use. |
| Timer alerts | A finished timer rings like an alarm until acknowledged. | A missed oven timer ruins food; one chime is not enough. See §8. |
| Storage | One JSON file for the active session via DataStore or an app-private file. No Room. | One object, no queries. |
| Network | None. No `INTERNET` permission. | Nothing needs it. |
| Time display | Elapsed time by default, with a switch to wall-clock times. | §9.9. |
| Gram weights | The sender supplies a gram weight per ingredient. The app converts only between mass units and never guesses a density. | Offline, no ingredient database. |
| Scaling | Scales ingredient amounts only, per recipe. Step durations and the schedule are not changed. | The app does not schedule. |
| Time recovery | If the cook falls behind, later steps shift later. The app does not try to win time back in free gaps. | Simple and predictable; §7.4. |
| Tools | Tool use and tool state are declared in the plan and checked by the validator. The app displays and checks them; it does not plan around them. | §5.2. |
| Layouts | Import: step list. Live: large single-instruction layout. Recipe card: ingredients grouped by step. | Chosen from mockups. |
| Input | Live is operable with imprecise touches and with the volume buttons. No drag or swipe gestures on Live. | §10. |

## 3. Build order

Build in these milestones, in order. At the end of each one: the project builds, all tests pass, commit, and write a short note listing what was verified and how (unit test, emulator, physical device, or not verified). Stop after each milestone for review before starting the next.

1. **Core.** Model, parser, validator (including tools), session engine, unit tests. No UI beyond a debug screen that dumps engine state for the sample plan.
2. **Plain screens.** Empty, Import, Live, Full schedule, Done, with persistence. No animation, default transitions.
3. **Alerts.** Alarms, ringing, notifications, boot handling (§8).
4. **Reference screens.** Recipe card with scaling, grams and tracking; Kitchen screen.
5. **Kitchen input and clock times.** Volume buttons, undo bar, timer sheet, time display switch (§10, §9.9).
6. **Motion, haptics, hold gestures, finish.** §11.1 to §11.4, including the motion gallery.

## 4. Tech stack

- Kotlin, Jetpack Compose, Material 3, single activity.
- `minSdk` 26. `targetSdk` and `compileSdk`: latest stable at build time.
- Use current stable versions of the Android Gradle Plugin, Kotlin, and the Compose BOM. Check them at build time; do not pin versions from memory.
- `kotlinx.serialization` for JSON.
- `ViewModel` + `StateFlow`. No DI framework; pass dependencies by constructor.
- Tests: JUnit for the parser, validator and engine (pure Kotlin, no Android dependencies in those packages).

```
app/src/main/java/<pkg>/
  model/      Plan, Recipe, Ingredient, Tool, Step, Session, StepState   (pure Kotlin)
  parse/      PlanParser, PlanValidator                                  (pure Kotlin)
  engine/     SessionEngine                                              (pure Kotlin)
  data/       SessionStore, Settings
  alarm/      AlarmScheduler, TimerReceiver, RingingService, BootReceiver, Notifications
  ui/         EmptyScreen, ImportScreen, LiveScreen, ScheduleScreen, KitchenScreen,
              RecipeScreen, DoneScreen, theme/
  MainActivity.kt
```

## 5. Plan format (`cookplan` v1)

### 5.1 Schema

```jsonc
{
  "cookplan": 1,                      // required, format version
  "title": "Chicken, rice, beans",    // optional
  "tools": [                          // optional; see 5.2
    { "id": "oven", "name": "Oven", "initial": "off" },
    { "id": "burners", "name": "Burners", "count": 2 }
  ],
  "recipes": [
    {
      "id": "c", "name": "Chicken thighs", "short": "Chicken",
      "servings": 4,                  // optional
      "ingredients": [                // optional
        { "id": "thighs", "name": "bone-in chicken thighs", "amount": 8, "grams": 1200 },
        { "id": "paprika", "name": "smoked paprika", "amount": 2, "unit": "tsp", "grams": 5, "note": "or sweet" }
      ],
      "notes": "Pat the skin dry first."   // optional, free text
    }
  ],
  "steps": [
    {
      "id": "c1",                     // unique in plan
      "recipe": "c",                  // recipe id, or null for a shared kitchen chore
      "kind": "active",               // "active" = needs hands, "passive" = unattended timer
      "title": "Season thighs, onto sheet pan",
      "detail": "Salt, pepper, paprika. Skin side up.",   // optional, one or two sentences
      "duration_sec": 480,
      "planned_start_sec": 20,        // offset from session start
      "after": [],                    // ids of steps that must be done first
      "uses": ["thighs", "paprika"],  // optional; ingredient ids of this step's own recipe
      "tools": [                      // optional; see 5.2
        { "id": "sheetpan", "needs": "clean", "leaves": "raw chicken" }
      ],
      "interrupt": false,             // active only; see 7.3
      "timer_label": null,            // passive only; e.g. "oven", "simmer"
      "max_hold_sec": null            // passive only; how long it can sit after the timer ends
    }
  ]
}
```

Ingredient fields: `id` (optional, unique within its recipe; required only for ingredients that steps refer to), `name` (required), `amount` (number, optional), `unit` (free text, optional), `grams` (number, optional: the weight in grams of the stated `amount`), `note` (optional).

### 5.2 Tools

Tools model the physical kitchen: the oven, each pot and pan, the cutting board, the burners. They exist so a plan cannot put two things in one pot, chop vegetables on a board that has raw chicken on it, or roast in an oven nobody turned on.

- **Tool:** `id`, `name`, `count` (default 1), `initial` (starting state, default `"clean"`).
- **State:** a short free-text string chosen by the sender: `"clean"`, `"raw chicken"`, `"425°F"`, `"boiling water"`. The app compares states only for exact equality.
- **A step's tool entry:** `id`, optional `needs` (the state the tool must be in when the step starts), optional `leaves` (the state it is in when the step ends; omitted means unchanged).
- A step occupies each tool it lists for its whole duration.
- A tool with `count` greater than 1 is a pool of interchangeable units (burners). Pool tools carry no state: `needs` and `leaves` are not allowed on them.
- Anything that changes a tool's state must be a step. Turning the oven on, waiting for it to heat, putting water on to boil and washing a board are all steps, with a recipe or with `"recipe": null`. A passive step starts by itself the moment its `after` steps are done, so any physical action that starts it (pan into the oven, pot onto the heat) belongs to the active step before it.

### 5.3 Validation rules

Reject the plan with a specific, readable error for each of these. Errors name the steps and tools involved and give times.

1. `cookplan` missing or not `1`.
2. 1 to 4 recipes; 1 to 80 steps; at most 30 tools.
3. Duplicate ids (steps, recipes, tools, or ingredients within a recipe); a step naming an unknown recipe; `after` naming an unknown step.
4. Cycle in `after`.
5. `duration_sec` ≤ 0 or > 6 hours. `planned_start_sec` < 0.
6. A step planned to start before any of its `after` steps is planned to end.
7. Two non-interrupt active steps whose planned intervals overlap (one cook).
8. `interrupt: true` on a passive step, or on an active step longer than 120 s.
9. An ingredient with an empty `name`, a non-positive `amount` or `grams`, `grams` without `amount`, or more than 60 ingredients in one recipe. `servings`, if present, must be > 0.
10. A step's `uses` naming an id that is not an ingredient of that step's recipe, or `uses` on a step whose recipe is null.
11. A step naming an unknown tool; `count` < 1; `needs` or `leaves` on a tool whose `count` is greater than 1.
12. **Tool capacity.** At any moment in the planned schedule, more steps occupy a tool than its `count`. One step ending and another starting at the same second is allowed.
13. **Tool state.** Walk the planned schedule in time order, applying each step's `leaves` at its planned end (ends before starts at the same second). A step whose `needs` differs from the tool's state at its planned start is rejected: "Cutting board is 'raw chicken' at 0:31, but 'Trim the beans' needs it 'clean'."

Unknown fields are ignored. Missing optional fields take the defaults shown above.

### 5.4 Extracting a plan from shared text

Shared text will usually be a whole chat message with prose around the JSON. Extraction order:

1. First fenced code block whose content parses as an object containing the key `cookplan`.
2. Otherwise, the first balanced `{...}` substring that parses and contains `cookplan`.
3. Otherwise fail with "No cook plan found in the shared text."

Parse leniently (`ignoreUnknownKeys`, `isLenient`). Do not try to repair broken JSON.

### 5.5 Reference plan

Use this as the built-in sample ("Load sample" on the empty state) and as the main test fixture. 18 steps, 52 min 50 s. The gram weights are approximate. Two ingredients deliberately have no `grams`: green beans (unit `lb`, which the app converts itself) and the lemon (no weight available).

```json
{
  "cookplan": 1,
  "title": "Chicken, rice, beans",
  "tools": [
    {"id": "oven", "name": "Oven", "initial": "off"},
    {"id": "burners", "name": "Burners", "count": 2},
    {"id": "sheetpan", "name": "Sheet pan"},
    {"id": "board", "name": "Cutting board"},
    {"id": "ricepot", "name": "Rice pot"},
    {"id": "blanchpot", "name": "Blanching pot"},
    {"id": "skillet", "name": "Skillet"}
  ],
  "recipes": [
    {"id": "c", "name": "Chicken thighs", "short": "Chicken", "servings": 4, "ingredients": [
      {"id": "thighs", "name": "bone-in, skin-on chicken thighs", "amount": 8, "grams": 1200},
      {"id": "oil", "name": "olive oil", "amount": 1, "unit": "tbsp", "grams": 14},
      {"id": "salt", "name": "kosher salt", "amount": 1.5, "unit": "tsp", "grams": 5},
      {"id": "pepper", "name": "black pepper", "amount": 0.5, "unit": "tsp", "grams": 1},
      {"id": "paprika", "name": "smoked paprika", "amount": 2, "unit": "tsp", "grams": 5}
    ], "notes": "Pat the skin dry before seasoning. Skin side up on the pan." },
    {"id": "r", "name": "Rice pilaf", "short": "Rice", "servings": 4, "ingredients": [
      {"id": "rice", "name": "long-grain white rice", "amount": 1.5, "unit": "cup", "grams": 278},
      {"id": "onion", "name": "yellow onion", "amount": 1, "grams": 110, "note": "small, finely chopped"},
      {"id": "butter", "name": "butter", "amount": 2, "unit": "tbsp", "grams": 28},
      {"id": "stock", "name": "chicken stock", "amount": 2.75, "unit": "cup", "grams": 650},
      {"id": "salt", "name": "kosher salt", "amount": 0.75, "unit": "tsp", "grams": 3}
    ] },
    {"id": "b", "name": "Green beans", "short": "Beans", "servings": 4, "ingredients": [
      {"id": "beans", "name": "green beans", "amount": 1, "unit": "lb"},
      {"id": "garlic", "name": "garlic cloves, sliced", "amount": 3, "grams": 9},
      {"id": "oil", "name": "olive oil", "amount": 1, "unit": "tbsp", "grams": 14},
      {"id": "lemon", "name": "lemon", "amount": 0.5, "note": "juice only"},
      {"id": "salt", "name": "kosher salt", "amount": 0.5, "unit": "tsp", "grams": 2}
    ] }
  ],
  "steps": [
    {"id": "c_on", "recipe": "c", "kind": "active", "title": "Turn the oven on to 425°F", "duration_sec": 20, "planned_start_sec": 0, "after": [], "tools": [{"id": "oven", "needs": "off", "leaves": "heating"}]},
    {"id": "c_pre", "recipe": "c", "kind": "passive", "title": "Oven preheats", "duration_sec": 720, "planned_start_sec": 20, "after": ["c_on"], "tools": [{"id": "oven", "needs": "heating", "leaves": "425°F"}], "timer_label": "preheat", "max_hold_sec": 3600},
    {"id": "c1", "recipe": "c", "kind": "active", "title": "Season thighs, onto sheet pan", "duration_sec": 480, "planned_start_sec": 20, "after": [], "uses": ["thighs", "oil", "salt", "pepper", "paprika"], "tools": [{"id": "sheetpan", "needs": "clean", "leaves": "raw chicken"}], "detail": "Pat dry. Oil, salt, pepper, paprika. Skin side up."},
    {"id": "r1", "recipe": "r", "kind": "active", "title": "Chop onion", "duration_sec": 240, "planned_start_sec": 500, "after": [], "uses": ["onion"], "tools": [{"id": "board", "needs": "clean", "leaves": "onion"}]},
    {"id": "c_in", "recipe": "c", "kind": "active", "title": "Chicken into the oven", "duration_sec": 30, "planned_start_sec": 740, "after": ["c1", "c_pre"], "tools": [{"id": "oven", "needs": "425°F"}, {"id": "sheetpan"}], "interrupt": true},
    {"id": "c2", "recipe": "c", "kind": "passive", "title": "Roast at 425°F", "duration_sec": 2100, "planned_start_sec": 770, "after": ["c_in"], "tools": [{"id": "oven"}, {"id": "sheetpan"}], "timer_label": "oven", "max_hold_sec": 120},
    {"id": "r2", "recipe": "r", "kind": "active", "title": "Sauté onion, toast rice, add stock and cover", "duration_sec": 360, "planned_start_sec": 875, "after": ["r1"], "uses": ["onion", "butter", "rice", "stock", "salt"], "tools": [{"id": "ricepot", "needs": "clean", "leaves": "rice and stock"}, {"id": "burners"}]},
    {"id": "r3", "recipe": "r", "kind": "passive", "title": "Simmer covered", "duration_sec": 1080, "planned_start_sec": 1235, "after": ["r2"], "tools": [{"id": "ricepot"}, {"id": "burners"}], "timer_label": "simmer", "max_hold_sec": 60},
    {"id": "b1", "recipe": "b", "kind": "active", "title": "Trim the green beans", "duration_sec": 300, "planned_start_sec": 1235, "after": [], "uses": ["beans"], "detail": "Snap off stem ends. Leave whole."},
    {"id": "b_pot", "recipe": "b", "kind": "active", "title": "Fill a pot with salted water, set it to boil", "duration_sec": 60, "planned_start_sec": 1790, "after": [], "uses": ["salt"], "tools": [{"id": "blanchpot", "needs": "clean", "leaves": "heating water"}, {"id": "burners"}]},
    {"id": "b_boil", "recipe": "b", "kind": "passive", "title": "Water comes to a boil", "duration_sec": 480, "planned_start_sec": 1850, "after": ["b_pot"], "tools": [{"id": "blanchpot", "needs": "heating water", "leaves": "boiling water"}, {"id": "burners"}], "timer_label": "boil", "max_hold_sec": 600},
    {"id": "r4", "recipe": "r", "kind": "active", "title": "Rice off heat, keep the lid on", "duration_sec": 15, "planned_start_sec": 2315, "after": ["r3"], "tools": [{"id": "ricepot"}, {"id": "burners"}], "interrupt": true},
    {"id": "r5", "recipe": "r", "kind": "passive", "title": "Steam off heat", "duration_sec": 600, "planned_start_sec": 2330, "after": ["r4"], "tools": [{"id": "ricepot"}], "timer_label": "steam", "max_hold_sec": 900},
    {"id": "b2", "recipe": "b", "kind": "active", "title": "Blanch the beans, then drain", "duration_sec": 240, "planned_start_sec": 2330, "after": ["b1", "b_boil"], "uses": ["beans"], "tools": [{"id": "blanchpot", "needs": "boiling water", "leaves": "hot water"}, {"id": "burners"}]},
    {"id": "b3", "recipe": "b", "kind": "active", "title": "Sauté with garlic, finish with lemon", "duration_sec": 300, "planned_start_sec": 2570, "after": ["b2"], "uses": ["beans", "garlic", "oil", "lemon"], "tools": [{"id": "skillet", "needs": "clean", "leaves": "cooked beans"}, {"id": "burners"}]},
    {"id": "c3", "recipe": "c", "kind": "active", "title": "Chicken out of the oven", "duration_sec": 60, "planned_start_sec": 2870, "after": ["c2"], "tools": [{"id": "oven", "leaves": "hot, empty"}, {"id": "sheetpan", "leaves": "cooked chicken"}], "interrupt": true},
    {"id": "c4", "recipe": "c", "kind": "passive", "title": "Rest", "duration_sec": 240, "planned_start_sec": 2930, "after": ["c3"], "tools": [{"id": "sheetpan"}], "timer_label": "rest", "max_hold_sec": 600},
    {"id": "r6", "recipe": "r", "kind": "active", "title": "Fluff with a fork", "duration_sec": 60, "planned_start_sec": 2930, "after": ["r5"], "tools": [{"id": "ricepot", "leaves": "cooked rice"}]}
  ]
}
```

## 6. Getting plans into the app

### 6.1 Share sheet (primary)

- `MainActivity` intent filter: `ACTION_SEND`, `text/plain`.
- Read `Intent.EXTRA_TEXT`, run §5.4 then §5.3.
- Success → Import screen. Failure → Import screen in error state.
- Handle `onNewIntent` (activity is `singleTop`).

### 6.2 URL scheme (secondary)

- Intent filter: `ACTION_VIEW`, scheme `cookplan`, host `import`.
- `cookplan://import?d=<payload>` where payload is base64url (no padding) of the gzip-compressed UTF-8 JSON. Also accept `?j=<url-encoded JSON>` for short plans.
- Whether links with this scheme are tappable inside the Claude Android app is unverified. Do not depend on it.

### 6.3 Paste (fallback)

Empty state has "Paste plan". Reads the clipboard, same pipeline.

### 6.4 Replacing a session

If a session is in progress, importing shows: "A cook is in progress (0:20 of 0:53). Replace it?" with Replace and Keep.

## 7. Session engine

Pure Kotlin, no Android imports, fully unit tested. All functions take `nowMs` as a parameter; never read the clock inside the engine.

### 7.1 State

```kotlin
data class Session(
  val plan: Plan,
  val startedAtMs: Long,
  val steps: Map<String, StepState>,   // by step id
  val slipSec: Int                     // see 7.4
)

data class StepState(
  val status: Status,                  // PENDING, RUNNING, DONE
  val startedAtMs: Long?,
  val endedAtMs: Long?,
  val extraSec: Int = 0,               // user-added time, passive or active
  val acknowledgedAtMs: Long? = null   // passive only: when its alarm was silenced
)
```

### 7.2 Rules

- **Passive steps** start automatically at the moment their last `after` step becomes DONE. A passive step with no `after` starts at session start. They become DONE when `nowMs >= startedAt + duration + extra`. No user action.
- **Active steps** start when they become the Now step (7.3) and end when the user marks them done.
- **Eligible** (non-interrupt active): status PENDING, all `after` steps DONE, every tool it lists has a free unit (not occupied by a RUNNING step), and either
  - elapsed ≥ `planned_start_sec + slipSec`, or
  - it has at least one passive step in `after` (it follows a timer and is eligible the moment the timer ends).
- **Now step:** among eligible steps, pick follow-ups to ended timers first (earliest timer end first), then lowest `planned_start_sec`, then plan order. A RUNNING active step stays Now until done.
- **Free:** if no active step is RUNNING or eligible and the session is not finished, the state is Free until the earliest moment something becomes eligible. "Start next early" makes the next active step whose `after` steps are DONE and whose tools are free eligible immediately.
- **Finished:** every step DONE.

### 7.3 Interrupts

An active step with `interrupt: true` is a short action forced by a timer ending (turn off a burner, pull a pan, put a pan in). When its `after` steps are DONE:

- If no active step is RUNNING, it becomes Now like any follow-up.
- If another active step is RUNNING, it appears as an interrupt bar above the Now block with its own Done. The running step is not paused or reset.
- Steps depending on it wait until it is marked done.

### 7.4 Slip

`slipSec` is how far behind plan the cook is. Recompute whenever a non-interrupt active step is marked DONE:

```
slipSec = max(slipSec, elapsedAtDone - (planned_start_sec + duration_sec))
```

It never decreases. After falling 3 minutes behind, every later plan-timed step becomes eligible 3 minutes later, which keeps dishes finishing together. Timer follow-ups ignore slip, so food never sits in the oven because the cook was slow on another recipe.

### 7.5 Projection

`project(session, nowMs): List<ProjectedStep>` returns a projected start and end for every step by simulating forward with the rules above, assuming each remaining active step takes exactly `duration_sec + extraSec`. A RUNNING active step that has already passed that length is projected to end at `nowMs`, so later times move out second by second while the cook is over. Used for "Then", time to serve, the Full schedule, clock times and alarm scheduling.

### 7.6 Tool states

```kotlin
data class ToolNow(val tool: Tool, val state: String, val occupiedBy: List<String> /* step ids */, val freeAtMs: Long?)
fun toolStates(session: Session, nowMs: Long): List<ToolNow>
```

A tool's state is the `leaves` of the most recently ended step that set one, else its `initial`. `occupiedBy` lists RUNNING steps using it. `freeAtMs` is the projected end of the last of those.

`toolWarning(session, stepId): String?` returns a message when the Now step's `needs` does not match the tool's actual state ("Cutting board is 'raw chicken'. This step expects 'clean'."). The validator makes this impossible on plan; it can happen when steps are done out of order with "Start next early". It warns and does not block.

### 7.7 Ingredient states

```kotlin
enum class IngredientState { IDLE, IN_USE, DONE }
fun ingredientStates(session: Session, recipeId: String): Map<String, IngredientState>
```

- **IN_USE** if any active step that uses it is RUNNING (including an interrupt step).
- **DONE** if at least one step uses it and every step that uses it is DONE, or is a passive step that has started.
- **IDLE** otherwise. Ingredients with no `id`, or that no step uses, are always IDLE.

### 7.8 User actions

| Action | Effect |
|---|---|
| `done(stepId)` | Marks the RUNNING active or interrupt step DONE, starts dependents, updates slip. |
| `startEarly()` | From Free only. See 7.2. |
| `extend(stepId, sec)` | Adds `sec` to `extraSec`. On a RUNNING passive step it moves the timer's end (also allowed for 60 s after it ended, which reopens it). `sec` may be negative on a passive step, but never so far that less than 30 s remains. On a RUNNING active step it lengthens the expected duration, so remaining time and every projected time update immediately. It does not change `slipSec` directly; slip is measured at Done against the original `duration_sec`. |
| `acknowledge(stepId)` | Records that a finished passive step's alarm was silenced. Changes nothing else. |
| `undo()` | Reverts the last `done` if it was within 30 s and no passive step it started has ended. |

Every action returns a new `Session`; the caller persists it and reschedules alarms.

## 8. Timer alerts

A finished timer must be impossible to miss and easy to silence with a knuckle.

### 8.1 Scheduling

- For each RUNNING passive step, schedule one exact alarm at its end (`setAlarmClock`, falling back to `setExactAndAllowWhileIdle`). Cancel and reschedule all alarms after every engine action.
- Permissions: `POST_NOTIFICATIONS` (requested at first session start, API 33+), exact alarms (`USE_EXACT_ALARM` where available, `SCHEDULE_EXACT_ALARM` on API 31–32), `RECEIVE_BOOT_COMPLETED`, `VIBRATE`, and whatever the ringing service needs on the target API. Verify the current exact-alarm and foreground-service rules against the Android docs before finalizing the manifest.
- `BootReceiver` re-reads the stored session and reschedules alarms.

### 8.2 Ringing

When a passive step's timer ends:

- Start ringing: a looping alarm sound on the alarm audio stream (`USAGE_ALARM`) with a repeating vibration pattern, driven by a short-lived foreground service so it survives the screen being off.
- Ring continuously for up to 3 minutes. After that, ring for 10 seconds once a minute until acknowledged. Never stop for good on its own while the session is active.
- Ringing stops when the step is acknowledged, when its follow-up step is marked done, or when time is added to it.
- If two timers are ringing, there is one sound, and each timer has its own notification and its own row on screen.
- Steps with no follow-up action and a `max_hold_sec` of 5 minutes or more (resting, steaming) do not ring. They post one normal notification with the default sound.

### 8.3 Notifications

- **Timer ended** (high-importance channel "Timers"): title is the follow-up step's title if one exists ("Chicken out of the oven"), else "<timer label> done". Text: recipe name. Actions:
  - **Done**: shown only when the follow-up is an interrupt step; marks it done.
  - **Silence**: acknowledges without marking anything done.
  - **+1 min**: extends the timer by 60 s and stops the ringing.
  - Tapping the body opens Live.
- **Hold warning**: if a passive step has `max_hold_sec` and its follow-up is not done by then, post a second notification and resume continuous ringing for 30 s: "<title> has been waiting 3 min."
- **Ongoing** (low-importance channel "Session"): while a session is active and the app is backgrounded, shows the current step and the nearest timer end with a chronometer countdown.

### 8.4 In the app

While a timer is ringing and Live is in the foreground, its row is the interrupt bar (§9.3) with a Silence button beside Done, and the bar's border pulses. If notification or exact-alarm permission is denied, show a one-line warning on Live: "Notifications are off. Timers will only alert while this screen is open." with an Allow button.

The Live screen sets `FLAG_KEEP_SCREEN_ON` while visible.

## 9. Screens

### 9.1 Empty

The dark lockup (`brand/four-burners-lockup-app-transparent.svg`, §11.5) at the top, about 190 dp wide. "No cook in progress" in the display font, 32 sp. One paragraph: this app runs a cook plan for two or three recipes at once; it does not make plans. Three numbered lines: ask Claude for a cook plan; on the reply, tap Share and choose Four Burners; check the plan here and start cooking. Buttons at the bottom: "Paste plan" (primary), "Load sample".

### 9.2 Import

Single column, in this order:

1. Small label "New cook plan", then the plan title in the display font, 24 sp.
2. Three stat tiles in a row: total time, hands-on minutes (sum of active durations), number of steps.
3. "Start now, ready at 7:17 PM" from the device clock, refreshed every 30 s.
4. A row of recipe chips, each with a color dot and the recipe name. Tapping one opens its recipe card.
5. The step list in planned order inside one Surface card, 30 dp rows: planned start, recipe color dot (Muted dot for null-recipe steps), title (ellipsized), and a tag: filled "8 min" for active, outlined "timer 35" for passive. Durations under a minute read "20 s".
6. A "Show clock times" switch row (§9.9).
7. Bottom: "Cancel" (outlined) and "Start cooking" (primary, wider). Start sets `startedAtMs = now`.

**Error state:** label "Plan not loaded" in Rose, the validation error as a headline, a card with the specifics (step names, times, tool states), one sentence saying nothing was changed, then "What was received" with the first 300 characters in a monospace block and the total length. Buttons: "Paste a corrected plan", "Close".

### 9.3 Live (the main screen)

Designed to be read from across the kitchen and hit with a knuckle. Portrait, no scrolling in the top block.

**Top block.** Full width, flush to the top and sides, bottom corners 24 dp, filled with the Now step's solid recipe color (Muted-toned Surface for a null-recipe step), Background-colored text, 20 dp side padding.

1. Overline row, 13 sp semibold, letter-spaced, uppercase: recipe short name on the left (tapping it opens that recipe's card); on the right "0:20 · serve in 33". In clock mode: "6:44 PM · serve 7:17 PM". Tapping the right side toggles the mode.
2. Step title, display font, auto-sized from 50 sp down to 30 sp, up to three lines, never truncated.
3. Detail text if present, 17 sp medium.
4. Tools line if the step lists tools, 14 sp: "Sheet pan · Oven". If `toolWarning` returns a message, show it here instead, in a Background-colored pill with Amber text.
5. Remaining time: the number in the display font at 72 sp with "min left" at 20 sp beside it. Under one minute: seconds with "s left". Once over: "1" with "min over" and the number in a Background pill with Amber text.
6. Button row, 76 dp high: "+1 min" (96 dp wide, 2 dp Background-colored outline) and "Done" (fills the rest, Background fill, Foreground label, display font 30 sp).

**Interrupt bar.** When §7.3 applies or a timer is ringing, a bar sits above the top block: recipe tint, 1.5 dp solid-accent border, the step title at 19 sp in the display font, and a Done button 64 dp high (plus Silence when ringing). If the hold limit has passed, a line in Amber: "Waiting 3 min. Planned hold was 1 min."

**Free state.** The top block becomes Background with a 1.5 dp dashed Hairline border: "Nothing needs you" as the title, the countdown to the next step at 72 sp, "next: Blanch the beans at 0:38", and one outlined 76 dp button "Start next early".

**Timers.** Below the top block, 16 dp padding, one full-width row per RUNNING passive step, 10 dp apart: recipe tint, 1.5 dp solid-accent border, 14 dp radius. Left: "Chicken · oven" at 17 sp semibold. Right: countdown mm:ss at 38 sp, display font, tabular figures. Under both: an 8 dp progress bar in the solid accent on a Background track. In clock mode add "until 7:11 PM" under the label. Tapping a row opens the timer sheet (§10). If more than three timers run, this area scrolls.

**Then.** Label "THEN", then the next projected item at 20 sp semibold and the one after at 16 sp Muted. A free gap of 2 minutes or more counts as an item: "Free for 7 min".

**Bottom row.** Three outlined buttons, equal width, 56 dp: "Adjust", "Kitchen", "Full schedule".

**Undo bar.** For 30 s after a Done, a full-width bar above the bottom row: "Marked done: Chop onion" with a 56 dp "Undo" button. It replaces hunting for undo in a sheet.

Updates: recompute state once per second while visible via a ticker in the ViewModel. Countdown text must not recompose the whole screen.

### 9.4 Adjust sheet

Bottom sheet, rows 56 dp or taller: "Show clock times" switch; "Use volume buttons" switch (§10); "Reduce motion" switch (§11.1); "End session" in Rose, hold-to-confirm (§11.3) with the line "Ends the cook and cancels all timers."

### 9.5 Full schedule

Back button "‹ Live", current time and serve time on the right, title, then a row of recipe chips (tap opens that recipe's card). All steps in projected order, 40 dp rows: projected time (elapsed or clock per §9.9; DONE rows show the actual start), recipe color bar, recipe short name as an overline (with "· when timer ends" for timer follow-ups), title, right tag as on Import. DONE rows at 45% opacity with "done". RUNNING timers show their remaining time in a tinted tag. The Now step has a 2 dp Aqua inner outline and "now" in place of its time. Free gaps of 2 minutes or more appear as dashed rows. A final Aqua row: "Everything is ready" with the projected serve time.

### 9.6 Kitchen

What every tool is doing right now. One row per tool from `toolStates`, in plan order:

- Tool name at 17 sp semibold; its state on the right in a pill ("425°F", "raw chicken", "clean").
- Second line, Muted: who has it and until when ("Chicken · roast, free at 7:11 PM"), or "free". Pool tools read "1 of 2 in use".
- Occupied rows take the occupying recipe's tint and border. Free rows are Surface.
- Under the list, "Coming up": the next three tool state changes from the projection ("0:30 Blanching pot → heating water").

If the plan has no tools, the Kitchen button is hidden.

### 9.7 Recipe card

One recipe from the plan by itself, with ingredients grouped under the step that uses them. Reachable from the Import chips, the Live overline and timer rows, and the Full schedule chips. Available before, during and after a session.

1. **Header.** Back button. Overline in the recipe color: "Recipe · 11 min hands on · 42 min total" (hands-on is the sum of this recipe's active durations; total is first planned start to last planned end of its steps). Recipe name, display font, 30 sp.
2. **Controls row.** Servings stepper and the "As written / Grams" control (§9.8).
3. **All ingredients.** A collapsible section listing every ingredient once, in plan order, for gathering. Expanded before a session starts, collapsed once it is running. Label shows the count: "All ingredients (5)".
4. **Steps.** A vertical rail with one numbered node per step, in recipe order (topological order of `after` within the recipe, ties by `planned_start_sec`), connected by a 2 dp line.
   - Each step: title at 15 sp semibold, then its ingredients, one per line: amount right-aligned in a 52 dp column in semibold, then the name, then the note or the as-written measure in Muted.
   - An ingredient used by two steps appears under both.
   - A passive step shows "timer 18 min · starts 0:20" in Muted and has an accent-outlined node; an active step with no ingredients shows only its duration and time.
   - Tools, if listed, on one Muted line: "Rice pot · Burners".
5. **Not tied to a step.** Ingredients no step uses, under the label "Also needed".
6. **Notes.** The recipe's `notes`, if present.

**Progress, during a session** (the "Track progress" switch in the Steps header, global, default on):

- DONE steps and their ingredients drop to 45% opacity; the node shows a check.
- The RUNNING active step becomes a card in the recipe tint with a solid-accent border and a filled node, labelled "now", with its remaining time. Its ingredients are the ones in use.
- A RUNNING passive step shows its countdown.
- Pending steps show their projected start, elapsed or clock per §9.9.
- With the switch off, nothing fades or highlights.

The card is read-only: steps cannot be marked done from here.

If no step in the recipe has `uses`, the Steps section lists steps without ingredients and "All ingredients" stays expanded.

### 9.8 Scaling and gram weights

Controls in the recipe card header. They affect only that card's ingredient amounts.

**Scaling**

- Stepper beside "Serves": −, the value, +. Step 0.5. Minimum 0.5. Maximum 4 times the base `servings`. "Reset" appears when the value differs from the base. Buttons are 44 dp and auto-repeat when held (§11.3).
- With no `servings`, the stepper shows a multiplier ("×1"), step 0.5, range ×0.5 to ×4.
- `factor = chosen / base`. Every `amount` and `grams` is multiplied by `factor`. Ingredients with no `amount` are unchanged.
- Scale is per recipe, persisted with the plan.
- Display rounding applies only when `factor != 1`: `cup` to the nearest ⅛; `tbsp`, `tsp`, other units and unitless counts to the nearest ¼. Anything that would round to 0 shows the smallest increment. No unit promotion.
- Format amounts with the fractions ⅛ ¼ ⅓ ⅜ ½ ⅝ ⅔ ¾ ⅞ where they match exactly, otherwise up to two decimals with trailing zeros dropped.
- Scaling does not touch step durations, the schedule, or step text. While `factor != 1`, show in Amber under the controls: "Amounts scaled to 6 servings. Step times and the schedule were planned for 4."

**Gram weights**

- Segmented control "As written / Grams". One global setting, default "As written".
- In Grams mode each amount is replaced, first match wins:
  1. `grams` present → `grams × factor`.
  2. `unit` is a mass unit → convert: `g` ×1, `kg` ×1000, `oz` ×28.3495, `lb` ×453.592. Match case-insensitively, also `gram(s)`, `kilogram(s)`, `ounce(s)`, `pound(s)`, `lbs`.
  3. Otherwise show the amount as written. Do not estimate from volume.
- Rounding: under 10 g to the nearest 0.5 g; 10 to 100 g to the nearest 1 g; above 100 g to the nearest 5 g.
- Row format: "280 g long-grain white rice (1½ cup)" with the original measure, scaled, in Muted.
- One Muted line at the bottom of the card in Grams mode: "Weights for cups, spoons and whole items are estimates."

### 9.9 Time display

One persisted boolean, `showClockTimes`, default off. It changes how step times are labeled, not engine behavior.

- **Off:** offsets from session start: "0:34".
- **On:** wall-clock times, `startedAtMs + projected offset`, formatted with the device locale and 12/24-hour setting, no seconds.
- Applies to: Live, Full schedule, Kitchen, Import, recipe cards, Done.
- Timer countdowns always stay as mm:ss remaining.
- Clock times come from the projection: where each step lands if everything from now on goes to plan. They move later when the cook goes over, adds time, or extends a timer. Finishing early does not move them earlier.
- Switch it from the Live overline, the Adjust sheet, or the Import screen.
- Refresh labels on `ACTION_TIME_CHANGED` and `ACTION_TIMEZONE_CHANGED`. Stored timestamps are epoch milliseconds.

### 9.10 Done

Finish time as an overline in Aqua, "Everything is ready." in the display font at 44 sp. Three stat tiles: took, planned, difference (Amber if over). One line: start time, hands busy and free minutes. Button "Close" clears the session, with the note "Closing clears this cook. The plan is not kept."

## 10. Kitchen input

The cook's hands are wet, floury or holding something. Live must work with a knuckle, the side of a hand, or no touch at all.

- **Targets.** On Live, nothing tappable is smaller than 56 dp in either dimension, and Done is 76 dp high and at least 230 dp wide. At least 10 dp between adjacent targets.
- **No precision gestures on Live.** No swipes, drags, or long-press as the only route to anything. Wet screens register these badly.
- **Volume buttons.** While Live is in the foreground and "Use volume buttons" is on (default on):
  - Volume down = Done on the current step. If something is ringing, the first press silences it and a second press marks its follow-up done.
  - Volume up = +1 min on the current step; in the Free state, "Start next early".
  - Consume these key events so the volume does not change. The first time Live opens, show for 6 s: "Volume down marks a step done. Volume up adds a minute."
- **Mistakes are cheap.** Every Done shows the undo bar (§9.3) for 30 s, because accidental presses are more likely here than in most apps.
- **Timer sheet.** Tapping a timer row opens a bottom sheet with three 72 dp buttons: −1 min, +1 min, +5 min, and the timer's new end time above them. It closes itself 4 s after the last press.
- **Silence is big.** A ringing timer's Silence and Done buttons are 64 dp high, and the notification actions do the same without unlocking.
- Outside Live (recipe card, Import, Adjust) normal 44 dp targets and the hold gestures in §11.3 are fine; those screens are used with clean hands.

Voice control is out of scope for v1.

## 11. Visual design

Dark theme only in v1. The palette follows the same structure as the Dracula editor theme without using any of its colors: a dark, slightly tinted neutral ground, an off-white foreground, a muted mid-tone, and pastel accents spread around the hue wheel at similar lightness and mild saturation. The ground is tinted toward teal-slate, not Dracula's blue-violet.

**Neutrals**

| Token | Value | Use |
|---|---|---|
| Background | `#1E2529` | Screen ground. Also the text color on any accent-filled surface. |
| Surface | `#283136` | Cards, rows, sheets. |
| Hairline | `#3A474F` | Dividers, borders, dashed "free" outlines. |
| Foreground | `#E9EDEA` | Primary text. |
| Muted | `#9FB0AE` | Secondary text, labels, null-recipe steps. |

**Accents**

| Name | Solid | Tint (20% over Surface) |
|---|---|---|
| Coral | `#F0A08C` | `#504747` |
| Periwinkle | `#A3B8F0` | `#414C5B` |
| Sage | `#A6D4A0` | `#41524B` |
| Orchid | `#D0A6E6` | `#4A4859` |
| Aqua | `#8FD0CF` | `#3D5155` |
| Amber | `#EBC38A` | `#4F4E47` |
| Rose | `#EFA3BF` | `#504851` |

Recipe colors are assigned by recipe index: 0 Coral, 1 Periwinkle, 2 Sage, 3 Orchid. Aqua is the app's own accent (primary buttons, switches, the current-step outline). Amber marks over-time, hold warnings and tool warnings. Rose marks errors and destructive actions.

**Use**

- Hands-on: solid accent fill with Background-colored text.
- Unattended: accent tint fill, 1.5 dp solid-accent border, Foreground text.
- Free: no fill, 1.5 dp dashed Hairline border, Muted text.
- Text on a tint is always Foreground or Muted. Accent-colored text is allowed directly on Background or Surface.
- Never rely on color alone; every colored element also carries the recipe's short name. The accents are close in lightness, so this matters more than usual.

Measured contrast: Foreground on Background 13.1:1 and on Surface 11.2:1; Muted on Surface 5.9:1; every accent on Surface at least 6.4:1; Background-colored text on every solid accent at least 7.5:1; Foreground on every tint at least 7.0:1. Keep any new text pair at 4.5:1 or better.

**Type**

| Role | Font |
|---|---|
| Display (titles, large numerals, countdowns) | IBM Plex Sans, 500 and 700, bundled |
| Body | Inter, 400/500/600, bundled |
| Monospace (error excerpt only) | IBM Plex Mono, bundled |

### 11.1 Motion

Motion has two jobs: show where something came from or went, and confirm that a press landed. Anything that does neither is cut.

**Three springs, defined once in `ui/theme/Motion.kt`.** No ad hoc `tween` values in screens.

| Token | Damping ratio | Stiffness | Used for |
|---|---|---|---|
| `press` | 0.8 | 600 | Press-down and release of anything tappable |
| `arrive` | 0.55 | 350 | Things that enter or confirm. The one that overshoots. |
| `settle` | 1.0 | 300 | Layout that shifts because something else changed. No bounce. |

Fades are 120 ms out and 200 ms in. Nothing animates longer than 500 ms except the ambient item below.

**Overshoot** is only for things that arrive or confirm. Never on anything that represents time (countdowns, progress bars), and never on errors or warnings.

- **Press.** Every tappable surface scales to 0.97 on press and back with `press`. Ripples are tinted with the surface's own accent.
- **Done.** The top block's content slides up 24 dp and fades out; the next step's content rises from 32 dp below with `arrive`, so the title overshoots slightly and settles. The block's fill cross-fades between recipe colors over 200 ms. The first "Then" line is a shared element that travels up into the title.
- **Timer starts.** Its row scales in from 0.8 with `arrive`, originating at the bottom edge of the top block. Other rows move with `settle`.
- **Timer ends.** The row pulses once (1.0 → 1.05 → 1.0, `arrive`), its border goes to 3 dp, then it travels as a shared element into the interrupt bar.
- **Ringing.** The interrupt bar's border pulses between 1.5 and 3 dp once a second. This is the only repeating animation tied to an alert.
- **Countdown digits.** Each digit rolls vertically when it changes (120 ms, no bounce). Only digits that changed move. The 72 sp remaining-time number does the same.
- **+1 min.** The remaining-time number bumps (scale 1.12 → 1.0, `arrive`) and rolls upward. Timer bars and "Then" times move with `settle`.
- **Going over time.** The Amber pill fades in. Nothing shakes or flashes.
- **Free state.** The dashed border's dash offset drifts slowly (one dash length per 2 s). This is the only ambient animation.
- **Recipe card.** When a step becomes RUNNING its card grows out of the plain row with `arrive` and its node fills; when it finishes, it fades to 45% and the check draws itself along its path in 200 ms.
- **Servings stepper.** The number rolls in the direction of change. Amounts cross-fade with a 15 ms stagger per row, capped at 8 rows.
- **Clock-time toggle.** Time labels flip on their horizontal axis, 20 ms stagger top to bottom.
- **Screen changes.** Live → recipe card: the recipe's short name is a shared element into the header; the rest fades. Full schedule and Kitchen slide up from the bottom row. Support predictive back.
- **Done screen.** The stat tiles enter 60 ms apart with `arrive`. No confetti.

**Reduced motion.** If the system animator duration scale is 0, or the "Reduce motion" switch is on, replace every spring and slide with a 120 ms fade and stop the ambient animation. Countdowns still update and ringing still pulses.

**Motion gallery.** In debug builds only, a screen reachable by holding the logo on Empty lists every animation above with a Replay button.

### 11.2 Haptics

Use `LocalHapticFeedback` or `View.performHapticFeedback` so the system haptic setting is respected. Check which constants exist in the Compose version in use and fall back to the nearest `HapticFeedbackConstants` value.

| Event | Feedback |
|---|---|
| Done (button or volume key) | Confirm |
| +1 min, stepper step, each auto-repeat tick, timer sheet buttons | Light tick |
| Hold recognized (§11.3) | Long-press |
| Switch toggled | Toggle on / off |
| Stepper hits its minimum or maximum | Reject, and the number shakes 4 dp horizontally once |
| Hold-to-end completes | Heavy click |
| Undo | Confirm |

Ringing uses its own vibration pattern (§8.2). No haptic on navigation, scrolling, or automatic state changes.

### 11.3 Hold gestures

Used only where precision is not needed or where the action should be hard to do by accident. Each buzzes when recognized (400 ms).

- **Hold +1 min, or a servings − / +, to repeat.** After recognition it repeats every 300 ms, speeding up to every 120 ms after five repeats, a tick each.
- **Hold to end the session.** "End session" has no confirmation dialog. Holding fills the button left to right in Rose over 1.2 s; releasing early drains it back with `settle`; completing gives the heavy click and ends the session. A plain tap shows "Hold to end" under the button for 2 s.
- **Hold the Live overline's recipe name to peek.** The recipe card rises as a half-height sheet while held and dismisses on release.

Nothing is reachable only by holding. Each hold target declares a custom accessibility action with a plain label, and End session is exposed to TalkBack as a normal action with a confirmation dialog.

### 11.4 Finish

Each of these is a requirement.

- **No stock theme showing through.** Define the full Material color scheme from §11 so no default purple, ripple, switch color or elevation overlay appears. No drop shadows; depth is Background → Surface → tint.
- **Edge to edge.** Draw behind the status and navigation bars with correct insets. The status bar area takes the top block's color on Live. Themed splash screen and an adaptive icon with a monochrome layer, both from the brand files (§11.5).
- **One radius set:** 8, 12, 14, 24 dp.
- **Numbers.** Tabular figures for every time and amount. Real characters: °, ×, ½, and an en dash for ranges. Plurals resources ("1 min", "2 min"); seconds under a minute read "15 s".
- **Copy.** Sentence case. No exclamation marks, no emoji, no "Oops". Errors say what is wrong and what to do, with the actual step names, tools and times.
- **No spinners and no skeletons.** Everything is local.
- **Font scale.** Layouts hold at system font scale 1.3 with no clipped text. Portrait only.
- **Frame rate.** No animation may recompose the whole Live screen. Countdown, progress bars and springs each live in their own scope.

### 11.5 Brand

The logo is a woodcut-style view of a stove top: four toothed flame rings, each crossed by dark grate bars, with gouge marks in the open ground. Files are in `brand/` as SVG. Convert to Android vector drawables; do not redraw or simplify them by hand.

| File | Use |
|---|---|
| `four-burners-icon.svg` | Adaptive icon foreground. Simplified for small sizes. Scale the artwork to fit the adaptive icon safe zone; background layer is Background `#1E2529`. |
| `four-burners-icon-mono.svg` | Source for the monochrome (themed) icon layer: use its light shapes as the single-color layer. |
| `four-burners-mark-app.svg` | Splash screen, centered on Background. |
| `four-burners-lockup-app-transparent.svg` | Empty screen, and anywhere the name appears with the mark inside the app. |
| `four-burners-lockup.svg`, `four-burners-mark.svg`, `four-burners-mark-transparent.svg` | Black-on-paper versions for use outside the app (README, store listing). Not used in the UI. |

- The four burners take the four recipe accents in order: Coral, Periwinkle, Sage, Orchid.
- The lettering in the lockup is drawn shapes, not a font. Never set the name in that style with text; inside the app, the name in running text is IBM Plex Sans.
- Do not animate the logo, recolor it, or place it on any ground other than Background or paper.

## 12. Persistence and lifecycle

- Persist the `Session` after every engine action. On launch, load it and recompute from the wall clock; passive steps that ended while the app was dead become DONE with their true end time, their dependents start at that time, and unacknowledged ones ring.
- Use `System.currentTimeMillis()` for stored timestamps. If the stored start is in the future or more than 12 hours old, offer to discard the session.
- Settings (`showClockTimes`, grams mode, track progress, volume buttons, reduce motion) and per-recipe scale are stored separately from the session.
- Process death must not lose state.

## 13. Tests

Unit tests, required. All use the reference plan and a fake clock unless stated.

**Parser**
- Reference plan in a fenced block inside prose; bare JSON; no plan; two code blocks where only the second is a plan.

**Validator**
- One failing case per rule in §5.3.
- Tool capacity: move `b_pot` to start at 900 with a third burner user added → rejected, naming Burners and the time.
- Tool state: change `c_in`'s oven `needs` to "450°F" → rejected, quoting the actual state "425°F".
- A plan with no `tools` and no `ingredients` is accepted.

**Engine**
- Marking every step done exactly on plan finishes at 3170 s with slip 0.
- Finishing `c1` 180 s late delays `r1` and later plan-timed steps by 180 s and does not change the preheat or roast durations.
- `c_in` becomes available the moment `c_pre` ends, even with slip > 0. If `r1` is still RUNNING at that moment, `c_in` is an interrupt and `r1` is not reset.
- `c3` becomes Now at the roast timer's end regardless of slip.
- Free state after `b1`; `startEarly` makes `b_pot` Now.
- A step whose tool is occupied is not eligible (custom plan: a pot with `count` 1, passive step P on it for 600 s, active step A on the same pot with no `after`, planned at 600): if P is extended by 120 s, A does not become Now until P ends.
- `extend` on a running timer moves its end and its follow-up; a negative extend cannot leave less than 30 s.
- `extend(b1, 60)` moves the projected start of every later plan-timed step by 60 s at once; marking it done at the extended time gives slip 60.
- A running active step 90 s past its duration with no extension projects its end at `nowMs`.
- `undo` within 30 s restores the prior state; refused after.
- Restoring a session after 10 simulated minutes of process death yields the same state as running continuously.
- `toolStates`: at 800 s the oven is occupied by `c2` in state "425°F", the sheet pan is "raw chicken", the board is "onion", the blanching pot is "clean" and free.
- `toolWarning` (custom plan: passive P for 600 s; active A after P, planned at 600, board `needs` "clean"; active B with no `after`, planned at 700, board `leaves` "raw chicken"): `startEarly` during P runs B first; when A becomes Now the warning names the board and both states.

**Recipe card, scaling, grams**
- Recipe `r` lists its steps in the order r1, r2, r3, r4, r5, r6 and reports 11 min hands on and 42 min total.
- Amount formatting: 1.5 → "1½", 2.75 → "2¾", 0.5 → "½", 8 → "8", 1.2 → "1.2".
- Recipe `r` from 4 to 6 servings: rice "2¼ cup", butter "3 tbsp", onion "1½", stock "4⅛ cup". Stepper cannot go below 0.5 or above 16.
- Grams at 6 servings: rice 415 g, butter 42 g, chicken thighs 1800 g, green beans 680 g (converted from 1 lb), lemon stays "¾" with no gram value.
- Grams rounding: 4.3 → 4.5, 37.4 → 37, 417 → 415.
- Ingredient states: while `c1` is RUNNING all five chicken ingredients are IN_USE, and DONE after it. For recipe `b`: `beans` is IN_USE during `b1`, IDLE after it, IN_USE during `b2` and `b3`, DONE after `b3`; `salt` is DONE after `b_pot`; `lemon` is IDLE until `b3`.

**Alerts** (logic only, with a fake scheduler)
- A finished roast timer is in the ringing set until `acknowledge`, `done(c3)`, or `extend`.
- The rest timer (`c4`) never enters the ringing set.

Manual checks, listed in the README:
- Share the reference plan as text from any app → Import screen.
- A timer rings with the screen off and keeps ringing until silenced; Silence and Done work from the lock screen.
- Reboot mid-session → alarms still fire.
- Volume down marks a step done on Live and does not change the volume.

## 14. Acceptance criteria

1. `./gradlew assembleDebug` and `./gradlew test` succeed from a clean checkout.
2. Sharing text containing the reference plan opens the Import screen with 3 recipes, 53 min, 18 steps.
3. Running the sample to completion by marking steps done on schedule ends on the Done screen.
4. No `INTERNET` permission in the merged manifest.
5. The engine, parser and model packages have no Android imports.
6. A plan that double-books a tool or breaks a tool's required state is rejected with a message naming the tool, the step and the time.
7. A finished timer rings until acknowledged, including with the screen off.
8. Live can be driven start to finish with only the volume buttons.
9. Each recipe's card shows its steps with their ingredients beneath them; servings step by 0.5; Grams mode shows weights where available.
10. The Kitchen screen shows each tool's current state and who is using it.
11. Holding End session for 1.2 s ends the session and a tap does not.
12. With "Reduce motion" on, no spring or slide animation plays.

## 15. Out of scope for v1

Computing or re-optimizing schedules, judging whether a plan is efficient, rescheduling for a scaled batch, ingredient state tracking beyond what tool states say, multiple cooks, multiple saved sessions, unit conversion other than to grams, estimating weights the plan did not supply, a recipe library beyond the current plan, voice control, tablets and landscape layouts, light theme, Wear OS, widgets, cloud sync, Play Store release work.

## 16. Appendix: instructions for the sender

Text to give Claude in chat so it emits plans the app accepts:

```
When I ask for a cook plan for two or three recipes, schedule them for one cook
and output a single ```json block in cookplan v1 format:

- Top level: "cookplan": 1, "title", "tools" [...], "recipes" [...], "steps" [...].
- Each recipe: id, name, short, servings, notes, and "ingredients"
  [{id, name, amount, unit, grams, note}] with the full ingredient list.
- "grams" is the weight in grams of the stated amount. Include it for every
  ingredient you can weigh with reasonable confidence, including whole items.
  Leave it out when you can't estimate it or the unit is already g, kg, oz or lb.
- Each step: id, recipe (or null for a shared chore), kind ("active" = needs my
  hands, "passive" = unattended timer), title, optional detail, duration_sec,
  planned_start_sec (offset from start), after (ids that must finish first),
  uses (ingredient ids from that step's recipe).
- Passive steps also get timer_label and max_hold_sec (how long the food can sit
  once the timer ends).
- A passive step starts by itself when the steps before it are done. Put the
  physical action that starts it (pan into the oven, pot onto the heat, adding
  stock) in the hands-on step before it.
- Non-interrupt active steps must not overlap each other. Put active steps of one
  recipe inside the passive time of another to shorten the total.
- A short action forced by a timer ending (burner off, pan out, pan in) is an
  active step with "interrupt": true and duration_sec <= 120.
- Tools: list every oven, burner set, pot, pan and board in "tools"
  [{id, name, count, initial}]. On each step list the tools it occupies as
  [{id, needs, leaves}], where needs is the state the tool must be in at the
  start and leaves is its state at the end ("clean", "raw chicken", "425°F",
  "boiling water"). Burners are one tool with a count and no states.
- Every change of tool state is a step: turning the oven on, the preheat wait,
  water coming to a boil, washing a board. Do not assume a hot oven or boiling
  water at time zero.
- Order work so tools are in the state each step needs: vegetables on the board
  before raw meat, or a wash step in between.
- Schedule so the recipes finish at about the same time.
- Output only the JSON block and one line with the total time.
```
