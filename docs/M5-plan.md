# M5 — Plan tab real framing workflow

## Context

User's envisioned framing flow for a selected Plan-tab target:

1. Plot a star chart on screen with the target centered, whenever a target is selected.
2. User sets the desired frame via a rotator bar.
3. Goto and center.
4. Show the current camera angle/position and the difference vs. desired.
5. User takes a snapshot to adjust the frame (manual angle adjustment); if a real rotator/CAA
   device exists, the app can do this automatically instead.
6. Add to sequence.

Audited against the actual codebase (2026-08-24) before writing this plan — see per-step status
below. Findings, not guesses; every claim below cites real file:line.

## Per-step status (current, before this plan)

| # | Step | Status |
|---|---|---|
| 1 | Star chart, target centered | **Missing** |
| 2 | Rotator sets desired frame | **Partial** — real UI, fixture-only backend |
| 3 | Goto and center | **Real, implemented** |
| 4 | Show current angle vs. desired | **Missing** |
| 5 | Manual snapshot to adjust / auto CAA | **Partial** — real pieces exist elsewhere, not wired here |
| 6 | Add to sequence | **Partial** — real local job, not auto-pushed to Ekos |

### 1 — Star chart, target centered

`FramingCard` (`ui/plan/PlanScreen.kt:611-667`) currently draws `HatchBg` (a diagonal hatch
texture, `ui/components/Widgets.kt:423-434`) plus a rotated rectangle standing in for the camera's
FOV footprint — no star field, no sky image, no target-centered plot of any kind. No
star-chart/sky-plot concept exists anywhere in the app (confirmed by grep across
`app/src/main/java/com/nocturne/`). The one "hips" string in the codebase
(`protocol/MediaFrame.kt:58,69`) is an unrelated Media-channel frame-type tag from Ekos's own
SkyPoint feature, classified `MediaFrameType.OTHER` and never rendered.

### 2 — Rotator control

`RotatorRow` (`ui/plan/PlanScreen.kt:669-709`) is a real, working drag-to-set knob wired to
`ctrl::setRotatorAngle`. But `setRotatorAngle` (`SessionController.kt:111`) is **never overridden**
by `EkosRemoteController` — it only exists in `AbstractLocalSessionController.kt:654` as a plain
local-state update. **Confirmed during the simulator-removal audit** (`docs/
simulator-removal-plan.md:74-75`): no wire command for a rotator exists anywhere in
`EkosRemote-Command-Reference.md`; the knob was concluded to be "a local framing-preview
parameter, not a hardware control," and left as-is. Whether this rig even *has* a physical rotator
device was not established at that time.

### 3 — Goto and center

Real and working. `gotoTarget`/`gotoAndCenter` (`EkosRemoteController.kt:929-970`) send real
`Commands.MOUNT_GOTO_TARGET`, then flip the real Align module to `slewR`, send `ALIGN_SOLVE`, wait,
then restore the original solver-action — a genuine slew+plate-solve+re-slew loop against real
Ekos. Buttons at `PlanScreen.kt:132-146`.

**Known fragility, not a bug to fix here**: completion is gated on fixed heuristic delays
(`delay(8_000)`, `delay(15_000)`), not a decoded "slew finished"/"solve finished" signal — flagged
in the function's own doc comment. Revisit once step 4 below needs a real solve-completion signal
anyway.

### 4 — Show current angle vs. desired

Missing — and currently nothing to diff against even in principle. No wire event decodes a real
rotator angle or solved position angle. `NewAlignState` (`protocol/EkosEvent.kt:57`) is only
`{status: String}`; nothing structured is parsed from a real `align_solve` result.

### 5 — Manual snapshot / auto CAA

Real building blocks exist, just not here:
- `plateSolveHere()` → `Commands.ALIGN_SOLVE` (`EkosRemoteController.kt:894-896`), button in
  Controls tab (`ui/controls/ControlsScreen.kt:776`) — works, but reports only the bare status
  string, no angle.
- Real capture-preview: `latestAlignFrame`/`latestCaptureFrame`/`latestFocusFrame`/
  `latestGuideFrame` (`session/AppState.kt:328-331`), populated via the real Media channel
  (`transport/EkosRemoteClient.kt:51,60`), rendered via `MediaFramePreview` in
  `ui/session/Sheets.kt:208,254,1897` and `ui/session/SessionScreen.kt:246,297` — real, but never
  called from `PlanScreen.kt`.
- Closest real precedent for an automated capture→solve→adjust loop: `PaRealSheet`'s real polar
  alignment (`ui/session/Sheets.kt:1868`, driven by real `POLAR_START`/`POLAR_STOP`,
  `protocol/Commands.kt:97-98`) — server-side multi-slew capture/solve/rotate sequence once
  started. Same *shape* as a rotator CAA loop would need, but mount-pointing, not camera-rotation.
- No automatic solve→rotate loop exists, and per step 2, there is no real rotator command to drive
  even if one did.

### 6 — Add to sequence

`PlanScreen.kt:149-157` → `ctrl.addToSequence(tgt.id)`. Only implemented in
`AbstractLocalSessionController.kt:94-101` (creates a local `SequenceJob`/`Block`) — never
overridden in `EkosRemoteController.kt`, so this action alone sends nothing over the wire. Pushing
to real Ekos is today a separate, explicit Sequence-tab action (`pushJob`,
`EkosRemoteController.kt:1741-1786` → `SCHEDULER_SAVE_SEQUENCE_FILE` → `scheduler_add_jobs`). This
split is original, documented design (`docs/simulator-removal-plan.md:87-89`) — "not fake data,
it's the local editor for something not yet sent to Ekos" — not a bug.

