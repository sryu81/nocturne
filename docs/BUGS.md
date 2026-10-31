# Bugs

Bug reports and their fixes. `docs/STATUS.md` says what works and what is unverified; this file keeps
the detail of each defect: what happened, why, the workaround, and the fix (done or planned). Newest
first within each list. Commits are on `main` (the `controls-revision` branch was merged 2026-10-27) unless noted.

## Open

### B13 · The app's link to the rig was down from 20:21 to 23:34 and nothing says why

- **Found:** 2026-10-30, looking for the meridian flip in the phone Log (see B12): the Log has no
  lines at all between 20:21:23 and 23:34:46, though Ekos ran the whole time (the Pi's analyze file
  has the flip, refocus and frames).
- **Evidence (phone Log, source Link):** 20:21:01 "Lost the KStars connection (Software caused
  connection abort), reconnecting"; 20:21:23 "Disconnected from the rig"; next line 23:34:46.
  "Disconnected from the rig" is only logged by an explicit `client.disconnect()`, never by a lost
  socket, so 22 s after the loss something called it: the Disconnect card (`SessionViewModel.disconnect`),
  or the view model being cleared (`onCleared`, e.g. the app swiped away). A lost socket alone retries
  forever (1 s doubling to 30 s) and would have logged "Connecting to KStars" again.
- **Not known:** what the user did at 20:21 (closed the app or tapped Disconnect), and why the socket
  was aborted (`Software caused connection abort` is the phone's network going away: Wi-Fi out of range
  or a network switch). The app never auto-dials after an explicit disconnect, by design (see
  `SessionViewModel.disconnect`).
- **Fix (planned):** none until the cause is known. Cheap step: log why a disconnect happened (button,
  app closed, reboot/shutdown) so the next gap explains itself.

### B12 · Ekos stopped by itself mid-frame at 23:35 on 10-30, 15 minutes after a good meridian flip

- **Found:** 2026-10-31, from the Pi's analyze file `ekos-2026-10-30T20-05-46` and the phone Log.
- **Flip was fine:** waiting 23:17:42, running 23:19:15, completed 23:20:31; then plate solve, sync,
  guide calibration, refocus, and three more R frames (R_3 to R_5) before the stop.
- **Stop:** R_6 started 23:32:54; last guide sample 23:35:16; 23:35:18 "INDI services stopped" and the
  Scheduler logged "Observatory is in the startup process ... Starting Ekos"; a new analyze file starts
  23:35:20, the mount went home at 23:35:52. No job end event, no crash or core dump in the journal, and
  KStars itself stayed up (only Ekos restarted).
- **Cause:** unknown. The phone only reconnected at 23:34:46 (B13), so it saw none of it, and the
  KStars log of that night was overwritten at the 23:41 boot. It looks like a deliberate stop-and-start
  (someone or something pressed it) more than a fault.
- **Fix state:** Ekos file logging turned on 2026-10-31 (kstarsrc `LogToFile`, module logging for Capture,
  Focus, Guide, Alignment, Mount, Scheduler) and KStars restarted; logs in
  `~/.local/share/kstars/logs/<date>/`. Next occurrence will have a log.
- **Check:** after a night, read the log around any unexpected stop.

### B11 · The Polar alignment overlay is ugly: dashed ring, speckled lines

- **Found:** 2026-10-30, screenshots of Select Star and Refreshing.
- **Cause:** Ekos paints the overlay itself into the frame it sends: the RA-axis circle is a green
  `Qt::DashLine` (`AlignView::drawRaAxis`), the triangle edges are 2 to 3 px magenta, yellow and green
  lines. JPEG and the downscale to the phone break them into dots and shift their colours. The wire has
  no geometry for them (only `center_x/y`, `mag`, `pa` and the errors), so redrawing them as vectors
  would be a fit to pixels.
- **Fix state:** `OverlayBoost` (widen and recolour the overlay pixels) was rewritten 2026-10-31 as a soft
  splat: gaps close, edges fade, a stray hue is outvoted by its neighbours. Its first install was a stale
  APK, so the result has not been judged. The raw align JPEGs are dumped to the phone
  (`Android/data/com.nocturne/files/align-frames`, newest 20) to measure the real dash gap; the dump is
  temporary and should be removed once the fix is settled.
- **Check:** a Polar alignment run to Select Star, then pull the frames and look.

