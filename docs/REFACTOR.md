# Splitting EkosRemoteController

Plan written 2026-10-31 after the first piece (Rig storage) was moved out. Measured with
`python3 tools/controller_map.py`, which re-prints every number below for the file as it is now.

## Why

`EkosRemoteController.kt` was 3,156 lines: 267 members, 38 private fields, a 275-line event reducer,
and the wire half of every UI action. It is hard to read, and no test can reach the reducer because it
is a private function of a class that needs a live socket. Goals, in order:

1. **Testability.** The event reducer and the domain state it mutates must be reachable by unit tests.
2. **Ownership.** Each domain (plan search, scheduler sync, profiles, rig maintenance, devices) owns its
   state and its logic in one file, instead of 38 fields shared across 2,700 lines.
3. **Size.** Files of 150 to 600 lines. Not a goal in itself.

Non-goal: changing behaviour. Every step moves code verbatim.

## Where it stands (2026-10-31)

| | Lines |
|---|---|
| `EkosRemoteController.kt` | 3,006 (was 3,156) |
| `RigStorageController.kt`, `ControllerContext.kt` (done, `15f2866`) | 198, 22 |
| `AbstractLocalSessionController.kt` (the local-update half) | 893 |
| `SessionController.kt` (the interface the UI uses) | 440 |

## What the survey found

The first version of this plan assumed the settings setters were the easy bulk. They are not.

- **The controller is built on a `super` layering.** 181 of 267 members call `super.…`: the base class
  applies the optimistic local update and the controller then sends the command to the rig. A delegate
  class cannot call `super`, so these members cannot simply move.
- **Most of those are already minimal.** 136 of the 181 are six lines or fewer (send, then `super`). They are
  declarative boilerplate (about 800 lines in the shell), not logic. Moving them gains nothing structurally,
  so they **stay in the controller**.
- **The movable logic is the 86 members without `super`: 1,442 lines.** Plus about 45 members with real logic
  next to a `super` call, 19 of them over 12 lines (`gotoAndCenter` 45, `finishSetup` 25, `plateSolveHere` 23,
  `toggleDevice` 22, `gotoTarget` 19, `editProfile` 19, `syncLocationToRig` 18, `emergencyStop` 17, ...).
  In 31 members the `super` call sits in the middle, so order matters and each needs reading.
- **One knot.** The event reducer and follow-ups (591 lines, including the ~100-line `init` block that sits
  after them) own 6 fields and share 14 with four other domains. It cannot move first.
- **No behavioural tests exist for the controller.** Nothing constructs it. The compiler and a phone smoke test
  are the only checks, and goto, park, guide and focus cannot be exercised safely.

## Domains (from `tools/controller_map.py`)

| Domain | Funs | Lines | Call `super` | Fields owned / shared with the reducer |
|---|---|---|---|---|
| events: reducer, follow-ups, `init` | 9 | 591 | 0 | 6 / 14 |
| equipment actions (goto, park, guide, focus, align, polar) | 38 | 327 | 31 | 2 / 0 |
| scheduler / session | 18 | 247 | 5 | 0 / 3 |
| plan search | 16 | 228 | 6 | 3 / 8 |
| rig maintenance / sky atlas | 16 | 218 | 5 | 1 / 1 |
| profiles / trains / scopes | 14 | 180 | 9 | 1 / 2 |
| devices / INDI | 12 | 157 | 5 | 0 / 0 |
| settings setters (scheduler, guide, focus, capture, align, mount) | 127 | 606 | 120 | 0 / 0 |
| rig storage (done) | 17 | 25 | 0 | 0 / 0 |

## The pattern

Proven by Rig storage: a class per domain taking a `ControllerContext` (connection, scope, read and
update of `AppState`), holding the domain's private state, and exposing the functions the controller used to
hold. The controller keeps one-line `override`s that delegate. A domain that reacts to wire events exposes
`onEvent(...)`; the controller's follow-up dispatch calls it.

For a member that calls `super`, the controller override stays and keeps both halves in order:

```kotlin
override fun setSomething(x: Int) { domain.sendSomething(x); super.setSomething(x) }   // same order as before
```

## Steps

Each step is one commit. Between steps: compile, the unit tests, and the phone smoke test below.

