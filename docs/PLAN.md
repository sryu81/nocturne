# Future plan (draft)

**Draft, still being discussed** — nothing here is built yet; items are added as discussions go.

| # | Topic | State |
|---|---|---|
| 1 | Home tab: rig overview, status and logs | decided layout, open decisions below |
| 2 | Frames tab → file manager for the Pi | proposal written, open decisions below |
| 3 | Tab layout | decided in discussion, not built; Home tab, Session merged into Sequence |

---

# 1. Home tab: rig overview, status and logs

## Why

The night of 2026-10-23 showed the gap: the M31 flip failed and the session stalled for 26 minutes
with nothing on the phone saying so, and the morning power cut left almost nothing to explain it
(only the Ekos analyze files survived). The app should show what KStars, Ekos, the drivers and the
Pi are doing, keep that history on the phone, and tell the user when something goes wrong.

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
| Ekos main log (INDI start/stop, devices online, driver timeouts) | No |
| Mount module log (slews, limits, park) | No (only `pierSide`) |
| INDI driver messages (e.g. OnStep "[WARNING] Slew error") | No |
| KStars itself (crash, restart, blocking modal dialog) | No |

**Problem:** each module log push resends the module's **entire** history on every new line
(`Capture::getLogText()` = `m_LogText.join("\n")`, never trimmed), so traffic grows quadratically
over a night. The app should not consume it as-is.

## Home page layout

The first page after connecting: a glanceable rig overview that never needs a long scroll; deeper
views sit one tap away. The old Session tab becomes Home (its job progress moves to Sequence, see
section 3).

1. **Connection chain and Ekos control** — Wi-Fi, Pi, KStars, Ekos, devices with each state (rig
   health, below), plus start/stop Ekos and the active profile (moved from Gear). Must work with
   Ekos not running, the state you land in at the rig: a clear "Ekos not running → Start" card.
2. **Session one-liner** — target, subs done/planned, ETA, guide RMS; tap opens Sequence.
3. **Night arc** and sky-site card (they describe the night, not a job).
4. **Alerts and log** — count and latest lines, link to the full Log screen.
5. **Pi health** — CPU temperature, undervoltage, free disk, uptime, clock (from the maintenance
   daemon).
6. **Rig maintenance** — reboot, shut down, time and GPS sync (moved from Gear). The Connect screen
   keeps its own reboot/shut-down section for when there is no connection yet.

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

## Open decisions (Home, status and logs)

1. ~~Log screen as its own tab or a sheet~~ — decided: a section of Home, full timeline one tap away.
2. Fork changes OK (new per-line log push alongside the old one)?
3. Android background notifications: yes / no?
4. Log retention on the phone: last N nights (e.g. 7) or until deleted?

---

# 2. Frames tab → file manager for the Pi

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
3. ~~Where it lives in the app~~ — decided: the Frames tab, last in the order, with a "Phone" and
   a "Rig storage" view (section 3).
4. FITS header summary in listings: read on the Pi per file (cost on large folders) or on demand?

---

# 3. Tab layout

Decided in discussion; not built. Constraints: six tabs already fill the portrait bottom bar, so no
seventh tab; the landscape layout keeps the vertical tab rail (no horizontal tab bar).

## Tab order

**Home | Gear | Controls | Plan | Sequence | Frames** — the order of a night: connect and check the
rig, set it up, test it, choose the target, run, review. The label stays **Gear**. The app opens on
Home after connecting (today it opens on Session).

## What changes

- **Session becomes Home**: the rig overview, status and logs of section 1.
- **Sequence absorbs the rest of the old Session tab**: a running job's progress card on top
  (preview, stats, flip banner, End session), the queue below. Idle, the queue comes first and the
  progress card is absent.
- **Gear** keeps rig setup only: profile editing, devices, scopes, optical trains (Ekos control and
  rig maintenance move to Home).
- **Frames** gets a "Phone" view (stored previews, keep/discard, as today) and a "Rig storage"
  view (the Pi file manager, section 2).
- **Log** is not a tab: a section of Home.
- Plan is unchanged.

## Consequences to handle when building

- Notification taps and the "Back to session" flow after End session point at Sequence/Home, not
  the old Session tab; the Summary sheet stays.
- Controls is a very long single scroll (about 7,000 px) — collapsible per-module sections, to be
  designed separately (no horizontal tabs, to respect the landscape rule).
- Controls and Sequence end up two tabs apart; if jumping between them at night is awkward, add a
  shortcut from the Sequence progress card to Controls.
