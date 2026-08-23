# Simulator removal plan

**Status: Phases 1–2 done, live-verified against the real rig (through an actual
Pi reboot mid-session), committed 2026-08-23.** Phase 3 (deleting now-dead
fixture data) and Phase 4 (doc-comment polish) remain, both low-priority/optional
— see their sections below for what's actually left.

## What shipped

- `SimulatedController.kt` deleted; `SessionViewModel`/`ConnectScreen`/`GearScreen`
  no longer have any simulator escape hatch — the app is real-rig-only, boots
  straight to Connect screen.
- All 36 `isRealRig` branches removed (the field itself, too — nothing read it
  once the branches were gone) — each screen's real-rig path is now unconditional,
  its old fixture-mode branch deleted (not just unreachable).
- Dead-code chain removed: the fixture 3-step PA wizard (`StepPills`/`PaStep0/1/2`/
  `PaDial`/`paColorOf`/`PA_STEPS`) — `PaSheet` now always dispatches straight to
  `PaRealSheet`.
- Real gap found and fixed: deleting the old `SimulatorExitCard` left **zero** UI
  path to `SessionViewModel.disconnect()` anywhere in the app. Replaced with a
  proper `DisconnectCard` on the Gear tab.
- Rotator/dome (Phase 2 decision: disable-with-reason) — dome's `CloseRoofButton`
  now always disabled with "no real dome command exists yet" (matches the user's
  own standing call that real dome hardware is out of scope indefinitely).
  Rotator turned out to not need this at all — `RotatorRow`'s `setRotatorAngle`
  is a local framing-preview parameter, not a hardware control; no fix needed.
- Frames tab (Phase 2 decision: keep tab, honest placeholder) — now shows "Frames
  aren't available yet — needs the Media channel (M4)" instead of fixture frames,
  matching the disclosure pattern `StatsRow`/`SubPreview` already used. All the
  now-dead fixture grid composables removed.
- Found + fixed the same class of gap in the Session Summary sheet while in the
  area: KEPT/DISCARDED/MED HFR stats and a hardcoded fictional narrative
  ("Lost 20m — cloud 01:04–01:18...") were shown unconditionally, zero
  disclosure — worse than Frames tab had. Now an honest "M4" placeholder and a
  plain "no session-event log yet" line.

## Live verification

Boots to Connect screen; real connect flow; Session tab's real branches (fetching
real dusk/dawn, M4 stat placeholders, meridian flip always disabled); Frames tab
placeholder; Gear tab (real polar status text, `MaintenanceCard` unconditional,
new `DisconnectCard`); Controls tab's module-settings cards rendering real data
unconditionally; Sequence tab's Scheduler-settings icon/sheet unaffected. Also
incidentally confirmed the reconnect-banner path is solid — caught an actual real
Pi reboot mid-verification, banner correctly showed "Reconnecting to rig…" with
no crash, then recovered cleanly once Ekos came back up.

## Why

User's own words: *"what simulator shows and testing with real rig doesn't match.
at this point can you remove simulator? what advantage do we have with it now?"*

