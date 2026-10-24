# Persistent journal

Raspberry Pi OS keeps the systemd journal in RAM (`Storage=volatile` in
`/etc/systemd/journald.conf`), so a reboot or power cut wipes everything:
pier-guard output, USB drops, kernel messages. After the 2026-10-24 power
cut there was nothing left to explain the failed M31 meridian flip.

`90-nocturne-persistent.conf` is a journald drop-in that overrides that:
the journal is written to `/var/log/journal`, capped at 200 MB, with 1 GB
always left free on the SD card, and flushed every 30 s so a power cut
loses at most that much.

## Install

```
scp -r pi-tools/persistent-journal <user>@<rig-ip>:~/persistent-journal
ssh <user>@<rig-ip>
cd persistent-journal
sudo ./install.sh
```

Read the previous boot: `journalctl -b -1` (`journalctl --list-boots` lists
them). The Pi has no RTC, so a boot that starts without network time is
stamped with the last fake-hwclock save until the clock is set.
