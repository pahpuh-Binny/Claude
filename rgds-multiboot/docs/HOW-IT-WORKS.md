# How it works

## Boot chain

1. With a microSD card inserted, the RG DS starts the Rockchip loader at sector
   64 (32 KiB) of the card. This is what the official Anbernic Linux, ROCKNIX and
   Armbian rely on. Without a card it starts Android from the eMMC. Nothing in
   this repo writes to the eMMC's bootloader.
2. U-Boot (ROCKNIX's, or the mainline build from `build-uboot.sh`) finds the
   partition flagged bootable (p1) and reads `extlinux/extlinux.conf`.
3. The `DEFAULT` line picks the entry: ROCKNIX's original entry, or `KALI`.

### Why there is no boot menu

U-Boot for this board has no display driver, so a menu would be invisible. Worse,
in U-Boot's extlinux parser any top-level `MENU` line turns on the interactive
prompt. With two entries and no timeout, that waits forever on a screen you
cannot see. `make-sd-image.sh` therefore strips `MENU`/`PROMPT`/`TIMEOUT` lines
from ROCKNIX's config and writes `PROMPT 0` plus a single `DEFAULT`. With prompt
off, U-Boot boots the default immediately. If that entry fails to load (say a
missing file), U-Boot tries the other entries in order, so a broken Kali falls
back to ROCKNIX.

`rgds-switch-os` edits only that one line. In ROCKNIX it remounts `/flash`
read-write for the moment it needs it.

## Card layout

```
sector 0-33      GPT
sector 64-…      Rockchip loader + U-Boot (copied from ROCKNIX, or u-boot-rockchip.bin)
p1  16 MiB-      FAT32, label/serial kept from ROCKNIX ("ROCKNIX"); bootable flag
                   KERNEL, SYSTEM, device_trees/   ROCKNIX, untouched
                   kali/Image, initrd.img, .dtb    Kali kernel
                   extlinux/extlinux.conf          boot choice
                   rgds/rgds-switch-os, README.txt
p3               ext4 "KALI", fixed size
p2               ext4 "STORAGE", ROCKNIX's data and ROMs, physically last
```

These choices follow from how ROCKNIX boots and resizes (read from
ROCKNIX/distribution, branch `next`):

- **ROCKNIX's kernel line is `boot=LABEL=ROCKNIX disk=LABEL=STORAGE`.** The FAT
  partition is rebuilt larger (to make room for the Kali kernel) with the same
  label and serial. STORAGE keeps its label and UUID.
- **ROCKNIX's first-boot resize is hard-wired to partition 2**
  (`resizepart 2 100%`), then re-creates the filesystem. So STORAGE stays
  partition *number* 2 but sits *physically last*, the only place it can grow.
  The `.please_resize_me` flag is removed and STORAGE is pre-made instead, so
  ROCKNIX's resize never wipes the Ports entries placed there. Kali's
  `rgds-sd-setup` service grows STORAGE to fill the card the first time Kali
  boots, while STORAGE is unmounted.
- **ROCKNIX updates rewrite `FDT` lines** in extlinux.conf (its
  `bootloader/update.sh` moves device trees to `/device_trees`). The Kali entry
  uses the equivalent `devicetree` keyword, so updates leave it alone.
- On a Kali-only card, KALI is last and grows itself online.

## Kali kernel updates

The kernel is a normal Debian package. `/etc/kernel/postinst.d/zz-rgds-multiboot`
and `/etc/initramfs/post-update.d/zz-rgds-multiboot` call `rgds-kernel-sync`. It
decompresses the kernel to the `Image` U-Boot needs, then copies it, the initrd
and the RG DS `.dtb` into `kali/` on the boot partition, mounted at
`/boot/rgds-multiboot`. Files are written as `*.new` and renamed, and a kernel
that lacks the RG DS device tree is refused, so a bad upgrade leaves the previous
kernel in place.

The root filesystem is found by `root=PARTUUID=…`, and the SD controller and ext4
are built into the rgds kernel, so Kali boots even if the initrd is broken.

## Hardware notes (from the mainline device tree)

| Part | Detail |
|---|---|
| Screens | two Jadard JD9365DA-H3 640×480 panels: bottom on DSI0/VP0, top on DSI1/VP1 |
| Touch | two Goodix GT911: bottom on I2C5, top on I2C3 (`rgds-display-setup` maps them) |
| Wi-Fi/BT | Realtek RTL8821CS (SDIO Wi-Fi via `rtw88_8821cs`, UART Bluetooth) |
| Audio | RK817 codec + two AW87391 speaker amps |
| Controls | GPIO buttons (A/B/X/Y, D-pad, L1/L2/R1/R2, Start/Select, L3/R3, Home, volume), two ADC sticks, lid switch, rumble |
| Console | `ttyS2` at 1500000 baud, not reachable; use the USB gadget console `ttyGS0` |

### USB-C dual role

Mainline sets the USB-C controller to `peripheral` so the gadget serial console
always works. Kali users want USB host mode as well (Wi-Fi adapters, Ethernet,
keyboards, hubs). `build-kernel.sh` appends `config/kernel/rgds-usb-otg.dtsi`. It
sets `dr_mode = "otg"` and gives the OTG PHY the RK817 boost regulator so it can
power devices. This matches Armbian's `board-rgds-04-usb-otg-dual-role.patch`,
reported to keep the gadget console working on real hardware. Use `--no-otg` to
skip it.
