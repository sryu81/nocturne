# HiPS mirror

Downloads the DSS2 colour HiPS survey (CDS) down to a chosen order, in the survey's own layout
(`Norder<k>/Dir<d>/Npix<n>.jpg`). Tiles are FOV-independent: any scope, rotation or mosaic is
cropped from them.

**Run it on the laptop, never on the rig**: the rig's uplink can be a prepaid SIM with a 1 GB cap.
Pin it to the unmetered interface so a dropped network fails instead of rerouting:

```
HIPS_SOURCE_ADDR=<laptop IPv4 on the home network> \
  python3 pi-tools/hips-mirror/hips_mirror.py ~/astro-data/hips/DSSColor 5
```

Resumable (existing tiles are skipped), ~5 tiles/s, polite to CDS.

Sizes measured 2026-10-24 (tiles grow with order):

| Orders | Tiles | Size | Used by |
|---|---|---|---|
| 0–4 | 4,092 | ~211 MB | packed into the APK (app/build.gradle.kts) |
| 5 | 12,288 | ~900 MB | phone storage (`tools/push_sky_atlas.sh`), Pi |
| 6 | 49,152 | ~3.4 GB (est.) | not downloaded yet (future plan) |

Where the copies go:
- Pi (KStars offline HiPS + the app's "copy from rig"): `rsync -rt --ignore-existing
  ~/astro-data/hips/DSSColor/ <pi>:.local/share/kstars/hips/DSSColor/`, then in `~/.config/kstarsrc`
  `[HIPS]`: `HIPSUseOfflineSource=true`, `HIPSOfflinePath=<that folder>`, `HIPSSource=DSS Colored`,
  `ShowHIPS=true` (edit with KStars stopped; KStars offline mode only supports DSS Colored).
- Phone: orders 0–4 come with the APK; `tools/push_sky_atlas.sh` loads order 5 over USB.
