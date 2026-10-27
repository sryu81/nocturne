# Future plan (draft)

**Draft, still being discussed** — plan 1 is built and merged into `main`, plan 2 is built on a branch
waiting for a night test, plan 3 is planned but not started; items are added as discussions go.

| # | Plan | State |
|---|---|---|
| 1 | Home tab and tab layout revision | M1–M6 built, installed and merged into `main` (2026-10-25); rig checks left, listed in `docs/STATUS.md` |
| 2 | Controls tab revision | C1–C4 built and installed on branch `controls-revision`; rig checks left, listed in `docs/STATUS.md` |
| 3 | Frames tab revision | decisions, screens and milestones R0–R6 written (2026-10-25); deferred until the plan 2 test is closed |
| 4 | Night 2026-10-26 fixes | cooler at session start (N1) and guiding tuning (N2) written; not started, N2 waits for the Pi |

The three are independent: plan 1 only reorders the tabs and moves the old Session content;
Controls and Frames keep their content until their own plans are built.

## Milestone tracker (updated 2026-10-25)

"Built" = compiled, unit-tested and installed on the phone. "Rig-checked" = also seen working on the
rig; what is still unchecked is in `docs/STATUS.md`. Details of each milestone are in its plan below.

| Plan | Milestone | What | State |
|---|---|---|---|
| 1 | M1 | Tab shell (Home \| Gear \| Controls \| Plan \| Sequence \| Frames) | Built, merged; partly rig-checked |
| 1 | M2 | Home with today's data | Built, merged; partly rig-checked |
| 1 | M3 | Rig health card and daemon `/health` | Built, merged; rig-checked |
| 1 | M4 | Phone-side Log | Built, merged; rig-checked |
| 1 | M5 | Fork: per-line logs | Built, deployed to the Pi; Capture, Focus, Guide and Mount lines unseen |
| 1 | M6 | Phone notifications | Built, merged; not seen delivering an alert |
| 2 | C1 | Controls menu and page shell | Built on `controls-revision`; rig-checked |
| 2 | C2 | Live menu: one-liners, pinning, mount STOP | Built; one-liners and pinning rig-checked, STOP card not |
| 2 | C3 | Page upgrades (frames, backlash, chart, coordinates) | Built; mount layout, backlash and align frame rig-checked, focus frame and guide chart not |
| 2 | C4 | Polar alignment page with pinned Start/Stop | Built; page and settings rig-checked, the run itself not |
| 3 | R0 | USB automount (Pi tool) | Not started |
| 3 | R1 | Fork: drives and listings | Not started |
| 3 | R2 | App: Rig storage, read-only | Not started |
| 3 | R3 | Fork: preview and header | Not started |
| 3 | R4 | App: file page | Not started |
| 3 | R5 | Fork: file operations and eject | Not started |
| 3 | R6 | App: manage (selection, paste, progress, delete) | Not started |
| 4 | N1 | Cooler on at session start, hold first sub | Built and unit-tested (2026-10-26); not installed or rig-checked |
| 4 | N2 | Guiding tuning (gain, pulse, axes) | Built and unit-tested (2026-10-27); not rig-checked |
| 4 | N3 | Polar alignment: bigger, vivid triangle and target circle | Built and unit-tested (2026-10-27); not installed or seen on the rig |

**Next:** the user's night test of plan 2, then closing it (merge `controls-revision` into `main`).
Plan 3 (R0–R6) is deferred until the plan 2 test is finished and closed; nothing of it is started.

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

Layout, pages and build plan decided (sections below); not built. Inputs so far:

- Controls is one very long scroll (about 7,000 px in portrait): Primary camera, Cooler, Focuser,
  Plate solve, Align accuracy, Rotator, Align settings, Mount, Guide, Polar alignment (the section names today).
- Collapsible sections and a pinned active module were the first ideas; both are replaced by the
  menu and module pages below (the active module is pinned in the menu).
- Idea: remember the last cooler set point, cooler/fan state and exposure/gain/bin on the phone (the
  app keeps none of them today; it re-reads Ekos at connect, and the camera forgets its cooler on a
  restart). Re-sending the cooler settings would need a confirm, since it changes the rig.
- Cooler card: the caption under "Enforce temperature" and the unlabelled power bar were removed
  (2026-10-25); the power % stays in the card header.
- The 2026-10-24 screenshot fixes already covered the smaller Controls defects (EXP box following
  Ekos, blank bin chip, cooler state); see `docs/STATUS.md`.

## Layout (decided in discussion 2026-10-25; not built)

