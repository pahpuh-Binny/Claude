# Android on the RG DS

Android lives on the internal 32 GB eMMC and boots whenever no microSD card is
inserted. The multiboot card never touches it. This page covers changing
Android itself.

## Options, best first

| Option | Android | Dual screen | Risk |
|---|---|---|---|
| Stock Anbernic firmware | 14 | yes | none |
| **GammaOS Next** for RG DS (TheGammaSqueeze) | 14 (LineageOS 21) | yes | low: installs from SD, Anbernic-specific fixes |
| **GSI** (Generic System Image) | 15 / 16, maybe 17 | probably not | medium: experimental, needs a data wipe |

No Android newer than 14 has been built specifically for the RG DS. Anbernic
ships 14 and GammaOS is 14. A real Android 16/17 port would mean building AOSP
with a Rockchip board support package for that version, plus Anbernic's device
configuration and the closed Mali-G52 and vendor blobs. None of these are public
for the RG DS. It would also need a ~500 GB build machine and on-device
debugging. A GSI skips all that. It replaces only the `system`
partition and keeps Anbernic's vendor image, kernel and drivers.

## Trying a GSI (Android 16/17)

### 1. Check your firmware (read-only)

Enable *Developer options → USB debugging*, connect over USB, then:

```bash
android/preflight.sh
```

It reports Treble support, dynamic partitions, A/B slots, verified-boot state,
vendor level and display count, then gives a verdict. Stop here if it says FAIL.

### 2. Back up the whole eMMC

```bash
android/backup-emmc.sh ~/rgds-backup        # ~30 GB, 15-30 min
```

This reboots the device into Rockchip *Loader* mode and copies every sector with
`rkdeveloptool`, plus a checksum. Copy the file somewhere safe. It is your only
complete way back to stock Android, including your partition table.

Also back up saves and ROMs from Android's own storage. The GSI step wipes
userdata.

### 3. Get a GSI

- Google's official GSIs: developer.android.com → *Android {version}* → *GSI
  binaries*. Use **arm64** (`gsi_gms_arm64` includes Google Play,
  `aosp_arm64` does not).
- Community builds: the TrebleDroid / phhusson GSI list, or LineageOS GSIs
  (LineageOS 23.x = Android 16).

Start with an Android 16 GSI. The vendor image is Android 14, and newer system
images on older vendors fail more often.

### 4. Flash

```bash
android/flash-gsi.sh --gsi system-arm64.img.xz --backup ~/rgds-backup/rgds-emmc-*.img --wipe-data
```

Add `--vbmeta vbmeta.img` if preflight said verified boot is enforcing. A
`vbmeta.img` from Anbernic's firmware works, since the script flashes it with
verification disabled. Add `--delete-product` if flashing fails for lack of
space. The script asks you to type `FLASH` before it changes anything.

### What to expect on a GSI

- Top screen: Anbernic's dual-screen support is in their system image, which the
  GSI replaces. The top screen may stay off or only work in apps that drive a
  second display themselves.
- Controller: standard gamepad input should work; Anbernic's custom key mapping
  and performance modes will not.
- First boot can take 5+ minutes. A boot loop means this GSI/vendor combination
  does not work: restore and try another GSI.

## Getting back

```bash
android/restore-emmc.sh ~/rgds-backup/rgds-emmc-YYYYMMDD.img
```

Restore needs the device in *Loader* mode (`adb reboot loader` while Android
still runs), or in *Maskrom* mode if Android no longer boots. Maskrom is the
Rockchip chip's built-in USB recovery mode. How to enter it differs by device
(usually a key held while plugging in USB, or a test point), so look it up for
the RG DS *before* you need it. Once in Maskrom, load Rockchip's USB loader
(`rkdeveloptool db rk356x_spl_loader_v1.*.bin`, from rockchip-linux/rkbin), then
run the restore.

Anbernic also publishes full stock firmware images for its devices. Flashing
one with their tool is another way back, though it does not restore your data.

The GSI steps only write `vbmeta`, `system` and `userdata`, never the bootloader.
So the microSD card (ROCKNIX / Kali) should still boot while Android is broken.
