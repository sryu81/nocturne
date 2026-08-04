# Nocturne — Android astrophotography imaging app: build plan

## 1. What this is

`Nocturne` is a native Android app that controls an astrophotography imaging rig
from the field. It is the remote client for the **EkosRemote** fork of KStars:
the Pi runs KStars + INDI + Ekos and listens; the phone connects directly over
the local network and drives everything — target selection, capture sequences,
guiding, focusing, polar alignment, frames review, session summary.

The UI is a pixel-faithful Android port of the design handoff in
`project/Session Control.dc.html` (5 tabs: Session / Plan / Sequence / Frames /
Gear, plus detail sheets), themed by the Nocturne design system in
`project/nocturne.css`. The original prototype's 4-step rig setup wizard was
reworked during M1 into a single-screen device-role picker (Rig profile) plus
a separate Optical Train screen (Primary/Secondary, 10 real train roles each)
— see §7a for why.

### Source of truth

| Doc | Role |
|---|---|
| `project/Session Control.dc.html` | UI prototype — what the app looks and behaves like. Read in full; the `data-dc-script` block is the interaction/telemetry reference. |
| `project/nocturne.css` | Design tokens (colors, type, spacing, radius, elevation) — port to Compose theme. |
| `EkosRemote-Client-Guide.md` | Wire protocol: channels, envelope, lifecycle. Authoritative for connection semantics. |
| `EkosRemote-Command-Reference.md` | Every command/push and its exact payload. Authoritative for payload shapes. |
| `EkosWebSocket-Fork-Design.md` | Why the fork exists and what it replaced on the Pi side. Read for context, not for app work. |

## 2. Protocol model (confirmed)

- Pi listens on port **9000**, always on while KStars runs. No auth, trusted LAN.
- Two independent WebSocket connections:
  - **Message** `ws://<pi-ip>:9000/message/ekos` — JSON commands + state pushes.
  - **Media** `ws://<pi-ip>:9000/media/ekos` — binary frames: 512-byte JSON
    metadata header (null-padded) + JPEG bytes. Header carries `hfr`, `mean`,
    `median`, `stddev`, `bin`, `exposure`, `gain`, `pixel_size`, `uuid` (frame
    type: `""` capture, `+A` align, `+F` focus, `+G` guide, `+D` dark).
- Envelope: `{"type": "<command>", "payload": {...}}`.
- **Send `set_client_state` `{"state": true}` first** — until then most replies
  are suppressed.
- Broadcast semantics: no per-client request→response correlation. State is
  push-driven; a `get_states` burst rebuilds the whole UI on connect.
- Client owns reconnect/backoff.

## 3. Tech stack

- Kotlin 2.x, Jetpack Compose (no Material — custom Nocturne theme).
- OkHttp WebSocket (two connections) or Ktor client; kotlinx.serialization
  for envelope/JSON.
- Room: profiles cache, session log, frame keep/cut verdicts.
- DataStore: prefs, red-mode, alert rules, connection history.
- Coil: render JPEG frames from the media channel.
- Compose Canvas for all charts — no chart library (match the SVG fidelity in
  the prototype: night arc, altitude curves, RA/DEC guide traces, focus V-curve,
  HFR-across-run).

## 4. Architecture — four layers

```
UI (Compose screens/sheets)
   │  ViewModels observe flows
Session layer: SessionController interface
   │  telemetryFlow · frameFlow · stateFlow · command methods
Protocol: typed EkosRemote models (commands + pushes)
   │
Transport: EkosRemoteClient — Message + Media WebSocket connections
   │
   ▼
EkosRemote fork of KStars on the Pi (out of scope here)
```

### 4.1 Transport — `EkosRemoteClient`

- Opens both channels; validates paths; reconnect with backoff per channel.
- On Message connect: `set_client_state(true)`, then `get_connection`,
  `get_states`, `get_profiles`, `get_devices` to bootstrap the UI.
- Decodes inbound JSON; emits events into a `SharedFlow` (sealed
  `EkosEvent`: command-specific typed payloads, raw fallback for unknown).