The long scroll is replaced by a menu and one full-screen page per module, the same drill-down as
the Log screen opened from Home (a page with a back arrow, not a sheet).

- **Controls tab = module menu.** One compact card per module with a live one-line summary, e.g.
  Camera `−5 °C · 79% · 300 s`, Focuser `12 450 · HFR 2.1`, Mount `Tracking · HA +1:20`, Guide
  `Guiding · 0.6″`. Unknown values show "N/A", as everywhere else.
- **Pinned active module.** The module doing something (Guide while guiding, Focuser while focusing,
  and so on) is pinned to the top of the menu and highlighted.
- **Module pages:** an **Imaging** group (Camera with cooler, Focuser, Guide, Plate solve, Mount) and
  a **Setup** group (Polar alignment). Each page is full-screen, never a sheet. "Plate solve" is the
  old Align section (solve, accuracy, rotator); polar alignment is one-time setup and a different
  job, so it has its own entry despite Ekos filing both under Align.
- **Settings stay sheets.** Each module keeps its settings sheet (Mount, Camera, Align, Guide,
  Focus), opened from the module's page. Only the main page of a module is not a sheet.
- **Header switcher.** Each page's title is a switcher, e.g. `Focuser ▾`; tapping it opens a short
  vertical list of the modules with their one-liners and the active one marked, so you jump straight
  to another module without going back to the menu. The list is vertical to respect the landscape
  rule (vertical rail only). No swipe between pages (it would clash with sliders, charts and PAH
  pinch-zoom) and no prev/next arrows (modules have no natural sequence).
- **Back** returns to the menu, even after jumping between modules.
- **Tab re-entry** opens the menu, not the last module page, because the menu shows what is active.

## Camera page (decided 2026-10-25; not built)

Order, top to bottom:

1. Header `Camera ▾` (module switcher) with a button for the existing Camera settings sheet.
2. **Preview card**, unchanged: frame, status, EXP / gain / BIN / FILTER, image size line with "Set
   full frame", Preview / Stop / Cancel.
3. **Cooler card**, unchanged: sensor temperature, set point and Set, Cooler and Fan buttons,
   Enforce temperature.

The preview stays first (it is used more often than the cooler once cooling runs).

- **Menu one-liner:** sensor temperature, cooler power, loaded exposure and bin, e.g.
  `−5.0 °C · 79% · 300 s · bin 1`; with the cooler off, `cooler off · 12.3 °C`. Unknown values show
  "N/A".
- **Scope:** only the preview and the cooler. Focuser, plate solve, align accuracy and rotator leave
  the camera section for their own pages (Focuser; Plate solve).
- **No behaviour change.** The existing `SnapPanel` and `CoolerCard` are reused as they are, with
  the same commands; only the container, the menu, the module state and the header switcher are new.
  Landscape keeps the same grid.
- **Not in this step:** remembering camera and cooler settings on the phone (it changes behaviour;
  later).

**Test plan (run after building):** build, unit tests, install; screenshots in the phone's current
orientation (rotation is never changed); on the connected rig, Preview Main Cam shows a frame, and
EXP, gain, BIN and FILTER changes reach Ekos; Cooler ON/OFF, Fan, Set and the sensor readout work;
Back, the header switcher and tab re-entry behave as decided; portrait fits about one screen.

## Focuser page (decided 2026-10-25; not built)

Order, top to bottom:

1. Header `Focuser ▾` (module switcher) with a button for the existing Focus settings sheet. The
   "Focus settings" tile goes away; the sheet stays.
2. **Focuser card**, as today: position and range bar, the Focus module's EXP / gain / BIN / FILTER,
   Focus In / Out (step shown on the buttons, disabled at the end stops), hint line, autofocus
   status with Start / Stop.
3. **Focus frame with HFR** (new, read-only): the latest focus frame (`latestFocusFrame`) directly
   above the V-curve, so focus can be watched while stepping and during autofocus. Shown while
   autofocus runs or a focus frame exists; "no frame yet" otherwise.
4. **V-curve**, as today (while focusing or once samples exist).

- **Backlash hint:** the hint line's fixed text `backlash 90 out` is replaced by the real
  `focusBacklash` from the Focus settings, "N/A" while unknown (no placeholders).
- **Menu one-liner:** position and focus status, e.g. `12 450 · idle`; pinned as active while
  autofocus runs (`12 450 · autofocus running`). Without a focuser: "N/A".
- **No other behaviour change:** same commands and state; the frame adds no commands.

