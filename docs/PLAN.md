# Future plan (draft)

**Draft, still being discussed** — plan 1 is built and merged into `main`, plans 2 and 3 are not; items are added as discussions go.

| # | Plan | State |
|---|---|---|
| 1 | Home tab and tab layout revision | M1–M6 built, installed and merged into `main` (2026-10-25); rig checks left, listed in `docs/STATUS.md` |
| 2 | Controls tab revision | to be planned |
| 3 | Frames tab revision | proposal written, open decisions below |

The three are independent: plan 1 only reorders the tabs and moves the old Session content;
Controls and Frames keep their content until their own plans are built.

---

# 1. Home tab and tab layout revision

## Why

The night of 2026-10-23 showed the gap: the M31 flip failed and the session stalled for 26 minutes
with nothing on the phone saying so, and the morning power cut left almost nothing to explain it
(only the Ekos analyze files survived). The app should show what KStars, Ekos, the drivers and the
Pi are doing, keep that history on the phone, and tell the user when something goes wrong. The
Session tab, today's first page, only displays the progress of the sequence, so it is better placed
inside Sequence, leaving the first page free for the rig overview.

## Tab layout

Decided in discussion; not built. Constraints: six tabs already fill the portrait bottom bar, so no
seventh tab; the landscape layout keeps the vertical tab rail (no horizontal tab bar).

**Order: Home | Gear | Controls | Plan | Sequence | Frames** — the order of a night: connect and
check the rig, set it up, test it, choose the target, run, review. The label stays **Gear**. The
app opens on Home after connecting (today it opens on Session).

- **Session becomes Home**: the rig overview, status and logs described below.
- **Sequence absorbs the rest of the old Session tab**: a running job's progress card on top
  (preview, stats, flip banner, End session), the queue below. Idle, the queue comes first and the
  progress card is absent.
- **Gear** keeps rig setup only: profile editing, devices, scopes, optical trains (Ekos control and
  rig maintenance move to Home).
- **Log** is not a tab: a section of Home.
- Plan, Controls and Frames keep their content (revised in plans 2 and 3).

Consequences to handle when building:

- Notification taps and the "Back to session" flow after End session point at Sequence/Home, not
  the old Session tab; the Summary sheet stays.
- Controls and Sequence end up two tabs apart; if jumping between them at night is awkward, add a
  shortcut from the Sequence progress card to Controls.

## What exists today

- **Module status row** (top of every screen): Scheduler, Mount, Camera, Focus, Guide, Align, from
  each module's `new_*_state` push.
- **Alerts**: Ekos `new_notification` events (flip started/failed, capture failed, INDI server
  messages), also in the session report.
- **Scheduler log**: decoded from `new_scheduler_state.log`, not shown anywhere.
- On the Pi (2026-10-24): persistent journal, pier guard mount-event log
  `~/nocturne-logs/mount-events.log` (pier side, slew/track/park, OnStep errors, driver messages,
  stuck-slew warning).

## What EkosRemote sends (fork 3.8.3)

| Source | On the wire? |
|---|---|
| Capture / Guide / Focus / Align / Scheduler logs | Yes — `{"log": ...}` in each module's state push |
| Ekos main log (INDI start/stop, devices online, driver timeouts) | No (M5 adds it) |
| Mount module log (slews, limits, park) | No (only `pierSide`; M5 adds it) |
| INDI driver messages (e.g. OnStep "[WARNING] Slew error") | Yes, as `device_message`, only with the notifications option on and only lines with a level tag (found while building M5) |
| KStars itself (crash, restart, blocking modal dialog) | No |

**Problem:** each module log push resends the module's **entire** history on every new line
(`Capture::getLogText()` = `m_LogText.join("\n")`, never trimmed), so traffic grows quadratically
over a night. The app should not consume it as-is.

## Home page layout

