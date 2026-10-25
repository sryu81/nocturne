# KStars fork (EkosRemote)

Nocturne needs a KStars fork: stock KStars only *dials out* to the EkosLive cloud service. The fork
replaces that client with **EkosRemote**, a server inside KStars that listens on the LAN (port
9000) and reuses KStars' own `Message`/`Media` protocol code, so a phone can connect directly.

- Source: `/home/soo/cc/repo/kstars` (KStars 3.8.3), branch `ekosremote-fork`, pushed to
  `github.com/sryu81/kstars`. Wire protocol: `docs/PROTOCOL.md`.

## What the fork adds on top of KStars 3.8.3

| Commit | Change |
|---|---|
| `9664c0142` | EkosRemote listen-mode server (replaces the EkosLive dial-out client) |
| `1e362dada` | Clear stale meridian-flip state that leaked into later jobs |
| `759a53e5c` | `align_set_target_pa`, `kstars_set_location`, `kstars_set_time`; `focus_out` honours `steps` |
| `4b199d984` | Guide stats/RMS in `new_guide_state`; `guide_calibrate` / `guide_dither` / `guide_suspend` / `guide_resume`; `guide_set_calibration_settings` max-move fix |
| `25cc0cd4e` | Meridian flip timeout: a flip slew still running after max(300 s, MinFlipDuration + 120 s) aborts the mount, raises a `MeridianFlipFailed` alert and ends in `MOUNT_FLIP_ERROR` (capture aborts, scheduler error policy takes over) |
| `15de51474` | Tracking off without the confirmation dialog (remote `mount_set_tracking` used to wait on a modal box on the Pi) |

## Divergence from upstream (checked 2026-10-24)

- **Base**: `stable-3.8.3` (`61d849b04`, 2026-06-14). The fork is 7 commits ahead, 0 behind:
  34 files, +898 / −2,139 lines, of which ~1,830 deleted lines are the old EkosLive cloud client.
- **Upstream `master`** (KDE/kstars) is 420 commits ahead of that base; `stable-3.8.3` branched
  from master at `5ea17ac58` (2026-06-01) and carries 3 release-only commits.
- **Trial merge with `master`** (`git merge-tree`, in a scratch clone): 11 conflicting files caused
  by the fork, all from the EkosRemote server commit — the deleted `ekoslive/ekosliveclient.*` and
  `nodemanager.*` (upstream still edits them; port by hand into `ekosremote/`), `message.cpp`,
  `commands.h`, `media.cpp`, `manager.h` (4 blocks), `manager.cpp`, `org.kde.kstars.Ekos.xml`,
  `.gitignore`. Four more conflicts (`indidrivers.xml`, `schedulermodulestate.*`, `fitsdata.cpp`)
  also occur for stock `stable-3.8.3` vs `master`.
- The small fixes (flip timeout, stale flip state, tracking dialog; edits to `camerastate`,
  `cameraprocess`, `meridianflipstate`, `mount.cpp`, `focus.cpp`, `kstars.kcfg`) merge cleanly.
- **So**: staying on 3.8.x (rebase onto a later 3.8.x tag, cherry-pick fixes) is cheap; moving to
  `master` means redoing the EkosRemote commit against ~70 upstream commits in `ekoslive/` and
  `manager.cpp`.
- Re-check: `git remote add upstream https://github.com/KDE/kstars.git`, fetch with
  `--filter=blob:none` in a scratch clone, `git merge-tree --write-tree --name-only <fork> upstream/master`.

## Build and deploy

```bash
cd ~/cc && docker start rpi5-builder && make fix-env && make kstars-deb   # ~10–15 min
# → ~/cc/deb/kstars-custom_3.8.3_arm64.deb
scp ~/cc/deb/kstars-custom_3.8.3_arm64.deb <pi>:/tmp/
ssh <pi> sudo apt-get install -y --reinstall /tmp/kstars-custom_3.8.3_arm64.deb
```

Then restart KStars on the Pi (it runs in VNC display `:1`):

1. Stop Ekos: `gdbus call --session --dest org.kde.kstars --object-path /KStars/Ekos --method org.kde.kstars.Ekos.stop`
   (`DBUS_SESSION_BUS_ADDRESS` from `/proc/$(pgrep -x kstars)/environ`).
2. `pkill -TERM -x kstars`, and kill any leftover `indiserver` (it survives and blocks port 7624).
3. `DISPLAY=:1 DBUS_SESSION_BUS_ADDRESS=<same> setsid kstars &`
4. Start the profile again: `…Ekos.setProfile "<name>"` then `…Ekos.start`.

Rollback builds: `~/cc/deb/kstars-custom_3.8.3_arm64.deb.prev-2026-07-27`, `.prev-2026-10-24`
(the build before the flip timeout).

## Backlog (open)

1. **Per-line log push** — each module's `log` field resends the whole history on every line;
   push one line at a time (`{module, time, level, text}`) plus a `get_logs` snapshot, and forward
   the Ekos main log, the Mount log and INDI driver messages. See `docs/PLAN.md`.
2. **Preview size** — captures reach the app at 1920 px, focus/align/guide frames at 960 px with
   fast scaling (`media.cpp`). Raise the cap (e.g. 3112 px / smooth scaling) or let the client
   choose.
3. **Graceful scheduler pause** — `Scheduler::pause()` (`scheduler.cpp`) defers instead of aborting
   the job, but it is `protected` and not on the wire; the app uses Stop/Start meanwhile. Add a
   `scheduler_pause` command.
4. **Meridian-flip countdown** — `Mount::hourAngle()` and `meridianFlipValue()` exist but can't be
   read (`invoke_method` drops return values). Push them, e.g. in `new_mount_state`.
5. **Bogus flip planned at home** — Ekos plans a meridian flip after a goto from the home position
   (HA ≈ 6 h, pier side unknown). Investigate `MeridianFlipState::checkMeridianFlip`.
6. **`polar_set_algorithm` no-op** — looks up widget `PAHRefreshAlgorithmCombo`, the real name is
   `pAHRefreshAlgorithm`; also a missing `else` in the same function. The app uses
   `align_set_all_settings` instead, so low priority.

Not planned: authentication on port 9000 (trusted LAN; the app has no auth anywhere), mDNS
discovery (the UDP beacon covers it), dome control (no dome on this rig).

Rig item outside the fork: reflash OnStep with `PIER_SIDE_PREFERRED_DEFAULT EAST` so the pier
guard is no longer needed after a mount power cycle.
