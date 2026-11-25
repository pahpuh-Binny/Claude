#!/usr/bin/env bash
# Flash a newer Android (a Generic System Image, GSI) onto the RG DS's
# internal storage, keeping Anbernic's Android 14 vendor and kernel.
#
#   android/flash-gsi.sh --gsi system.img[.xz|.gz] --backup rgds-emmc-*.img \
#                        [--vbmeta vbmeta.img] [--delete-product] [--wipe-data]
#
#   --gsi FILE        arm64 GSI system image (e.g. Google's Android 16/17 GSI,
#                     or a LineageOS / TrebleDroid build)
#   --backup FILE     your full eMMC backup from backup-emmc.sh (required: this
#                     is how you get Android 14 back)
#   --vbmeta FILE     vbmeta image to flash with verification disabled
#                     (needed if android/preflight.sh reported AVB enforcing)
#   --delete-product  free space in the super partition for a large GSI
#   --wipe-data       factory-reset userdata (a GSI will not boot without it)
#
# EXPERIMENTAL: not tested on an RG DS. Run android/preflight.sh first.
set -euo pipefail
GSI="" BACKUP="" VBMETA="" DEL_PRODUCT=0 WIPE=0
while (($#)); do
  case $1 in
    --gsi) GSI=$2; shift 2 ;;
    --backup) BACKUP=$2; shift 2 ;;
    --vbmeta) VBMETA=$2; shift 2 ;;
    --delete-product) DEL_PRODUCT=1; shift ;;
    --wipe-data) WIPE=1; shift ;;
    -h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done

die() { echo "flash-gsi: $*" >&2; exit 1; }
for t in adb fastboot; do command -v "$t" >/dev/null || die "$t not found (install Android platform-tools)"; done
[[ -f $GSI ]] || die "--gsi FILE is required"
[[ -f $BACKUP ]] || die "--backup must point at your full eMMC backup (android/backup-emmc.sh)"
(( $(stat -c %s "$BACKUP") > 8 * 1024 * 1024 * 1024 )) || die "$BACKUP is too small to be a full eMMC backup"
((WIPE)) || die "a GSI needs a data wipe to boot; add --wipe-data once you have backed up your saves"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
case $GSI in
  *.xz) echo "Unpacking $GSI"; xz -dc "$GSI" > "$WORK/system.img"; GSI="$WORK/system.img" ;;
  *.gz) echo "Unpacking $GSI"; gzip -dc "$GSI" > "$WORK/system.img"; GSI="$WORK/system.img" ;;
esac

wait_fastboot() {
  for _ in $(seq 60); do
    fastboot devices 2>/dev/null | grep -q . && return 0
    sleep 1
  done
  die "device did not show up in fastboot mode"
}
getvar() { fastboot getvar "$1" 2>&1 | sed -n "s/^$1: *//p" | head -n 1; }

cat <<EOF

About to replace Android on the RG DS internal storage with:
  $(basename "$GSI") ($(( $(stat -c %s "$GSI") / 1048576 )) MiB)
This ERASES all apps and data on internal storage. The microSD card is not touched.
Your way back is: android/restore-emmc.sh $BACKUP
EOF
read -r -p "Type FLASH to continue: " answer
[[ $answer == FLASH ]] || { echo "Cancelled."; exit 1; }

if [[ $(adb get-state 2>/dev/null) == device ]]; then
  adb reboot bootloader
fi
wait_fastboot

if [[ -n $VBMETA ]]; then
  echo "Flashing vbmeta with verification disabled"
  fastboot --disable-verity --disable-verification flash vbmeta "$VBMETA"
fi

if [[ $(getvar is-userspace) != yes ]]; then
  echo "Switching to fastbootd (userspace fastboot) for the dynamic partitions"
  fastboot reboot fastboot
  sleep 5
  wait_fastboot
fi
[[ $(getvar is-userspace) == yes ]] || die "fastbootd is not available; this firmware cannot take a GSI this way"

if ((DEL_PRODUCT)); then
  slot=$(getvar current-slot)
  fastboot delete-logical-partition "product${slot:+_$slot}" || echo "no product partition to delete"
fi

fastboot erase system || true
fastboot flash system "$GSI"
fastboot -w
fastboot reboot

cat <<EOF

Done. The first boot of a GSI can take several minutes.
If it boot-loops: hold power to switch off, then restore with
  android/restore-emmc.sh $BACKUP
(Maskrom mode works even when Android no longer boots: see docs/ANDROID.md.)
EOF
