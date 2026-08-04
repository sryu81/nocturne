# M3 — Profiles/devices live, Plan/Sequence tabs operate real Ekos

## Context

M2 (done) built the transport (`EkosRemoteClient`, envelope codec, connect
screen, reconnect/backoff) and split `SessionController` into
`AbstractLocalSessionController` (shared local-state methods) +
`SimulatedController` + `EkosRemoteController`. Deliberately, M2 shipped
**zero real command-sends** — every method inherited by `EkosRemoteController`
still only does a local `_state.update{}`, and the 9 wire-mirror fields it
does populate (`wireCaptureStatus` etc.) are read by nothing in the UI yet.

M3, per README's milestone table: *"Profiles/devices/Plan/Sequence live:
profile start/stop, device list + property sheets, `astro_*` lookups,
capture sequences — Plan + Sequence tabs operate real Ekos end-to-end."*
This is the milestone where `EkosRemoteController` starts actually sending
commands and the UI starts actually displaying real data instead of fixture
data, for the first time.

Two research passes preceded this plan: one read both EkosRemote protocol
docs cover-to-cover for every command in scope; one surveyed the current
codebase for exactly what's stubbed and where the real seams are. A third
closed the one real gap the docs left open — the `.esq` sequence-file XML
schema — by reading the actual KStars source checkout at
`~/cc/repo/kstars` (`kstars/ekos/capture/sequencejob.cpp`/`sequencequeue.cpp`).
INDI's `DRIVER_INTERFACE` bitmask (needed to bucket real devices by role)
was likewise confirmed against `~/cc/repo/indi/libs/indidevice/basedevice.h`
rather than assumed. Two product decisions the user made, both taken as
the recommended option:

