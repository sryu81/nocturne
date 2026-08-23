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

**Status: real gap confirmed, not started.**

The per-block `Block.forceAfOnStart` toggle (Sequence tab, block editor) has
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

## Setting real filter wheel slot names from the app

**Status: likely already works via the generic INDI Controls panel — needs live confirmation, not new code.**

Found alongside the above: the block editor's filter *picker* (`FILTER_CYCLE`
— `Ha, OIII, SII, L, R, G, B`) is a fixed fixture list, identical for every
rig, also reused for the Align/Focus module filter pickers — that part is a
real gap (see below). But the user's actual ask — the app should let you
*set* the real filter wheel's slot names, at the INDI property level — may
already work today, with zero new code: the Device detail sheet (Gear tab
→ tap a device card) already has a fully generic "INDI Controls" panel
(`IndiPropertyPanel`, `Sheets.kt`) that renders *any* real INDI property for
that device, including editable text properties via `ctrl.setIndiText` →
real `DEVICE_PROPERTY_SET` — already wired end-to-end, not a stub. If the
real EFW driver reports `FILTER_NAME` (the standard INDI filter-wheel
property), it should already show up there as an editable field.

**Not yet confirmed live** — check on the rig: Gear tab → tap the filter
wheel device → does "INDI CONTROLS" show a `FILTER_NAME` text field? If
yes, done, just wasn't discoverable. If genuinely missing, that means the
real driver isn't reporting it (or something's filtering it), which is a
different, real investigation — not this same fix.

Separately, still a real gap either way: the block editor's own filter
*picker* (`FILTER_CYCLE`) doesn't read from `FILTER_NAME` even once it's
set for real — it'd need its own fetch-and-populate wiring to stop being a
fixture list. Not started.

## (add more entries here as found)