### B10 · Goto & center sent the mount the wrong way and Stop did not end it

- **Found:** 2026-10-30, 19:54, from the phone Log and the Pi's `mount-events.log`.
- **Symptom:** Goto & center on M31 (RA 0:44:14, Dec +41:25) right after a polar alignment. The first
  solve put the picture 84° from where the mount said it pointed (mount 23h56m, solution 19h09m), Ekos
  synced the mount to that solution and slewed again, a second time 75° off. Then "Mount homing in
  progress" x14, "Slewing failed", OnStep "Error setting RA/DEC", Under Pole and Meridian limits. Stop
  presses (17 "Mount motion was aborted" alerts to 20:00) did not end it, and at 20:01 Ekos lost the
  mount's serial port.
- **Cause:** not settled. The mount's pointing was off by hours of RA after the polar alignment
  rotations, and the centre loop trusts each solve. Stop only sent `mount_abort`, and Align's loop
  re-slews on its own; the app had no way to end the loop.
- **Fix:** `align_stop` was already in the fork (`Align::abort()`); the app never sent it (the B1 note
  below, that the wire has no align abort, was wrong). Built 2026-10-31: "Abort centering" under Goto &
  center on the Plan tab (`abortAlign`: align stop + mount abort, cancels the centre job) and an
  Emergency stop card on Home under Ekos (`emergencyStop`: mount abort, Scheduler stop and job removal,
  align/capture/guide/focus/polar stop; never moves the mount; Park is a second tap). User checked on 2026-10-31 that the buttons work (Emergency stop on Home, Abort centering on
  Plan); a stop during a real centre loop is still unseen.
- **Check after the fix:** start Goto & center with the mount parked or the way clear, tap Abort
  centering mid-slew: the slew stops and Align does not re-slew. Emergency stop with a job running:
  everything stops, jobs are gone, the mount stays put.

### B9 · "Plate solve here" slewed the mount to Dec +90°

- **Found:** 2026-10-26, evening, from the Pi's KStars log (line of the first `align_solve`).
- **Symptom:** a plain solve was followed by "Slewing to target coordinates: RA 16h52m Dec +90°",
  twice more "Slewing failed" / OnStep "Already in motion", and a bogus "Syncing to RA 01h26m
  Dec 88°" (the solve was indoors). The mount was sent to the pole.
- **Cause:** Ekos keeps the Align solver action (`nothingR`/`slewR`/`syncR`) between runs, and it was
  `slewR` ("slew to target"), so a solve re-slewed to Align's stale target. `plateSolveHere()` sent
  only `align_solve`. The app sets `slewR` for goto-and-center and restores the value it read from
  `align_get_all_settings`; an interrupted run (or a read taken mid-loop) leaves `slewR` as the
  "original" and restores it. Not proven which of the two left it on.
