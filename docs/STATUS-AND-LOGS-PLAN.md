# Rig status and logs — plan (draft)

Started 2026-10-24. **Draft: still being discussed** — more items to be added before any build.
Related decision still open elsewhere: the app's tab layout (to be discussed separately; it
decides where the Log screen lives).

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

## Proposal

### 1. Rig health (status)

A sheet opened from the status row, laid out as a chain, so it is clear where it breaks:

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

### 2. Log screen

- One merged, timestamped timeline of all sources; filter chips per module; "warnings/errors only"
  toggle; search; pause/follow; copy/share an excerpt.
- **Stored on the phone** (Room, like Frames) so a night's history survives an app restart, a Pi
  reboot or a power cut.
- On demand, fetch the Pi's own logs through the daemon: mount-event log, journal excerpt.

### 3. Fork changes (EkosRemote)

- Push **one new line at a time** (`{module, time, level?, text}`) instead of the full history;
  keep the old push for compatibility. Add a `get_logs` snapshot for (re)connect.
- Forward the **Ekos main log** and the **Mount** module log.
- Forward **INDI driver messages** with their level (INFO / WARNING / ERROR).

### 4. Phone notifications

Alerts and errors (flip failed, slew stuck, guiding lost, undervoltage, disk low) as Android
notifications with sound while the phone is in a pocket. Needs the app to keep running in the
background (foreground service, small persistent notification).

## Open decisions

1. Log screen as its own tab or a sheet from the status row — **deferred to the tab-layout
   discussion**.
2. Fork changes OK (new per-line log push alongside the old one)?
3. Android background notifications: yes / no?
4. Log retention on the phone: last N nights (e.g. 7) or until deleted?

## To be added

(More items from upcoming discussions.)