## Open items needing a decision before implementation starts

1. **Does this rig have a physical rotator device at all?** Check the real INDI driver list /
   `EkosRemote-Command-Reference.md` directly. This gates steps 2, 4, and 5's auto-CAA half:
   - If **no rotator hardware**: step 2's knob should get an explicit "framing preview only, no
     rotator on this rig" disclosure (matching the app's existing honesty convention — e.g. the
     dome roof button's "no real dome command exists yet" label) instead of implying it's live.
     Steps 4/5-auto become moot; only the manual "capture, look, decide" half of step 5 applies.
   - If **rotator hardware exists**: need its real INDI property name (likely something like
     `ABS_ROTATOR_ANGLE`) to wire a real `device_property_set`/read pair, following the existing
     `setIndiNumber`/`indiNumber` pattern (`session/SessionController.kt:115`,
     `session/AppState.kt`).
2. **Does real `align_solve` actually return usable orientation data**, or only the bare status
   string this app currently parses? Needs checking against the real wire payload
   (`new_align_state`), not assumed — same discipline as every other "confirm against the actual
   payload" item elsewhere in this repo's docs. This gates step 4 entirely, and gates whether
   step 5's auto-CAA loop can compute a real angle delta at all.
3. **Star chart source (step 1)**: bundled star catalog + RA/Dec→screen projection (could reuse
   the star-catalog/geometric-hash groundwork already scoped for the M4.5 Part B offline plate
   solver, `docs/M4.5-plan.md:133-172`) vs. an online DSS/HiPS tile fetch (no networking path for
   this exists in the app today, would be new). Recommend the offline/bundled-catalog route —
   consistent with this app's existing no-internet-dependency norm, and shares work with M4.5 Part
   B if that lands first.
4. **Step 6**: should Plan's "Add to sequence" also auto-push (`pushJob`) once framing is
   confirmed, or intentionally stay local-editor-first as today? Recommend leaving as-is unless
   the user asks otherwise — auto-push would remove the deliberate local-review step the rest of
   the app relies on before anything reaches the real Scheduler.

## Proposed implementation order

Ordered so each step's real prerequisite lands before the step that needs it, and so the
highest-uncertainty open item (rotator hardware) is resolved before work that depends on it:

1. **Resolve open items 1 and 2** (rotator hardware check, real `align_solve` payload check) —
   pure investigation, no code, but blocks correctly scoping everything after this.
2. **Step 5, manual half only**: wire a "snapshot"/"solve" action directly into `FramingCard`,
   reusing `plateSolveHere`/`ALIGN_SOLVE` and `MediaFramePreview`/`latestAlignFrame` (all already
   real elsewhere) — gets a captured/solved frame displaying under the framing rectangle. No new
   protocol work; pure reuse/wiring.
3. **Step 2, honesty fix**: if item 1 comes back "no rotator hardware," add the disclosure label
   immediately — cheap, removes a live misleading-UI gap regardless of what else gets built. If
   real hardware exists, implement the real wire command instead.
4. **Step 4**: once item 2's answer is known, decode real orientation/PA from `align_solve` (or
   from a real rotator-angle INDI read, depending on item 1's answer) and add the desired-vs-actual
   diff readout to `FramingCard`.
5. **Step 5, auto-CAA half**: only if items 1 and 2 both resolve favorably (real rotator + real
   angle data available) — a solve→compute-delta→rotate→re-solve loop modeled on `PaRealSheet`'s
   real polar-align loop pattern (`ui/session/Sheets.kt:1868`).
6. **Step 1, star chart**: independent of the above — can be built any time once item 3's catalog
   source is chosen. Lowest technical risk of the missing pieces (pure rendering + a static
   catalog, no new real-time wire dependency), but the most net-new UI work.
7. **Step 3's fragility**: revisit the fixed-delay heuristic in `gotoAndCenter` once step 4's real
   solve-completion signal exists — replace the blind `delay()`s with an actual decoded
   status transition if the payload supports it.

## Verification

Real rig, live, for each step as it lands:
- Step 5 manual: confirm a real captured/solved frame renders under `FramingCard` for an actual
  target, matching what Controls-tab "Plate solve here" already shows.
- Step 2/4: if real rotator hardware exists, confirm a physical rotation shows up in the app's
  angle readback within one poll cycle; confirm the desired-vs-actual diff updates correctly after
  a manual physical adjustment. If no hardware, confirm the new disclosure label renders and no UI
  claims live control.
- Step 1: confirm the rendered star field's orientation/scale roughly matches the real target's
  known field, for at least 2-3 different real targets at different declinations (a naive
  projection can look right at one declination and be visibly wrong at another).
- Step 5 auto (if built): confirm the loop actually converges to within the coarse-accuracy
  expectation already set for M4.5 Part B, and confirmed via `PaRealSheet`'s precedent — no
  fabricated "aligned" success state without a real converged solve.

## README updates

Add an M5 row to the milestone table (§7) once any of the above ships, same convention as M3/M4 —
note explicitly whether the rotator ended up real hardware or stays framing-preview-only, so the
README doesn't overstate what's real.