**Test plan (after building):** build, unit tests, install; on the rig Focus In/Out moves by the
step on the buttons, the position and range bar follow and the buttons disable at the end stops;
autofocus Start/Stop works, the frame updates and the V-curve fills; the Focus settings sheet opens
from the header; the backlash text matches the settings value; portrait fit checked on the phone
(frame plus curve may pass one screen, which is accepted).

## Mount page (decided 2026-10-25; not built)

Order, top to bottom:

1. Header `Mount ▾` (module switcher) with a button for the existing Mount settings sheet. The
   "Mount settings" tile goes away; the sheet stays.
2. **Mount card**, as today: status line, D-pad with STOP in the centre, slew-rate chips beside it,
   Park / Unpark, Go home, Tracking.
3. **Coordinates full-width** under the D-pad and rate chips (RA, Dec, HA, Alt, Az, ZA with the
   JNow/J2000 toggle and the "not updating" dimming), instead of squeezed beside the D-pad. Layout
   only; the readouts are unchanged.

- **Manual slew safety (decided: no auto-stop).** A direction button starts a slew that runs until
  STOP, and leaving the page does not stop the mount (a goto or park in progress must keep going).
  So while the mount moves, its menu card is pinned to the top in the warning colour with a **STOP**
  button on the card itself, the same `mount_abort` as the D-pad's STOP.
- **Menu one-liner:** state, pier side and hour angle, e.g. `Tracking · East · HA +1:20`; or
  `parked`, `slewing N`, `tracking off`. Pier side uses the existing label (East/West only where the
  rig confirmed it); unknown values show "N/A".
- **No other behaviour change:** same commands and state.

**Test plan (after building).** Without moving the mount: build, unit tests, install; coordinates
update, rate chips switch, JNow/J2000 toggle, Tracking toggle, header switcher, Back, the settings
sheet opens, the menu card turns to the warning colour with STOP while a slew state is shown.
Moving the mount only with the user's go-ahead and the rig safe: a short N/S nudge then STOP (also
from the menu card), Park / Unpark, Go home.

## Guide page (decided 2026-10-25; not built)

Order, top to bottom:

1. Header `Guide ▾` (module switcher) with a button for the existing Guide settings sheet. The
   "Guide settings" tile goes away; the sheet stays.
2. **Preview card**, unchanged: guide frame, status, EXP / gain / BIN (no filter), Preview Guide Cam
   with Stop. First, as on the Camera page: see the star before anything else.
3. **Control card**, unchanged: status line, Start guiding / Stop, Loop and Clear calibration
   (idle-only), RA / Dec / Total RMS.
4. **Drift chart inline at about 140 dp** (64 dp today), so it reads without opening a sheet.
   `GuideTelemetry` already takes a chart height, so this is layout only. Tapping it still opens the
   Guide sheet, which stays reachable from the Sequence RMS tile.

- **Menu one-liner:** status and total RMS, e.g. `Guiding · 0.62″ RMS`, `looping`, `calibrating`,
  `idle`; pinned as active while guiding or calibrating. Unknown values show "N/A".
- **No behaviour change:** same commands; Stop stays an abort, not a pause; Clear calibration stays
  without a confirmation (a dialog would change behaviour, later if wanted).

**Test plan (after building).** Build, unit tests, install; Preview Guide Cam shows a frame; EXP,
gain and BIN changes reach Ekos; the settings sheet opens from the header; tapping the chart opens
the Guide sheet; the menu one-liner and pinning follow the guide state; portrait fit checked on the
phone (a little scrolling is accepted). Start / Stop, Loop and Clear calibration need a tracking
mount and a star in the guide camera, so they are verified under the sky and listed as not verified
in `docs/STATUS.md` until then.

## Plate solve page (decided 2026-10-25; not built)

Order, top to bottom:

1. Header `Plate solve ▾` (module switcher) with a button for the existing Align settings sheet. The
   "Align settings" tile goes away; the sheet stays.
2. **Plate solve here** button with the Align status line, unchanged.
3. **Align frame** (new, read-only): the latest align frame (`latestAlignFrame`, already received)
   under the button, so you see what the solver was given; "no frame yet" until a solve has run.
   Same pattern as the Focuser page's frame.
4. **Target accuracy** trend, unchanged (still hidden until the first solve of the connection).
5. **Rotator** card, unchanged, including the running-sequence warning.

- **Menu one-liner:** align status and last accuracy, e.g. `Complete · 3.2″`, `solving…`, `Failed`;
  pinned as active while a solve runs. Before the first solve: the status only, or "N/A".
- **No behaviour change:** same commands and rotator logic; the frame adds no commands.

