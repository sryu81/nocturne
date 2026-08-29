# Nocturne — Project Status & Plan

Single source of truth for what's shipped, skipped, superseded, or still planned.
Replaces `M3-plan.md`, `M3.3-plan.md`, `M4-plan.md`, `M4.5-plan.md`, `M5-plan.md`,
`simulator-removal-plan.md`, `app-side-feature-backlog.md` (all deleted, content folded in below).

Not touched by this consolidation: `README.md`, `EkosRemote-Client-Guide.md`,
`EkosRemote-Command-Reference.md`, `EkosWebSocket-Fork-Design.md`, `emulator-troubleshooting.md`
— those are living protocol/reference docs, not progress tracking.

**Known stale spot found while writing this**: README's own milestone table (§7) and status
prose (§7a) stop at "Not started: M4" — M4 is actually fully shipped (below) and M4.5/M5 work
happened after. README's §4-6 (data-mapping/command tables) stayed current; only §7/7a drifted.
Not fixed here (out of this doc's scope) — flagging so it doesn't get trusted at face value next time.

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
- [x] M4.6 Summary sheet + export wired to real Room frame data (KEPT/DISCARDED/MED HFR)
- [ ] PA richer vector/correction-arrow overlay — NOT STARTED (folded into M5's scope instead, arguably superseded)
- [ ] **M4.5 half A — Alerts real wiring** — NOT STARTED: no `NewNotification` EkosEvent case exists; `AlertsSheet` still reads the static `ALERTS` fixture
- [ ] **M4.5 half B — Prefs real wiring** — NOT STARTED: zero `OPTION_GET`/`OPTION_SET` wire constants; `PrefsSheet` is local-only
- [ ] Summary sheet's session-event log — blocked on the same missing `NewNotification` wiring above

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
- [x] Step 5 — auto-drive a real rotator — `align_set_astrometry_settings`'s `rotator_control` bool wired, master gate for the whole feature (confirmed against `align_goto.cpp`'s `checkIfRotationRequired()`), UI switch unconditional. "Manual snapshot" half still not built (no snapshot/capture trigger exists on this path — "take image" is just the existing `align_solve`/Controls-tab Solve button, confirmed identical to the real ManualRotator dialog's own Take Image button)
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

### M4.5
Part A: `Preview/<date>/Prev_NNNNN.jpg` (per-day, counter seeded from disk so a same-day relaunch
doesn't overwrite), `Plan/<date>/<target>/<target>_<date>_<filter>_<exp>sec_<temp>C_<seq>.jpg`.
Part B (plate solver) design, never started: star-centroid extraction + small bundled bright-star
catalog + geometric-hash match; scale is free from the real header's `focal_length`/`pixel_size`/
`bin`, only RA/Dec/rotation need solving for.

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

---

## Network discovery, connection & authentication

Prompted by: app is used on a closed local network today, but the user anticipates outside security
review at some point and wants the real trust model documented honestly, not assumed.

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
