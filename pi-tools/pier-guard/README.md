# Pier guard

Keeps OnStep's **Preferred Pier Side** on **East**, which Ekos's meridian
flip needs on this rig. With "Best", OnStep stays on the current side when it
can, so the flip re-slew does nothing.

OnStep can't remember this setting: `:SX96` (what the INDI switch sends) only
changes RAM, and the power-on value is the compile-time
`PIER_SIDE_PREFERRED_DEFAULT` (stock `BEST`). INDI doesn't save it either, so
every mount power cycle brings back Best. The only permanent fix is
reflashing OnStep with `#define PIER_SIDE_PREFERRED_DEFAULT EAST`; this
daemon is the no-reflash alternative.

It's a plain INDI client of the local indiserver (port 7624): whenever any
device reports a "Preferred Pier Side" property that isn't East, it switches
it to East and logs it. It retries every 5 s while indiserver is down (Ekos
stopped), so it covers every profile start and mount reconnect. Devices
without that property (non-OnStep mounts) are ignored.

East is correct in both hemispheres: OnStep chooses the side from the
target's hour angle (`Goto.ino`), and East means "the post-flip side for
targets west of the meridian" north or south.

The Nocturne app also checks this setting and raises an alert if it ever
sees something other than East (it fixes it too, while connected).

## Install

```
scp -r pi-tools/pier-guard <user>@<rig-ip>:~/pier-guard
ssh <user>@<rig-ip>
cd pier-guard
sudo ./install.sh
```

Installs to `/opt/nocturne-pier-guard/` with a systemd unit
(`nocturne-pier-guard.service`) that starts on boot. Re-running is safe.

Log: `journalctl -u nocturne-pier-guard -f`

## Mount event log

The guard also logs what the mount does, one line per change: pier side, slew state (coords
Idle/Ok/Busy/Alert with RA/DEC), tracking, park, Preferred Pier Side, AutoFlip, OnStep's error
field and the mount driver's messages. It warns when a slew stays Busy for more than 5 minutes
(a flip takes about 80 s), the sign of a hung mount or serial link.

- File: `~/nocturne-logs/mount-events.log` (5 MB × 10 rotated files;
  override with `PIER_GUARD_LOG`). Same lines also go to the journal.
- Driver messages are the errors/warnings/info lines the driver's own INDI
  log file would hold at default levels. That file isn't used: it needs the
  driver's DEBUG switch, and Ekos turns DEBUG back off unless its INDI
  logging settings include the mount (Settings → Logs).

Pair it with `pi-tools/persistent-journal` so the journal survives a
power cut too.