**Test plan (after building).** Build, unit tests, install; Plate solve here on the connected rig
(a real exposure, the mount does not move; a daylight solve may fail, which still exercises the
status line and the frame); the frame appears after a solve; the accuracy trend and the settings
sheet work; the Rotator switch behaves as today; the menu one-liner and pinning follow the align
state; portrait fit checked on the phone (a little over one screen is accepted).

## Polar alignment page (decided 2026-10-25; not built)

Polar alignment is initial setup, plate solving is imaging work: different jobs that Ekos files
under one Align module. Here they are separate. Polar alignment is the only entry of the **Setup**
group in the Controls menu and opens as a full-screen drill-down, not a sheet (today it is the
full-height `PaRealSheet` behind a tile). The switcher list shows both groups, so any module is one
tap away; Back returns to the menu.

Content, the same as the sheet and in its order, with the same stage logic and commands:

1. Header `Polar alignment ▾`.
2. Stage title and the 3-step bar while measuring; Ekos' prompt message.
3. The align frame: pinch-zoom, double-tap reset, tap to pick a star (Ekos paints the correction
   triangle on it).
4. Total / az / alt error.
5. Settings (direction, rotation, mount speed, manual slew, refresh exposure and method), shown only
   while idle or at Select Star, as on the desktop.
6. The warning that PAH drives the mount by itself.
7. **Sticky action bar** (new): the primary action (Start, Done rotating, Start adjusting, Done) and
   Stop pinned at the bottom of the page so they never scroll away. Same buttons, same rules as
   the sheet's. Built only if it fits the page container cleanly.

- **While it runs:** its menu card is pinned to the top in the warning colour with a **Stop** button
  (the same `polar_stop`) and the stage as its one-liner; no auto-stop on leaving the page, as with
  Mount.
- **Menu one-liner:** stage and error, e.g. `measuring · image 2 of 3`, `refreshing · total 0.12°`,
  `not started`.
- **Pure move:** no logic change. `PaRealSheet` and `PaImage` (in `Sheets.kt`) become reusable by the
  page; the "Polar align" tile and `SheetType.PA` go once the page works. This is the riskiest move:
  PAH drives the mount and its full run is not yet verified under the sky.

**Test plan (after building).** Build, unit tests, install. Without moving the mount: the page opens
from the Setup entry, the stage text and settings load, the settings chips write to Ekos, the frame
shows with pinch-zoom and reset, the sticky bar shows the right buttons per stage, the menu
one-liner and pinning follow the stage. Start, Done rotating, Start adjusting, Done and Stop move
the mount: only under the sky with the user's go-ahead, listed as not verified in `docs/STATUS.md`
until then.

## Menu order (decided 2026-10-25)

The night's order. **Imaging:** Mount, Camera, Focuser, Plate solve, Guide. **Setup:** Polar
alignment. The active module is pinned above this order while it runs.

## Milestones

Each ends compiled, unit-tested, installed on the phone and screenshotted in the phone's current
orientation (rotation is never changed). Rig checks are listed in `docs/STATUS.md` until verified.
Anything that moves the mount or starts guiding waits for the user's go-ahead or the sky.

| # | Milestone | Done when |
|---|---|---|
| C1 | **Menu and page shell** | Controls opens on a module menu (Imaging group, Setup group). Mount, Camera, Focuser, Plate solve and Guide each open as a full-screen page holding their **existing cards unchanged**. The header has a back arrow, the title switcher `▾` and the settings button; the four settings tiles are gone (their sheets stay). Back returns to the menu; re-entering the tab opens the menu. Polar alignment still opens its sheet, from a Setup entry. No command or logic change. |
| C2 | **Live menu** | Each menu card shows its one-liner. The active module is pinned and highlighted. A moving Mount is in the warning colour with its own STOP. The one-liner and pinning rules are pure functions with unit tests. |
| C3 | **Small page upgrades** | Focuser: focus frame above the V-curve and the real backlash instead of the fixed `90`. Plate solve: align frame under the button. Guide: chart at about 140 dp. Mount: coordinates full-width. |
| C4 | **Polar alignment page** | The sheet's content moves to the Setup drill-down page with the sticky Start/Stop bar; the pinned menu card gets a Stop button; the old tile and `SheetType.PA` are removed. |

**Why this order.** C1 is a pure move, so it is the safest to verify against today's screens. C2 and
C3 add to pages that already work. C4 is last because it is the riskiest move (PAH drives the mount
and its full run is not yet verified under the sky) and can ship on its own.

**Shape.** A `ControlsModule` choice (none = the menu) decides what Controls shows; it renders inside
the tab, so the status row and bottom bar stay visible (unlike the Log, an overlay driven by
`state.logOpen`). Module pages reuse the existing card composables.

