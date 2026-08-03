# Nocturne — Android astrophotography imaging app: build plan

## 1. What this is

`Nocturne` is a native Android app that controls an astrophotography imaging rig
from the field. It is the remote client for the **EkosRemote** fork of KStars:
the Pi runs KStars + INDI + Ekos and listens; the phone connects directly over
the local network and drives everything — target selection, capture sequences,
guiding, focusing, polar alignment, frames review, session summary.

The UI is a pixel-faithful Android port of the design handoff in
`project/Session Control.dc.html` (5 tabs: Session / Plan / Sequence / Frames /
Gear, plus detail sheets and a 4-step rig setup wizard), themed by the Nocturne
design system in `project/nocturne.css`.

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
| Sequence tab — night plan bar, blocks, exposure/gain/binning/dither/AF | `capture_get_sequences`, `capture_add_sequence`, `capture_set_all_settings`, `capture_get_all_settings`, `capture_start/stop/loop`; night bar from sequence + `new_capture_state` |
| Frames tab — sub grid, keep/cut, HFR across run | media frames (HFR from header), local Room verdicts, capture progress |
| Gear tab — readiness, new profile, bench, PA, device list, power/dew, roof | `get_profiles`, `profile_add/start/stop`, `get_devices`, `device_get`, `device_property_get/set/subscribe`, `new_temperature`, `new_notification` |
| Guide sheet | `new_guide_state`, `guide_get_all_settings`, media `+G` frames |
| Focus sheet | `new_focus_state`, `focus_get_all_settings`, `focus_in/out`, media `+F` frames |
| Alerts sheet | `new_notification`, `option_get/set` for rules |
| Prefs sheet | `option_set` (alert rules, quiet hours) — app-local mirrors of Ekos options |
| Setup wizard (4 steps) | step 1 profile name/optics/site → `astro_get_location` (or phone GPS); step 2 connect devices → `profile_add` + `device_*`; step 3 bench/PA links; step 4 `profile_start` |
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
