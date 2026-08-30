# Nocturne — Project Status & Plan

Single source of truth for what's shipped, skipped, superseded, or still planned.
Replaces `M3-plan.md`, `M3.3-plan.md`, `M4-plan.md`, `M4.5-plan.md`, `M5-plan.md`,
`simulator-removal-plan.md`, `app-side-feature-backlog.md` (all deleted, content folded in below).

Not touched by this consolidation: `EkosRemote-Client-Guide.md`,
`EkosRemote-Command-Reference.md`, `EkosWebSocket-Fork-Design.md`, `emulator-troubleshooting.md`
— those are living protocol/reference docs, not progress tracking. `docs/FORK-BACKLOG.md` is a
separate, related doc: real KStars-fork-side work (not buildable from this repo alone) — see that
file, not here, for anything requiring a change to `/home/soo/cc/repo/kstars`.

**README.md rewritten** (2026-08-29, same day as the M5/M4.5 work below) — its old milestone table
(§7) and status prose (§7a) had stopped at "Not started: M4" (M4 was actually fully shipped, plus
all of M4.5/M4.6/M5 after) and it still described a since-deleted `SimulatedController`. Replaced
with a current-state overview pointing here for detailed status rather than duplicating a second,
inevitably-drifting tracker — check `README.md` itself for what's current, this note is just the
"why," not itself a status source.

---

## Checklist