**Testing.** Every milestone: build, unit tests, install, screenshots. C1 and C3 are also checked on
the connected rig with the non-moving checks (Preview, cooler, EXP / gain / BIN, Focus In/Out, Plate
solve); moving the mount, guiding and the PAH run are checked under the sky.

**Built, 2026-10-25** — C1 to C4 are compiled, unit-tested and installed (branch
`controls-revision`, not yet merged). Seen on the rig: the menu and every page, the one-liners, the
header switcher, Back (a sheet first, then the page, then the tab), a Main Cam preview, a real Plate
solve with its align frame and the pinned card while it ran, a PAH direction round trip. Not seen:
the focus frame (needs a focus capture), the guide chart (needs guide data), the mount STOP card,
and the polar alignment steps that drive the mount. Differences from the plan: the Camera header has
no settings button (the sheet it would open is "File settings", which stays on the Frames tab);
the pier side shows only when it is East or West; and Back now closes an open sheet before leaving
the screen, for every sheet.

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
  folders by target/job, files with size and date. The FITS header summary (filter, exposure, gain,
  frame type, HFR when present) is read **on demand**, when a file is opened, not for every row of a
  listing.
- **Preview** any FITS file as a small JPEG, generated by KStars on the Pi (`Media::sendFile`).
- **Manage on the Pi**: copy and move between the SD card and USB drives, rename, delete
  (files or folders), create folders; long copies with progress and cancel; a free-space check
  before copying; confirmation for deletes and moves.
- **Manage on the phone**: delete stored previews, keep/discard, export (as today).
- **Safety**: operations limited to allowed roots (capture folder, `/mnt/*`, `/media/*`) — the
  stock `remove` would delete any path; never touch a folder the running capture is writing to;
  USB drives that disappear mid-operation fail cleanly.
- **No bulk image transfer over the network** (confirmed): copies and moves run on the Pi itself;
  only listings, metadata and small preview JPEGs travel to the phone.

## Where it lives: the fork (decided)

All of it goes in the fork (EkosRemote), on the one connection the app already has: `file_*`
commands for copy/move/delete-file/rename with progress pushes, a drive list (`QStorageInfo`), the
allowed-roots check, a header-summary command (read on demand) and a `file_preview` command wrapping
`Media::sendFile`, which gets FITS decoding for free. The maintenance daemon (9001) is not used.

Consequence: the file manager needs KStars running. With KStars down (the daemon still answers) the
Rig storage view says so instead of listing anything; files on the Pi stay reachable over SSH as
today.

## Decisions (file manager), settled 2026-10-25

1. No image transfer to the phone over the network: copy and move run on the Pi.
2. File operations live in the fork (not the daemon, not split).
3. In the app: the Frames tab (last in the tab order), with a "Phone" view (stored previews,
   keep/discard, as today) and a "Rig storage" view (the Pi file manager).
4. FITS header summary: read on demand, not per row of a listing.

## Rig storage screens (decided 2026-10-25; not built)

Drill-downs with a back arrow, the same pattern as the Controls pages, never sheets.

1. **Entry:** a fourth card in the Frames picker, `Rig storage`, with a one-liner such as
   `SD 412 GB free · USB 1`. Preview and Plan stay as the phone side; File settings stays.
2. **Drives (root):** one card per drive: the SD card (`/home/soo`) and each attached USB drive, with
   label, filesystem, a free-space bar and `free / total`. A running copy or move shows its progress
   card on top. A USB drive card has an **Eject** button that unmounts it safely before it is pulled
   (not offered while a copy or move uses it). With KStars down the screen says "Needs KStars
   running" instead of listing anything.
3. **Folders:** the contents of a drive or folder, newest first, under a breadcrumb such as
   `SD › M31_2026-10-24 › Light`. Rows show name, file count, size and date. The folder a running
   capture is writing to shows an "in use" lock and has no actions.
4. **Files:** rows with name, size and time; a tap opens the file page.
5. **File page:** the preview JPEG (generated by KStars from the FITS), the header summary read on
   demand (filter, exposure, gain, bin, frame type, HFR), and the actions Copy, Move, Rename, Delete.