The first page after connecting: a glanceable rig overview that never needs a long scroll; deeper
views sit one tap away. The old Session tab becomes Home (its job progress moves to Sequence, see
"Tab layout" above).

Top to bottom:

1. **Night arc** — dusk to dawn with the "now" dot; it describes the night, not a job, so it comes
   first. A sky-and-site card (roof, ambient, dew, SQM, cloud) is dropped: nothing on the wire
   supplies those values.
2. **Ekos** — start/stop Ekos, the profile picker when it is stopped, readiness when it runs (moved
   from Gear). Must work with Ekos not running, the state you land in at the rig.
3. **Rig health** — a compact card: the verdict naming the likeliest failure, a line of link
   figures, mount and cooler state, a Pi power warning. Tap opens the **Rig health sheet** with every
   link, module and Pi figure (the long form is too long for Home).
4. **Session** — target, subs done/planned, time left, guide RMS; tap opens Sequence.
5. **Alerts** and **Log** — counts and latest lines; the Log card opens the full Log screen.
6. **Rig maintenance** (reboot, shut down, time and GPS sync; moved from Gear) and **Disconnect**.
   The Connect screen keeps its own reboot/shut-down section for when there is no connection yet.

## What it needs

### Rig health

Section of the Home page, laid out as a chain, so it is clear where it breaks:

**Phone ↔ Pi link → KStars → Ekos profile → indiserver → each driver → each module**

- **Phone ↔ Pi link** (top row, e.g. `Wi-Fi ▂▄▆ · Pi up · KStars ✓ · 45 ms`), parts that fail
  independently:
  - Wi-Fi: phone joined to the rig network, signal strength.
  - Beacon (UDP 50505): the Pi is up and on the network even if nothing else answers.
  - EkosRemote websocket (9000): connected/reconnecting, time since last message, round-trip time.
  - Media channel: images flowing or stuck.
  - Maintenance daemon (9001): the Pi's OS answers even when KStars doesn't.

  Distinguishes: phone out of range (Wi-Fi weak/lost) · Pi lost power (beacon gone) · KStars
  crashed/stopped (beacon + daemon fine, 9000 refused → restart KStars, not reboot) · KStars frozen,
  e.g. stuck on a modal dialog (connection open, no messages).
- **Per module**: state plus last change ("Guide: Guiding · 0.62″ RMS · 2 min ago").
- **Pi health** from the 9001 daemon (extended `/health`): CPU temperature, undervoltage/throttling
  flags (`vcgencmd get_throttled`), SD and USB free space, uptime, clock in sync.

### Log screen

- A section of Home with the latest lines and a link to the full screen. The full screen is one
  merged, timestamped timeline of all sources; filter chips per module; "warnings/errors only"
  toggle; search; pause/follow; copy/share an excerpt.
- **Stored on the phone** (Room, like Frames) so a night's history survives an app restart, a Pi
  reboot or a power cut.
- On demand, fetch the Pi's own logs through the daemon: mount-event log, journal excerpt.

### Fork changes (EkosRemote)

- Push **one new line at a time** (`{module, time, level?, text}`) instead of the full history;
  keep the old push for compatibility. Add a `get_logs` snapshot for (re)connect.
- Forward the **Ekos main log** and the **Mount** module log.
- Forward **INDI driver messages** with their level (INFO / WARNING / ERROR).

### Phone notifications

Alerts and errors (flip failed, slew stuck, guiding lost, undervoltage, disk low) as Android
notifications with sound while the phone is in a pocket. Needs the app to keep running in the
background (foreground service, small persistent notification).

## Decisions

All settled:

1. Log screen: a section of Home, full timeline one tap away.
2. Fork changes: yes — a new per-line log push alongside the old one.
3. Android background notifications: yes — foreground service, with an on/off switch.
4. Log retention on the phone: kept until deleted (a Clear log action, and delete by night).

## Milestones

