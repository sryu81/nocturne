# Nocturne — status

Current state of the app, the Pi-side tools and the rig. Updated 2026-10-24.
History lives in git; this file only says what is true now and what is left.

## What works

**Connection** — rig auto-detected on the LAN (UDP beacon), message + media websockets with
reconnect, one client at a time. Maintenance daemon (port 9001): reboot Pi, set Pi clock, sky-atlas
tiles. GPS location and time sync to KStars.

**Gear** — rig profiles (start/stop Ekos, new/edit/delete). The profile wizard offers every INDI
driver installed on the Pi (`get_drivers`) with search, "used before", grouping by manufacturer;
new profiles start empty and need mount + camera. Optical trains, module ↔ train assignment, scopes
catalog, device list with INDI property panels.

**Plan** — target search (KStars catalogs by type/magnitude, or by name over ~44k names),
"My targets", altitude chart with the Moon, framing card with camera FOV and rotator angle over an
**offline DSS2 sky atlas** (no internet needed; CDS online only as fallback, never on the rig's
network), goto and goto & center, add to sequence.

**Sequence** — job queue owned by the app, written to `.esq` and synced to the Ekos Scheduler;
per-block progress; scheduler settings.

**Session** — night arc, latest sub, HFR/RMS/SNR tiles, module status row (tap for sheets),
alerts from Ekos notifications, session summary and export.

Anything the rig hasn't reported yet shows "N/A" — there are no demo values or placeholders.

**Frames** — captured previews stored on the phone (Room + JPEG files), keep/discard, HFR across
the run. HFR shows "—" when Ekos measured no star.

**Controls** — main camera preview with exposure/gain/bin/filter and the **real output size**
(warns on a cropped capture frame, "Set full frame" fixes and saves it); cooler, fan, enforce
temperature; focuser jog + autofocus with live V-curve; mount motion, goto abort guard, tracking,
park; guiding start/stop/loop/clear-calibration with live RMS and drift chart; plate solve, target
accuracy trend, rotator control; polar alignment (stages, refresh, crosshair); module settings
sheets for Mount, Camera, Align, Guide, Focus.

## Pi-side tools (`pi-tools/`)

| Tool | Does |
|---|---|
| `reboot-daemon` | Port 9001: reboot, set time, serve sky-atlas tiles |
| `pier-guard` | Keeps OnStep Preferred Pier Side = East; mount event log `~/nocturne-logs/mount-events.log`; warns on a slew stuck > 5 min |
| `persistent-journal` | Journal survives reboots and power cuts |
| `hips-mirror` | Downloads the DSS2 HiPS survey (run on the laptop, never on the rig) |

Sky atlas: orders 0–4 are packed into the APK at build time from `~/astro-data/hips/DSSColor`;
order 5 goes to the phone with `tools/push_sky_atlas.sh` (or "Copy from rig" in Gear → Rig
maintenance); the Pi keeps orders 0–5 on the SD card for KStars' offline HiPS. Order 6 is deferred.

## Not yet verified on the rig

- Polar alignment full run under the sky (Done rotating / Start adjusting / Done, Ekos triangle in
  the frames, tap-to-select star).
- 2026-10-24 screenshot fixes (compiled and unit-tested, not run on the phone): signed PAH az/alt
  readout, pinch-zoom on the PAH frame, FOV box sized to the DSS cutout, outline-button border,
  header outline and alert badge, EXP boxes following Ekos, `1000000"` accuracy sentinel.
- Home tab rework on branch `home-tab-revision` (compiled and unit-tested, not viewed on a screen or
  run on the rig): tab order and Home/Sequence (M1–M2), the Rig health card and the daemon's
  extended `/health` (M3, daemon deployed), and the phone-side Log (M4). Things to check at the rig:
  after Ekos stop/start every device incl. the filter wheel must show (a first M3 build fetched the
  device list only once, before the wheel existed; fixed by probing with `get_profiles`, not
  `get_connection`); the module log pushes (Capture/Focus/Guide/Align/Scheduler) now decode as
  state events with a `log`; the Log fills while connected and survives an app restart.
- M6 phone notifications (compiled, unit-tested, installed; not seen delivering an alert): a
  foreground service (`monitor/RigMonitorService`, type specialUse, persistent "Nocturne is watching
  the rig" notification) keeps the connection alive with the screen off, with a CPU and a Wi-Fi lock;
  checked: with the screen off the Pi still received the phone's probe every 10 s for 75 s (USB
  connected, so Doze was not in play). `NotificationRules` decides what to post: Ekos alerts of
  warning level and up, a module aborting or failing while a job runs, a broken link held 30 s (and
  its recovery), a volume under 5% free. Quiet hours keep only the critical ones. Settings →
  Alerts → "Push to this phone" switches it off. Still to check on the rig: an alert actually
  arriving on the locked phone, and the service surviving a night on battery (Doze, battery
  optimisation); swiping the app away from recents ends the connection and the service.
