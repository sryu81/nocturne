# App-side feature backlog

Nocturne-only ideas/toggles that don't map onto a single real Ekos wire call —
either because real Ekos has no equivalent at all, or because wiring them for
real needs app-side orchestration (watching pushes, detecting a moment, then
firing a real command) rather than a straight settings round-trip. Collected
here as they're found rather than implemented immediately, so they don't get
lost once M2/M3 core Ekos integration work moves on. Add to this list; don't
implement from it without checking in first — several entries need live
protocol investigation before any code is written.

## Autofocus at block start (per-block)

**Status: real gap confirmed; the stub UI was removed 2026-08-23 (user's own call — "this will
be handled after M4"), rather than left in place as dead/no-op. Nothing to pick back up in the
UI when this is eventually built for real; start from this doc entry instead.**

The old per-block `Block.forceAfOnStart` toggle (Sequence tab, block editor) had
no real Ekos equivalent at any level — confirmed against real KStars source
(`message.cpp:542,546`, README §8): `capture_set_all_settings` writes
straight through to *global* `Options` (`enforceRefocusEveryN`,
`hFRDeviation`, `maxFocusTemperatureDelta`, ...), no job index, applies once
for the whole running queue. It was always a deliberate Nocturne-only
addition, meant to fire a standalone `focus_start` right as that specific
block begins.

To wire it for real: Nocturne itself has to detect "this block just started
capturing" and fire `focus_start` at that moment, for any block flagged on.
That detection path is `new_capture_state` cross-referenced with
`capture_get_sequences`'s current job index — and `capture_get_sequences`'s
actual reply shape is **still undocumented** in this codebase (also flagged
separately for real per-block progress, see `EkosRemoteController`'s
`distributeCompleted`/`applySchedulerJobs` doc). Needs a live-rig protocol
investigation (watch `capture_get_sequences` while a real sequence runs)
before any implementation.

Alternative considered and rejected for now: patching KStars/Ekos's own C++
source to add a genuine per-sequence-step autofocus field. Not pursued — a
much heavier commitment (Qt/C++ work on a separate codebase, building and
deploying a custom KStars on the Pi, re-patching on every future KStars
upgrade), inconsistent with this app staying purely a wire-protocol client.

**Adjacent, smaller, already-wireable finding** (different granularity, not
a fix for the above): each real `SchedulerJob` carries its own
`inSequenceFocus: Boolean` (`WireSchedulerJob.inSequenceFocus`) — a
per-*job* snapshot of the Scheduler's `schedulerFocusStep` setting at the
moment that job was added. Setting `schedulerFocusStep` right before
pushing a specific job would give that job real "autofocus at job start" —
genuinely per-job (per target/session), purely over the existing wire
protocol, no orchestration needed. This is coarser than per-block (whole
job, not a specific mid-sequence filter/exposure block) but worth doing on
its own if a per-job version of this toggle is ever wanted.

## Setting real filter wheel slot names, and wiring the block picker to them

**Status: done, live-verified.**

The Device detail sheet's generic "INDI Controls" panel (Gear tab → tap the
filter wheel device) showed `FILTER_NAME` but wasn't usably editable — two
real bugs found live, both fixed:
1. `IndiProperty.TextProp` only ever kept the *first* element of a
   multi-element text vector — `FILTER_NAME` has one element per filter
   slot, so every slot past the first was silently dropped. Now holds all
   elements (`elements: List<Pair<String, String>>`, same shape
   `LightProp` already used), and the panel renders one editable row per
   element — a real numbered table, not a single field.
2. Typing more than one character didn't register — the field was bound
   straight to the live server value, and a real INDI echo/refresh landing
   mid-keystroke snapped it back to the stale value, fighting typing one
   character at a time. Fixed with a local text buffer decoupled from the
   live prop (`IndiTextElementRow`), same "clear-and-retype" fix this
   codebase already used for numeric fields (`NumberField` etc.) — just not
   yet applied here.

Separately, the block editor's own filter *picker* (`FILTER_CYCLE`) now
prefers real slot names too: new `SimState.realFilterNames` (looked up via
the filter wheel's real device name, not the `"efw"` role key) feeds
`cycleBlockFilter` (now takes an explicit `names: List<String>` rather than
hardcoding the fixture), plus the same real-vs-fixture fallback in
`addBlock`/`addToSequence`'s default-filter picks. Fixture `FILTER_CYCLE`
stays the fallback whenever the wheel isn't connected or hasn't reported
names yet.

## (add more entries here as found)