The tab-layout change creates the page that the status and log work fills, so both are built
together. Ordered so each milestone is usable on its own and the fork-dependent work comes last.
Every milestone ends compiled, unit-tested and installed on the phone; "rig check" items are
verified on the rig and noted in `docs/STATUS.md` until then.

| # | Milestone | Needs | Done when |
|---|---|---|---|
| M1 | **Tab shell** | app only | Order is Home \| Gear \| Controls \| Plan \| Sequence \| Frames and the app opens on Home. Sequence shows the running job's progress card (preview, stats, flip banner, End session) above the queue. Notification taps and "Back to session" go to Sequence/Home. The old Session tab is gone. |
| M2 | **Home with today's data** | app only | Home shows the Ekos card (start/stop, profile, "Ekos not running → Start" with Ekos down), the session one-liner, night arc, alert count with the latest lines, and rig maintenance (reboot, shut down, time and GPS sync). Gear has only profile editing, devices, scopes and trains. |
| M3 | **Rig health** | app + daemon | Home shows the connection chain (Wi-Fi, beacon, EkosRemote, media, daemon) with per-module state and "last change" times. The daemon's `/health` is extended with CPU temperature, throttling, SD/USB free space, uptime and clock; reinstalled on the Pi. |
| M4 | **Log, phone-side** | app only | Per-module log pushes (full history each time) are diffed to new lines only; with alerts and connection events they go into a Room-backed, timestamped store. Home shows the latest lines; the full Log screen has module filters, warnings-only, search, pause/follow, copy/share. Kept until deleted: Clear log and delete-by-night. |
| M5 | **Fork: per-line logs** | fork build + rig deploy | One-line-at-a-time log push, a `get_logs` snapshot on connect, and the Ekos main log, Mount module log and INDI driver messages with levels. The app prefers the new push and falls back to M4's diffing on the old fork. |
| M6 | **Notifications** | app + foreground service | Alerts and errors (flip failed, slew stuck, guiding lost, undervoltage, disk low) reach the phone while it is in a pocket, with a small persistent notification while the service runs and an on/off switch in the app. |

**Built, 2026-10-25** — all six are compiled, unit-tested and installed; M5's fork build is deployed
on the Pi. Not yet seen on the rig: a real running job on Sequence, an alert arriving on a locked
phone, a night on battery for M6. Found while building them and fixed on the same branch: filter
wheel after an Ekos stop/start, camera properties defined late (cooler, fan, sensor), the cooler
button, the End session/Finish procedure, and Stop Ekos (below). Merged into `main`
(fast-forward) on 2026-10-25.

**Stop Ekos (added after M6).** Ekos' Stop kills the INDI drivers without disconnecting the devices,
so a camera is never closed and its cooler keeps running; Ekos' own screen locks Stop until
Disconnect is done. Home now has a "Disconnect devices" button (CONNECTION=DISCONNECT per device) and
Stop Ekos stays locked until every device reports disconnected. App only, no fork change.

## Order and dependencies

- M1 → M2 → M3 are sequential: each fills the page the previous one created. M4 can start once M2
  exists; it doesn't wait for M3.
- M5 is the only milestone that needs a fork build (about 15 minutes) and a KStars restart on the
  Pi, so it waits for an idle rig and your go-ahead. M1–M4 touch the phone and the daemon only.
- M6 follows M3 (it reuses the health checks for undervoltage and disk alerts) and M5 (driver and
  mount warnings are worth a notification only once they reach the phone).

---

# 2. Controls tab revision

To be planned. Inputs so far:

- Controls is one very long scroll (about 7,000 px in portrait): Primary camera, Cooler, Focuser,
  Plate solve, Align accuracy, Rotator, Align settings, Mount, Guide, Polar alignment.
- Idea: collapsible per-module sections, remembering which are open. No horizontal tab bar (the
  landscape rule: vertical rail only), so any jump list must be vertical.
