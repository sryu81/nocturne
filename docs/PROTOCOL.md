# EkosRemote protocol (as Nocturne uses it)

The Pi runs KStars from the fork `github.com/sryu81/kstars`, branch `ekosremote-fork` (see
`docs/FORK.md`). Its `EkosRemote` server is the only thing the app talks to besides the
maintenance daemon (`pi-tools/reboot-daemon`, port 9001).

The authority for anything not listed here is the fork source:
`kstars/ekos/ekosremote/commands.h` (wire strings, ~230 commands) and `message.cpp`
(`Message::onTextReceived` and the `process*Commands` functions, each a plain
`if (command == commands[...])` chain with the payload inline).

## Connection

| Channel | URL | Carries |
|---|---|---|
| Message | `ws://<pi>:9000/message/ekos` | JSON commands, replies, state pushes |
| Media | `ws://<pi>:9000/media/ekos` | Binary image frames |

- The server starts with KStars (no setting), whether or not an Ekos profile is running.
- No TLS, no authentication: trusted LAN only. Anything that reaches port 9000 can control the rig.
- **One client at a time.** A new connection takes over; a test probe from a laptop disconnects the
  phone (force-stop the app before probing).
- The rig announces itself on UDP 50505 (`astro-beacon`); the connect screen lists it.

## Envelope and lifecycle

Every Message-channel frame is `{"type": "<command>", "payload": <object|array|value>}`. `type`
is the string from `commands.h` (not the C++ enum name).

On connect the app sends, in order:

1. `set_client_state {"state": true}` — required; until it is sent the server sends no replies.
   (Events such as `new_notification` arrive regardless.)
2. `get_connection` → `new_connection_state {connected, online}`; `online` = Ekos fully started.
3. `get_scopes`, `get_profiles`, `get_drivers` — answered even while Ekos is stopped (rig setup
   needs them before Start).
4. Once `online`: `get_states` (a burst of module states), `get_devices`, `train_get_all`,
   `train_get_profiles`, the module `*_get_all_settings`, `option_get`.

Module commands are refused until Ekos has finished starting.

## Media frames

Each binary frame is a 512-byte null-padded JSON header followed by JPEG bytes.

- Header fields: `resolution` (of the original image, e.g. `"6224x4168"`), `size`, `channels`,
  `mean`, `median`, `stddev`, `min`, `max`, `bin`, `bpp`, `uuid`, `exposure`, `focal_length`,
  `aperture`, `gain`, `pixel_size`, `shadows`, `midtones`, `highlights`, `hasWCS`, `hfr`, `view`,
  `ext`. `bpp` and `size` are strings.
- `uuid` tags the frame: a capture has the image's 32-hex object name; `+A` align, `+F` focus,
  `+G` guide, `+D` dark; `skypoint_hips` / `hips_*` are sky lookups, not camera frames; no `uuid`
  = live video.
- The JPEG is downscaled by the server: captures to 1920 px wide (smooth), `+`-tagged frames to
  960 px (fast, blocky). Quality 90. Full-resolution data stays on the Pi.
- `hfr` is `-1` when no star was measured.
- `set_blobs` must be on for frames to flow.

## Pushes the app handles

| Push | Payload (fields the app reads) | Notes |
|---|---|---|
| `new_connection_state` | `connected`, `online` | |
| `new_mount_state` | `status`, `target`, `slewRate`, `pierSide`, plus ~1 Hz coordinate telemetry | Several shapes under one name |
| `new_capture_state` | `status`, progress fields, `log` | |
| `new_camera_state` | `name`, `temperature` | |
| `new_focus_state` | `status`, or per frame `{hfr, pos}`, or `{title}` | V-curve points come one frame at a time |
| `new_guide_state` | `status`; fork adds `raError`, `decError`, `raPulse`, `decPulse`, `snr`, `skyBg`, `numStars`, `rmsRA`, `rmsDE` | Internal guider sends `snr`/`skyBg`/`numStars` as 0 |
| `new_align_state` | `{status}` or `{solution}` | |
| `align_manual_rotator_status` | `currentPA`, `targetPA`, `threshold` | |
| `new_polar_state` | `stage`, `enabled`, `message`, `vector`, `updated*Error` | |
| `new_scheduler_state` | `status` (int) or `{log}` | |
| `new_notification` | `source`, `severity`, `message`, `uuid` | Ekos alerts (flip, capture failed, INDI server) |
| `*_get_all_settings` | full settings map | Also pushed on every change (`settingsUpdated` → full map) |

**Rule:** many pushes have several payload shapes under one name. Every decoded field defaults to
null and is merged into state, never replaced wholesale, or one shape wipes another.

Each module's `log` field carries the module's **entire** log text on every new line (never
trimmed) — see `docs/PLAN.md`.

## Commands the app sends

