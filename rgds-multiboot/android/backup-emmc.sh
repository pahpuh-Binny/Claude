#!/usr/bin/env bash
# Back up the RG DS's entire internal storage (eMMC) to one image file, so
# Android can always be put back exactly as it was.
#
#   android/backup-emmc.sh [--sectors N] [OUTPUT_DIR]
#
# Uses rkdeveloptool over USB in Rockchip "Loader" mode. If USB debugging is
# on, the script asks the device to reboot into Loader mode itself.
# Expect about 30 GB of output and 15-30 minutes.
set -euo pipefail
SECTORS=""
OUTDIR=.
while (($#)); do
  case $1 in
    --sectors) SECTORS=$2; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) OUTDIR=$1; shift ;;
  esac
done

command -v rkdeveloptool >/dev/null || { echo "rkdeveloptool not found (https://github.com/rockchip-linux/rkdeveloptool)" >&2; exit 1; }
mkdir -p "$OUTDIR"

mode() { rkdeveloptool ld 2>/dev/null | sed -n 's/.*\(Loader\|Maskrom\).*/\1/p' | head -n 1; }

if [[ -z $(mode) ]] && command -v adb >/dev/null && [[ $(adb get-state 2>/dev/null) == device ]]; then
  echo "Rebooting the RG DS into Loader mode..."
  adb reboot loader
fi
for _ in $(seq 60); do [[ -n $(mode) ]] && break; sleep 1; done
case $(mode) in
  Loader) ;;
  Maskrom)
    echo "Device is in Maskrom mode. Load the RK3568 USB loader first, e.g.:" >&2
    echo "  rkdeveloptool db rk356x_spl_loader_v1.*.bin   (from rockchip-linux/rkbin, bin/rk35)" >&2
    echo "then run this script again." >&2
    exit 1 ;;
  *) echo "No Rockchip device in Loader mode found. With the RG DS on USB run: adb reboot loader" >&2; exit 1 ;;
esac

rkdeveloptool ppt | tee "$OUTDIR/partitions.txt"
if [[ -z $SECTORS ]]; then
  SECTORS=$(rkdeveloptool rfi | sed -n 's/.*Flash Size: *\([0-9]\{1,\}\) *Sectors.*/\1/p' | head -n 1)
fi
[[ $SECTORS =~ ^[0-9]+$ && $SECTORS -gt 0 ]] || { echo "Could not read the eMMC size; pass it with --sectors N (see 'rkdeveloptool rfi')." >&2; exit 1; }

bytes=$((SECTORS * 512))
avail=$(df -PB1 "$OUTDIR" | awk 'NR == 2 { print $4 }')
((avail > bytes)) || { echo "Not enough space in $OUTDIR: need $((bytes / 1073741824)) GiB." >&2; exit 1; }

IMG="$OUTDIR/rgds-emmc-$(date +%Y%m%d).img"
echo "Reading $SECTORS sectors ($((bytes / 1048576)) MiB) to $IMG ..."
rkdeveloptool rl 0 "$SECTORS" "$IMG"
[[ $(stat -c %s "$IMG") -eq $bytes ]] || { echo "Backup is incomplete; do not flash anything." >&2; exit 1; }
sha256sum "$IMG" | tee "$IMG.sha256"
echo
echo "Backup done: $IMG"
echo "Keep it safe (and copy it off this computer). Restore with: android/restore-emmc.sh $IMG"
echo "Rebooting the RG DS."
rkdeveloptool rd || true
