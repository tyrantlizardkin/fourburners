# Four Burners

A recipe interlacing Android app that plans and schedules cooking multiple dishes simultaneously so everything finishes ready-to-serve at the same time.

## Overview

Four Burners takes a plan — a set of recipes with their cook times and steps — and produces an interleaved schedule showing exactly **what to do now**, what's next, and when each dish will be ready. It handles real-world complexity: overlapping timelines, adjustable step durations, slip-and-undo during active cooking, and full projection of the remaining plan.

## Features (Milestone 1 + 2)

| Area | Status |
|---|---|
| **Plan import** — paste or share plain text from any source | Done |
| **Text parsing** — extract recipes, steps, durations, capacity hints | Done |
| **Schedule generation** — interleave steps across burners (pots/stoves) | Done |
| **Live session** — timer tracking, undo, slip, interrupts | Done |
| **Full schedule view** — projected timeline with status indicators | Done |
| **Session finish summary** — took vs planned, diff stats | Done |
| **Offline persistence** — JSON save/restore via app-private storage | Done |
| **Share-sheet intake** — import plans from any app | Done |

## Tech Stack

- **Language:** Kotlin 1.x (JVM 17)
- **UI:** Jetpack Compose (Material 3, no navigation library — conditional rendering)
- **State:** ViewModel + StateFlow
- **Serialization:** kotlinx.serialization (JSON)
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 37 (Android 15)

## Project Structure

```
app/src/main/java/app/fourburners/
├── engine/          SessionEngine, Simulator — core scheduling logic
├── model/           Plan, Session data classes
├── parse/           PlanExtractor, PlanParser, PlanValidator
├── data/            SessionStore (JSON persistence)
├── ui/              AppViewModel + all Compose screens
│   ├── screens/     EmptyScreen, ImportScreen, LiveScreen, ScheduleScreen, DoneScreen
│   └── theme/       Dark Material3 theme, recipe colors
└── MainActivity.kt  Entry point, share-intent handling, screen routing

plans/               Spec, mockups, brand assets, sample plans
```

## Build & Run

### Prerequisites
- Android Studio (Arctic Fox or later)
- JDK 17
- Android SDK with API level 37

### Gradle (command line)
```bash
./gradlew assembleDebug          # build debug APK
./gradlew :app:test              # run unit tests
./gradlew compileDebugKotlin     # verify compilation only
```

### In Android Studio
1. Open this project directory
2. Sync Gradle files when prompted
3. Run the `FourBurners` app configuration (connected device or emulator)

## License

Private / internal project.