1. **Job edit lock**: once a `SequenceJob` is synced/running on the real
   Scheduler, its block editor goes read-only in the UI (matches real Ekos —
   there's no live-edit-a-running-job wire primitive either). Stop the job
   to unlock editing again.
2. **Profile vs Optical Train split**: `RigProfile` shrinks to what a real
   Ekos Profile actually is (name + driver selection + connection mode);
   `opticMm`/`guideOpticMm` move fully onto the existing `OpticalTrainCard`/
   `TrainSlot`/`TrainRole` model (which already matches Ekos's real 10-ish-role
   structure), wired via `train_*` commands instead of stuffed into `profile_add`.

## Protocol facts (confirmed against source, not guessed)

**Profiles** — `get_profiles` → `{selectedProfile, profiles: [ProfileInfo,...]}`
(`name, auto_connect, port_selector, mode, remote_host, remote_port, guiding,
remote_guiding_host/port, use_web_manager, drivers: {"<DeviceFamily>":
[label,...]}, scripts`). `profile_start {"name"}` / `profile_stop {}` —
**no direct reply**, watch `new_connection_state.online`. `profile_add`/
`profile_update` — same schema as above, **single atomic call** (unlike
scheduler), auto-replies with a refreshed `get_profiles`. `profile_delete
{"name"}` — silently refused for `"Simulators"` or the active profile, **no
error sent**; detect by diffing the list. `profile_get` is confirmed dead
(computed, never sent) — always use `get_profiles` + client-side filter.

**Devices** — `get_devices` → `[{name, connected, version, interface}]`,
`interface` is INDI's `DRIVER_INTERFACE` bitmask, confirmed values:
`TELESCOPE=1, CCD=2, GUIDER=4, FOCUSER=8, FILTER=16, DOME=32, GPS=64,
WEATHER=128, AO=256, DUSTCAP=512, LIGHTBOX=1024, DETECTOR=2048,
ROTATOR=4096, SPECTROGRAPH=8192, CORRELATOR=16384, AUX=32768` (+ several
newer roles not relevant here) — a device ORs several bits together.
`device_get {"device"}` / `device_property_get {"device","property"}` both
take `compact: bool` — **compact (default) omits min/max/step/format/label/
group/perm**; must pass `compact: false` explicitly to get slider bounds.
Switch/Number/Text vector shapes: `{device, name, state, switches:
[{name,state}]}` / `{..., numbers: [{name,value,min,max,step,format}]}` /
`{..., texts: [{name,text}]}`. `device_property_set {device, property,
elements: [{name, state|value|text}]}` — fire-and-forget, change arrives via
the normal subscribed-property push, not a direct reply.
`device_property_subscribe {device, properties?|groups?}` (neither → all)
is required to get live push updates; pushes always compact. BLOB
properties unreachable over this protocol entirely (documented `// TODO`).

**astro_\*** — `astro_search_objects {jd?, type?, direction?, maxMagnitude?,
minAlt?, minDuration?, minFOV?}` → flat array of name strings (not full
data). `astro_get_object_info {object, exact?}` → `{name, designations,
magnitude, ra0, de0 (J2000 hrs/deg), ra, de (current epoch), object: bool}`.
`astro_get_objects_info {names: [...], jd?, exact?}` → array, same shape
+ `a/b/pa` for DSO catalog objects. `astro_get_objects_riseset {names, jd?,
exact?, days?}` → per-name `{rise, set, transit, altitudes: [49 doubles
every 30min], date}`. `astro_get_almanac {}` → sun/moon rise/set/twilight
for today at the configured site.

**Scheduler + `.esq`** — `scheduler_get_jobs {}` → `{jobs:
[SchedulerJob,...]}` (`name, pa, targetRA/DEC, state, stage, sequenceCount,
completedCount, minAltitude, minMoonSeparation, maxMoonAltitude,
repeatsRequired/Remaining, inSequenceFocus, startupTime/completionTime/
stopTime, altitude, sequence` — `sequence` is a **path**, not inline data).
`state`/`stage` enums confirmed from `kstars/ekos/scheduler/schedulertypes.h`:
`SchedulerJobStatus` (`SCHEDJOB_IDLE=0, EVALUATION=1, SCHEDULED=2, BUSY=3,
ERROR=4, ABORTED=5, INVALID=6, COMPLETE=7`), `SchedulerJobStage` (`IDLE=0`
through `COMPLETE=14`, 15 values — see scheduler docs comment for the full
list if needed later; only `IDLE`/`BUSY`/`COMPLETE`/`ERROR`/`ABORTED` matter
for M3's UI). `scheduler_add_jobs {}` — **empty payload**, adds from
whatever's currently in the Scheduler's own UI form fields (no "edit"
primitive exists — only add-from-current-form-state and
`scheduler_remove_jobs {index}`). `scheduler_start_job {}` — **toggle**, not
exclusively start. `scheduler_save_sequence_file {filedata?, path?}` —
`filedata` optional (no-op if absent), `path` required only if `filedata`
present, resolved relative to the Pi's home dir.

**`.esq` schema** (from `kstars/ekos/capture/sequencejob.cpp`/
`sequencequeue.cpp`, format version `2.6`, written as plain `QTextStream`
XML, decimals always `.`-locale):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<SequenceQueue version='2.6'>
<GuideDeviation enabled='false'>0</GuideDeviation>
<HFRCheck enabled='true'><HFRDeviation>10</HFRDeviation>...</HFRCheck>
<RefocusOnTemperatureDelta enabled='true'>1</RefocusOnTemperatureDelta>
<RefocusEveryN enabled='true'>45</RefocusEveryN>
<Job>
  <Exposure>300</Exposure><Format>Mono</Format><Encoding>FITS</Encoding>
  <Binning><X>1</X><Y>1</Y></Binning>
  <Filter>Ha</Filter><Type>Light</Type><Count>10</Count><Delay>0</Delay>
  <TargetName>NGC 7000</TargetName>
  <GuideDitherPerJob>2</GuideDitherPerJob>
  <FITSDirectory>/home/pi/...</FITSDirectory>
  <PlaceholderFormat>/%t/%T/%F/%t_%T_%F_%e_%D</PlaceholderFormat>
  <UploadMode>0</UploadMode>
  <Properties>
    <PropertyVector name='CCD_GAIN'><OneElement name='GAIN'>100</OneElement></PropertyVector>
    <PropertyVector name='CCD_OFFSET'><OneElement name='OFFSET'>50</OneElement></PropertyVector>
  </Properties>
  <Calibration><PreAction><Type>1</Type></PreAction>
    <FlatDuration dark='false'><Type>Manual</Type></FlatDuration></Calibration>
</Job>
<!-- one <Job> per Block, repeated -->
</SequenceQueue>
```
Nocturne's `Block` fields map directly: `filter→Filter`, `exposureSec→
Exposure`, `subCount→Count`, `gain→Properties/PropertyVector[CCD_GAIN]/
OneElement[GAIN]`, `offset→…CCD_OFFSET/OFFSET`, `binning→Binning/X,Y`,
`ditherEvery→GuideDitherPerJob`. `forceAfOnStart` (Nocturne-only, no Ekos
per-job analog — documented in README §8 already) has no `.esq` field and
stays local-only/cosmetic in M3, same as today.

**Optical Train** — `train_get_all {}` → array of `{id, name, profile,
mount, camera, guider, focuser, filterwheel, rotator, reducer, dustcap,
lightbox, scope, adaptiveoptics}`. `train_set {module, name}` assigns a
train to a module (`capture`/`focus`/`guide`/`align`/`mount`/`darklibrary`).
`train_add`/`train_update`/`train_delete`/`train_reset` — payload shape for
add/update not traced in the docs; all four auto-push a refreshed
`train_get_all` afterward, so treat as fire-and-refresh like profiles.

## 1. `protocol/` additions

`protocol/Commands.kt` — append `const val`s for: `GET_PROFILES` (exists),
`PROFILE_START`, `PROFILE_STOP`, `PROFILE_ADD`, `PROFILE_UPDATE`,
`PROFILE_DELETE`; `DEVICE_GET`, `DEVICE_PROPERTY_GET`,
`DEVICE_PROPERTY_SET`, `DEVICE_PROPERTY_SUBSCRIBE`; `ASTRO_SEARCH_OBJECTS`,
`ASTRO_GET_OBJECTS_INFO`, `ASTRO_GET_OBJECTS_RISESET`; `SCHEDULER_GET_JOBS`,
`SCHEDULER_ADD_JOBS`, `SCHEDULER_REMOVE_JOBS`, `SCHEDULER_START_JOB`,
`SCHEDULER_SAVE_SEQUENCE_FILE`, `SCHEDULER_SET_ALL_SETTINGS`;
`TRAIN_GET_ALL`, `TRAIN_SET`, `TRAIN_ADD`, `TRAIN_UPDATE`.

`protocol/EkosEvent.kt` — new `@Serializable` cases: `Profiles(selectedProfile,
profiles: List<WireProfile>)`, `Devices(devices: List<WireDevice>)` (from
`get_devices`), `DeviceProperty(device, name, ...)` (one per switch/number/
text/light vector — reuse a shared sealed shape mirroring `IndiProperty`,
see §3), `SchedulerJobs(jobs: List<WireSchedulerJob>)`, `Trains(trains:
List<WireTrain>)`, `AstroSearchResult(names: List<String>)`,
`AstroObjectsInfo(objects: List<WireAstroObject>)`,
`AstroObjectsRiseset(entries: List<WireRiseset>)`. Each is a straight
`@Serializable data class` + `protocolJson.decodeFromJsonElement<T>` case in
`EkosEventCodec`, same pattern as the 7 M2 events — mechanical, no new
pattern needed.

## 2. Device catalog + property sheets → live

**New additive `SimState` fields** (never touched by `SimulatedController`,
same discipline as M2's wire-mirror fields): `wireDevices: List<WireDevice>?
= null`. `WireDevice(name: String, connected: Boolean, roles: Set<DeviceRole>)`
where `DeviceRole` is an enum derived from the confirmed bitmask
(`TELESCOPE, CCD, GUIDER, FOCUSER, FILTER, DOME, WEATHER, AO, ROTATOR, AUX,
...`) via a small `bitmaskToRoles(Int): Set<DeviceRole>` helper.

**`indiProps: Map<String, List<IndiProperty>>`** (already exists, already
mutable) gets reused as-is for real devices too, keyed by the device's real
`name` string instead of a catalog key — `EkosRemoteController` populates an
entry here per `device_get`/`device_property_get` response, calling
`device_property_subscribe` once per connected device right after
`get_devices` arrives so pushed updates keep it live.

**`IndiProperty.SwitchProp` reshape** (mechanical, backward compatible):
add `elementNames: List<String> = options` (defaults to today's behavior,
so all 19 `DRIVER_INDI_PROPS` fixture entries keep compiling unchanged).
`setIndiSwitch(deviceKey, propName, index)` on `EkosRemoteController` looks
up `elementNames[index]` to build the real `{name, state: 1}` element
instead of sending a bare position.

**Gear tab / device sheets**: `DevicePickerBody`/`DeviceSheet` branch on
`state.wireDevices != null` — when present (real connection, data arrived),
list real devices bucketed by `DeviceRole` instead of the fixed 9-category
`DEVICES` catalog; `IndiPropertyPanel` (already generically shape-driven,
per README §8's own instruction) needs no change — it already renders
whatever `IndiProperty` list it's given. `DEVICES`/`DRIVER_INDI_PROPS` stay
exactly as-is as `SimulatedController`'s only data source — not deleted,
since the simulator still needs fixture data to render a Gear tab demo.

`EkosRemoteController` overrides: `toggleDevice` → `device_property_set`
connect/disconnect equivalent (confirm exact CONNECTION property name/
elements against `DRIVER_INDI_PROPS`'s own existing entries — every driver
has a standard `CONNECTION` switch vector with `CONNECT`/`DISCONNECT`
elements); `setIndiSwitch/setIndiNumber/setIndiText` → `device_property_set`
with the real element name, plus the existing local `_state.update` so the
UI reflects the change optimistically before the subscribed-property push
confirms it (same optimistic-then-reconcile pattern as always).

## 3. Profile management + Optical Train split

Per the approved split: `RigProfile` (`SimState.kt`) loses `opticMm`/
`guideOpticMm`, keeps `name`/`deviceKeys` (renamed/reused for driver
selection). `state.profiles` gets **directly overwritten** (not additive)
by `EkosRemoteController` from `get_profiles` translation, since it's
already a free-form list the UI reads as its single source of truth (unlike
the device catalog, which stays additive because a fixed const can't be
swapped per-controller).

`startProfile`/`stopProfile` → `profile_start {"name"}` / `profile_stop {}`,
local `ekosRunning`/`activeProfile` update stays as the optimistic UI state
until `new_connection_state.online` confirms it. `finishSetup` → builds a
`profile_add`/`profile_update` payload from the wizard's device-role
picks (`drivers: {"<DeviceFamily>": [label]}`, family derived from each
picked device's `DeviceRole`), sensible defaults for fields the wizard
doesn't expose (`mode: "local"`, `auto_connect: true`, `use_web_manager:
false`, `guiding: 0`) — Setup wizard UI itself is unchanged, only
`finishSetup`'s wire translation is new. `deleteProfile` → `profile_delete`,
diff the next `get_profiles` push against the pre-delete list to detect the
documented silent-refusal case (active/`"Simulators"` profile) and surface
it rather than silently doing nothing.

**Optical Train** becomes real: `OpticalTrainCard`/`OpticalTrainSheet`'s
existing `TrainSlot`/`TrainRole` model (9 roles today — `MOUNT, CAMERA,
ROTATOR, GUIDE_VIA, DUST_CAP, SCOPE, FILTER_WHEEL, FOCUSER, LIGHT_BOX`,
`SimState.kt:259-262`) is close enough to the real train shape
(`mount/camera/guider/focuser/filterwheel/rotator/dustcap/lightbox/scope`
+ `adaptiveoptics`, `reducer`) to keep as-is, adding one role
(`ADAPTIVE_OPTICS`) to match. `setTrainRole`/`setTrainReducer` on
`EkosRemoteController` → `train_set`/`train_add`/`train_update`
(`opticMm`/`guideOpticMm`, formerly on `RigProfile`, move onto the train's
`scope`/`reducer` fields here). New additive `wireTrains: List<WireTrain>? =
null` populated from `train_get_all`, read by `OpticalTrainCard` when
present instead of the locally-built `trainRolePool`.

## 4. Plan tab — `astro_*` live search

`EkosRemoteController` only: search bar (`setQuery`) triggers
`astro_search_objects` (map `state.chips` filter selections to `type`/
`direction`/`maxMagnitude`/`minAlt` params — `PLAN_CHIPS`'s existing
predicate shapes translate directly), then `astro_get_objects_info` on the
returned names to get `ra0`/`de0`/`magnitude`, then
`astro_get_objects_riseset` for the rise/set/altitude-arc data the Plan
card displays. New additive field `wireSearchResults: List<WireAstroObject>?
= null` — `PlanScreen`'s `matches` computed property reads this instead of
filtering the fixture `TARGETS` list when non-null. `Target` gains optional
`ra0`/`de0`/`magnitude` fields (nullable, additive) to carry resolved
coordinates through to `addToSequence`/`SequenceJob.targetRA/DEC` (§5).
`SimulatedController` and its fixture `TARGETS`/chip-filtering stay
completely untouched — this is purely an `EkosRemoteController`-side data
source swap, same discipline as every other real-vs-fake split so far.
User-added custom targets (`addUserTarget`) stay local-only in both modes —
no wire concept for "star this random RA/Dec" exists to sync against.

## 5. Sequence tab — Scheduler + `.esq` wiring

This is the piece the "job edit lock" decision governs. `SequenceJob` gains
an additive `synced: Boolean = false` field (+ `wireIndex: Int? = null` to
remember its `scheduler_get_jobs` position for later remove/toggle calls).

- **Editing** (add/remove/reorder blocks, filter/exposure/gain/etc.) stays
  100% local exactly as today, *while* `synced == false`. `BlocksList`/
  `BlockDetails` composables check `job.synced` and render read-only
  (no drag handles, no +/- steppers, dimmed) when true — same visual
  language `state.ekosRunning` already uses elsewhere for "can't touch this
  right now" states.
- **`toggleJobRun(jobId)`**, on `EkosRemoteController`, when transitioning
  a not-yet-synced job to running: serialize its `Block` list to `.esq` XML
  (new pure function, `session/EsqWriter.kt`, following §"Protocol facts"
  schema exactly — `HFRCheck`/`RefocusEveryN` sourced from the existing
  sequence-wide `afRefocusMin`/`afTempDeltaC`/`afOnFilterChange` fields) →
  `scheduler_save_sequence_file {filedata, path: "nocturne_<jobId>.esq"}` →
  set the Scheduler's own form fields via `scheduler_set_all_settings`
  (`nameEdit` = target name, `sequenceEdit` = the saved path,
  `startupTimeConditionR`/`schedulerAltitude` etc. from sensible defaults —
  minimum viable field set, not the full ~40-field form) → `scheduler_add_jobs`
  → `scheduler_get_jobs` to confirm and capture `wireIndex`, then mark
  `synced = true`. Toggling an already-synced job → `scheduler_start_job`
  (the toggle command) instead of the add flow.
- **`removeJob(jobId)`**: if `synced`, `scheduler_remove_jobs {index:
  wireIndex}` first; if never synced, stays a pure local removal (today's
  behavior, no wire call needed — nothing to remove on the rig).
- **Progress display** (`doneCount`, `currentBlockIndex`): `new_capture_state`
  pushes (already decoded in M2, currently dropped into `wireCaptureStatus`
  only) get cross-referenced against `scheduler_get_jobs`' `completedCount`/
  `stage` for the currently-`SCHEDJOB_BUSY` job to advance `doneCount` —
  approximate (real per-block progress needs `capture_get_sequences`, which
  the codebase survey flagged as having an undocumented shape too) but
  good enough to show forward progress without inventing data.

`SimulatedController`'s job/block behavior is entirely unchanged — `synced`
simply stays `false` forever there, so the lock UI never engages in demo
mode.

## 6. Verification

- Unit-level: `EsqWriter` round-trips against the real `9filters.esq` fixture
  at `~/cc/repo/kstars/Tests/ekos/scheduler/9filters.esq` — parse expected
  values, generate from equivalent `Block`s, diff.
- `EkosEventCodec` decode tests for each new event type, literal JSON
  fixtures per the confirmed shapes above (mirroring M2's approach).
- End-to-end against the same local mock EkosRemote server pattern used to
  verify M2 (extend `mock_ekos_server.py` with `get_profiles`/`get_devices`/
  `device_property_get`/`astro_*`/`scheduler_*` canned responses): confirm
  Gear tab shows the mock's device list instead of the fixture catalog,
  property sheet get/set round-trips, Plan tab search returns mock astro
  results, Sequence tab syncs a job (writes the expected `.esq` XML, sends
  the expected `scheduler_add_jobs` sequence) and locks its editor once
  "running."
- Regression: `SimulatedController` path (all of Gear/Plan/Sequence in demo
  mode) must render pixel-identical to pre-M3 — this is the main risk given
  how much shared UI code now branches on wire-field presence.

## README updates alongside this work

- §6: mark new files under `protocol/`/`session/`/`ui/` as built.
- §7a: M3 status entry — what's live (profiles/devices/property sheets,
  Plan search, Sequence sync+lock) vs explicitly still deferred (full
  `capture_get_sequences`-based per-sub progress, `scheduler_set_all_settings`'s
  full ~40-field form beyond the minimum set, mDNS still N/A, Media channel
  still M4).
- §8: new bullets — INDI interface bitmask now confirmed (cite
  `basedevice.h`), `.esq` schema now confirmed (cite `sequencejob.cpp`/
  `sequencequeue.cpp`), the Profile/Optical-Train split decision and why,
  the job-edit-lock decision and why, and the approximate-progress caveat
  for `doneCount` pending real `capture_get_sequences` modeling.
