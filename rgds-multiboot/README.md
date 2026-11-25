# RG DS multiboot: Android + ROCKNIX + Kali Linux

Tools to turn an **Anbernic RG DS** (Rockchip RK3568, 3 GB RAM, two 640×480
touchscreens) into a triple-boot handheld:

| OS | Where it lives | Good for |
|---|---|---|
| **Android** (stock 14, GammaOS, or a newer GSI) | internal eMMC, untouched by the card | Android apps, streaming, stock DS setup |
| **ROCKNIX** | microSD card | Emulation. It is the strongest Linux option for this console, with dual-screen DS support |
| **Kali Linux** (arm64, Xfce) | the same microSD card | Security tools, a full Linux desktop on both screens |

You can also make a **dual-boot** card (Android + Kali) without ROCKNIX.

> **Status: untested on hardware.** Everything that can be checked off-device is
> automated and passes: the card image layout, boot config, OS switcher, kernel
> update hooks, the USB-C device-tree change, the gamepad mapping and the Android
> pre-checks (see [Testing](#testing)). Nothing here has been booted on a real
> RG DS yet. The design never writes to the eMMC bootloader, and the Android
> steps start with a full backup. Read [docs/ANDROID.md](docs/ANDROID.md) before
> you touch internal storage.

## How the boot choice works

```
power on ──► card inserted? ──no──► Android (internal eMMC)
                 │
                yes
                 ▼
   U-Boot on the card reads extlinux.conf ──► DEFAULT ROCKNIX  or  DEFAULT KALI
```

U-Boot on the RG DS cannot draw a menu, so the card remembers which Linux to
start. You switch like this:

| From | To | How |
|---|---|---|
| ROCKNIX | Kali | **Ports → Switch to Kali** |
| Kali | ROCKNIX | app menu **Reboot into ROCKNIX**, or `sudo rgds-switch-os rocknix` |
| either | Android | **Shut down for Android**, then remove the card and power on |
| Android | Linux | insert the card and power on |
| a PC | anything | edit the `DEFAULT` line in `extlinux/extlinux.conf` on the card's FAT partition |

Card layout (partition numbers are deliberately out of disk order; see
[docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md)):

```
| U-Boot @32 KiB | p1 FAT "ROCKNIX" 2 GB | p3 ext4 "KALI" | p2 ext4 "STORAGE" (ROMs) → grows to fill the card |
                   ROCKNIX system,          Kali root fs
                   Kali kernel, boot config
```

## Quick start (triple boot)

On a Linux PC (or WSL2/Docker; see [docs/BUILDING.md](docs/BUILDING.md)):

```bash
# 1. Kernel for Kali (mainline Linux, which carries the RG DS device tree)
scripts/build-kernel.sh                       # -> out/kernel/*.deb

# 2. Kali root filesystem (about 10 GB, 30-90 min under emulation)
sudo scripts/build-kali-rootfs.sh --profile default

# 3. Download the ROCKNIX image that supports the RG DS (guides currently point
#    to the RK3566 nightly build), then put everything on one card image
sudo scripts/make-sd-image.sh --rocknix ROCKNIX-RK3566.*.img.gz \
     --kali out/kali-rootfs -o rgds-triple.img

# 4. Write it to a microSD card (64 GB or larger recommended)
sudo dd if=rgds-triple.img of=/dev/sdX bs=4M conv=fsync status=progress
```

First boot starts ROCKNIX (use `--default kali` to change that). The first time
you boot Kali, it grows ROCKNIX's STORAGE partition to fill the rest of the card.
Kali login: `kali` / `kali`. Change it.

**Dual boot (Android + Kali only):** build U-Boot with `scripts/build-uboot.sh`,
then `sudo scripts/make-sd-image.sh --uboot out/uboot/u-boot-rockchip.bin --kali out/kali-rootfs -o rgds-kali.img`.

## About "the latest Android"

The newest Android is 17 (June 2026). No Android newer than 14 has been built for
the RG DS: Anbernic ships 14, and GammaOS Next for the RG DS is Android 14
(LineageOS 21). A true port means building AOSP 16/17 with a Rockchip board
support package for that Android version, plus Anbernic's device configuration
and closed GPU/vendor blobs. None of those are public for the RG DS, and the
build also needs a ~500 GB machine and the device on the bench. This repo does
not attempt that.

The realistic route to Android 16/17 is a **Generic System Image (GSI)** on top
of the stock Android 14 vendor partition. The repo includes:

- `android/preflight.sh`: read-only check of whether your firmware can take a GSI
- `android/backup-emmc.sh` / `restore-emmc.sh`: full internal-storage backup and restore
- `android/flash-gsi.sh`: guarded flashing via fastbootd

Expect the second screen and Anbernic's controller tweaks not to work on a GSI.
For daily use, stock Android 14 or **GammaOS Next** remains the better Android.
Details: [docs/ANDROID.md](docs/ANDROID.md).

## Docs

- [docs/BUILDING.md](docs/BUILDING.md): requirements, Docker route, every option
- [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md): boot chain, partition layout, ROCKNIX compatibility, kernel updates
- [docs/KALI.md](docs/KALI.md): using Kali on a handheld: controls, screens, Wi-Fi, USB-C, rescue console
- [docs/ANDROID.md](docs/ANDROID.md): Android options, GSI procedure, backup and recovery

## Testing

```bash
sudo make test
```

This runs shellcheck, then the tests. Without hardware or network access they:

- build a triple-boot and a Kali-only card from a mock ROCKNIX image and a mock
  rootfs, then check the partition table, bootloader placement, labels and UUIDs,
  boot config, removed resize flag, ROCKNIX Ports entries and fsck results
- switch the default OS back and forth
- run the kernel-update hook against a fake rootfs
- compile the USB-C device-tree override with `dtc`
- unit-test the gamepad-to-mouse mapping

CI runs the same suite (`.github/workflows/rgds-multiboot.yml`).

## Layout

```
scripts/        build-kernel.sh, build-uboot.sh, build-kali-rootfs.sh, make-sd-image.sh
config/kernel/  kernel config fragment + USB-C dual-role device-tree override
overlay/        files installed into Kali (switcher, kernel hooks, screen/touch setup,
                gamepad mouse, on-screen keyboard, SD upkeep service)
rocknix/ports/  "Switch to Kali" / "Shut down for Android" entries for ROCKNIX
android/        preflight, eMMC backup/restore, GSI flashing
tests/          the test suite above
```

## Credits

The kernel device tree for the RG DS is upstream Linux work (Chris Morgan's
"Add Anbernic RG-DS" series). The U-Boot recipe and the USB-C dual-role change
follow Armbian's RG DS support (`config/boards/anbernic-rg-ds.conf`, PR #10124).
ROCKNIX, Kali Linux and GammaOS are their own projects; this repo only
combines them.