- Binary frames from Media: parse 512-byte header, expose
  `Frame(meta, jpegBytes)`.

### 4.2 Protocol — typed models

Commands grouped by module (wire strings from the reference, exact):

| Area | Commands |
|---|---|
| Lifecycle | `get_connection`, `set_client_state`, `get_states`, `get_devices`, `get_profiles`, `get_scopes`, `get_dslr_lenses`, `get_stellarsolver_profiles` |
| Generic RPC | `get_property`, `set_property`, `invoke_method` (args `{type: QVariant::Type int, value}`) |
| Profiles | `profile_add`, `profile_update`, `profile_delete`, `profile_get`, `profile_start`, `profile_stop`, `profile_set_mapping`, `profile_set_port_selection` |
| Trains/Scopes | `train_get_all`, `train_set`, `train_add/update/delete`, `scope_add/update/delete` |
| Capture | `capture_preview`, `capture_start`, `capture_stop`, `capture_loop`, `capture_get_sequences`, `capture_add_sequence`, `capture_remove_sequence`, `capture_clear_sequences`, `capture_get_all_settings`, `capture_set_all_settings`, `capture_toggle_camera`, `capture_toggle_filter_wheel` |
| Mount | `mount_goto_rade`, `mount_goto_target`, `mount_goto_pixel`, `mount_sync_rade`, `mount_sync_target`, `mount_set_slew_rate`, `mount_set_motion`, `mount_set_tracking`, `mount_park`, `mount_unpark`, `mount_abort`, `mount_get_all_settings`, `mount_set_all_settings`, `mount_toggle_autopark` |
| Focus | `focus_start`, `focus_stop`, `focus_capture`, `focus_in`, `focus_out`, `focus_loop`, `focus_reset`, `focus_set_crosshair`, `focus_get_all_settings`, `focus_set_all_settings` |
| Guide | `guide_start`, `guide_stop`, `guide_capture`, `guide_loop`, `guide_clear`, `guide_get_all_settings`, `guide_set_all_settings`, `guide_set_calibration_settings` |
| Align | `align_solve`, `align_stop`, `align_load_and_slew`, `align_manual_rotator_toggle`, `align_get_all_settings`, `align_set_all_settings`, `align_set_astrometry_settings` |
| Polar | `polar_start`, `polar_stop`, `polar_refresh`, `polar_set_algorithm`, `polar_reset_view`, `polar_set_crosshair`, `polar_star_select_done`, `polar_set_zoom` |
| Scheduler | `scheduler_get_jobs`, `scheduler_add_jobs`, `scheduler_remove_jobs`, `scheduler_get_all_settings`, `scheduler_set_all_settings`, `scheduler_save_file`, `scheduler_import_mosaic` |
| Devices | `device_get`, `device_property_get`, `device_property_set`, `device_property_subscribe`, `device_property_unsubscribe` |
| Dark lib | `dark_library_start/stop`, `dark_library_get_all_settings`/`set_all_settings`, `dark_library_get_masters_image`, `dark_library_set_camera_presets`, `dark_library_get_camera_presets` |
| Live stack | `livestacker_initialize`, `livestacker_start`, `livestacker_stop`, `livestacker_get_all_settings`, `livestacker_set_all_settings`, `livestacker_close` |
| Horizon | `artificial_horizon_import`, `artificial_horizon_toggle`, `artificial_horizon_get` |
| Files | `file_default_path`, `file_directory_operation` |
| Filters | `fm_get_data`, `fm_set_data` |
| Options | `option_get`, `option_set` |
| Astro | `astro_get_almanac`, `astro_get_names`, `astro_get_designations`, `astro_get_location`, `astro_search_objects`, `astro_get_object_info`, `astro_get_objects_info`, `astro_get_objects_observability`, `astro_get_objects_riseset` |

