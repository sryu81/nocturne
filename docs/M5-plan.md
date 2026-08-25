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

## Decisions (resolved 2026-08-24)

1. **Rotator hardware presence — app already knows this, no new detection needed.**
   `TrainAssignment.rotator` (`session/AppState.kt:580`) is a real field, already populated from
   the real `train_get_all` response (`WireTrain.toTrainAssignment`,
   `EkosRemoteController.kt:1927-1939`): `"None"` when no rotator role is assigned in the active
   Optical Train, a real device name (e.g. "Optec Pyxis") otherwise. `primaryTrain.rotator !=
   "None"` is the exact real check step 2/4/5 need — no INDI property probing required, this data
   is already flowing.
2. **Real `align_solve` orientation data — found, and it's better than what was scoped.** Real
   Ekos already ships a **purpose-built feature for exactly steps 4 and 5**, currently 100%
   unwired in this app (confirmed by grep — zero references):
   - `align_manual_rotator_toggle` (`ALIGN_MANUAL_ROTATOR_TOGGLE`, `message.cpp:924`) — request
     `{"toggled": bool}`, starts/stops Ekos's own real repeated capture→solve→report loop.
   - `align_manual_rotator_status` (`ALIGN_MANUAL_ROTATOR_STATUS`, `message.cpp:2731`) — **server
     push only**, real payload `{"currentPA": double, "targetPA": double, "threshold": double}`.
     This is step 4, verbatim — no decoding of `new_align_state`/`solution` needed at all.
   - `align_set_astrometry_settings`'s `rotator_control` bool field (`message.cpp:890`) is the
     real switch for step 5's auto-CAA half: when true, real Ekos drives the actual rotator
     hardware itself during this loop instead of just reporting the delta for a human to adjust
     by hand. `kcfg_AstrometryRotatorThreshold`/`kcfg_AstrometryFlipRotationAllowed` (already in
     `align_get/set_all_settings`'s live-verified field list) are this feature's own tuning knobs.
   - This reframes step 5 from "build a custom solve→delta→rotate loop" to "wire three already-
     real commands/pushes Ekos already runs the loop for" — the biggest scope-down in this plan.
3. **Star chart source: offline/bundled catalog**, reusing the star-catalog/geometric-hash
   groundwork scoped for M4.5 Part B (`docs/M4.5-plan.md:133-172`) rather than a new online
   DSS/HiPS fetch path. Consistent with the app's existing no-internet-dependency norm.
4. **Step 6, "Add to sequence" button — leave as-is.** No auto-push change. The original 6-step
   description was the user's own account of their real-world workflow, not a request to change
   this button's behavior.

## Proposed implementation order

Per your call, star chart goes first:

1. **Step 1, star chart.** Bundled bright-star catalog + RA/Dec→screen projection, target
   centered. Independent of everything else below — no wire dependency beyond data already on
   the wire (`astro_get_object_info` for the target's own RA/Dec). Shares catalog work with M4.5
   Part B if that lands around the same time.
2. **Steps 4 + 5 together** — they're now the same real feature, wire it once:
   - New `Commands.ALIGN_MANUAL_ROTATOR_TOGGLE`.
   - New `EkosEvent.ManualRotatorStatus(currentPA, targetPA, threshold)` + codec case for the
     `align_manual_rotator_status` push.
   - `FramingCard` gets a real currentPA/targetPA/threshold readout + start/stop toggle, replacing
     the fixture-only `rotatorAngle` knob's role as "desired frame" indicator.
   - Gate the auto-drive option (`rotator_control` in astrometry settings) on
     `primaryTrain.rotator != "None"` from decision 1 — show it only when real rotator hardware is
     actually assigned; otherwise the loop still runs and reports currentPA/targetPA (useful even
     with no motorized rotator — that's the literal "manual adjustment, watch the numbers"
     half), just without the auto-drive toggle exposed.
3. **Step 2's old knob**: once step 4/5's real currentPA/targetPA readout exists,
   `RotatorRow`'s fixture-only `setRotatorAngle` either gets repurposed to set the real
   `targetPA` (if that's settable pre-toggle — check `align_manual_rotator_toggle`'s request
   shape again once implementing; the reference above shows only `toggled`, so target PA may need
   to come from `pAHRotation`/a different settings field, confirm before assuming) or gets
   dropped in favor of the new readout. Don't keep both a fake local angle and a real one on
   screen at once.
4. **Step 3's fragility** (`gotoAndCenter`'s fixed-delay heuristics): revisit once step 4/5's real
   push-driven status exists — same underlying pattern (a real async server operation with no
   decoded completion signal) may now have a template to copy from.

## Verification

Real rig, live, for each step as it lands:
- Step 1: confirm the rendered star field's orientation/scale roughly matches the real target's
  known field, for at least 2-3 different real targets at different declinations (a naive
  projection can look right at one declination and be visibly wrong at another).
- Step 4/5: toggle `align_manual_rotator_toggle` on a real target, confirm `currentPA`/`targetPA`/
  `threshold` land and update on the real `align_manual_rotator_status` push. If real rotator
  hardware is assigned in the active train (`primaryTrain.rotator != "None"`), confirm the
  auto-drive (`rotator_control`) path actually rotates the physical device and the reported
  `currentPA` converges toward `targetPA` on its own; without hardware, confirm the readout still
  updates as the frame is manually/physically rotated by hand between solves.
- Step 2: confirm no leftover fixture-only angle control coexists with the new real readout once
  it lands (decision 3 above — one or the other, not both).

## README updates

Add an M5 row to the milestone table (§7) once any of the above ships, same convention as M3/M4 —
note explicitly whether the rotator ended up real hardware or stays framing-preview-only, so the
README doesn't overstate what's real.
