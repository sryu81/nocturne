# USB automount

The Pi mounts only the one stick listed in `/etc/fstab` (`UUID=824F-1D2E` at `/mnt/astro`), and only
if it was in at boot. `udisks2` is not running, so any other stick is seen by the kernel but never
mounted, and the Rig storage screen (plan 3) would not list it.

A udev rule mounts any USB stick when it is plugged in and unmounts it when it is pulled.

- Mount point: `/media/<label>` (label with anything but letters, digits, `.`, `_`, `-` turned into
  `_`; the UUID when the stick has no label; `-<kernel name>` added when two sticks share a label).
- The fstab stick keeps `/mnt/astro`, now also when plugged in after boot.
- Files are owned by `soo` (`uid`/`gid`/`umask=022`) for FAT32, exFAT and NTFS. ext4 sticks keep
  their own ownership.
- NTFS uses the kernel `ntfs3` driver; exFAT the kernel `exfat` driver. Both are modules in the Pi's
  kernel (6.12.75+rpt-rpi-2712), checked 2026-10-31; `vfat` and `ext4` are built in.
- Pulling a stick unmounts it lazily (`umount -l`) and removes its empty `/media/<label>` folder.
  Pull a stick only when nothing is copying to it; the fork's `eject` (plan 3, R5) does this safely.

## Install

```
scp -r pi-tools/usb-automount <user>@<rig-ip>:~/usb-automount
ssh <user>@<rig-ip>
cd usb-automount
sudo ./install.sh
```

Installs `/usr/local/bin/nocturne-usb-mount` and `/etc/udev/rules.d/99-nocturne-usb.rules`, then
reloads udev. Nothing is downloaded or installed from the internet. Remove both files and run
`sudo udevadm control --reload` to undo.

## Check

Plug a stick in, then:

```
findmnt -rn -o SOURCE,TARGET,FSTYPE,OPTIONS | grep /media
journalctl -t nocturne-usb -n 20
```

Pull it and the `/media/<label>` line is gone. `journalctl -t nocturne-usb` shows every mount and
unmount with the device and filesystem.

The script can be dry-run on any machine without root:
`NOCTURNE_USB_DRY_RUN=1 NOCTURNE_USB_DEVDIR=<dir with image files> NOCTURNE_USB_ROOT=<dir> ./nocturne-usb-mount add <file name>`.