Pushes consumed by the UI: `new_connection_state`, `new_capture_state`,
`new_mount_state` (`status`/`target`/`slewRate`/`pierSide`), `new_focus_state`,
`new_guide_state`, `new_align_state`, `new_polar_state`
(`stage`/`enabled`/`message`), `new_temperature`, `new_notification`,
`new_scheduler_state`, `new_livestacker_state`, `new_mosaic_tiles`,
`capture_get_sequences` (push), `capture_get_preview_label`, `device_message`,
`dialog_get_info`, `polar_*` PAH pushes, `train_configuration_requested`.

### 4.3 Session layer — `SessionController`

Single interface the whole UI consumes:

```kotlin
interface SessionController {
    val telemetry: StateFlow<SessionTelemetry>   // HFR, RMS, SNR, cooler, dew, sky, power…
    val frames: SharedFlow<MediaFrame>            // preview + sub frames
    val state: StateFlow<SessionState>            // tab-relevant module states
    val events: SharedFlow<EkosEvent>             // alerts, notifications, device messages
    fun startCapture(cfg: SequenceBlock)
    fun stopCapture()
    fun slewTo(target: AstroObject)
    fun guide(start: Boolean)
    fun focusRun(), focusStep(delta: Int), ...
    fun startProfile(name: String), stopProfile()
    fun setOption(key: String, value: Any), ...
}
```

Two implementations:

- `SimulatedController` — **M1**. Reproduces the prototype's behavior exactly
  (the `data-dc-script` logic: `wiggle()` trace generator, 1 s timer, night-arc
  math, cooler ramp, frame keep/cut toggling). Lets the full UI ship and be
  demoed before any Pi exists.
- `EkosRemoteController` — **M2+**. Same interface over the transport. The
  simulator and the real driver are swappable behind a settings toggle.

### 4.3a Terminology — job / sequence / session

Introduced with the M1 Sequence-tab rework (§7a); "sequence" gets used three
different ways across this doc and the code, so pin it down here:

- **Sequence** — the ordered list of exposure recipes for *one* target:
  filter/exposure/subs/gain/offset/binning/dither. In code:
  `SequenceJob.blocks: List<Block>`. Maps to one Ekos `.esq` file / the
  Capture module's queue (`capture_*` commands).
- **Job** (`SequenceJob`) — one target + its one sequence + run state (`id`,
  `targetId`, `blocks`, `running`). Always one target ↔ one sequence.
  Mirrors Ekos's `SchedulerJob` (`scheduler_*` commands) — named `SequenceJob`
  rather than bare `Job` to avoid colliding with `kotlinx.coroutines.Job`,
  already used for the simulator's ticker. `SimState.jobs: List<SequenceJob>`
  is the whole night's queue — what the Sequence tab lists.
- **Session** — not a class, no dedicated data object; the implicit "what's
  happening right now" across the whole app — `SimState` as a whole, viewed
  through the **contract job** (`SimState.contractJob`: the running job, else
  the first queued job, else null — see §7a). Session tab is a window onto
  whichever job is currently the contract job. "End session" stops that
  contract job and opens a choice: resume it, advance to the next queued job,
  or finish the night (clear the queue, park mount, cooler off).

So: a **sequence** is data (a list of blocks) owned by a **job** (target +
that sequence + running flag); a **session** is the live, whole-app view of
the job queue, not a stored thing itself.

