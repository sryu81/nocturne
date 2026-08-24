# M4 — Media channel, Frames, live Guide/Focus/PA/Bench, Alerts/Prefs, Summary/export

## Context

M3 (done) made Plan/Sequence tabs operate real Ekos end-to-end but deliberately
shipped **zero** imaging telemetry — every "no live preview yet" / "not
available (M4)" placeholder found and left honest throughout M3/M3.3 (Session
tab's sub-preview + HFR/RMS/SNR cards, Bench Primary/Guide cam preview boxes,
the whole Frames tab, Guide/Focus sheets' charts, Alerts, Summary's
KEPT/DISCARDED/MED HFR) is this milestone's job. Per README's own table:
*"Media channel → preview + Frames grid, HFR from JPEG header, keep/cut
persistence, live guide/focus/PA/bench, alerts + prefs, summary + export —
full session runnable from a phone; export produces log + FITS list."*

Two research passes preceded this plan, matching M3's own precedent: one read
`EkosRemote-Client-Guide.md`/`EkosRemote-Command-Reference.md` cover-to-cover
for every M4-relevant command/push; one surveyed the current codebase for
exactly what's stubbed and where. Both passes flagged real gaps in the
protocol docs themselves — a third pass then read the actual KStars source
checkout (`~/cc/repo/kstars/kstars/ekos/ekosremote/message.cpp`) to resolve
the highest-value ones directly, the same way M3's `.esq` schema and INDI
bitmask were confirmed. **One of those findings changes M4's scope
materially — see "Guide/Focus real telemetry does not exist" below.**

## Protocol facts (confirmed against source, not guessed)

**Media channel** (`ws://<pi-ip>:9000/media/ekos`) — binary frames: a fixed
**512-byte** JSON metadata header (`Media::METADATA_PACKET`, null-padded, no
length prefix), then raw JPEG bytes. Three distinct header shapes exist, not
one:
1. **Full** (`capture`/`preview`) — `resolution` (`"WxH"`), `size`, `channels`,
   `mean`/`median`/`stddev`, `min`/`max` (FITSView overload only), `bin`
   (`"XxY"`), `bpp`, `uuid`, `exposure`/`focal_length`/`aperture`/`gain`
   (strings, from FITS header records), `pixel_size` (`%.4f`),
   `shadows`/`midtones`/`highlights`, `hasWCS`, `hfr`, `view` (FITSView
   overload only), `ext` (always `"jpg"`).
2. **Reduced** (align `+A` fast-preview) — same minus
   `min`/`max`/`shadows`/`midtones`/`highlights`/`hasWCS`/`hfr`/`view`.
3. **Minimal** (live video) — only `resolution` + `ext`, **no `uuid` at all**.

`uuid` frame-type tag: `""` capture/preview, `"+A"` align, `"+F"` focus,
`"+G"` guide, `"+D"` dark-library (plus `"hips_<md5>"`/`"skypoint_hips"` for
an unrelated sky-image lookup feature, out of scope here). Any tag starting
with `+` gets scaled to half-width via fast (lower-quality) transformation —
a real, documented quality tradeoff for align/focus/guide previews vs. a full
capture frame.

**No `set_client_state` gate on this channel at all** — connecting is
sufficient to start receiving frames. The only suppression is **`set_blobs`**,
whose payload is a **bare boolean**, not an object: `{"type": "set_blobs",
"payload": true}`. It does **not** persist across a disconnect — a fresh
connection always starts with blobs enabled. The channel also carries a few
JSON **text** commands (`astro_get_objects_image`, `astro_get_skypoint_image`,
`set_blobs`) — `MediaChannel` needs the same `send(text)` path
`MessageChannel` has, not binary-only. ⚠️ **Any binary frame the *client*
sends on this channel is treated as an align-solve image upload and forwarded
straight to `Align::loadAndSlew`** (`Media::onBinaryReceived`) — there is no
separate "start" command for this; test/mock tooling must never send
arbitrary binary frames here.

