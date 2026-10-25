# Bugs

Bug reports and their fixes. `docs/STATUS.md` says what works and what is unverified; this file keeps
the detail of each defect: what happened, why, the workaround, and the fix (done or planned). Newest
first within each list. Commits are on branch `controls-revision` unless noted.

## Open

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