### M3 — Plan + Sequence tabs operate real Ekos end-to-end
- [x] `protocol/Commands.kt`/`EkosEvent.kt` additions (PROFILE_*, DEVICE_*, ASTRO_*, SCHEDULER_*, TRAIN_*)
- [x] Device catalog + property sheets live (`wireDevices`, `DeviceRole`, `device_property_subscribe`)
- [x] Profile management + Optical Train split (`RigProfile` shrink, `ADAPTIVE_OPTICS` role)
- [x] Plan tab live `astro_*` search
- [x] Sequence tab real Scheduler/`.esq` wiring (`EsqWriter`, sync lock, `wireIndex`)
- [x] Per-block progress approximation (waterfall-fill) — **permanent**, not a stopgap (real per-block progress needs undocumented `capture_get_sequences`)
- [ ] ~~Mock `mock_ekos_server.py` e2e harness~~ — SUPERSEDED: project norm became live-rig testing instead
- [x] JUnit test source set (came later than M3 itself, doc's own "no tests exist" claim now stale)

### M3.3 — Module settings sheets
- [x] Mount settings (10 curated fields)
- [x] Camera settings (save dir, guide-deviation guard, dither)
- [x] Focus settings (5 curated fields incl. `focusAlgorithm`)
- [x] Align settings (5 curated fields)
- [x] Guide settings (7 curated fields, corrected once from a bad initial field-name probe)
- [x] Real Autofocus start/stop, real Polar Alignment (moved from Gear → Controls tab)
- [x] Align "Solve" / Guide start-stop → Controls tab (relocated from original Bench-card plan)
- [ ] ~~5 separate Gear-tab cards~~ — SUPERSEDED: consolidated into one **Controls** tab
- [ ] Observatory/dome settings sheet — **SKIPPED, permanently**: protocol has no settings surface for this at all
- [ ] Observatory control (`dome_park`/`unpark`/`goto`/`stop`) — NOT STARTED, no `DOME_*` wire command exists; `CloseRoofButton` still honestly disabled

### M4 — Media channel, live previews, Frames, real Guide/Focus/PA
- [x] M4.1 Media channel core (`SET_BLOBS`, `MediaFrame.kt`, `MediaChannel.kt`, Coil)
- [x] M4.2 Live preview rendering (Session/Controls/Align/PA)
- [x] M4.3 Frames tab + Room persistence (`FrameDatabase.kt`, `FrameEntity`)
- [x] M4.4 Guide/Focus/PA real wiring — fixture `GuideTraceChart`/V-curve deleted outright (not deferred)
- [x] Real target-accuracy trend (`AlignAccuracyCard`, Controls tab) — real per-solve `targetDiff`, re-confirmed (again) no Guide RMS/drift data exists on this wire at all
- [x] Sheet-wide landscape width fix (`NocturneSheet`) + Guide 2-column landscape layout — see detail below
- [x] M4.6 Summary sheet + export wired to real Room frame data (KEPT/DISCARDED/MED HFR)
- [ ] PA richer vector/correction-arrow overlay — NOT STARTED (folded into M5's scope instead, arguably superseded)
- [x] **M4.5 half A — Alerts real wiring** — `NewNotification` EkosEvent added (`new_notification` push, confirmed a genuinely comprehensive real stream — see detail below); `AlertsSheet`/Summary sheet's session-event log/export report/persistent banner all read real `AppState.wireNotifications` now, `ALERTS` fixture deleted
- [x] **M4.5 half B — Prefs real wiring** — `option_get`/`option_set` added (generic `Options::self()` reflection, confirmed real). The old 6-category `PREF_DEFS` fixture had no matching real settings at all (checked live against `kstars.kcfg`) and zero consumers anywhere — replaced with the 2 settings that actually exist: `ekosRemoteNotifications`/`ekosRemoteSound`. Same mechanism also closed the M5 `rotator_control` read-gap for real — see detail below
- [x] Summary sheet's session-event log — real now, same pass as half A above

### M4.5 — Frame storage, Frames tab restructure, offline plate solver
- [x] Part A — real on-device frame storage (`FrameFileWriter`, `Preview/<date>/`, `Plan/<target>/`)
- [x] Part C — Frames tab Preview/Plan navigation, schema v2→v3, full detail overlay
- [ ] **Part B — offline coarse plate solver** — NOT STARTED. Zero star-catalog asset, zero centroid/geometric-hash matching code anywhere in the repo.

### M5 — Plan tab real framing workflow (6-step flow)
Doc is planning-only; **nothing below has been implemented** as of this writing.
- [x] Step 3 — goto + center (already real, pre-existing)
- [ ] Step 1 — star chart with target centered — blocked on M4.5 Part B's catalog work
- [x] Step 2 — rotator angle set — **corrected same pass**: `RotatorRow` slider now sends real
  `align_set_target_pa` (`{"angle": double}`) — a command this repo's own reference doc never
  documented at all until found by reading the fork source directly this session. Earlier claim
  ("no rotator wire command exists on this protocol at all") was wrong; that command does exist,
  just wasn't in the doc anyone had read.
- [x] Step 4 — show current vs. desired angle — `align_manual_rotator_status` read (`NewManualRotatorStatus`), readback now on Controls tab's `RotatorControlCard` (moved from Plan tab, 3rd pass — see below)
- [x] Step 5 — auto-drive a real rotator — `align_set_astrometry_settings`'s `rotator_control` bool wired, master gate for the whole feature (confirmed against `align_goto.cpp`'s `checkIfRotationRequired()`), UI switch unconditional, re-sent on every connect since no GET exists for it (4th pass, see below). "Manual snapshot" half still not built (no snapshot/capture trigger exists on this path — "take image" is just the existing `align_solve`/Controls-tab Solve button, confirmed identical to the real ManualRotator dialog's own Take Image button)
- [x] Step 6 decision — "Add to sequence" stays local-editor-first, no auto-push — deliberate, not a gap
- [x] **3rd pass, same day**: relocated `rotator_control` switch + readback from Plan tab to Controls tab's Plate Solve section (`RotatorControlCard`, next to `AlignSolveCard`) — naturally adjacent to the Solve button that actually refreshes it. Dropped `align_manual_rotator_toggle`/`manualRotatorToggled` entirely (dead code, not just hidden UI) — confirmed it only shows/hides Ekos's own dialog on the Pi's own physical screen, no remote effect at all. Added a real FOV reticle (`FovOverlayBox`, transparent fill/`c.warn` stroke) over the real live preview in both Plan tab's `FramingCard` (was a decorative hatch-background box, no real image at all) and Controls tab's Primary Camera preview (`SnapPanel` gained an optional `overlay` slot). Explicit "optional — skip if you don't need precise framing" caption added to `FramingCard`. Pause/resume mid-sequence: real Ekos has a genuinely graceful `Scheduler::pause()`/`pauseB` (`SCHEDULER_PAUSED`, deferred "pause planned") that would be the correct mechanism, but confirmed it's **not reachable on the EkosRemote wire protocol at all** — zero references in `message.cpp`/`commands.h`, and not reachable via the generic `invoke_method` escape hatch either since `pause()` is a plain `protected` method, not `Q_INVOKABLE`. **User's decision: log as a follow-on fork task** (needs a change in `/home/soo/cc/repo/kstars/kstars/ekos/ekosremote/` + rebuild + redeploy to the Pi — new item added to the network-hardening backlog below), ship this pass with the existing, already-wired Stop/Start `SchedulerToggleButton` as the interim (real: Stop marks the active job `ABORTED`, not removed; Start re-evaluates and resumes if `kcfg_RememberJobProgress` is on, confirmed true on this rig previously) — `RotatorControlCard` shows a hint pointing at it when a sequence is running.

### Simulator removal (complete, `28225a2`)
- [x] `SimulatedController.kt` deleted, all 36 `isRealRig` branches removed (0 hits confirmed live)
- [x] Dome disabled-with-reason; rotator correctly left local-only (no fix needed, by design)
- [x] Frames/Summary honest-placeholder step — since superseded by real M4.3/M4.6 data
- [x] `FRAME_IDS`/`FRAME_HFRS` fixture — turned out to get deleted for free as an M4.3 side effect, not a deliberate pass
- [ ] Remaining dead fixture catalogs still live and still load-bearing (not safe to delete): `TARGETS`, `DEFAULT_JOBS`, `ALERTS` — real call sites still depend on them, don't touch without a real replacement first
- [ ] Phase 4 — ~50 stale doc-comments still naming deleted `SimulatedController` — cosmetic, still not done, still low priority

### App-side feature backlog
- [x] Real filter wheel slot names + block-picker wiring, live-verified (2 real bugs found+fixed along the way)
- [ ] Autofocus-at-block-start (`forceAfOnStart`) — **SKIPPED, stub UI actively removed** rather than left dead, user's own call to defer past M4
- [ ] Smaller-scope alternative (`inSequenceFocus` per-job) — still on the table, not started
- [x] Lunar altitude curve + phase on Plan tab's altitude chart — user request, real data, no new
  wire command needed (see detail below)

### Network discovery / connection / authentication — NEW, this pass
See full detail below. Checklist:
- [x] Current trust model fully documented (fork source read, not guessed)
- [ ] Client-side RFC1918/private-range validation on connect — NOT STARTED
- [ ] Scoped `network_security_config.xml` (replace app-wide `usesCleartextTraffic`) — NOT STARTED
- [ ] mDNS/service-discovery for the Pi — NOT FEASIBLE without a further fork change (server never advertises itself; app-side lookup alone has nothing to find)
- [ ] Token-pairing scheme on the EkosRemote channel itself — NOT FEASIBLE app-side alone; requires forking `nodemanager.cpp` again (same class of change that built the reboot-daemon's own token auth, just on a different channel)
- [ ] Second-client-displaces-first behavior — surfaced, not yet decided whether it needs a UI warning

---

## Detail

### M3
`EkosRemoteController` sends 12 real wire methods, all following the optimistic-local-then-reconcile
pattern (no request/response correlation exists on this wire at all — broadcast pushes only).
`astro_search_objects` has no free-text field server-side (only `type`/`direction`/`maxMagnitude`/
`minAlt`/`minDuration`/`minFOV`) — typed query filtering happens client-side against the returned
name list. `type` is also single-valued server-side, so the "Narrowband" chip only approximates
"any of Ha/SHO/OIII" to one `SkyObject::TYPE`. Real "Start sequence" bug chain found live-testing:
`raBox`/`decBox` never populated (typing a name doesn't trigger server-side name resolution),
`opticalTrainCombo` left unset, and `scheduler_start_job` was never sent at all — all three fixed.

### M3.3
Field-name probing mattered: an initial Guide-settings field-name guess was wrong and had to be
corrected live against the actual wire shape before shipping (5→7 real fields). Controls tab layout
went through two regroupings (Addendum 1, then 2) before settling on Primary Camera (+Autofocus+
Plate Solving) / Mount / Guide / Polar Alignment.

### M4
`NewPolarState`/`NewMountState`/`NewCameraState` all needed defensive decode fixes — real payloads
arrive in multiple independently-partial shapes (confirmed against KStars source each time, not
guessed), and a too-strict required-field model silently degrades to `Raw` on the common case.
Same bug class hit 3 separate times across sessions — worth remembering as a standing pattern for
any *new* decoded event type: default every field, merge-non-null on arrival.

**Follow-up, meridian flip / Guide / landscape review.** User asked to check 2 things: meridian
flip, and "guide status chart display and target accuracy status plot," plus a suspicion that
Guide needs its own landscape layout.

- **Meridian flip**: existing state re-confirmed accurate (pier side + auto-flip setting real,
  FLIP NOW/DEFER genuinely disabled, no real trigger command exists on this wire for either). Two
  new findings: real Ekos fires actual `KSNotification::event()` calls for flip start/completion
  (`camera_device.cpp:764`, `camerastate.cpp:497`) — these now surface for free in the `new_notification`
  stream wired earlier this session, once a flip actually happens. Also found real `Q_SCRIPTABLE`
  `Mount::hourAngle()`/`Mount::meridianFlipValue()` that could compute a genuine countdown-to-flip
  via the generic `invoke_method` hatch — **not shipped**, since reaching them needs
  `findObject("Mount")` to resolve, the same category of live-connection-dependent uncertainty
  already declined once this session for `Options` (no live Ekos connection available to verify).
- **Guide chart**: re-confirmed, not a miss — checked all 3 real `new_guide_state` senders fresh
  (`message.cpp:2598-2601`/`:2670-2677`/`:2910-2918`), every one is `{"status"}` only, no secondary
  export exists (unlike Align's `solution`, found earlier this session). Matches `GuideSheet`'s own
  existing honest disclosure — not buildable without a fork change. **User asked directly whether
  this is a real server-side gap, not a protocol ceiling — confirmed yes**: `Ekos::Guide` emits real
  live per-sample signals (`guide.h`: `newAxisDelta(ra, de)`, `newAxisSigma(ra, de)` — real RMS,
  arcsec, `guideStats(raError, decError, raPulse, decPulse, ...)`), the exact numbers the desktop
  chart plots. Checked every connection in `manager.cpp` — none reach `ekosRemote` at all:
  `newAxisSigma`→`Manager::updateSigmas` (local UI label), `newAxisDelta`→a local lambda +
  `Capture::setGuideDeviation` (feeds Capture's own guide-deviation guard), `guideStats`→
  `Analyze::guideStats` (feeds the local `.analyze` log). 3 real local consumers, 0 reaching the
  remote bridge — the data was simply never wired to a 4th connection forwarding it over the wire.
  **Follow-on fork task, not started**: add a new push (e.g. `new_guide_stats`) in `message.cpp`
  connecting `newAxisDelta`/`newAxisSigma` (and/or `guideStats`) the same way `setAlignSolution`
  already does for Align — same category as the Pause Scheduler item above, needs the fork rebuilt
  + redeployed, not app-side alone.
- **"Target accuracy status plot"**: real, just from the wrong module — Align's own
  `solution.targetDiff`/`dRA`/`dDE` (found earlier this session for the FOV box, not yet displayed
  anywhere). New `AppState.alignAccuracyHistory` (bounded, real per-solve pointing error appended
  on every solve) + `AccuracyTrendChart` (generic sparkline, normalizes to its own data's dynamic
  range rather than a fixed scale) + `AlignAccuracyCard` (Controls tab, next to `AlignSolveCard`) —
  hidden entirely until at least one real solve has landed.
- **Landscape, bigger than expected**: `NocturneSheet`'s panel width was hardcoded `420.dp`
  regardless of orientation, for **every sheet** — even ones already flagged `fullscreen=true`
  (PA, Setup) never actually used landscape's real width. Not a Guide-specific gap. Fixed
  systemically: panel widens to ~92% of screen width once `maxWidth > maxHeight`, portrait
  behavior (420dp cap) unchanged. `GuideSheet` also gained a real 2-column landscape layout
  (preview left, status/settings right) — the width fix alone would have still left a stacked
  layout scrolling inside a wider box; both halves were needed.

Compiles + unit tests pass, not live-verified — the flip-countdown `invoke_method` path
specifically needs a real Ekos connection to test at all, flagged as a real next step, not shipped
speculatively.

### M4.5
Part A: `Preview/<date>/Prev_NNNNN.jpg` (per-day, counter seeded from disk so a same-day relaunch
doesn't overwrite), `Plan/<date>/<target>/<target>_<date>_<filter>_<exp>sec_<temp>C_<seq>.jpg`.
Part B (plate solver) design, never started: star-centroid extraction + small bundled bright-star
catalog + geometric-hash match; scale is free from the real header's `focal_length`/`pixel_size`/
`bin`, only RA/Dec/rotation need solving for.

**Half A (Alerts), shipped this session** — user's own framing: "M4.5 alarm should be implemented
along with status... it should have a dedicated bar same position as connection status bar. or use
the connection status bar to show all app and ekos status and display always on." Investigated the
real wire first: `new_notification` (`Message::sendEvent`, `message.cpp:2721-2735`) turned out to be
a genuinely comprehensive stream — `KSNotification::event()` (`ksnotification.cpp:98-113`) is the
single generic entry point essentially every notable real event anywhere in KStars already routes
through (mount faults, capture/focus/guide/align failures, scheduler transitions, INDI server
messages...), gated only by the real `Options::ekosRemoteNotifications()` (default `true`). Shape:
`{source, severity, message, uuid}`, real `KSNotification::EventSource`/`EventType` enum ints
(`ksnotification.h`).

Shipped: `EkosEvent.NewNotification`, `AppState.wireNotifications` (real, capped at
`MAX_NOTIFICATIONS`=100, deduped by `uuid`, newest-first) replacing the old fixture `Alert`/
`AlertIcon`/`ALERTS` (invented meridian-flip/HFR-cut/cloud/autofocus categories with zero real
basis) everywhere it was used: `AlertsSheet`, Summary sheet's session-event log, the exported HTML
report's alert rows. Severity styled with the only 2 severity-relevant Phosphor icons this app has
(`Warning`/`CheckCircle`) — no new icon assets added.

**Persistent banner, user's explicit design choice** (asked: dedicated new bar vs. extend the
existing connection banner; latest alert vs. a per-module status dashboard — picked "extend
existing" + "latest alert"): `NocturneApp.kt`'s top banner — previously hidden entirely once
`ConnectionState.ONLINE` — now falls back to the newest real alert (severity-colored: danger/warn/
accent) instead of disappearing, with a tap target to open the full Alerts sheet. A genuine
connection problem still takes priority and displays exactly as before; the banner is only fully
hidden when there's neither a connection issue nor any alert yet this session (an honest "nothing
has happened," not a fixture default). Compiles + unit tests pass, not live-verified — this session
has no live Ekos connection to actually trigger a real `new_notification` push against (same
constraint noted in the M5 rotator-control-reapply pass earlier).

**Follow-up, same day — user clarified the actual ask.** "OK it works but it is not exactly I
wanted. I want to display all EKOS schedulars, cameras, mount, guiding, align, focusing... status
in a banner always." The latest-alert-text banner above wasn't it — reverted that banner back to
connection-only (its original behavior), added a new, separate, always-rendered `ModuleStatusRow`
(horizontally scrollable, real per-module status already decoded elsewhere in this app —
`schedulerRunning`/`wireMountStatus`/`wireCaptureStatus`/`wireFocusStatus`/`wireGuideStatus`/
`wireAlignStatus`, nothing newly fetched). Colored by a generic keyword heuristic (Fail/Abort/Error
→ danger, Idle/Complete/Successful/Connected → neutral, anything else → busy/accent) rather than a
hand-enumerated table per module — checked 4 real state vocabularies (`ekos.h`'s `guideStates`/
`captureStates`/`focusStates`/`schedulerStates`) first and confirmed they share enough common
English wording for one heuristic to cover all of them reasonably. Rendered unconditionally, not
gated on connection health — real per-module status is still meaningful during a `SOCKET_OPEN`
hiccup (values just stale from the last real push), and an honest "—" for everything pre-connection
is itself real signal. Compiles + unit tests pass, not live-verified.

**M4.5 half B, same day — user picked "Prefs real wiring" off a "what's next" menu.** Investigated
`option_get`/`option_set` (`message.cpp:1445-1471`) — confirmed a genuinely generic mechanism:
`Options::self()->property(name)`/`setProperty(name, value)` via Qt's own reflection, reaching ANY
real kcfg-backed setting by its Qt property name (camelCase), no `findObject()` lookup involved at
all — a materially stronger guarantee than the separate `GET_PROPERTY`/`invoke_method` escape hatch
considered (and deliberately not shipped) earlier in the M5 rotator work.

Checked whether the existing `PrefsSheet` fixture (6 categories: guide-degraded/cloud/disconnect/
flip/frameCut/seqEnd) had any real settings to map onto — it didn't. None of the 6 match a real
`kstars.kcfg` entry, and `prefs`/`togglePref` had zero consumers anywhere in the app (a switch that
flipped its own map entry with no downstream effect). Replaced with the 2 settings that actually
exist and are directly useful given M4.5 half A's new real alert stream: `ekosRemoteNotifications`
(the stream's own master gate) and `ekosRemoteSound`. "Quiet hours" kept as an explicitly-labeled
local-only nicety (no matching real setting either — it's app-side scheduling, not a kcfg toggle).

**Also fixed, same pass**: `AppState.wireAstrometryUseRotator` — a real, unconditional GET for
`rotator_control`'s ground truth, finally closing the gap the 4th M5 pass could only work around
(re-sending Nocturne's own local guess on every connect via `align_set_astrometry_settings`, since
that command family has no GET at all). **The old reapply-on-connect workaround was removed
outright**, not just superseded silently — it would otherwise race the new real `option_get` fetch
and could clobber the just-learned real value with a stale local guess if it landed second.
`option_get` for all 3 real values (`ekosRemoteNotifications`/`ekosRemoteSound`/
`astrometryUseRotator`) is batched into one request, eager on every connect, same shape as every
other `*_GET_ALL_SETTINGS` fetch already there.

Compiles + unit tests pass, not live-verified.

### M5
Biggest finding: real Ekos already ships a purpose-built rotator angle-readback + auto-adjust
feature — was 0% wired, **now wired** (steps 2/4/5, this pass, in 2 sub-passes same session).

**First pass** wired `align_manual_rotator_toggle`/`_status` (`NewManualRotatorStatus`, defaults+
merge-non-null per the repo's standing decode norm) and `align_set_astrometry_settings`'s
`rotator_control` bool, with a new `ManualRotatorSection` on the Plan tab's Framing card. Left
step 2's slider (`RotatorRow`) as local-only, on the (wrong) belief no wire command existed for it.

**User asked directly why the slider wasn't the target-PA control** — re-checked against the real
fork source (`/home/soo/cc/repo/kstars`, not just `EkosRemote-Command-Reference.md`, which turned
out to have 2 real gaps) and found:
1. `align_set_target_pa` (`{"angle": double}`) — sets `Align::m_TargetPositionAngle` directly, a
   real command **completely undocumented** in the reference doc (now fixed, same pass). Without
   it, nothing before this pass could ever have made the readback do anything at all — `RotatorRow`
   now sends this instead of staying local-only, closing step 2 for real.
2. `rotator_control` doesn't just gate real-hardware auto-drive — confirmed against
   `align_goto.cpp`'s `checkIfRotationRequired()` it's the master gate for the *entire* feature,
   manual-diff-readback path included. First pass's UI hid this switch behind
   `TrainAssignment.rotator != "None"` — exactly backwards, hiding it precisely when the no-hardware
   manual path needed it most. Fixed: switch is unconditional now, label/sub-text branch on
   hardware presence instead of visibility.
3. `toggleManualRotator` (`align_manual_rotator_toggle`) turned out to only show/hide Ekos's own
   dialog **on the Pi's own screen** (`align_components.cpp:146`) — real, but irrelevant to this
   remote readback; doc comments and the UI's own switch label corrected to say so plainly instead
   of implying it drives anything.
4. Confirmed **no separate "take image" command exists for this** — the real ManualRotator dialog's
   own "Take Image" button calls the identical `captureAndSolve()` as `align_solve` (Controls tab's
   pre-existing "Solve" button). Documented on `ManualRotatorSection`'s own doc comment.

Compiles + unit tests pass; **not yet live-verified against the real rig** (no rotator hardware
confirmed present on this rig as of writing, though the no-hardware manual-diff path is now
reachable and should be testable regardless). Rotator-hardware presence was already answerable from
existing data (`TrainAssignment.rotator`) with no new detection needed. Still open: step 1 (star
chart) blocked on Part B's catalog work; step 3's fixed-`delay()` heuristic not revisited.
**Lesson, worth remembering**: this session's own protocol reference doc had 2 real gaps
(`align_set_target_pa` missing outright, `rotator_control`'s actual scope undersold) — the fork
source itself (`/home/soo/cc/repo/kstars`) is available locally and is worth checking directly
before treating this repo's own `EkosRemote-Command-Reference.md` as complete, especially for a
just-forked or lightly-used command family.

**3rd pass, same session — layout + pause/resume review.** User asked for a plan (not immediate
code) covering: not-mandatory + resumable-mid-sequence as standing principles, moving
`rotator_control`/readback into Controls tab next to Solve, a real FOV box on the camera preview,
and why Nocturne doesn't just use KStars' own ManualRotator "Take Image" button. Findings, all
source-confirmed:
- Relocated `rotator_control` switch + readback to Controls tab's `RotatorControlCard` (Plate Solve
  section, next to `AlignSolveCard`) — the switch/readback and the Solve button that refreshes it
  are now physically adjacent, closing the "why isn't Solve right here" gap directly.
- Dropped `toggleManualRotator`/`align_manual_rotator_toggle`/`manualRotatorToggled` entirely (dead
  code removal, not just UI hiding) — matches this project's own norm (`forceAfOnStart`).
- New `FovOverlayBox` (`ui/components/Widgets.kt`) — transparent fill, `c.warn` stroke (reused
  existing warm-tone token, not a new color) — over the real live preview in **both** Plan tab's
  `FramingCard` (was decorative `HatchBg`, no real image ever shown there at all) and Controls tab's
  Primary Camera preview (`SnapPanel` gained an optional `overlay` slot, Guide's own call site
  unaffected).
- **Real Pause Scheduler exists** (`Scheduler::pause()`/`pauseB`, `SCHEDULER_PAUSED`, genuinely
  graceful — "Scheduler pause planned...", doesn't abort the active job like Stop does) — user was
  right to ask about it, this is the correct mechanism for "pause and adjust, then resume." **Not
  reachable on the EkosRemote wire protocol at all**, confirmed by direct grep of the fork source:
  zero references in `message.cpp`/`commands.h`. Also checked whether the existing generic
  `invoke_method` escape hatch could reach it anyway — no: `Scheduler::pause()` is a plain
  `protected` method, not a slot or `Q_INVOKABLE`, so Qt's `QMetaObject::invokeMethod` (what that
  wire command uses) can't resolve it regardless of the class being otherwise reachable via
  `findObject("Scheduler")`. **User's call: log as a follow-on fork task, ship this pass with the
  already-wired Stop/Start `SchedulerToggleButton` as the interim** — real (`SchedulerProcess::stop()`
  marks the active job `ABORTED`, not removed; restart resumes via `kcfg_RememberJobProgress`,
  confirmed true on this rig previously) but abrupt (interrupts the current exposure, unlike a real
  pause). `RotatorControlCard` shows a hint pointing at the existing button when `schedulerRunning`.
  **Follow-on fork task, not started**: add a real `SCHEDULER_PAUSE` (and confirm/wire a resume path
  — `SchedulerProcess::execute()`'s `SCHEDULER_PAUSED` case looks like the right target, "Scheduler
  resuming.", though `toggleScheduler()`'s own `start()` branch takes a different, job-state-resetting
  path that wasn't fully traced this session) in `/home/soo/cc/repo/kstars/kstars/ekos/ekosremote/`,
  rebuild + redeploy to the Pi.

**4th pass, same day — user asked to review "what does Rotator control actually do", then fix 2 real
gaps that review surfaced.** Live-checked this rig via SSH (`~/.config/kstarsrc`):
`AstrometryUseRotator=false`, `AstrometryRotatorThreshold=0` — both explicitly saved, overriding
stock kcfg defaults (`true`/`30`). Confirmed via `align_solver.cpp:980` that `checkIfRotationRequired()`
is the *last* step of solve-completion (after goto/sync/report already ran) — leaving it `false`
does not affect plate solving at all, matches the "not mandatory" principle exactly.
- **Gap 1 fixed — `rotator_control` had zero ground-truth, ever.** Confirmed why: it's backed by a
  `QGroupBox` in `opsalign.ui` (`kcfg_AstrometryUseRotator`), a widget type the
  `align_get_all_settings`/`align_set_all_settings` reflection doesn't cover (only
  `QComboBox`/`QDoubleSpinBox`/`QSpinBox`/`QCheckBox`/`QRadioButton`) — genuinely no GET exists for
  it anywhere on this wire. Considered reading it via the generic `GET_PROPERTY`/`findObject`
  escape hatch (confirmed a real `Q_PROPERTY astrometryUseRotator` exists on the generated `Options`
  singleton, `GenerateProperties=true` in `Options.kcfgc`) but **could not live-verify
  `findObject("Options")` actually resolves it** — Ekos wasn't running on the Pi this session
  (`kstars` process up, but nothing listening on port 9000, confirmed via `/proc/net/tcp`) — so this
  was deliberately not shipped as a guess. Fixed instead with a guaranteed-correct alternative: on
  every real connect (`NewConnectionState.online`, `sendFollowUpCommands`), re-send Nocturne's own
  current `rotatorAutoControl` value via the same already-proven `align_set_astrometry_settings`
  command. Doesn't read the true value, but guarantees the real rig matches what the app displays
  from that point forward — same accepted fire-and-forget/no-reconcile risk as every other
  write-only setting in this app, not a permanent never-grounded gap like before.
- **Gap 2 fixed — rotator threshold exposed in Align settings sheet.** Unlike `rotator_control`,
  `kcfg_AstrometryRotatorThreshold` **is** a normal reflection-covered field (confirmed in the
  reference doc's own live-verified field list) — real GET *and* SET via the standard
  `align_get_all_settings`/`align_set_all_settings` path, same shape as every sibling
  `WireAlignSettings` field. Added `kcfg_AstrometryRotatorThreshold: Double = 30.0` to
  `WireAlignSettings`, `setAlignRotatorThreshold(arcmin)` end-to-end, new "Rotator threshold" field
  in `AlignSettingsSheet` (arcmin, distinct unit from the existing "Solver accuracy threshold"
  field's arcsec). **Note, distinct from the readback's own threshold**:
  `AppState.wireRotatorThreshold` (from the `align_manual_rotator_status` push) arrives already
  converted to **degrees** (`align_goto.cpp`: `Options::astrometryRotatorThreshold() / 60.0`) for
  direct diff comparison — this new settings field is the raw arcminute value instead, no unit bug
  introduced. **The live rig's actual `0` value itself was not changed** — the fix is the app control
  to let the user set it themselves, not a one-off SSH edit.
Compiles + unit tests pass; not yet live-verified (same as the rest of this session's M5 work).

**5th pass, same day — user correction: the FOV box was rotated to the wrong angle.** It had been
wired to the target-PA slider (`state.rotatorAngle`) in both Plan tab's `FramingCard` and Controls
tab's Primary Camera overlay — user's own intent was always the real **current** camera angle from
the last solve (`wireRotatorCurrentPA`), not the target. Fixed in both places: `rotationDeg` now
reads `state.wireRotatorCurrentPA?.toFloat() ?: 0f` (honest 0°/unrotated until a solve has actually
run this connection, not a guess). Dropped the old prototype-calibrated `-11°`/`118.4°` offset along
with it — that calibration was specific to the target-angle slider's own default and doesn't carry
over; no established real correspondence between this angle and Compose's `.rotate()` direction is
confirmed yet anyway (same open question as `NewPolarState`'s vector `pa` field). Also: added a
"Plate solve here" button to `FramingCard` (same `ctrl::plateSolveHere`/`align_solve` as Controls
tab's `AlignSolveCard`) directly under the slider — lets the box refresh without leaving for
Controls tab. Renamed the slider's row label "Rotator" → "Target angle" (composable renamed
`RotatorRow` → `TargetAngleRow`) — it stopped being a generic rotator knob once it became the real
`align_set_target_pa` control.

**6th pass, same day — user follow-up on filter/binning, the missing target box, and the
background image.** Checked `AlignSettingsSheet` directly: "Filter"/"Binning" fields were already
there (M3.3), nothing to add — told the user plainly rather than duplicating. Confirmed a real
regression from the 5th pass: switching the single box to current-angle-only had silently dropped
the target-angle representation entirely, with no replacement. Fixed by giving `FovOverlayBox` a
`color`/`dashed` param (dash via a custom `drawWithContent`+`Stroke(pathEffect=dashPathEffect)`,
`.border()` has no dash support) and drawing **two** boxes in `FramingCard`: target (dashed,
`c.accent`, `state.rotatorAngle`) and current (solid, `c.warn`, `state.wireRotatorCurrentPA`) —
same two composable calls, distinguished visually rather than conflated into one.

**Bigger change, explicit user instruction**: `FramingCard`'s background swapped from the main
camera's own just-captured frame (`state.latestCaptureFrame`) to a real DSS sky-survey cutout
centered on the framed target — "framing is about the sky the target sits in, not whatever this
session's camera has captured." New `transport/ReferenceImageClient.kt` (plain OkHttp GET, same
callback shape as the existing `RigRebootClient`) hits CDS Strasbourg's public `hips2fits` service
— **the first direct internet call this app makes that isn't to the Pi** (see the Network section's
own update above). Real RA/Dec resolution added as `Target.raDecDegrees` (prefers real `ra0`/`de0`
when a live search result set them — `ra0` confirmed in **hours**, `×15` for degrees; falls back to
parsing the same `coords` sexagesimal string every target already carries, fixture/custom included).
Fetch triggered from `selectTarget` (`ensureReferenceImage`, same dedup-by-target-id shape as the
existing `ensureTargetRiseset`) plus a `LaunchedEffect(tgt.id)` in `FramingCard` for the
never-explicitly-selected default target case. Cached in `AppState.referenceImageJpeg`/
`referenceImageForTargetId`; null jpeg (offline/failed/still fetching) shows an honest text
placeholder over the hatch background rather than silently looking broken. Controls tab's own
Primary Camera preview is intentionally untouched — still the real live capture, which is correct
there (general camera monitoring, not framing).
Compiles + unit tests pass; not live-verified (needs a real network round-trip + a real target
selection to see the cutout render, neither exercised by the unit test suite).

**7th pass, same day — real bug found: Align's Filter chip was always force-switching, never
respecting "use current filter".** User: "filter should not change but only set the value with
current filter settings." Confirmed against real source (`align_devices.cpp:583/590`,
`Align::checkFilter`): real Ekos has a whole separate `alignUseCurrentFilter` checkbox (`align.ui:739`,
already in the reference doc's own live-verified field list, just never modeled here) — checked
means a solve **never** changes the filter (forced to whatever's actually loaded); unchecked means
`alignFilter` is a fixed choice a solve force-switches to *every time, even mid-sequence*.
Nocturne's Filter chip only ever implemented the unchecked/fixed behavior — every solve was silently
forcing a filter switch regardless of what was actually loaded, with no way to turn that off.
Added `WireAlignSettings.alignUseCurrentFilter` + `setAlignUseCurrentFilter` end-to-end (same
`sendAlignSetting`/reflection path as every sibling field), new "Use current filter" switch in
`AlignSettingsSheet`; when on, the Filter chip becomes a disabled, dimmed, non-clickable display of
the real current filter (`wireCaptureSettings.FilterPosCombo`) instead of a live editable
control — matches the real widget's own `setEnabled(false)` behavior rather than leaving a tap that
silently does nothing. `CycleChip` gained an `enabled` param for this (drops `clickable`, dims text).
**Also, user request**: Filter and Binning fields now share one row (two weighted columns) instead
of stacking full-width.

**8th pass, same day — real bug found live: cycling the Filter chip showed a filter that doesn't
exist on the real wheel, then snapped back.** Root cause: the fixed-filter cycle (`else` branch
above) cycled the fixture `FILTER_CYCLE` (`["Ha","OIII","SII","L","R","G","B"]`) unconditionally,
never the real filter wheel's own slot names (`AppState.realFilterNames`) — every other filter-cycle
chip in the app (Controls tab, Sequence tab's block editor) already prefers
`realFilterNames ?: FILTER_CYCLE`; this one, pre-existing since M3.3 and untouched by the 7th pass's
edit, never did. Cycling past this rig's real last slot ("B") wrapped into "Ha" — a fixture name,
not a real one — which the real Align combo has no matching entry for, so the set was silently
ignored server-side; a later debounced `align_get_all_settings` reply then corrected the optimistic
local display back to "B", producing exactly the "shows Ha, then switches back" symptom reported
live. Fixed: same `realFilterNames ?: FILTER_CYCLE` pattern as its siblings.

**9th pass, same day — user review question surfaced a real 4th occurrence of this repo's own
decode-bug pattern.** Asked whether solve results are wired to Framing/Main-camera views and
whether solving status shows anywhere. Checked: **no on both**, and found the actual solve *result*
(not just status) was never modeled at all. Confirmed against `message.cpp:942-969`:
`Message::setAlignStatus` (`{"status"}`) and `Message::setAlignSolution` (`{"solution"}`) are two
entirely independent senders sharing the `new_align_state` push name — `NewAlignState` required
`status`, so every real solution-only push (fires on **every successful solve**, unconditionally)
silently failed to decode. Same class of bug as `NewMountState`/`NewPolarState`/
`NewManualRotatorStatus` — 4th occurrence now. Fixed: both fields defaulted/merged, new
`WireAlignSolution` (`camera`/`ra`/`ra.Hours`/`de.Degrees`/`de`/`dRA`/`dDE`/`dAZ`/`dAL`/
`targetDiff`/`pix`/`PA`/`fov`, confirmed against `align_solver.cpp:875-891`'s real `QJsonObject`
literal). `solution.PA` is the real solved position angle, sent on every solve — the *correct*
primary source for the FOV boxes' current-angle rotation, since it's unconditional, unlike
`wireRotatorCurrentPA` (only arrives when `rotator_control` is on, `false` by default on this rig).
Both `FramingCard` and Controls tab's overlay now prefer `wireAlignSolution.PA`, falling back to
`wireRotatorCurrentPA` only if a solve's own result hasn't landed yet. Also added: real solving
status (`wireAlignStatus` — decoded since M2, never displayed anywhere in the app until now) shown
in both `FramingCard` (top-right of the preview box) and `AlignSolveCard` (under the Solve button).
Real vocabulary (`ekos.h`): `Idle`/`Complete`/`Failed`/`Aborted`/`In Progress`/`Successful`/
`Syncing`/`Slewing`/`Rotating`/`Suspended` — "Rotating" specifically fires during the rotator-diff
wait, directly relevant to this feature and previously invisible. Compiles + unit tests pass, not
live-verified.

**10th pass, same day — real bug found live: M64's altitude chart showed wrong peak time/max
angle.** User: "when I choose M 64... its peak time and its max angle is wrong." Root cause
confirmed in real source, not guessed: `astro_get_objects_riseset`'s reply `"name"` field is
`exact ? name : oneObject->name()` (`message.cpp:2295`) — with `exact` defaulted `false` (never
sent by either of this app's 2 call sites), a name that resolves via `findByName`'s fuzzy fallback
gets echoed back as KStars' own internal preferred name for that object, not the exact string
requested. `EkosRemoteController`'s own reply-matching (`event.entries.firstOrNull { it.name ==
name }`, both for `wireTargetRiseset`'s single-target case and `buildSearchResults()`'s full-list
merge) keys strictly on the *sent* name — a mismatch means the match silently fails. For the
framed-target case specifically, `wireTargetRiseset` then just keeps whatever the *previously*
framed target's real curve was — a real, plausible-looking curve, just the wrong one, which is why
this read as "wrong data" rather than an obvious blank/placeholder and went unnoticed until
reported. Confirmed this actually happens for M64. Fixed: `"exact": true` added to both
`ASTRO_GET_OBJECTS_RISESET` call sites (`ensureTargetRiseset`, and the full search-results fetch in
`sendFollowUpCommands`) — safe, since `exact` only changes whether a *failed* exact lookup falls
back to fuzzy resolution at all (`catalogscomponent.cpp:334-338`), not whether an already
catalog-sourced name resolves. Reference doc updated with this quirk. Compiles + unit tests pass,
not live-verified.

**11th pass, same day — real bug, different class than M64's: multiple different targets showing
the identical wrong peak time.** User: "M 101, M51, M 95 show the peaktime as 21:30 which is not."
Not a name-echo mismatch this time — a real logic gap in `realNightMaxAltitude` (the 2026-08-22 fix
that clamped "peak" to the highest altitude sample *within* tonight's dusk-to-dawn window, to avoid
pairing a daytime peak with a correctly-computed "0h 00m usable"). Root cause: dusk/dawn
(`realNightWindow`) are site-wide — the same instants for every target, every night — so for any
target whose real transit already precedes dusk (a spring-sky object like M101/M51/M95, by late
August already transiting during daytime — altitude simply descending all night from dusk onward),
"highest sample within the window" degenerates to the first sample after dusk, rounded to the same
30-minute grid point regardless of the target's own actual RA/transit. Three astronomically
unrelated targets (RA ~10h44m–14h03m, ~3+ hours apart) all landing on the identical displayed time
was the tell — not plausible if each were genuinely reporting its own real peak. **Also found**:
`AltitudeChart`'s own dashed peak-line was never night-clamped in the first place (always used the
unclamped `realAltitudes` array) — meaning the line and the "peak HH:MM" text had already silently
drifted apart for exactly these targets, a second symptom of the same root cause. **User's explicit
call, given the choice**: revert to always showing the real absolute daily peak/transit
(`riseset.altitudes.maxOrNull()`/`riseset.transit`), accepting the original daytime-peak-vs-
"0h 00m usable" look-inconsistent case can recur — real data either way, this one just doesn't
collapse multiple different targets onto one wrong shared value. `realNightMaxAltitude` deleted
outright (dead code, matches this project's own norm) rather than left unused. Compiles + unit
tests pass, not live-verified.

### Simulator removal
Full inventory (`SessionController` 179 methods vs `EkosRemoteController`'s 124 overrides) done
before touching anything — found a few of the un-overridden 55 (`setRotatorAngle`/`setDomeOpen`)
have **zero real wire command anywhere**, not just "not yet wired." Frames tab and Session Summary
sheet were showing fixture data with **zero disclosure** — worse than the HFR/RMS/SNR gaps ever
were — fixed with honest placeholders at the time, since organically replaced by real M4.3/M4.6 data.

### App-side feature backlog
Real filter-wheel fix uncovered two independent real bugs: multi-element `TextProp` truncation, and
a keystroke-vs-live-echo race (fixed with a decoupled local text buffer). `forceAfOnStart` stub was
removed rather than left as dead code, on the user's explicit call — matches this project's general
norm of not leaving inert-looking UI around once a feature's real scope is understood.

**Lunar altitude + phase, same day, user request.** Both real, no new wire command needed:
- **Altitude curve**: the Moon is a real, generically-resolvable `SkyObject` (`ksmoon.cpp`:
  `KSMoon::KSMoon() : KSPlanetBase(i18n("Moon"), ...)` — confirmed the real internal name is
  literally `"Moon"` in an English-locale build) — the *same* `astro_get_objects_riseset` command
  already used for every target resolves it too. Bundled "Moon" as a second name into
  `ensureTargetRiseset`'s existing per-target request (one round trip, not a separate fetch) —
  the Moon's own altitude doesn't depend on which target is framed, so `wireMoonRiseset` is kept
  once captured, not re-cleared on target switch (dedup guard extended to still fetch once if it's
  never been captured yet, even when the current target itself is already cached). New dashed line
  in `AltitudeChart`, distinct from the target's own solid accent curve.
- **Phase**: `astro_get_almanac`'s reply — the *same* reply this app already fetches for Dusk/Dawn
  at connect — turned out to already carry `MoonIllum` (illuminated fraction, `[0.0, 1.0]`) the
  whole time, just never decoded (`AstroAlmanac`'s model only had `Dusk`/`Dawn`). Also confirmed a
  `MoonPhase` field exists in the same reply (`[0, 180]` degrees — the Sun-Moon elongation angle,
  *not* a full 0-360 waxing/waning-distinguishing value) but deliberately not modeled — illuminated
  percentage alone is the unambiguous, directly-displayable real number this ask needed.

Not live-verified — specifically, whether `"Moon"` resolves with `exact: true` (the same flag this
session's own M64 fix requires for the *target* name in the same request) hasn't been confirmed
against a real connection; if it doesn't resolve, check locale (`i18n("Moon")` assumes English) or
try `exact: false` for this one name specifically.

---

## Network discovery, connection & authentication

Prompted by: app is used on a closed local network today, but the user anticipates outside security
review at some point and wants the real trust model documented honestly, not assumed.

**Update, M5 reference-image fetch (2026-08-29)**: `transport/ReferenceImageClient.kt` is the
**first direct internet call this app makes that isn't to the Pi itself** — a plain HTTPS GET to CDS
Strasbourg's `hips2fits` service for a real DSS sky cutout, keyed on the framed target's RA/Dec. No
credentials, no data sent beyond RA/Dec/FOV (all already visible in the app's own UI). Depends on the
*phone's own* internet access, separate from the rig LAN — fails independently of Pi connectivity.
Doesn't change the threat model documented below (that's specifically about the EkosRemote channel
to the Pi), but is a real, new outbound dependency worth listing here for completeness next time this
section gets audited.

### Current state (confirmed by reading the fork's actual source, not the app's docs about it)

- **Listening**: `NodeManager` binds `QHostAddress::Any` (`0.0.0.0`) on a hardcoded port **9000**
  (`ekosremoteserver.h:33`). Not configurable — no kcfg option, no UI field for it anywhere in
  `ekosremotedialog.ui`. The header comment states outright: *"trusted local network, no auth, no
  enable/disable toggle."*
- **Discovery**: **none**. KStars does have a real mDNS responder (`qMDNS.cpp`, UDP 5353), but its
  only call site anywhere is client-side lookup during profile setup (finding a StellarMate box) —
  `nodemanager.cpp`/`ekosremoteserver.cpp` never touch it. The server never announces itself on the
  LAN in any way (no mDNS, no Bonjour, no UPnP, no broadcast).
- **Auth: deliberately removed, not merely absent.** Confirmed via the fork's own migration commit
  (`9664c0142`): stock upstream KStars EkosLive did a real HTTP handshake first — `POST
  /api/authenticate {username,password}` → token — *before* opening the socket. This fork's
  `NodeManager::onNewConnection` gate is a **URL-path string match only** (`/message/ekos` or
  `/media/ekos`); no credential, cert, or IP-allowlist check exists anywhere in the accept path.
  The commit message states the intent plainly: *"no relay and no auth."*
- **Client model**: one `Node` slot per channel (Message, Media), not a per-client table. A second
  client connecting to the same path **silently displaces** the first (`Node::adoptSocket` tears
  down the previous socket) — no rejection, no warning to either party. Pure broadcast to whichever
  socket is currently attached, gated only by a `m_ClientState` bool, never a client ID.
- **App side**: manual host/port entry only (`ConnectionSettings`, DataStore-persisted). Connect
  screen shows a static, non-dismissible trust-boundary warning — the *only* mitigation today.
  `usesCleartextTraffic="true"` is set app-wide (`<application>` level), not scoped to private
  ranges via `network_security_config.xml`. No client-side check that an entered host is actually
  RFC1918. `ws://`, no TLS — matches the protocol, which has no TLS story at all.
- **Existing precedent that a token scheme is workable**: the separate reboot-daemon side-channel
  (`pi-tools/reboot-daemon/`, `ConnectionSettings.rebootPort`/`rebootToken`) already does real token
  auth — proof this pattern works *on a channel Nocturne controls*. The main EkosRemote channel is
  upstream-fork protocol, not something Nocturne can unilaterally add auth to from the app side.

### Bottom line / threat model

Perimeter is the *only* control. Anyone who can reach TCP 9000 on the host — same LAN, a
misconfigured port-forward, a VPN with route leakage, another device on the same Wi-Fi — and knows
the two fixed path strings (both public knowledge, they're in this repo's own docs) gets full
read/write access to Ekos: slew the mount, run the Scheduler, change camera/focus/guide settings,
pull image frames. There is no logging of who connected, no way to tell two clients apart, and a
second connector silently takes over from the first. This is fine for "one phone, one Pi, one
trusted home LAN" — the stated real use case — but would not survive any external security review
as stated, and the phrase "no auth, trusted LAN" needs to be said exactly that plainly if anyone
outside asks, not softened.

### Hardening backlog, prioritized

App-side changes Nocturne can make unilaterally (no fork changes needed):
1. **Client-side RFC1918/private-range validation on connect** — refuse or strongly warn on a
   non-private host, closing the gap README §8 already flagged but never enforced.
2. **Scoped `network_security_config.xml`** — replace the app-wide `usesCleartextTraffic` with a
   config scoped to private address ranges, so the app can't be tricked into cleartext to a public
   host at all, by design rather than by user vigilance.
3. **UI disclosure for the second-client-displacement behavior** — at minimum, something that tells
   the user "another device just took over this connection" instead of silent takeover.

Changes that require touching the KStars fork itself (out of Nocturne's own repo, bigger lift,
needed only if real auth is ever actually required):
4. **Real handshake/token auth on the EkosRemote channel** — same shape as the reboot-daemon's
   existing token scheme, or restoring a stripped-down version of stock EkosLive's HTTP-auth-before-
   socket pattern. This is the only way to close the "no credential check at all" gap — no amount of
   app-side work reaches it, since the server accepts any correctly-pathed socket.
5. **mDNS advertisement** — would need the fork to call `qMDNS`'s registration side (currently
   unused for this purpose) to announce the service; today there is nothing to discover, so app-side
   "mDNS support" would have nothing to find regardless of how it's built.
6. **Per-client session tracking / connection allowlist** — would need `NodeManager` to hold more
   than one `Node` per channel and make an explicit accept/reject decision instead of unconditional
   displacement.

None of 4-6 are started, and none can be started from this repo alone — they'd need a change to
`/home/soo/cc/repo/kstars/kstars/ekos/ekosremote/` and a rebuilt/redeployed KStars on the Pi.