**Guide/Focus real telemetry does not exist on this protocol — confirmed
against the actual sender functions, not just "undocumented."**
`Message::updateGuideStatus`/`updateFocusStatus` (and every other call site
that constructs a `new_guide_state`/`new_focus_state` push,
`message.cpp:2598-2601,2672-2678,2913-2919`) send **`{"status": <string>}`
and nothing else, every time — there is no RA/DEC drift, SNR, star position,
RMS, or V-curve position/HFR series anywhere in these pushes. The `guide_get_all_settings`/
`focus_get_all_settings` fields that look telemetry-adjacent
(`rMSDisplayedOnGuideGraph`, `sNRDisplayedOnGuideGraph`, `focusCFZDisplayVCurve`,
etc.) are desktop-UI *display toggles*, not the plotted values. **This means
the Guide sheet's RMS/SNR/drift chart and the Focus sheet's V-curve chart are
not blocked on the Media channel the way the Session-tab preview is — they
are blocked on data that this wire protocol never sends, full stop.** No
future Media-channel work unlocks them. Treated accordingly below (§4).

**`capture_get_sequences`** (`message.cpp` `Camera::createJsonJob`,
`camera_jobs.cpp:865-886`) — confirmed real per-job shape: `{"Status":
"Idle"|"In Progress"|"Aborted"|"Complete", "Filter", "Count", "Exp", "Type",
"Bin", "ISO/Gain", "Offset", "Encoding", "Format", "Temperature",
"EnforceTemperature", "DitherPerJobEnabled", "DitherPerJobFrequency"}` — all
job-config echoes plus one coarse status string (set via
`cameraprocess.cpp:411,911,1429`). **No per-sub completed-count field exists
here either** — same conclusion as `scheduler_get_jobs`' own `completedCount`
(a per-job total): real per-block/per-sub progress is not obtainable from any
documented or now-source-confirmed command. `doneCount`'s existing M3
waterfall-fill approximation (README §8) stays the permanent approach, not a
stopgap.

**`new_notification`** (`message.cpp:2712-2725`, `Message::sendEvent`) —
confirmed real shape: `{"source": int, "severity": int, "message": string,
"uuid": string}`. Real enums (`kstars/auxiliary/ksnotification.h:23-42`):
`EventType` (severity) `Debug=0, Info=1, Warn=2, Alert=3`; `EventSource`
`General=0, INDI=1, Capture=2, Focus=3, Align=4, Mount=5, Guide=6,
Observatory=7, Scheduler=8`. Gated server-side by
`Options::ekosRemoteNotifications()` — if that option is off, **nothing
arrives on this push ever**, silently (same gate `device_message` uses).

**Temperature — real command is `new_camera_state`, not `new_temperature`.**
`new_temperature` exists as a declared command name (`commands.h:37,293`) but
is **never sent anywhere** in `message.cpp` — confirmed dead, same class as
README §8's already-known `mount_clear`/`device_restart`/`device_blob_get`
dead entries. Real camera temperature pushes arrive as `new_camera_state`:
`{"name": <device>, "temperature": <double>}` (`message.cpp:438-449`).

**`option_get`/`option_set`** — a generic `Options`-class key/value store
(`message.cpp:1438,1447`): `option_set {"options": [{"name","value"}...]}`,
no reply; `option_get {"options": [{"name"}...]}` → same shape echoed back
with current values. Mechanism confirmed; **no alert-rule-specific key names
are documented anywhere** — these would need to be probed live (`option_get`
with candidate names) or found in the `Options` class itself before Prefs can
send anything real. Scoped out of this pass — see §5.

**Polar alignment already ships real** (`PaRealSheet`/`PaCard`, wired to
`wirePolarStage`/`wirePolarEnabled`/`wirePolarMessage`, confirmed in the
codebase survey) — M4 does not need to touch the PA wizard itself. The
richer `new_polar_state` vector payload (`vector.center_x/center_y/mag/pa/
error/azError/altError`, `Command-Reference.md` §11) that would draw a real
correction-arrow graphic is a nice-to-have layered on top of the Media
channel's own align-frame imagery, not a blocker — deferred, see §4.

**Session log / export**: confirmed no wire-level session-history command
exists anywhere in either protocol doc (grepped both files for
session/log/history/export). Export stays purely local: Room-log + real
fields already available (frame verdicts from Media headers, block totals,
almanac). Matches the existing `SessionReport.kt`'s own design; nothing to
add on the wire side.