Concretely, this session alone found four independent real-vs-fixture mismatches
(night-window literals, Scheduler settings never surfaced, a results-list riseset
cache bug, and TargetCard's max/peak looking contradictory next to "usable") — all
only found by live-testing against the real rig, none of which the simulator could
have caught or would have shown correctly. The project's own testing norm, every
session for a long time now, has been "connect to the real Pi and watch the wire,"
never the simulator. Checked concretely before proposing this: **zero unit tests
depend on `SimulatedController`** — removing it costs no test coverage.

## The risk this plan exists to avoid

`AbstractLocalSessionController` implements all 179 `SessionController` methods.
`EkosRemoteController` overrides 124 of them. The other 55 still do the exact same
local-only `SimState` mutation under a **real** connection today as they do under
the simulator — no wire command sent, no real hardware touched. Most of those 55
are harmless local UI chrome (sheet open/close, tab selection, prefs toggles) that
should just stay local forever, sim or not.

But a few are not harmless to leave as-is once the simulator (and its "this is
fake, don't worry" framing) is gone:

- **`setRotatorAngle`, `setDomeOpen`** — no wire command for either exists *anywhere*
  in `EkosRemote-Command-Reference.md`. These are decorative today, full stop, in
  both modes. Removing the simulator doesn't create this gap, but it does remove
  the one context where "this control does nothing" was expected/harmless.
- **Manual meridian flip** (`requestFlipNow`/`requestDeferFlip`) — already disabled
  with an honest explanation under a real rig (`FlipBanner`, `SessionScreen.kt`).
  No change needed, already correct.
- **PA fixture wizard** (`openPa`/`paNext`/`setPaRate`) — already steered around by
  `PaSheet` dispatching to a separate `PaRealSheet` under a real rig. Already correct.
- **Frames tab** — zero `isRealRig` gating at all; renders fixture `FRAME_IDS`/
  `FRAME_HFRS` unconditionally in both modes. **No real replacement exists** — blocked
  on the Media channel (M4). This one needs a decision (see Phase 2).
- **Sequence/block editing** (`addBlock`, `setBlockExposure`, etc.) — local-only by
  original design (M3 scope, per the repo's own `docs/M3-plan.md`), edits a job
  *before* it syncs to the real Scheduler. This is legitimately fine to stay local —
  it's not "fake data," it's the local editor for something not yet sent to Ekos.

Full per-method/per-branch/per-fixture-constant breakdown is in the investigation
report (session transcript, 2026-08-22) — not duplicated here to keep this doc
skimmable; re-run the same inventory sweep if this plan goes stale.

## Phased plan

### Phase 1 — Mechanical removal (low risk, no judgment calls)
- Delete `SimulatedController.kt`.
- `SessionViewModel.kt`: remove `useSimulator()`, `ConnectionMode.Simulated`, the
  `useSimulator` persisted-settings flag; default/boot state becomes `NeedsConnect`
  unconditionally (no default `ctrl` needed until a real `connect()` succeeds —
  or keep a `null`-until-connected `ctrl` if the type system needs a placeholder).
- `ConnectScreen.kt`: remove the "Use simulator instead" link + `onUseSimulator` param.
- `GearScreen.kt`: remove `SimulatorExitCard` + `onExitSimulator` wiring in `NocturneApp.kt`.
- Strip the now-always-true `isRealRig` guards that were pure card-visibility
  (`ControlsScreen.kt` ×5, `GearScreen.kt`'s `MaintenanceCard` inclusion, all 7
  `Sheets.kt` early-return guards) — each becomes unconditional.
- `SimState.isRealRig` field itself can likely be deleted once nothing branches on
  it — check for stragglers before removing the field.

### Phase 2 — Per-feature decisions (needs sign-off, not mechanical)
For each fixture-only feature with **no real replacement**, decide: hide the
control entirely, or leave it visibly disabled with an honest reason (the
established pattern already used for `FlipBanner`/PA/HFR-RMS-SNR)?
- Rotator angle / dome open — no wire command exists at all. Disable-with-reason,
  matching `FlipBanner`'s precedent, or hide entirely?
- Frames tab — no real data path (Media channel, M4). Hide the whole tab under a
  real rig until M4, or leave it showing fixture frames with a banner disclosing
  that? (Currently shows fixture frames with zero disclosure — that's the one
  actual "looks live, isn't" gap already fixable independent of this whole effort.)
- Any other `AbstractLocalSessionController`-only method worth double-checking
  against the investigation's "NOT overridden" list before Phase 3 deletes its
  backing fixture data.

### Phase 3 — Delete dead fixture data
Only after Phase 2 lands, once nothing references them: `PA_SECS`, `PLAN_CHIPS`
(if the filter chips are removed), `DEFAULT_JOBS`/`DEFAULT_BLOCKS` (once a fresh
install's starting state is decided — empty queue? still needs *some* default),
`ALERTS`, `FRAME_IDS`/`FRAME_HFRS` (per Phase 2's Frames decision), `DEVICES`'s
19-name catalog (keep its category-key skeleton, which is real/load-bearing),
`TARGETS` (partial — still the fallback for `findTarget`/id-lookup; needs a real
replacement mechanism first, not just deletion).

### Phase 4 — `AbstractLocalSessionController`'s fate
Most of its 55 non-overridden methods are legitimate permanent local UI-state
plumbing (sheet nav, prefs) that both a real controller and (formerly) the
simulator needed identically — recommend keeping this class as `EkosRemoteController`'s
base for that reason, just no longer describing it as "shared with the simulator."
Re-read its own class doc comment and rewrite once Phase 1-3 land, since it
currently frames its own purpose around the simulator.

## Open questions before starting (resolved — kept for history)
1. Phase 1 — approved, started same session. ✅ done.
2. Rotator/dome: disable-with-reason. ✅ done (rotator turned out not to need it).
3. Frames tab: keep tab, honest M4 placeholder (not hidden entirely). ✅ done.

## What's actually left (Phase 3/4, optional, low priority)
- Phase 3's fixture-data deletions weren't done — `TARGETS`/`DEFAULT_JOBS`/
  `ALERTS`/`FRAME_IDS`/`FRAME_HFRS`/etc. are all still referenced by at least one
  real call site (`SessionReport.kt`'s disclosed-fixture export section,
  `FrameExpandOverlay`'s now-unreachable-but-still-compiling guard, `findTarget`'s
  id-lookup fallback) — none of them are safe to delete outright yet without
  either building their real replacement or removing those call sites too.
  Not attempted this pass.
- Phase 4's doc-comment polish: the two class-level headers
  (`AbstractLocalSessionController`/`SessionController`) and everything touching
  code actually edited this pass got fixed; ~50 more inline comments elsewhere
  still name `SimulatedController` in historical "why this defaults to X"
  reasoning — still substantively true, just referencing a deleted class name.
  Cosmetic, not urgent, skipped this pass for time.