6. **Selecting several:** a long-press starts selection; a bar pinned at the bottom (like polar
   alignment's) shows `3 selected · 1.2 GB` with Copy, Move and Delete.
7. **Copy and move:** browse to the destination, then tap **Paste here** on a pinned bar, like a file
   manager. Before it starts, a free-space check blocks it when there is not enough room ("needs
   3.4 GB, 1.1 GB free"). While it runs, a progress card shows `8/24 files · 1.2 / 3.4 GB` with
   Cancel; it stays visible from the root, and the transfer carries on if the user leaves the
   screen (it runs on the Pi).
8. **Delete:** one plain confirm dialog for files and folders alike. It says what goes ("Delete
   folder M31_2026-10-24 with 124 files, 18 GB? This cannot be undone."). The fork enforces the
   allowed roots, so nothing outside the capture folder and the drives can be touched.
9. **Drive removed mid-operation:** the operation fails cleanly and the progress card names the
   drive that disappeared.

## USB sticks on the Pi

State on the Pi (checked 2026-10-25, no stick attached): one stick is configured, in `/etc/fstab`
(`UUID=824F-1D2E` at `/mnt/astro`, FAT32, `nofail`), a plain boot-time mount with no
`x-systemd.automount`; `udisks2` is inactive, `/media` is empty and there are no USB udev rules. So
that stick is mounted only if it was in at boot, and any other stick is seen by the kernel but never
mounted: a stick plugged in at the rig would not appear in the file manager.

Fix, with nothing installed over the SIM uplink: a small tool in `pi-tools/` (like the pier guard) —
a udev rule plus `systemd-mount`, already on the Pi — that mounts any USB stick at `/media/<label>`
owned by the user when it is plugged in, and unmounts it when it is pulled. FAT32 and exFAT come from
the kernel; NTFS depends on the Pi's kernel driver, to be checked. The old stick keeps working at
`/mnt/astro`; the fork's allowed roots cover `/mnt/*` and `/media/*`. The fork's drive list then
shows whatever is mounted, and its `eject` command (below) unmounts a stick before it is pulled.

## Milestones

Each ends built, tested and installed; fork steps need a fork build (about 15 minutes) and a KStars
restart on the Pi, so they wait for an idle rig and the user's go-ahead. The read-only steps (R0–R4)
are safe on the rig; R5 can lose files, so it is tested on a scratch folder first.

| # | Milestone | Done when |
|---|---|---|
| R0 | **USB automount (Pi tool)** | `pi-tools/usb-automount` mounts a stick plugged into the running Pi at `/media/<label>` and unmounts it when pulled; the Pi kernel's filesystem support (FAT32, exFAT, NTFS) is checked and noted; the old `/mnt/astro` mount is untouched. Verified by plugging a stick in and out (needs the user). |
| R1 | **Fork: drives and listings** | A command lists the mounted volumes (SD, USB) with label, filesystem and free/total, and a folder listing returns name, size, date, file count and an "in use" flag for the folder a capture is writing to; paths are checked against the allowed roots. |
| R2 | **App: Rig storage, read-only** | The `Rig storage` card in the Frames picker; drives, folders and files drill-downs with the breadcrumb and the "needs KStars running" state; falls back cleanly on an older fork. |
| R3 | **Fork: preview and header** | `file_preview` (a JPEG made by KStars from the FITS, via `Media::sendFile`) and a header-summary command read on demand. |
| R4 | **App: file page** | The preview, the header summary (filter, exposure, gain, bin, frame type, HFR) and the action buttons (disabled until R6). |
| R5 | **Fork: file operations** | Copy, move, delete and rename on the Pi with the allowed-roots check, a free-space check before a copy, progress pushes and cancel, clean failure when a drive disappears, and `eject`. Tested on a scratch folder before any real capture folder. |
| R6 | **App: manage** | Long-press selection with the pinned action bar, browse and Paste here, the progress card with Cancel, the plain delete dialog, rename, and the Eject button on USB drive cards. |

R0 has no fork build, so it can start first and gives R1 real USB volumes to list. R1, R3 and R5 could
share one fork build if they are written together, at the cost of testing later.

---

# 4. Night 2026-10-26 fixes

Two problems from the M31 night (bugs B6 and B7 in `docs/BUGS.md`). Independent of plans 2 and 3;
N1 can land on `controls-revision` or its own branch, as the user prefers.

## N1 · Cooler at session start (B6)

**What happened.** After an abort the user started a new session; the cooler was off and the app did
not say so, so job 2 took 180 s H subs at 15 °C.

**Decided approach.** The app owns the cooler state around a session:

- **On Start sequence**, when the camera can cool (`CCD_TEMPERATURE` defined) and the cooler is off,
  switch it on at the set point (the Camera page's, default the last commanded `coolTarget`), as
  `setCoolerEnabled(true)` does today; the start is not blocked.
- **Hold the first sub** until the sensor is within about 2 °C of the set point. Ekos' own
  "enforce temperature" can do the wait (`cameraTemperatureS` plus `cameraTemperatureN`), so the
  app sets those two with the cooler on rather than building its own wait. A hard timeout (the
  Camera page's note says how long) then lets imaging start with a visible warning instead of
  stalling.
- **Say it.** The Sequence header and the Home Session card show "cooling to −X °C" while it waits,
  and a warning line "cooler off" if the cooler is still off at the first sub. The held-Preview
  note (B2) gets the same wording: "cooling to X °C started".
- **Remember it.** `coolerCommandedOn` is kept across an Ekos restart (or re-read from
  `CCD_COOLER` once the camera defines it) so a reconnect does not look like "never asked".
- **Finish.** Cause confirmed: Summary → **Finish** runs `finishNight()` (stop, park, cooler off) by
  design, and the next start never undid it. The Finish button already says "park mount, cooler off";
  the cooler-on-Start step above is what makes a restart safe.
- **As built.** `AppState.coolerStartPlan()` decides (camera present and able to cool, cooler not
  known on, a set point known: the app's `coolTarget`, else Ekos' `cameraTemperatureN`; an unknown
  cooler state counts as off). `ensureCoolingBeforeStart()` in `EkosRemoteController` runs it before
  every Scheduler start (Start Scheduler, Resume, Next job; not when the toggle would stop a running
  Scheduler): cooler on, set point written, "Enforce temperature" switched on if Ekos did not have
  it, and a Log line "Cooler was off at start: cooling … first sub waits for it". Not built: the
  Sequence-header "cooling to X °C" note and keeping `coolerCommandedOn` across an Ekos restart (an
  unknown state is treated as off, so it is not needed). Enforce stays on afterwards; it is the
  user's usual setting.

**Not decided.** Whether the cooler should come on at "Connect" for a night plan, not only at
Start; whether Cancel of a held Preview switches the cooler off (B2).

**Done when.** Unit tests cover the start decision (cooler off, on, unknown, no cooling camera);
installed; on the rig, an aborted-then-restarted session shows the cooler on and the first sub waiting
for the set point. Cooler tests change real rig state: tell, then undo.

## N2 · Guiding tuning (B7)

**What happened.** RA guiding oscillated (±7–10″, RMS 3–4″) and the sheet had no control for it.

**Today.** The Guide settings sheet has accuracy threshold, dither, frame and calibration; the
guide exposure, gain and binning are on the Guide page. The rig uses the internal guider.

**Decided approach.** A **Guiding tuning** section in the Guide settings sheet (not on the page;
tuning is rare), applied live, with one plain line of help per control:

- **RA and DEC aggressiveness** (proportional gain), the main control for this oscillation.
- **Integral gain**, **hysteresis**, **minimum and maximum pulse** (ms or arcsec as Ekos has them).
- **RA and DEC guiding on/off** (so DEC can be left off or one axis tested alone).
- **Guide algorithm** choice, only if the wire exposes it.
- The guide **exposure** stays on the page; a note says that a longer exposure also calms a loop.

Because the guide settings are saved by Ekos, the sheet shows a **Reset to defaults** and the
values at the time the sheet opened, so a wrong change is one tap back.

**Findings so far (2026-10-26 logs).** Job 1 guided at RA 2–3.7″ with Ekos' RA P 0.70 / DEC P 0.55 /
1 s exposure; job 2 with the same gains ran at 0.7–1.4″ after a fresh calibration, so the guiding
page also needs to show the calibration (and the exposure it was done at) before gain is the knob.
See B7.

**As built (2026-10-27).** Keys confirmed from the fork source (`opsguide.ui`, `cgmath::configureInParams`,
`KSUtils::setGlobalSettings`), not from a live reply: `kcfg_RAProportionalGain`,
`kcfg_DECProportionalGain`, `kcfg_RAIntegralGain`, `kcfg_DECIntegralGain` (doubles, 0..1),
`kcfg_RAMinimumPulseArcSec`, `kcfg_DECMinimumPulseArcSec` (doubles, 0..1000) and
`kcfg_RAMaximumPulseArcSec`, `kcfg_DECMaximumPulseArcSec` (ints, 0..3600, arcsec), plus the axis
checkboxes `rAGuideEnabled`, `dECGuideEnabled` (no prefix). `setGlobalSettings` strips `kcfg_` and
writes `Options`, which `configureInParams` re-reads every guide cycle, so a change acts on the
running guider. Rig `kstarsrc` on 2026-10-26: RA P 0.70, DEC P 0.55, max pulse 35. `GuideTuning` /
`GuideAxis` (`EkosEvent.kt`) carry key and range; `setGuideTuning` clamps and sends; the Guide sheet
has a **Guiding tuning** section (axis switches, eight fields, **Revert to values when opened**).
Left out on purpose: `kcfg_GuideAlgorithm` is the star-detection method (SEP, Smart…), not the control
algorithm; the pulse algorithm (`RAGuidePulseAlgorithm`) has no widget, so the reply never carries it;
the calibration readout and who changed the guide exposure (still open). Not rig-checked: the live
reply is expected to carry the eight keys (the sheet shows defaults if it does not; check with the
sheet open on the rig), and that a write changes the running guider.

**Needs the Pi first (was blocking the build).** `guide_get_all_settings` returns 84 fields; the exact
wire key names, types and the unit of the pulse limits are read from a real reply (read-only: the
probe in `tools/ekos_probe.py` or a DBus read), not guessed from the `kstars.kcfg` names. Together with that, the guide log
and analyze file from the night are read to confirm that the over-correction is RA gain and not
the flip or the guide exposure (0.5 s → 2.0 s → 1.0 s during the night is also odd and is
checked: who changed the exposure).

**Done when.** The keys are confirmed and added to `WireGuideSettings` with defaults; the sheet
section, setters in the controller and unit tests (codec, setters) exist; installed; under the
sky, lowering RA aggressiveness on a running guide settles the trace. Until then it is listed as not
verified in `docs/STATUS.md`.

## N3 · Polar alignment overlay too small and faint

**What the user saw (2026-10-26, Polar alignment, "Refreshing", total 0.44°).** The target circle
(green, at the right edge) and the correction triangle (green, yellow, magenta edges) are hairlines
on the align frame, hard to see on a phone and under the red night filter.

**Why.** Ekos paints both into the frame JPEG at frame scale, about 1 px wide (see the `PaImage`
comment, `Sheets.kt`); the frame is then scaled down to the phone width, so the lines shrink further.
The app draws only its own tap ring (`polarSelectStar`). The wire also sends the correction line
(`new_polar_state.vector`: centre, length, angle), but not the triangle corners or the target circle,
so the app cannot redraw them itself.

**Options.**
- **A. App only, recolour the frame.** In `PaImage`, after decoding the JPEG, find the overlay pixels
  (the frame is grey, the overlay is saturated), widen them by 2–3 px, and give them a stronger,
  night-mode-safe colour; draw the result instead of the raw frame. Works on any fork, costs a
  per-frame pixel pass (a 1–2 MP JPEG; done once per frame off the main thread). Risk: picks up
  coloured stars or noise in a colour frame; the Ekos align frame is mono, so it should be clean.
- **B. Fork: thicker pen.** `PolarAlignmentAssistant` draws with a fixed pen; make its width and
  radius scale with the frame size. Cleanest result, but needs a fork build (about 15 minutes) and a
  KStars restart, so it waits for an idle rig.
- **C. App draws its own.** Needs the triangle and target geometry on the wire (a fork change as
  well as B), so it costs more than B for the same picture.

**Recommended.** A now (app only, testable at the desk against the saved align frames), B later if A
is not enough. Size target: lines at least 3 dp on screen, the target circle at least 24 dp across,
colours with enough contrast on the dark frame (the screenshots show the lines vanish into noise).

**As built (option A).** `OverlayBoost` (`ui/components`) runs on the align frame in `PaImage`
(`MediaFramePreview(..., boostOverlay = true)`, decoded off the main thread like every frame): pixels
with a channel spread of at least 14 count as overlay (a mono frame is exactly grey), are widened by
`width/300` px (1–6, about 3 dp) and repainted fully saturated at full brightness in their own hue.
Tried on the real overlay in the 2026-10-26 21:52 phone screenshot: the dark magenta edge and the
green circle (spread under 32) need the low threshold, and plain brightening washes them to pastel,
hence the saturation rebuild. Not changed: the circle's size (Ekos draws it; only its line gets
thicker); that needs option B if it is still too small. The 24 dp circle target above is dropped for
that reason.

**Done when.** The overlay is clearly visible at 1× on the phone in the red night mode, still shows
when zoomed, tap-to-pick still maps correctly (the recolour keeps the image size), and a unit test
covers the overlay-pixel detection on a sample frame. Only the visual check needs the sky or a
Polar alignment run; the run drives the mount, so it waits for the user's go-ahead.

## Order

1. Logs read 2026-10-26 (cooler: Finish; guiding: see B7). Still open: who changed the guide
   exposure, and the `guide_get_all_settings` key names. Read-only; fork and KStars untouched.
2. N1 (no Pi dependency beyond that).
3. N2 after the key names are confirmed.
4. N3 can go any time (option A is app only).