| Area | Commands |
|---|---|
| Lifecycle | `set_client_state`, `get_connection`, `get_states`, `get_devices`, `set_blobs` |
| Profiles | `get_profiles`, `get_drivers`, `profile_start`, `profile_stop`, `profile_add`, `profile_update`, `profile_delete` |
| Trains / scopes | `train_get_all`, `train_get_profiles`, `train_set`, `train_add`, `train_update`, `get_scopes`, `scope_add`, `scope_update`, `scope_delete` |
| Devices | `device_get`, `device_property_get`, `device_property_set`, `device_property_subscribe` |
| Sky | `astro_search_objects`, `astro_get_names`, `astro_get_objects_info`, `astro_get_object_info`, `astro_get_objects_riseset`, `astro_get_almanac`, `astro_get_location` |
| Scheduler | `scheduler_get_jobs`, `scheduler_add_jobs`, `scheduler_remove_jobs`, `scheduler_start_job`, `scheduler_save_sequence_file`, `scheduler_get_all_settings`, `scheduler_set_all_settings` |
| Capture | `capture_preview`, `capture_stop`, `capture_get_all_settings`, `capture_set_all_settings` |
| Mount | `mount_goto_target`, `mount_abort`, `mount_park`, `mount_unpark`, `mount_set_tracking`, `mount_set_motion`, `mount_set_slew_rate`, `mount_get_all_settings`, `mount_set_all_settings` |
| Align | `align_solve`, `align_set_astrometry_settings`, `align_set_target_pa`, `align_get_all_settings`, `align_set_all_settings` |
| Focus | `focus_start`, `focus_stop`, `focus_in`, `focus_out`, `focus_get_all_settings`, `focus_set_all_settings` |
| Guide | `guide_start`, `guide_stop`, `guide_loop`, `guide_clear`, `guide_capture`, `guide_get_all_settings`, `guide_set_all_settings` |
| Polar align | `polar_start`, `polar_stop`, `polar_refresh`, `polar_refreshing_done`, `polar_slew_done`, `polar_set_crosshair` |
| Options / KStars | `option_get`, `option_set`, `kstars_set_location`, `kstars_set_time` |

## INDI devices and properties

- `get_devices` → `[{name, connected, version, interface}]`; `interface` is the INDI
  `DRIVER_INTERFACE` bitmask (a guide camera ORs `CCD` and `GUIDER`).
- `device_get {device}` → `{device, properties: [...]}`, every property of one device.
- `device_property_get {device, property}` → one property.
- `device_property_subscribe {device}` (all properties) or `{device, properties: [names]}` → the
  same property shape is pushed on every change. Live pushes are compact (no labels).
- `device_property_set {device, property, elements: [{name, state}]}` for switches (`state: 1` =
  on); numbers and texts use `value` / `text`.
- A property object is `{device, name, state, label, group, perm, rule}` plus **one** element list,
  which identifies its kind (there is no type field): `switches: [{name, state, label}]`,
  `numbers: [{name, value, label, min, max, step, format}]`, `texts: [{name, text, label}]` or
  `lights: [{name, state, label}]`. `state` is the INDI `IPState` index (0 Idle, 1 Ok, 2 Busy,
  3 Alert).
- Busy pushes can be very frequent (e.g. `CCD_EXPOSURE` counting down); throttle on the client.

## Behaviour worth knowing

- **Settings maps** (`*_get_all_settings`) are Qt widget values by object name. Combo values are
  strings (`alignBinning: "1x1"`). Group-box settings are not in the map; use
  `option_get`/`option_set` (Qt property names) — `option_set` writes the stored default, not the
  live widget.
- **Writes**: send only the keys you change (`capture_set_all_settings {"captureBinHN": 2}`).
  Capture/Align/Focus/Guide settings writes are saved with the optical train; Ekos's own GUI
  Reset-frame button is not, and Ekos reloads the train's saved settings on train refresh.
- **`invoke_method`** discards return values; it can call slots but never read getters.
- **`astro_get_objects_riseset`** returns KStars' preferred name unless `"exact": true`.
- **`astro_get_object_info`** replies with the resolved name, not the query.
- **Scheduler**: `scheduler_start_job` toggles; `scheduler_remove_jobs` is refused while running
  (stop first); job flags (track/focus/align/guide) are new-job defaults only. `.esq` files need a
  server-side absolute path; KStars' name sanitizer doesn't strip `#`.
- **Temperature**: there are two settings — INDI `CCD_TEMPERATURE` and Capture's
  `cameraTemperatureN`; write both. "Enforce temperature" holds previews until the set point is
  reached.
- **Focus**: `focus_in`/`focus_out` take `steps`; `steps: 0` moves by Ekos's Relative Step.
- **Profiles**: `drivers` is keyed by INDI family label (`Telescopes`, `CCDs`, `Filter Wheels`,
  `Focusers`, `Rotators`, `Domes`, `Weather`, `Power`, `Auxiliary`); the guide camera is a second
  `CCDs` entry, identified only by the separate `guider` field.
- **High-rate pushes** (e.g. exposure progress) are throttled app-side to avoid UI stalls.
