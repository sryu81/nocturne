# Bugs

Bug reports and their fixes. `docs/STATUS.md` says what works and what is unverified; this file keeps
the detail of each defect: what happened, why, the workaround, and the fix (done or planned). Newest
first within each list. Commits are on branch `controls-revision` unless noted.

## Open

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
- **Fix state:** built 2026-10-26 (`coolerStartPlan`, unit-tested); installed and rig check pending.
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
- **Fix (planned):** plan 4, N2.
- **Check after the fix:** lower RA gain on a running guide; the RA trace settles and RMS drops.

### B1 · Plate solve can leave Ekos "In Progress" after a failed solve

- **Found:** 2026-10-25, daytime, Plate solve here on the connected rig.
- **Symptom:** the solve ends with the alert "Astrometry alignment failed", but the Plate solve card
  keeps saying `solving…` and stays pinned under ACTIVE in the Controls menu; the status stays "In
  Progress" for minutes. The Plate solve page has no way to stop it.
- **Cause:** Ekos itself. Read over DBus on the Pi (`org.kde.kstars.Ekos.Align` `status`) the Align
  module was still `4` (`ALIGN_PROGRESS`) after the failure alert, so the app shows the real state.
  Why Ekos does not go to Failed is not known. The wire has no align abort command (`Commands.kt` has
  `align_solve` only).
- **Workaround:** Ekos' own Stop on the desktop, or on the Pi
  `gdbus call --session --dest org.kde.kstars --object-path /KStars/Ekos/Align --method org.kde.kstars.Ekos.Align.abort`
  (done 2026-10-25: status became `3`, `ALIGN_ABORTED`, the card showed "Aborted" and unpinned).
- **Fix (planned):** a fork command `align_abort` that calls `Align::abort()` (see `docs/FORK.md`
  backlog), and a Stop button on the Plate solve page while a solve is active, next to "Plate solve
  here". The menu card then unpins by itself when the status leaves the active set. Find out why
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
- **Fix (planned, app only):** say it in the note ("… cooling to X °C started"), so a Preview is not
  a silent cooler start. Whether Cancel should also switch the cooler off needs a decision, since
  the user may want it cooling.

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