Note the "Sequence" name is still overloaded in casual use — the *Sequence
tab* (the job-list screen), a *job's sequence* (its blocks), and the wire
concept `.esq`/`capture_*` (Ekos's own "sequence") are three different things
that happen to share a word. No code-level collision, just keep the three
apart in conversation and in future docs.

### 4.4 UI

- Compose screens per tab, `ModalBottomSheet` for the detail sheets, adaptive
  panes in landscape (the prototype's 2-column `pane-cols` layout).
- Nocturne theme: port tokens from `nocturne.css` (bg `#161826`, surface
  `#232532`, text `#e9e9ed`, accent `#9184d9`, neutral/accent ramps, spacing
  scale 0.7×, radius 4/8/14, shadow sm/md/lg, Inter, mono numerals for
  telemetry).
- Red mode: Compose-level night palette remap (deep-red ramp + dim) applied at
  the theme root — not a color filter hack.
- Charts as reusable Canvas composables: `NightArc`, `AltitudeCurve`,
  `TraceChart`, `VCurve`, `HfrRun`, `SubGrid`.

## 5. Design → data mapping

| Screen | Data source |
|---|---|
| Session tab — night arc, integ label, sub preview, HFR/RMS/SNR cards, flip banner, sky & site, end-session | `new_capture_state`, `new_focus_state`, `new_guide_state`, `new_mount_state`, `new_temperature`, media capture frames (`hfr` from header), `astro_get_almanac` for night span |
| Plan tab — catalog search, chips, altitude chart, framing box | `astro_search_objects`, `astro_get_object_info`, `astro_get_objects_observability`, `astro_get_objects_riseset` (`altitudes[]` → chart), `get_scopes`/`scope` for pixel-scale/rotator |
| Sequence tab — job queue (one target ↔ one sequence, list + drill-down) | Job-queue membership (list/add/remove/select which job is queued) → `scheduler_get_jobs`/`scheduler_add_jobs`/`scheduler_remove_jobs`/`scheduler_start_job`, each `SequenceJob` maps to one `SchedulerJob`. A job's own block list (exposure/gain/binning/dither/AF, drilled into per job) → `capture_get_sequences`/`capture_add_sequence`/`capture_set_all_settings`/`capture_get_all_settings`/`capture_start/stop/loop`, and becomes the `.esq` file the owning `SchedulerJob.sequence` field points at (see §8). Night bar from the currently-running job + `new_capture_state`/`new_scheduler_state` |
| Frames tab — sub grid, keep/cut, HFR across run | media frames (HFR from header), local Room verdicts, capture progress |
| Gear tab — readiness, rig profile, optical train, bench, PA, device list, power/dew, roof | `get_profiles`, `profile_add/start/stop`, `get_devices`, `device_get`, `device_property_get/set/subscribe`, `new_temperature`, `new_notification`. Power/dew and roof cards only render when Powerbox/Dome are selected in the rig profile, and dim to an idle state when Ekos isn't running; roof control is separate Open/Close buttons, not a single toggle. |
| Guide sheet | `new_guide_state`, `guide_get_all_settings`, media `+G` frames |
| Focus sheet | `new_focus_state`, `focus_get_all_settings`, `focus_in/out`, media `+F` frames |
| Alerts sheet | `new_notification`, `option_get/set` for rules |
| Prefs sheet | `option_set` (alert rules, quiet hours) — app-local mirrors of Ekos options |
| Rig profile (single screen) | profile name + device-role picker (mount/CCD/guide CCD/EFW/EAF/rotator/dome/weather/powerbox + scope/guide scope as free-typed name+focal+aperture) → `profile_add`/`profile_update` + `device_*`; Save calls `profile_start` |
| Optical Train sheet | Primary/Secondary, 10 roles each (mount/camera/rotator/guide via/dust cap/scope/filter wheel/focuser/reducer/light box) → `train_get_all`/`train_add`/`train_update`, pools sourced from the rig profile's device/scope picks |
| Bench sheet | `capture_preview`, `focus_in/out`, `mount_set_motion`, cooler via `device_property_set` |
| PA sheet | `new_polar_state` (`stage`/`enabled`/`message`), `polar_start/refresh/set_algorithm`, media `+A` frames |
| Device sheet | `device_get` properties, `device_property_set`, `device_property_subscribe`, connect/disconnect |
| Summary + export | capture progress + local session log → export log + FITS list |

App-local data (kept in Room): frame keep/cut verdicts, session log, profile
display cache, alert rule overrides. Ekos/INDI remains master for sequences,
profiles, options.

## 6. Repository layout (proposed)

```
app/
  src/main/java/com/nocturne/
    transport/     EkosRemoteClient, MessageChannel, MediaChannel, reconnect
    protocol/      commands, models, codec (serialization), EkosEvent
    session/       SessionController, SimulatedController, EkosRemoteController
    ui/            theme/ (tokens), nav/, session/, plan/, sequence/, frames/,
                   gear/, sheets/ (guide, focus, alerts, prefs, setup, bench,
                   pa, device, summary), components/ (charts, cards, chips)
    data/          Room (frames, session log), DataStore prefs, connect history
  src/test/java/   transport+codec tests, simulator determinism tests
```

## 7. Milestones

| # | Scope | Exit criteria |
|---|---|---|
| M0 | Gradle scaffold; Nocturne Compose theme; nav shell (5 tabs, bottom nav, rotate, red-mode overlay) | App builds; theme tokens match `nocturne.css`; tab shell navigates, rotate + red mode work |
| M1 | Full UI on `SimulatedController`: Session/Plan/Sequence/Frames/Gear + all 7 sheets, charts in Canvas, live simulated telemetry | Demos the prototype pixel-faithfully as a real app; charts animate like the prototype |
| M2 | Transport: 2-channel client, envelope codec, connect screen (manual IP, optional mDNS), `get_states` bootstrap, reconnect/backoff | Connects to EkosRemote Pi; Session tab driven by live pushes |
| M3 | Profiles/devices/Plan/Sequence live: profile start/stop, device list + property sheets, `astro_*` lookups, capture sequences | Plan + Sequence tabs operate real Ekos end-to-end |
| M4 | Media channel → preview + Frames grid, HFR from JPEG header, keep/cut persistence, live guide/focus/PA/bench, alerts + prefs, summary + export | Full session runnable from a phone; export produces log + FITS list |

## 7a. Current status

**M0 — done.** Gradle scaffold, Nocturne Compose theme, 5-tab nav shell (bottom
nav + landscape rail), rotate + red-mode overlay all build and work.

**M1 — in progress, past the pixel-port stage into a UI audit/fix pass on top
of `SimulatedController`:**

- Session/Plan/Frames tabs: pixel-ported from the prototype, not yet
  re-audited past the initial port.
- Sequence tab: reworked past the initial port — blocks are now real
  (add/remove/drag-reorder), block fields (exposure/subs/gain/offset/
  binning/dither) are editable and derive the header spec/progress instead of
  static text, autofocus is a sequence-wide rules sheet (matches real Ekos —
  see §8) plus a per-block force-autofocus override, and the header alerts
  bell + "Fix in Gear" banner are wired instead of dead. Reworked again to a
  **multi-target job queue**: the tab is now a list of `SequenceJob`s (one
  target ↔ one sequence each, matching real Ekos's Scheduler module rather
  than Capture's single flat sequence — see §8), tap-through to the existing
  block editor scoped per job. Fed from the Plan tab's previously-dead "Add
  to sequence" button (find-or-create a job for the selected target, seed one
  block, jump to Sequence tab). This means **Scheduler**, not just Capture,
  is now in scope for M2/M3 wiring — see §8's `SequenceJob`→`SchedulerJob`
  bullet.
- Gear tab: rig profile setup collapsed from the prototype's 4-step wizard to
  a single device-role picker (9 categories: mount/CCD/guide CCD/EFW/EAF/
  rotator/dome/weather/powerbox, each picked from a simulated multi-option
  catalog) + scope/guide-scope as free-typed name+focal+aperture fields. A
  separate Optical Train screen models Primary/Secondary trains with the real
  10 Ekos roles each, sourced live from the rig profile's picks. Powerbox/roof
  cards only show when that category is selected, dim to idle when Ekos isn't
  running, and roof control is Open/Close, not one toggle.
- Device control panels: replaced with real per-driver INDI property layouts
  (not a generic per-role stand-in) for every catalog device, sourced from
  `~/cc/repo/indi`/`indi-3rdparty` driver source — see §8 for the 3 catalog
  entries that turned out not to correspond to any real driver and were
  swapped. **This 19-device catalog is a demo sample of several hundred real
  drivers, not a comprehensive list — §8 has a ⚠️ entry on why it must not
  carry forward into M2/M3 as-is.**
- Cross-cutting fixes: keyboard no longer covers focused text fields
  (`imePadding` — edge-to-edge + targetSdk 35 stopped honoring
  `windowSoftInputMode`), device-sheet nav returns to where it was opened from
  instead of closing everything, emulator GPU-selection issue documented in
  `docs/emulator-troubleshooting.md`.

**Not started:** M2 (transport), M3, M4. `SessionController` still has exactly
one implementation (`SimulatedController`); the interface is the seam M2 swaps
behind.

## 8. Risks & decisions to confirm

- **Push-driven state.** Broadcast semantics → no request/response correlation;
  ViewModels must derive UI from pushed state, not correlate last request.
  Mitigated by `get_states` burst on every reconnect.
- **No auth / trusted LAN.** App should refuse/flag non-local connections and
  surface the trust boundary on the connect screen.
- **PAH requires star selection + solves.** Simulator fakes the 3-step flow; the
  real flow needs the align module and the `polar_star_select_done` /
  `polar_slew_done` round-trip — build behind the simulator contract, wire last.
- **Frames keep/cut + session log are app-local.** Ekos is master for frames on
  disk; decide the export contract (FITS list via `file_directory_operation`,
  log format) before M4.
- **`align_load_and_slew` and media uploads** run on the Media channel — the
  Media client must also speak JSON commands, not just binary.
- **Unimplemented surface to skip:** dome/cap section, `mount_clear`,
  `device_restart`, `device_blob_get` (all confirmed dead in the reference).
- **Landscape layout** uses the prototype's two/three-column panes; confirm the
  column split per tab during M0.
- **Export format** (log + FITS list) and **session summary contents** —
  confirm with user before M4.
- **Autofocus rules are global in real Ekos, not per-job.** `capture_get_all_settings`/
  `capture_set_all_settings` (message.cpp:542,546) has no job index and explicitly
  "writes through to global `Options`" — `enforceAutofocusHFR`, `enforceRefocusEveryN`,
  `hFRDeviation`, `maxFocusTemperatureDelta` etc. apply once for the whole running
  queue. The Sequence tab models this correctly as one `Autofocus rules` sheet, not
  per-block fields. The per-block `forceAfOnStart` toggle on each block is a
  deliberate **Nocturne-only** addition on top of that — not an Ekos setting — meant
  to fire a standalone `focus_start` (message.cpp:720) right as that block begins.
  It's a no-op stub under `SimulatedController`; wiring it for real needs M2/M3
  transport to detect "this block just started" from `new_capture_state` pushes
  cross-referenced with `capture_get_sequences`'s current job index.
- **`SequenceJob` → `SchedulerJob` mapping needs research against real Ekos
  source before M2 — the `.esq` round-trip isn't a single atomic call.**
  Nocturne's job queue (one target ↔ one sequence, M1 §7a) is designed to map
  onto Ekos's Scheduler module (`scheduler_get_jobs`/`scheduler_add_jobs`/
  `scheduler_remove_jobs`/`scheduler_start_job`), not Capture's flat
  `capture_add_sequence`. But `scheduler_add_jobs`'s wire request is *empty* —
  it adds a job "from whatever's currently in the Scheduler's own form
  fields" (message.cpp:973), and each `SchedulerJob`'s `sequence` field is a
  path to a `.esq` file, not inline block data. So the real M2/M3 flow is
  likely: build the block list client-side (as today) → serialize it to
  whatever XML shape a real `.esq` file has → `scheduler_save_sequence_file`
  (`{"filedata": string, "path": string}`) to write it → populate the
  Scheduler's target/constraint form fields → `scheduler_add_jobs` → poll
  `scheduler_get_jobs` to confirm the job landed — not one atomic "create job
  with this target + sequence" call. The `.esq` XML shape itself is
  unresearched; confirm against real Ekos source before M2 starts.
- **Session tab's "Sky & Site" card is a fan-in dashboard, not a 1:1 device
  card — needs a Weather device, a Dome device, and a device category that
  doesn't exist yet, before it can be wired for real.** Unlike most cards
  (one card, one device/module), Sky & Site aggregates: AMB/CLOUD from a
  Weather device (`AAG CloudWatcher NG`'s `WEATHER_CLOUD` + humidity), roof
  open/shutter from a Dome device (`DOME_SHUTTER`), and "safe" from a
  combination of weather safety + other monitors in real Ekos, not one
  property. DEW is not a device-reported value at all — real dew point is
  computed from temperature + humidity, not read directly. SQM has no
  backing device category in `DEVICES`/`DRIVER_INDI_PROPS` — a Sky Quality
  Meter would need to be added as a new device type first. Left as the
  fixture placeholder it always was (all 4 stats + roof state are hardcoded
  literals in `SkySite()`, no state params at all) rather than partially
  wiring the easy half (weather/roof) while DEW/SQM stay fake — a
  half-live card is worse than an honestly-fake one. Needs the Weather +
  Dome + new-SQM-category work done first, then a small aggregation layer,
  not a single-device lookup like other cards.
- **Simulated device catalog is modeled on verified real drivers, not
  invented ones — but the local `indi-3rdparty` checkout is incomplete.**
  `~/cc/repo/indi-3rdparty` (a squashed rpi5-builder snapshot) has no
  `indi-pegasusastro` directory — Pegasus's rotator (Falcon) genuinely isn't
  present in either local repo, so it was dropped from the rotator category
  rather than faked. `ScopeDome` and `Boltwood` were also fake (no such driver
  exists in either repo, confirmed via full git history) and were swapped for
  real ones: dome → MaxDome II/NexDome, weather → AAG CloudWatcher NG/Weather
  Watcher. Pegasus's power (`indi-upb`/`indi-ppba`) and focuser
  (`pegasus_focuscube3`) drivers *did* turn up, but migrated into core `indi`
  (`drivers/power/`, `drivers/focuser/`), not `indi-3rdparty` — worth knowing
  before assuming a driver's absence from 3rdparty means it doesn't exist.
  `DRIVER_INDI_PROPS` in `SimState.kt` documents the source file per driver;
  extending the catalog should cite real source the same way, not guess.
- **⚠️ The 19-device catalog is a tiny demo sample, not close to comprehensive
  — read this before M2/M3, don't carry the M1 catalog model forward
  unquestioned.** Real INDI driver counts, from this repo checkout alone:
  `indi-3rdparty` has **60 driver packages** (`indi-asi`, `indi-qhy`, etc. —
  many bundle several drivers each, e.g. ZWO's package covers camera + EFW +
  EAF); core `indi/drivers/` alone has telescope 54, focuser 52, rotator 15,
  dome 17, weather 12, power 13, filter_wheel 13, ccd 4 — roughly **180 files**
  across just those 8 categories. Total realistic driver count is in the
  **several hundreds**. Nocturne's `DEVICES`/`DRIVER_INDI_PROPS` catalog
  covers 19 named devices total — it exists only so `SimulatedController` has
  *something* concrete to render per role while there's no real Pi to talk to.
  **Two consequences for M2/M3, don't skip these:**
  1. **Stop hand-maintaining a device list once transport exists.** M2's
     `get_devices` (message.cpp:275) returns whatever's *actually connected*
     — `{name, connected, version, interface}` — with the INDI
     `DRIVER_INTERFACE` bitmask on `interface` deciding which role(s) a
     device fills (a camera can report both CCD + guider interface bits,
     etc.). `EkosRemoteController` must bucket real devices by that bitmask,
     not by matching against `DEVICES.catalog` string lists — those lists
     have no future past M1's simulator.
  2. **Stop hardcoding per-driver property panels by device name.** The
     `DRIVER_INDI_PROPS` map (19 entries, hand-transcribed from driver source
     during M1) cannot scale to "whatever's actually connected" — a real rig
     might report a driver never seen during this pass. M3's device sheet
     must render `device_get`/`device_property_get`'s response generically
     (already exactly what `IndiProperty`'s sealed Switch/Number/Text/Light
     shape was designed for — reuse the type, not the hardcoded data) rather
     than looking up a fixed Kotlin map by name. Treat `DRIVER_INDI_PROPS` as
     what it is — M1 fixture data proving the panel *rendering* code works —
     and delete it once real `device_get` responses replace it, don't keep
     extending it toward "eventually cover everything."