- Idea: pin the active module (Guide while guiding, Focuser while focusing) to the top.
- Idea: remember the last cooler set point, cooler/fan state and exposure/gain/bin on the phone (the
  app keeps none of them today; it re-reads Ekos at connect, and the camera forgets its cooler on a
  restart). Re-sending the cooler settings would need a confirm, since it changes the rig.
- Cooler card: the caption under "Enforce temperature" and the unlabelled power bar were removed
  (2026-10-25); the power % stays in the card header.
- The 2026-10-24 screenshot fixes already covered the smaller Controls defects (EXP box following
  Ekos, blank bin chip, cooler state); see `docs/STATUS.md`.

## To be added

(Contents, order and per-section behaviour from upcoming discussions.)

---

# 3. Frames tab revision: file manager for the Pi

## Why

The Frames tab only shows the JPEG previews the phone received during capture. The real data —
FITS files — lives on the Pi (SD card, plus USB thumb drives when attached), and there is no way
to see, organise or clean it up from the app: everything needs SSH today.

## What exists

- **App**: previews stored on the phone (Room + JPEG files), keep/discard flags, HFR across the
  run, session summary/export. Nothing reaches the Pi's files.
- **EkosRemote (stock)**: `file_directory_operation` with `list` (name, path, size, isFile,
  created, modified), `create` (mkdir), `remove` (recursive delete of **any** path, no
  restriction) and `exists`; `file_default_path`. No copy, move, single-file delete, drive list or
  progress.
- **Media channel**: `Media::sendFile(filename, uuid)` already loads a FITS file on the Pi and
  sends a JPEG preview (the same path capture previews take) — not yet reachable by a command.
- **Pi**: captures go to `/home/soo/<target_job_timestamp>/<FrameType>/*.fits` on the SD card; the
  USB stick mounts at `/mnt/astro` (FAT32, `nofail`); no numpy/astropy on the Pi (and nothing gets
  installed over the rig's SIM uplink).

## Proposal

- **Browse** the Pi's storage: SD capture folder and every attached USB drive (with free space),
  folders by target/job, files with size, date and FITS header summary (filter, exposure, gain,
  frame type, HFR when present).
- **Preview** any FITS file as a small JPEG, generated by KStars on the Pi (`Media::sendFile`).
- **Manage on the Pi**: copy and move between the SD card and USB drives, rename, delete
  (files or folders), create folders; long copies with progress and cancel; a free-space check
  before copying; confirmation for deletes and moves.
- **Manage on the phone**: delete stored previews, keep/discard, export (as today).
- **Safety**: operations limited to allowed roots (capture folder, `/mnt/*`, `/media/*`) — the
  stock `remove` would delete any path; never touch a folder the running capture is writing to;
  USB drives that disappear mid-operation fail cleanly.
- **No bulk image transfer over the network**: copies and moves run on the Pi itself; only
  listings, metadata and small preview JPEGs travel to the phone. *(Assumed reading of
  "now allowed to copy over network" as "not allowed" — to confirm.)*

## Where it could live

- **Fork (EkosRemote)**: `file_*` commands for copy/move/delete-file/rename with progress pushes,
  a drive list (`QStorageInfo`), allowed-roots check, and a `file_preview` command wrapping
  `Media::sendFile`. Keeps everything on one connection and gets FITS decoding for free.
- **Or the maintenance daemon (9001)**: file listing/copy/move/delete in Python (stdlib), works
  even when KStars is down; previews would still need KStars (no FITS decoding in stdlib).

## Open decisions (file manager)

1. Confirm: no image transfer to the phone over the network (on-Pi copy/move only)?
2. File operations in the fork, the daemon, or split (daemon for files, fork for previews)?
3. ~~Where it lives in the app~~ — decided: the Frames tab (last in the tab order), with a "Phone"
   view (stored previews, keep/discard, as today) and a "Rig storage" view (the Pi file manager).
4. FITS header summary in listings: read on the Pi per file (cost on large folders) or on demand?