## Current state (confirmed via codebase survey, not re-derived here)

- `MediaChannel.kt` — 18-line stub, `TODO("M4")`, never instantiated by
  `EkosRemoteClient`. `MessageChannel.kt` is the pattern to mirror (one
  OkHttp `WebSocket` → `SharedFlow`s) but for `onMessage(WebSocket,
  ByteString)`, not the `String` overload.
- Frames tab was **deleted down to an honest placeholder card** during the
  simulator-removal pass, not merely left fixture — `FrameExpandOverlay`
  and `SimState.frames`/`FRAME_IDS`/`FRAME_HFRS` still exist but are
  unreachable dead code today.
- Session tab's `SubPreview`/`SubPreviewOverlay` and `StatsRow`
  (HFR/RMS/SNR) are honest `"—"`/`M4` placeholders already — no fixture
  numbers shown.
- Bench `SnapPanel` (Primary + Guide cam, just reworked this session) already
  has a properly-sized 4:3 `HatchBg` placeholder box ready for a real image
  to slot into — exposure/gain/bin/filter controls around it are already
  real (M3.2/this session).
- Guide sheet: chart + RMS/SNR are 100% procedural fixture
  (`wiggle()`/`state.rms`/`state.guideStarSnr`), no wire read at all.
- Focus sheet: V-curve is a **hardcoded 9-point path**, not parameterized at
  all; `focusLastBestPos`/`focusLastHfr` are static defaults. (`eafTemp`/
  `focusNextAfMin` nearby are already real — untouched, not part of this gap.)
- Alerts sheet: fully static 4-item fixture list, doesn't even take `state`.
- Prefs sheet: toggle *state* is real (in-memory `SimState`, not persisted),
  but rules have zero wire effect — no `option_get`/`option_set` exists in
  `Commands.kt` at all yet.
