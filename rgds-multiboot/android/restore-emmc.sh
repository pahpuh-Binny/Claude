#!/usr/bin/env bash
# Write a full eMMC backup (from backup-emmc.sh) back to the RG DS. This
# replaces everything on internal storage with the backup's contents.
#
#   android/restore-emmc.sh rgds-emmc-YYYYMMDD.img
#
# Works from Loader mode, and from Maskrom mode after loading the USB loader
# (rkdeveloptool db rk356x_spl_loader_*.bin), so it also recovers a device
# whose Android no longer boots.
set -euo pipefail
IMG=${1:?usage: restore-emmc.sh BACKUP.img}
[[ -f $IMG ]] || { echo "$IMG not found" >&2; exit 1; }
command -v rkdeveloptool >/dev/null || { echo "rkdeveloptool not found" >&2; exit 1; }

if [[ -f $IMG.sha256 ]]; then
  echo "Verifying checksum..."
  (cd "$(dirname "$IMG")" && sha256sum -c "$(basename "$IMG").sha256") || { echo "Checksum mismatch: not restoring." >&2; exit 1; }
fi

if ! rkdeveloptool ld 2>/dev/null | grep -q 'Loader'; then
  echo "Put the RG DS in Loader mode (adb reboot loader), or in Maskrom mode and run" >&2
  echo "  rkdeveloptool db rk356x_spl_loader_v1.*.bin" >&2
  exit 1
fi

echo "This overwrites the RG DS internal storage with $IMG."
read -r -p "Type RESTORE to continue: " answer
[[ $answer == RESTORE ]] || { echo "Cancelled."; exit 1; }
rkdeveloptool wl 0 "$IMG"
rkdeveloptool rd
echo "Restore finished; the RG DS is rebooting."
