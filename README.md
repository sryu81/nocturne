# Nocturne — Android astrophotography imaging app

## 1. What this is

`Nocturne` is a native Android app that remote-controls a real astrophotography imaging
rig from the field. It's the client for **EkosRemote**, a fork of KStars: the Pi runs
KStars + INDI + Ekos and listens on the local network; the phone connects directly and
drives everything — target selection, framing, capture sequences, guiding, focusing,
polar alignment, frames review, alerts, session summary.

**Real-rig only.** There is no simulator or demo mode — `SimulatedController` (the
original M1 fixture-data driver used to build the UI before a real Pi existed) was
deleted outright once the app went real-rig-only; every screen either talks to the real
wire or honestly says a given feature isn't wired yet. The app boots straight to a
connect screen; there's no escape hatch to a fake rig.

Six tabs: **Session / Plan / Sequence / Frames / Gear / Controls** (`ui/nav/NocturneTab.kt`
is the actual current list). Gear is rig topology/setup (profile, devices, scopes,
optical trains); Controls is per-module operational settings + live control
(Camera/Mount/Guide/Align/Focus/Polar Alignment) — split out of Gear once that tab
started accumulating both concerns.

### Where to look for what

| Doc | Role |
|---|---|
| **`docs/STATUS.md`** | Single source of truth for what's shipped, skipped, superseded, or still planned — the real status tracker. Read this, not this README, for "is X done yet." |
| **`docs/FORK-BACKLOG.md`** | Real features/fixes that need the KStars fork itself touched (not buildable from this repo alone) — exact wire contracts (command names, JSON shapes, C++ signatures) for each, confirmed against the fork source. |
| `EkosRemote-Client-Guide.md` | Wire protocol: channels, envelope, lifecycle. Authoritative for connection semantics. |
| `EkosRemote-Command-Reference.md` | Every command/push and its exact payload. Authoritative for payload shapes — kept current as real gaps are found (several this session alone). |
| `EkosWebSocket-Fork-Design.md` | Why the fork exists, what it replaced on the Pi side, and the design decisions behind its current shape. Historical record now (the fork is built and running), not a forward-looking plan. |
| `docs/emulator-troubleshooting.md` | Known emulator-specific gotchas (GPU selection, rotation/coordinate quirks) — irrelevant once testing against a real device/rig. |

This file stays a short current-state overview + architecture map. Detailed
shipped/planned tracking lives in `docs/STATUS.md` on purpose — this doc drifted badly
out of sync with reality once before (an old M0–M4 milestone table and status narrative
that stopped mid-M4 while M4.5/M4.6/M5 shipped after), and the fix isn't to try harder at
keeping two trackers in sync, it's to only have one.

## 2. Protocol model

- Pi listens on a hardcoded port **9000**, always on while KStars runs. **No auth, no
  discovery, trusted-LAN only** — confirmed by reading the fork source directly, not
  assumed. See `docs/STATUS.md`'s "Network discovery, connection & authentication"
  section for the full real trust-model writeup, and `docs/FORK-BACKLOG.md` for what a
  real auth/discovery fix would actually need (fork-side, not app-side).
- Two independent WebSocket connections:
  - **Message** `ws://<pi-ip>:9000/message/ekos` — JSON commands + state pushes.
  - **Media** `ws://<pi-ip>:9000/media/ekos` — binary frames: a JSON metadata header +
    JPEG bytes (real capture/align/focus/guide frames, M4.1/M4.2).
- Envelope: `{"type": "<command>", "payload": {...}}`.
- Broadcast semantics: no per-client request→response correlation. State is
  push-driven — a real connect fetches `get_states`/`get_devices`/`get_profiles`/every
  module's `*_get_all_settings` to bootstrap the whole UI, then everything after that is
  optimistic-local-update-then-reconcile-against-the-next-push, never an awaited reply.
- One client slot per channel — **a second device connecting silently displaces the
  first**, no warning either side (real gap, tracked in `docs/STATUS.md`).
- Client owns reconnect/backoff.

Don't trust a command list here — `EkosRemote-Command-Reference.md` is the authoritative,
continuously-corrected one. This session alone found and fixed 2 real doc gaps
(`align_set_target_pa` was entirely undocumented; `astro_get_objects_riseset`'s `exact`
flag was undersold) by reading the fork source directly rather than trusting the
existing reference — a standing reminder that even the "authoritative" doc needs
re-checking against source when something doesn't add up.

## 3. Tech stack (real, confirmed against `build.gradle.kts`)

- Kotlin, Jetpack Compose — Material3 provides base primitives (`Text`, `Icon`), the
  actual visual design is a custom Nocturne theme layered on top (tokens ported from the
  original design handoff), not stock Material.