- Home layout (2026-10-25, compiled and unit-tested, installed, seen on the phone): Night arc, Ekos,
  a compact Rig health card that opens a Rig health sheet, Session, Alerts, Log, maintenance. The
  "Rig" box (mount/cooler line) is folded into the health card. The cooler set point reads N/A until
  the camera's `CCD_TEMPERATURE` exists and Capture's settings are re-read.
- M5 (fork per-line logs, deployed to the Pi 2026-10-25 02:15): `new_log` and `get_logs` work end to
  end (INDI driver messages, the Ekos main log, Scheduler/Align lines arrive with `fork:` ids and no
  duplicates); Capture, Focus, Guide and Mount lines are unseen so far. Cooler N/A after an Ekos
  stop/start was the app dropping `device_property_add` (properties a camera defines ~100 s after it
  connects); decoded now, confirmed on reconnect, not yet on a real stop/start. Focus and Guide
  binning are empty until their camera defines `CCD_BINNING`: the app shows 1x1, sets 1x1 once, and
  re-reads the settings when the camera's property arrives. Still to check at the rig: all of that
  after an Ekos stop/start, and that older forks fall back to the per-module log pushes.
- Under-voltage: a Pi 5 sets its throttle bits on a harmless supply, so the app no longer shows or
  alerts on them (Home card, health sheet, Connect screen's rig row). The daemon still sends the raw
  `vcgencmd get_throttled` in `/health`; the app ignores it.
- Shut down Pi (compiled, not run on the rig): `POST /shutdown` on the maintenance daemon, a "Shut
  down Pi" button in Rig maintenance, and a "Rig power" section (reboot + shut down) on the Connect
  screen that works with Ekos down. The daemon on the Pi must be reinstalled
  (`pi-tools/reboot-daemon/install.sh`) before the new endpoint exists.
- End session / Finish (compiled, not run on the rig): End session stops the Scheduler when Ekos
  reports it RUNNING and removes the job Ekos is busy with, even if no local job is synced (the
  2026-10-25 field test left the job and capture running because the app's queue had no synced
  job). Finish also stops guiding, sends `mount_park` and turns the cooler off; Ekos' own
  pre-shutdown queue stays off (`SchedulerShutdownEnabled=false`) and a manual Scheduler stop never
  runs it.
- Cooler reads 0% power at 9.9 °C with Cooler ON and a −5 °C set point (screenshot 20:29): the
  card now says "not cooling" in that state, but whether the camera really isn't cooling is a rig
  question.
- Guide RMS/drift chart and SNR tiles during real guiding; guide settings writes.
- Focus V-curve, guide box size, Goto & center waiting on real mount/align state (field fixes of
  2026-10-23).
- Gain "--" (driver default) write side.
- Meridian flip with capture/align/guide resuming, on the current fork build (the flip itself
  passed a daytime test 2026-10-24).

## Open — app

- **Future plan** (`docs/PLAN.md`, draft) has three plans: (1) Home tab and tab layout revision —
  decided, milestones M1–M6, order Home | Gear | Controls | Plan | Sequence | Frames, Session
  becomes Home and its job progress moves to Sequence; (2) Controls tab revision — to be planned;
  (3) Frames tab revision, the Pi file manager — open decisions. Nothing built yet.
- New sequence blocks start at 300 s × 10, gain 100, offset 50, dither every 2 — could start from
  Ekos's current capture settings instead. `TabPlaceholder` is unused.
- Warn when a second client takes over the connection.
- Per-job in-sequence autofocus option (`inSequenceFocus`).
- Scope cleartext traffic to the LAN (`network_security_config.xml`) and reject non-private hosts.
- "Delete loaded tiles" button shows even when nothing is loaded.

## Open — fork and rig

See `docs/FORK.md` (per-line log push, preview size, graceful scheduler pause, flip countdown,
bogus flip planned at home, `polar_set_algorithm`). Rig: reflash OnStep with
`PIER_SIDE_PREFERRED_DEFAULT EAST`.

## Known rig behaviour

- OnStep forgets Preferred Pier Side on a power cycle (the pier guard re-applies East).
- Recurring: mount faults ~1 s after some slews; CP2102 USB drops; Pi undervoltage warnings
  (considered harmless on this rig).
- The rig's internet uplink is a prepaid SIM with a 1 GB cap: no downloads on the Pi or over the
  rig network; the app skips optional internet there.
- The Pi has no RTC; after a power cut its clock starts from the last fake-hwclock save until
  synced (app → Rig maintenance → Sync time).

## Not planned

Dome control (no dome), authentication on port 9000 (trusted LAN), mDNS (beacon covers it),
an on-phone plate solver (Ekos solves on the Pi).
