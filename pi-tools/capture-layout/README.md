# Capture layout migration

Moves old capture folders into the Nocturne layout (`docs/PLAN.md`, "File layout rule"):

```
<drive>/Nocturne/<Target>/<YYYY-MM-DD>/<FrameType>/<Filter>/<file>.fits
```

Old folders are `<Target>_j<N>_<yyyymmdd>_<hhmmss>/<FrameType>/<Filter>/…` straight in the home
folder (or in `/mnt/astro/images`, behind symlinks left by the old `move-images.sh`).

## What it does

- **Target and night.** The target is the folder name without `_j<N>_<stamp>`. The night is the local
  date the night began: the stamp minus 12 hours, so `M_31_j2_20261030_201431` is the night of
  2026-10-30 and a stamp of `…_20261025_001500` belongs to 2026-10-24. A folder without a stamp
  (`M_33`) takes the date of its newest file.
- **Merging.** Several jobs of one target on one night end up in one folder. File names are never
  changed; if a name already exists the rest of that folder is skipped and left where it was.
- **No copying.** A folder is renamed into the `Nocturne` folder of the drive it is on, so nothing
  is half-written and a power cut leaves every file in either the old or the new place. A symlink
  from `move-images.sh` is followed to the stick, and removed once its folder is empty. Moving a
  night to another drive is the file manager's job.
- **Which folders.** Only folders with a `Light`, `Dark`, `Flat` or `Bias` subfolder. Anything else
  in the home folder (tools, logs, FireCapture) is left alone. `--only NAME` takes a folder that
  does not look like that.
- **Safe to repeat.** A second run finds nothing left to move.

## Use

Dry run first; it prints each folder and where it would go, and changes nothing:

```
ssh <user>@<rig-ip> 'bash -s' < pi-tools/capture-layout/migrate.sh
```

Then run it for real (no capture or Scheduler run may be going on, and not into a folder Ekos is
writing to):

```
ssh <user>@<rig-ip> 'bash -s -- --run' < pi-tools/capture-layout/migrate.sh
```

Other sources: `--src /mnt/astro/images`, one folder: `--only M_33`, another destination:
`--dest /somewhere/Nocturne` (must end in `Nocturne`).