- Summary: KEPT/DISCARDED/MED HFR are honest `M4` placeholders; the
  INTEGRATION BY FILTER bars are already real (M3's `Block.pct`). Export
  (`SessionReport.kt`) is real HTML generation, but two of its four sections
  (Frames, Session log) source from the same fixture data Frames/Alerts do.
- **No Room, no Coil** — neither is even in `gradle/libs.versions.toml` yet.
  `data/` has two DataStore-Preferences repositories (connection, sequence
  jobs) as the only persistence precedent so far.
- `Commands.kt` has zero media/notification/option entries.
  `EkosEvent.kt`'s sealed hierarchy has no `NewNotification`/`NewCameraState`
  case — both currently decode to the `Raw` fallback and are silently
  dropped.

## Sub-milestones

### M4.1 — Media channel core (blocks everything below)

- `protocol/Commands.kt`: `SET_BLOBS` (bare boolean payload, not an object —
  don't reuse the usual `buildJsonObject` helper shape for this one call).
- New `protocol/MediaFrame.kt`: three `@Serializable` header shapes (full/
  reduced/minimal, per the confirmed field lists above) decoded from the
  first 512 bytes (trim trailing nulls before parsing), + a plain
  `MediaFrame(header: MediaHeader, jpeg: ByteArray)` wrapper — the JPEG bytes
  are everything after byte 512, no length field to read.
- New `transport/MediaChannel.kt`, replacing the stub: same shape as
  `MessageChannel` but binary — `WebSocketListener.onMessage(WebSocket,
  ByteString)`, parse per above, emit `SharedFlow<MediaFrame>`. Needs its own
  `send(text: String)` too (align-image-lookup commands share this channel).
  Reuses `EkosRemoteClient`'s existing shared `OkHttpClient`.
- `EkosRemoteClient.kt`: construct `MediaChannel(okHttpClient, "ws://$host:
  $port/media/ekos")` alongside the existing `MessageChannel`, open both on
  connect, extend the reconnect/backoff bookkeeping to cover both sockets
  (currently scoped to Message only). Send `set_blobs: true` once media
  socket opens — matches the real "always re-enabled on fresh connect"
  server behavior, this is just being explicit about it rather than relying
  on the default.
- New additive `SimState` fields, one per real frame type, holding the most
  recent frame only (no history buffer yet — that's Frames tab's own job,
  §4.3): `latestCaptureFrame`, `latestAlignFrame`, `latestFocusFrame`,
  `latestGuideFrame: MediaFrame? = null`. `EkosRemoteController` routes each
  incoming `MediaFrame` by its header's `uuid` tag into the matching field.
- Add Coil (`gradle/libs.versions.toml` + `app/build.gradle.kts`) — decode
  `MediaFrame.jpeg: ByteArray` via Coil's `ByteArray`/`ImageRequest` support,
  no temp-file round-trip needed.

### M4.2 — Live preview rendering

- Session tab `SubPreview`/`SubPreviewOverlay`: render `state.latestCaptureFrame`
  via Coil when non-null, replacing the `HatchBg` placeholder; keep the
  hatch as the "nothing captured yet this session" fallback, not deleted.
  Real chips (`"SUB 013"`/`"Ha 300s g100"`) derive from the header's own
  fields (`exposure`/`gain`/`bin`) instead of the current literal strings.
- `StatsRow`'s HFR card: real `hfr` from the same latest capture frame's
  header — RMS/SNR stay honest placeholders (§ protocol facts, no wire data
  exists for either regardless of Media channel work).
- Bench `SnapPanel` (Primary cam): render `state.latestCaptureFrame` (or
  `latestFocusFrame` when a focus operation is active — same box, whichever
  is freshest) into the existing 4:3 placeholder box instead of `HatchBg`.
  Guide cam's own `SnapPanel`: `state.latestGuideFrame`.
- Align/PA: `state.latestAlignFrame` into `PaRealSheet`/wherever the
  align-solve preview belongs — first real visual for that flow.

### M4.3 — Frames tab + Room persistence

- New Room setup (`data/FrameDao.kt`, `@Entity FrameEntity`, `AppDatabase`) —
  first Room usage in the repo; every capture frame (`uuid == ""`) gets a
  row: `id, timestampMs, hfr, mean/median/stddev (from header), keep: Boolean
  = true`. Media-channel frames arrive without a stable id — mint one
  client-side (e.g. `System.currentTimeMillis()` + a counter) rather than
  inventing a fake server-assigned one.
- Rebuild the deleted grid UI in `FramesScreen.kt` on top of the Room-backed
  list — real thumbnails (Coil, same frames persisted above, not
  re-fetched), real HFR chips, tap-to-expand keeps `FrameExpandOverlay`'s
  existing shape but reads Room instead of the dead `SimState.frames`
  fixture. Keep/cut toggles write through to Room directly.
- `FRAME_IDS`/`FRAME_HFRS`/`SimState.frames`/`Frame` data class: delete once
  the grid reads Room instead — no fixture fallback needed here (unlike
  Guide/Focus's honest-placeholder pattern), since Frames already ships as
  an honest "not available" card today; real data replaces it outright.

### M4.4 — Guide/Focus/PA real wiring (scope corrected per protocol facts)

- **Guide sheet**: delete the fixture `wiggle()` trace chart and
  `state.rms`/`state.guideStarSnr` entirely — no wire data will ever back
  them. Replace with an honest status readout (`state.wireGuideStatus`,
  already decoded, currently unused here) plus `state.latestGuideFrame`
  (from M4.2) as the one real piece of guide telemetry this protocol can
  ever provide. Same treatment for **Focus sheet**'s V-curve —
  `focusLastBestPos`/`focusLastHfr` fixtures deleted, replaced by
  `state.latestFocusFrame`'s own real `hfr` header field + the already-real
  `eafTemp`/`focusNextAfMin`, left untouched.
- **PA**: add `NewCameraState`-style typed decoding for `new_polar_state`'s
  fuller `vector.*`/`updatedError`/`updatedAZError`/`updatedALTError` fields
  (currently only `stage`/`enabled`/`message` are modeled) — draw a real
  correction-vector indicator in `PaRealSheet` using `latestAlignFrame` as
  the background. Nice-to-have, not exit-blocking for this sub-milestone.
- `EkosEvent.kt`/`EkosEventCodec`: add the `NewCameraState({name,
  temperature})` case (real command, confirmed above) — this is what
  actually feeds any real cooler/dew-related card, not the dead
  `new_temperature`.

### M4.5 — Alerts + Prefs real wiring

- `Commands.kt`: add `OPTION_GET`/`OPTION_SET`.
- `EkosEvent.kt`: add `NewNotification({source: Int, severity: Int, message:
  String, uuid: String})`, decoded per the confirmed real shape/enums above.
- `AlertsSheet`: replace the static `ALERTS` fixture with a rolling
  in-memory list appended from real `NewNotification` pushes (map
  `severity` → this app's existing `AlertIcon`/`AlertStyle` scheme; `source`
  available for filtering later if wanted). No persistence needed — these
  are live-session events, not a history API (confirmed none exists).
- Prefs sheet rules → `option_set`/`option_get`: **blocked on discovering
  real `Options` key names**, which aren't documented anywhere read so far.
  Plan: `option_get` a batch of plausible candidate names live against the
  rig (e.g. guessing from `Options` class member-getter names visible in
  the KStars source, same reflection convention every other
  `*_get_all_settings` already follows) and confirm which respond with a
  real (non-null) value before wiring any specific rule. Scope this as its
  own short live-investigation step before writing `Commands`/setter code,
  same discipline as every other "confirm against source/live, don't guess"
  pass this session.

### M4.6 — Summary + export real

- `SummarySheet`: KEPT/DISCARDED/MED HFR computed from the Room frame table
  (M4.3) — `keep == true` count, `keep == false` count, median `hfr` across
  kept frames. Session-event log: append real `NewNotification` events
  (same stream Alerts now consumes) chronologically instead of the current
  "not wired to anything real" line.
- `export/SessionReport.kt`: swap `frameRows`/`alertRows` from the
  fixture lists to the same Room/notification sources. Add the still-missing
  **FITS list** artifact the milestone's own exit criteria names ("export
  produces log + FITS list") — likely a plain text/CSV manifest of frame
  filenames+metadata alongside the existing HTML, not actual FITS bytes
  (Nocturne never receives raw FITS over this protocol, only JPEG previews).

## Verification

- Unit: `MediaFrame` header-parsing round-trip against literal 512-byte
  fixture buffers per shape (full/reduced/minimal), same fixture-based
  approach as `EsqWriterTest`. `EkosEventCodec` decode tests for
  `NewNotification`/`NewCameraState` with literal JSON fixtures matching the
  confirmed real shapes above.
- Live, real rig: confirm `set_blobs` suppresses/resumes frames as
  documented; confirm each `uuid` tag routes to the right `SimState` field
  during an actual capture/align/focus/guide/dark operation; confirm Room
  frame rows survive an app relaunch; confirm a real notification (trigger
  one deliberately, e.g. a meridian-flip warning) actually reaches
  `AlertsSheet` — and separately confirm `Options::ekosRemoteNotifications()`
  is actually enabled on the rig, since the whole feature is silently dead
  if that server-side gate is off.
- Regression: every existing "M4" honest-placeholder string this session
  audited should either become real or be deliberately deleted (Guide/Focus
  charts) — none should survive unchanged once this milestone closes, or
  that's a sign something was missed, not a sign it's still correctly out
  of scope.

## README updates alongside this work

- §3: Coil/Room move from aspirational to built once M4.1/M4.3 land.
- §6: `MediaChannel.kt` marked built; new `data/FrameDao.kt`/`AppDatabase`
  listed under `data/`.
- §7a: M4 status entry — what's real (capture/align/focus/guide/dark
  preview, Frames grid, notifications) vs. permanently unavailable on this
  protocol (Guide RMS/SNR/drift chart, Focus V-curve, per-sub progress) vs.
  still deferred within M4 itself (Prefs' real rule wiring, pending the
  `Options` key-name investigation; PA's richer vector-graphic overlay).
- §8: new bullets — the Media channel's 3-header-shape reality (not the
  README's original 1-shape summary), the guide/focus telemetry absence
  (confirmed dead-end, not "not yet wired"), `new_temperature`'s dead-command
  status vs. real `new_camera_state`, `capture_get_sequences`'s confirmed
  shape (still no per-sub progress), `new_notification`'s confirmed shape
  and its server-side opt-in gate.
