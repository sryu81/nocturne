# Nocturne

Android app that remote-controls a real astrophotography imaging rig from the field —
target selection, framing, capture sequences, guiding, focusing, polar alignment, frames
review, alerts, session summary. It's the client for **EkosRemote**, a fork of KStars:
the Pi runs KStars + INDI + Ekos and listens on the local network; the phone connects
directly and drives everything. No cloud, no relay — just the phone and the rig on the
same LAN.

**Real-rig only.** There's no simulator or demo mode — the app boots straight to a
connect screen and needs a real Pi running the EkosRemote fork to do anything.

Six tabs: **Session · Plan · Sequence · Frames · Gear · Controls**.

| Session | Plan | Sequence |
|---|---|---|
| _screenshot pending_ | _screenshot pending_ | _screenshot pending_ |
| Live night arc, sub preview, HFR/RMS/SNR, meridian flip, per-module status | Target search, real altitude chart (+ Moon), framing, goto/center | Job queue, per-target sequences, real Scheduler sync |

| Frames | Gear | Controls |
|---|---|---|
| _screenshot pending_ | _screenshot pending_ | _screenshot pending_ |
| Capture review, keep/cut, HFR-across-run | Rig profile, optical trains, devices | Camera/Mount/Guide/Align/Focus/PA live control + settings |

_Screenshots aren't in the repo yet — the app has no demo/simulator mode, so a
meaningful screenshot needs a real connected session. Best captured next time there's a
live rig connection; happy to slot them in here once you have some._

## Setup

- **Android Studio** (any recent version) or just the command-line SDK tools — either way
  you need:
  - **JDK 17**
  - **Android SDK Platform 35** (compileSdk/targetSdk here are both 35) + a matching
    build-tools version
  - **minSdk 26** (Android 8.0) — the floor this app supports
- Clone the repo, open it in Android Studio (it'll prompt to sync Gradle), or build from
  the command line — the Gradle wrapper handles the Gradle version itself
  (`gradle-8.11.1`), you don't need Gradle installed separately.
- No `local.properties` setup beyond the usual `sdk.dir` Android Studio writes for you on
  first sync.

## Build

```bash
./gradlew :app:assembleDebug     # debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest       # real JUnit test source set (app/src/test/)
```

Or open the project in Android Studio and hit Run — same thing, GUI-driven.

## Test

### Emulator

```bash
./build_and_run.sh
```
Builds, boots the `Pixel_8` AVD (if not already running), installs, and launches. Needs
that AVD already created once in Android Studio's Device Manager. **Known gotcha**: the
emulator can silently fall back to slow software rendering on some GPUs, breaking plain
taps (drags still work) — `run_emulator.sh` already passes `-gpu host` to avoid this; see
`docs/emulator-troubleshooting.md` if taps still don't register.

Since there's no simulator mode, the emulator will only ever show you the connect
screen and empty/loading states unless it can actually reach a real Pi on the same
network — useful for UI/layout work, not for exercising real data flows.

### Real device

```bash
./build_and_run_a24.sh
```
Same build, installed over USB to a specific paired device (edit the `DEVICE` serial in
`run_a24.sh` for your own hardware — find it with `adb devices` once USB debugging is
enabled and the device is plugged in and authorized).

### Real rig

This is the only way to actually exercise the app — connect the phone (emulator or real
device) to the same LAN as a Pi running the EkosRemote fork, then use the connect
screen's manual host/port entry (`ws://<pi-ip>:9000`). No auth, no discovery — see
`docs/STATUS.md`'s network section for the full real trust model.

## Status

Detailed, continuously-updated tracking lives in **`docs/STATUS.md`** — read that for
what's actually shipped, skipped, or still planned, and why. Condensed summary:

| Milestone | Status |
|---|---|
| M0 — scaffold, theme, nav shell | ✅ done |
| M1 — full UI (originally on a simulator, since removed — see below) | ✅ done |
| M2 — transport, connect screen, reconnect | ✅ done |
| M3 — Plan + Sequence tabs operate real Ekos end-to-end | ✅ done |
| M3.3 — per-module settings sheets (Mount/Camera/Focus/Align/Guide) | ✅ done |
| M4 — Media channel, live previews, Frames tab, real Guide/Focus/PA | ✅ done |
| M4.5 — frame storage/restructure (half A/B: Alerts+Prefs real wiring) | ✅ done · offline plate solver (Part B) not started |
| M5 — Plan tab framing workflow (rotator target-PA, readback, FOV box) | ✅ steps 2–5 done · star chart (step 1) blocked on M4.5 Part B |
| Simulator removal | ✅ done — app is real-rig-only, no demo mode |
| Network hardening (auth, discovery, per-client tracking) | not started — needs the KStars fork touched, see `docs/FORK-BACKLOG.md` |

Real fork-side work (guide RMS/drift, a graceful Scheduler pause, mDNS auto-discovery,
auth) is tracked separately in **`docs/FORK-BACKLOG.md`**, with exact wire contracts for
each — none of it is buildable from this repo alone.

## Other docs

| Doc | Role |
|---|---|
| `docs/STATUS.md` | Full status tracker — read this for "is X done yet." |
| `docs/FORK-BACKLOG.md` | Real KStars-fork-side work, with exact wire contracts. |
| `EkosRemote-Client-Guide.md` | Wire protocol: channels, envelope, lifecycle. |
| `EkosRemote-Command-Reference.md` | Every command/push and its exact payload. |
| `EkosWebSocket-Fork-Design.md` | Why the fork exists, historical design record. |
| `docs/emulator-troubleshooting.md` | Emulator-specific gotchas. |