- **Fix:** `plateSolveHere()` forces `nothingR` first when the action is `slewR` or unknown (and
  waits 500 ms for it to apply); goto-and-center never restores `slewR` (back to `nothingR`, or
  `syncR` if that was the user's choice). `solverActionNeedsReset`/`restoredSolverAction`, unit-tested.
- **Check after the fix:** with the Align action set to "Slew to target" on the desktop, Plate solve
  here: the mount stays put and the action reads "Nothing" afterwards. Moving hardware if it goes
  wrong, so only with the mount parked and the user's go-ahead.

### B8 · Finish after a reconnect left the Scheduler running

- **Found:** 2026-10-26, 23:47, from the Pi's KStars log and the phone Log.
- **Symptom:** Finish sent `scheduler_remove_jobs` twice and Ekos refused ("Cannot delete currently
  running job"). The Scheduler kept running: it restarted guiding ("attempt #1 of 5") on a parking
  mount, so OnStep answered "Cannot guide while slewing or parking in progress" and the guider
  logged "Lost track of the guide star" and "calibration failed" every 5–10 s. The first Finish at
  22:59 worked (phone Log: "Scheduler status 2").
- **Cause:** the phone Log shows `End session: Scheduler status unknown` at 23:47. `get_states` has
  no Scheduler state (`Message::sendStates` covers Capture, Mount, Focus, Guide, Align, Polar), so after
  the message channel reconnected (three "Websocket connection error" lines that evening) the status
  stayed null until the next transition, and `stopSchedulerIfRunning()` only stops on a known
  running status.
- **Fix:** `AppState.schedulerIsRunning()`: unknown status falls back to the job list, a Busy job
  means the Scheduler is running. End session and Finish use it. Unit-tested. Not changed: the stop is
  still assumed synchronous before the remove (it was at 22:59).
- **Check after the fix:** reconnect the app while a job runs, then Finish: the Scheduler stops and
  the job is removed. Needs a running job, so under the sky or a deliberate test.

### B6 · A new session starts imaging with the cooler off

- **Found:** 2026-10-26, night. After aborting a session, the user created a new one and started it;
  the cooler was off and nothing said so.
- **Symptom:** job 1 frames were `-0C`/`0C` (cooled). Job 2 (`M_31_j2_20261026_230034`, H) came out
  as `H_180sec_15C_1.jpg`, and the Camera page at 23:08 read "not cooling · 0%", sensor 16.4 °C,
  set point 0.0, "Cooler ON" offered.
- **Cause (confirmed 2026-10-26 from the Pi's KStars log):** the abort went through the Summary
  sheet's **Finish**, which runs `finishNight()`: the log shows `scheduler_start_job` (a stop),
  `scheduler_remove_jobs`, `mount_park`, then `device_property_set CCD_COOLER COOLER_OFF` within about
  fifteen lines, around 22:59. That is by design (Finish = stop, park, cooler off). The new session
  then started with no cooler step, so job 2 shot warm until the user pressed Cooler ON (log:
  `COOLER_ON`, `cameraTemperatureS true`, `cameraTemperatureN 0.0`, a few minutes after 23:05).
  The same Finish sequence ran again near 23:48. Also: the park moved the mount, so Ekos logged
  "Mount is moving. Resetting calibration..." and the next start calibrated again.
- **Fix (planned):** plan 4, N1: nothing after Finish brings the cooler back, and the Finish button
  does not say it turns the cooler off. Same family as B2 (a Preview starting the cooler by surprise).
- **Fix state:** built 2026-10-26 (`coolerStartPlan`, unit-tested), installed; the start step was rig-checked 2026-10-27 (cooler on at Start Scheduler); the first-sub hold is not yet seen.
- **Check after the fix:** abort a session with the cooler on, start a new one; the cooler is on
  (or the start says it is off) and the first sub waits for the set point.

### B7 · Guiding oscillates in RA and the app has no knob for it

- **Found:** 2026-10-26, night, M31, internal guider.
- **Symptom:** at 22:41 RMS was 0.86″ (RA 0.80″, DEC 0.30″). At 22:56 it was 3.33″ (RA 3.27″,
  DEC 0.63″) and at 22:57 4.10″ (RA 4.06″, DEC 0.62″, exposure 1.0 s), with a regular RA swing of
  about ±7–10″; DEC stayed quiet. The Controls bar showed "Cam Suspended" at 22:57. The user reads
  it as over-correction: the guider reacts too hard and does not settle.
- **Evidence (Pi analyze file `ekos-2026-10-26T21-42-28.analyze`, 5-minute windows):** guiding of job 1
  (calibrated 22:41:12, 23 s) ran at RA RMS 2.0–2.4″ from the start, 3.35–3.67″ by 22:52–22:57, mean RA
  pulse 113 → 255 ms, with a 264″ outlier window at 22:47. Job 2 (new calibration 23:05, 30 s),
  same Ekos gains (RA P 0.70, DEC P 0.55, max pulse 35″, exposure 1 s per `kstarsrc`), ran at RA
  0.69″, mean pulse 35 ms, creeping to 1.1–1.4″ later. So the gains are not the whole story: the
  bad run followed the first calibration (or the guide exposure changes the app showed that night:
  0.5 s → 2.0 s → 1.0 s). Not yet decided between a poor calibration and loop gain at the changed
  exposure.
- **Gap:** `WireGuideSettings` (`EkosEvent.kt`) and the Guide settings sheet cover only threshold,
  dither, frame and calibration; no algorithm parameters, so the user had no way to tame it from
  the phone.
- **Fix state:** plan 4, N2 built 2026-10-27 (Guiding tuning in the Guide sheet); rig check pending.
- **Check after the fix:** lower RA gain on a running guide; the RA trace settles and RMS drops.

### B1 · Plate solve can leave Ekos "In Progress" after a failed solve

- **Found:** 2026-10-25, daytime, Plate solve here on the connected rig.
- **Symptom:** the solve ends with the alert "Astrometry alignment failed", but the Plate solve card
  keeps saying `solving…` and stays pinned under ACTIVE in the Controls menu; the status stays "In
  Progress" for minutes. The Plate solve page has no way to stop it.
- **Cause:** Ekos itself. Read over DBus on the Pi (`org.kde.kstars.Ekos.Align` `status`) the Align
  module was still `4` (`ALIGN_PROGRESS`) after the failure alert, so the app shows the real state.
  Why Ekos does not go to Failed is not known. The fork already has `align_stop` (`Align::abort()`); the
  app did not send it until B10's fix.
- **Workaround:** Ekos' own Stop on the desktop, or on the Pi
  `gdbus call --session --dest org.kde.kstars --object-path /KStars/Ekos/Align --method org.kde.kstars.Ekos.Align.abort`
  (done 2026-10-25: status became `3`, `ALIGN_ABORTED`, the card showed "Aborted" and unpinned).
- **Fix:** built 2026-10-31, not rig-checked: a Stop button on the Plate solve page while a solve is
  active (`In Progress`/`Syncing`/`Slewing`/`Rotating`), and the Plate solve card in the Controls menu has
  its own stop while solving. Both call `abortAlign` (`align_stop` + `mount_abort`, see B10). The menu card then unpins by itself when the status leaves the active set. Find out why
  Align does not reach Failed (Align log in the fork) before deciding whether the button is enough.
- **Check after the fix:** daytime solve, wait for the failure alert, Stop; status leaves "In
  Progress" and the menu card unpins.

### B2 · A held Preview starts the camera cooler

- **Found:** 2026-10-25, Preview Main Cam with Enforce temperature on and the cooler off.
- **Symptom:** Preview does not expose; the card says "status: Setting Temperature" and a Cancel
  button appears. After Cancel the capture is idle again, but the cooler is on (sensor 29 °C → about
  0 °C, power 30–40 %) without anyone pressing Cooler.
- **Cause:** Ekos' "Setting Temperature" step sends the set point to the camera, which turns the
  cooler on; cancelling the held capture does not undo that. The held-capture note only says "waiting
  for temperature … before exposing", not that cooling has started.
- **Fix:** built 2026-10-31, not rig-checked. The held note now says "The cooler is running for this
  Preview; Cancel switches it off." and Cancel (`capture_stop`) also sends cooler off, but only when the
  cooler was not known to be on when the Preview was requested (`coolerOffAtPreview`), so a cooler the
  user already had on is left alone. Decided with the user 2026-10-31 (Cancel switches the cooler off).
- **Check after the fix:** cooler off, Preview with Enforce temperature on, Cancel: the cooler is off
  and the note matched. With the cooler on first: Cancel leaves it on.

## Fixed

### B3 · The Back key left a sheet open and acted on the screen behind it

- **Found:** 2026-10-25, with the settings button on the new Controls pages. Back moved the page
  behind the Focus settings sheet to the menu, then to Home, with the sheet still open.
- **Cause:** sheets did not handle the system Back key at all (only the Log, a running job's summary
  and an expanded frame did).
- **Fix:** `BackHandler(onBack = ctrl::closeSheet)` in `SheetHost`, so Back closes a sheet first, for
  every sheet (`80ecdb6`). Checked on the phone: Back closes the sheet, then the page, then leaves
  the tab.

### B4 · The Focuser hint showed a fixed "backlash 90 out"

- **Found:** 2026-10-25, while planning the Focuser page; the Focus settings sheet showed driver
  backlash 0 and AF overscan 100.
- **Cause:** the hint line was a literal string, not read from Ekos.
- **Fix:** it now reads `focusBacklash` from the Focus settings, "N/A" until known (`1df6bb8`). Seen
  on the rig: "backlash 0".

### B5 · The Mount menu card said "raw -1" for an unknown pier side

- **Found:** 2026-10-25, first look at the live one-liners.
- **Cause:** `mountPierSideLabel` returns `raw N` for any value it cannot name, and `-1` means
  unknown.
- **Fix:** the card shows the pier side only when it is East or West (`93c1c84`, with a unit test).