| # | Step | Moves | Risk | Status |
|---|---|---|---|---|
| 1 | Rig storage | 151 lines | low | **done** `15f2866` |
| 2 | Top-level helpers after the class | ~240 lines | none (pure functions) | todo |
| 3 | Plan search: owns 3 fields, shares 8 with the reducer; the reducer's `Astro*` cases call `search.onEvent` | ~230 | medium | todo |
| 4 | Profiles / trains / scopes | ~180 | medium (9 `super` callers) | todo |
| 5 | Rig maintenance and sky atlas (reboot, shutdown, time and location sync, atlas copy) | ~220 | medium | todo |
| 6 | Scheduler / session (push, sync, finish, end session) | ~250 | high: job lifecycle, 5 `super` callers | todo |
| 7 | Devices / INDI (property sets, throttle, subscribe) | ~160 | medium | todo |
| 8 | Reducer: the shared state now lives in the domain classes, so `applyEvent` and the follow-ups become a dispatcher in `EventReducer.kt`, with **new unit tests** for each event case | ~590 | high | todo |
| 9 | Equipment actions, member by member, only where `super` is first or last | ~300 | high: moves hardware | todo, optional |

Steps 2 to 7 are independent of each other and can go in any order. Step 8 needs 3 to 6 done first.
Step 9 is optional; the declarative setters stay in the controller regardless.

**Expected result:** controller about 1,300 lines (the declarative overrides, the delegations, `init` and the
dispatch), domain files of 150 to 600 lines, and the reducer under unit test.

## How each step is checked

1. `./gradlew :app:compileDebugKotlin`: the move is mechanical, so the compiler catches most mistakes.
2. `./gradlew :app:testDebugUnitTest`: all existing tests still pass.
3. `python3 tools/controller_map.py`: the domain's lines left the controller and nothing new appeared.
4. Phone smoke test, rig idle, nothing that moves hardware: connect, open Plan and search a target,
   Gear (profiles, trains), Frames, Rig storage down to a file page, open the Sequence tab, Home.
5. For steps 3 and 8, a **characterisation test written before the move**: feed recorded wire payloads
   (those already in `EkosEventCodecTest`) through the reducer and assert the resulting `AppState`, so the move
   is checked against the old behaviour and not only against the compiler.

## Not verifiable here

Goto, park, guiding, focus and polar alignment (step 9) and the scheduler lifecycle (step 6) cannot be
exercised without the sky or moving the mount. Those steps move code verbatim, keep the order of every
`super` call, and are done only when the user has a night to check them. Wire traps from earlier nights
(partial-shape decode, `exact:true`, scheduler toggles; see `docs/PROTOCOL.md`) are in the code being moved,
so none of it is rewritten.

## Decisions

1. **Steps 6 and 9 stay in (decided 2026-10-31).** The user can test the scheduler lifecycle and the equipment
   actions under the sky, so they go as planned, last, with a rollback ready.
2. **Splitting `AbstractLocalSessionController` is out of this plan.** It is the larger redesign: one action is
   implemented in the interface (`SessionController`, 275 members), the base class (the local update, 255) and
   `EkosRemoteController` (the wire send, calling `super`); 205 actions have both halves. Splitting the base the same
   way means dissolving the inheritance: `_state` and `update` move to the shared context (touching every member), the
   181 `super.…` calls become explicit local-update-then-send code (31 have `super` in the middle, so order is a
   behaviour question per call), the interface is cut into sub-interfaces the screens consume, and nothing tests either
   class. About 4,300 lines across three files. Revisit only after steps 1 to 9, if three places per action still hurts.

## Testing tonight (2026-10-31)

Two things would be new on the same night: the refactored controller and the first real capture in the
`Nocturne/<Target>/<night>/…` tree (plan 3, L1). If something breaks, which one did it? Keep the order clear:

- Before dusk, install the refactored build and run only short, controlled checks of the moved paths (goto and
  park, guiding start and stop, autofocus, polar alignment screens, scheduler start, finish and end session), each
  watched in the Log tab.
- Keep the rollback APK ready: `~/Workspace/nocturne-rollback/app-debug-15f2866.apk` is the build installed before
  the split (git `15f2866`), restorable with `adb install -r`.
- If a moved path misbehaves, restore the rollback before the capture run, so the L1 layout check stays clean.