- OkHttp for both WebSocket channels; kotlinx.serialization for the envelope/JSON.
- Room (`kapt`, not KSP — see `docs/STATUS.md`'s M4 detail for why): real frame index
  (`FrameEntity`/`FrameDao`), on-disk JPEGs referenced by path, not blobbed in the DB.
- DataStore Preferences: connection settings, local job-queue persistence.
- Compose Canvas for every chart — no chart library. Real ones today: night arc,
  altitude curve (target + Moon), per-solve pointing-accuracy trend, HFR-across-run.
- **No image-loading library** (Coil was mentioned in an old planning note but was never
  actually added as a dependency) — real JPEG frames decode via plain
  `BitmapFactory.decodeByteArray`/`decodeFile`.
- A real JUnit test source set exists (`app/src/test/`) — an early planning doc's "no
  tests exist yet" claim is stale.

## 4. Architecture

```
UI (Compose screens/sheets)
   │  ViewModel observes state
Session layer: SessionController interface
   │  one StateFlow<AppState>, ~90 command methods
Protocol: typed EkosEvent models (commands + pushes), EkosEventCodec
   │
Transport: EkosRemoteClient (Message) + MediaChannel (Media) — OkHttp WebSockets
   │
   ▼
EkosRemote fork of KStars, running on the real Pi
```

- **`SessionController`** has exactly one concrete implementation now:
  `EkosRemoteController`, extending `AbstractLocalSessionController` (a base class
  holding every method that's pure local UI/state — sheet nav, block editing, prefs —
  with no real-time wire dependency). `EkosRemoteController` overrides whichever of
  those have since gained a real wire-command implementation; the rest stay local-only,
  either because they're genuinely local concerns or because no matching wire command
  exists at all (`docs/STATUS.md`/`docs/FORK-BACKLOG.md` track which is which).
  (Historical: this base class used to also be `SimulatedController`'s entire behavior;
  that class was deleted once the app went real-rig-only — the base class stayed, only
  the "shared with a fake driver" framing is gone.)
- **`AppState`** is the single state object every screen reads from — real wire-derived
  fields are named `wire*` (`wireCaptureStatus`, `wireAlignSolution`, `wireNotifications`,
  ...) by convention, distinct from pure local UI state fields.
- **Protocol decode norm, learned the hard way, repeatedly**: a too-strict required-field
  model on a new `EkosEvent` type silently degrades real pushes to a `Raw` fallback the
  instant the server sends a shape slightly different than first assumed. Every real
  event model defaults every field and merges non-null on arrival rather than requiring
  fields up front — this exact mistake has been made and fixed 4 separate times across
  new event types (a 5th, Guide's own RMS/drift, is a known real gap tracked in
  `docs/FORK-BACKLOG.md` rather than shipped yet); treat it as a standing rule for the
  next new event type, not a one-off lesson.
- **Real bug classes worth knowing about before adding new features**, both found by live
  testing rather than reasoning about the protocol on paper: (1) a fixture fallback list
  used unconditionally instead of `realX ?: fixtureX` silently breaks the moment real data
  disagrees with the fixture, in a way that looks like plausible-but-wrong data rather
  than an obvious error; (2) a wire reply's own `"name"`/identifier field isn't always
  byte-identical to what was requested unless an `exact`-style flag is explicitly set —
  confirmed causing a real, hard-to-notice bug (a target's real altitude chart silently
  showing a *different* target's real data).

## 5. Repository layout

```
app/src/main/java/com/nocturne/
  transport/     EkosRemoteClient (Message), MediaChannel, ReferenceImageClient
                 (a real DSS-cutout fetch for Plan tab framing — the one thing in this
                 app that talks to the internet instead of the Pi)
  protocol/      Envelope, Commands, EkosEvent, EkosEventCodec, Wire* payload shapes
  session/       SessionController, AbstractLocalSessionController, EkosRemoteController,
                 AppState, EsqWriter (.esq XML serializer)
  data/          ConnectionSettings/ConnectionRepository (DataStore), FrameEntity/FrameDao
                 (Room)
  export/        Session report HTML export
  ui/            theme/, nav/, session/, plan/, sequence/, frames/, gear/, controls/,
                 connect/, components/ (charts, cards, chips, sheets host)
app/src/test/    real JUnit test source set — codec/transport tests, EsqWriter
                 round-trip test
pi-tools/        reboot-daemon/ — a small companion HTTP+token daemon on the Pi,
                 entirely separate from the EkosRemote channel (recovers a hung Ekos
                 process without depending on that same process being alive)
```

## 6. If you're picking this up fresh

1. Read `docs/STATUS.md` top to bottom — it's the actual state of the app, organized by
   milestone, with a "Detail" section explaining the non-obvious findings behind each.
2. Skim `docs/FORK-BACKLOG.md` if you're going to touch the Pi/KStars side at all.
3. `EkosRemote-Command-Reference.md`/`EkosRemote-Client-Guide.md` for wire specifics —
   but verify against `/home/soo/cc/repo/kstars` directly (the real fork source) before
   trusting either as complete for anything you're about to build against; both have had
   real gaps found and fixed this way repeatedly.
4. This app has no simulator to test against — verifying anything beyond a compile
   means either a real Pi connection or careful reading of the fork source to reason
   about what a real push/reply would look like.
