#!/usr/bin/env bash
# End-to-end test of make-sd-image.sh against a mock ROCKNIX image and a mock
# Kali rootfs. No hardware, network or loop devices needed. Run as root:
#   sudo tests/test-make-sd-image.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
T=$(mktemp -d "${TMPDIR:-/tmp}/rgds-test.XXXXXX")
if [[ -n ${KEEP:-} ]]; then echo "keeping $T"; else trap 'rm -rf "$T"' EXIT; fi
export MTOOLS_SKIP_CHECK=1
fails=0
ok()   { printf '  ok   %s\n' "$*"; }
fail() { printf '  FAIL %s\n' "$*"; fails=$((fails + 1)); }
check() { local d=$1; shift; if "$@" >/dev/null 2>&1; then ok "$d"; else fail "$d"; fi; }

# ---------------------------------------------------------------- mock inputs
echo "building mock ROCKNIX image"
mkdir -p "$T/rfat/extlinux" "$T/rfat/device_trees"
echo kernel > "$T/rfat/KERNEL"
head -c 300000 /dev/urandom > "$T/rfat/SYSTEM"
echo dtb > "$T/rfat/device_trees/rk3568-anbernic-rg-ds.dtb"
cat > "$T/rfat/extlinux/extlinux.conf" <<'EOF'
MENU TITLE ROCKNIX
TIMEOUT 30
LABEL ROCKNIX
  LINUX /KERNEL
  FDT /device_trees/rk3568-anbernic-rg-ds.dtb
  APPEND boot=LABEL=ROCKNIX disk=LABEL=STORAGE quiet console=ttyS2,1500000 console=tty0
EOF
mkfs.vfat -F 32 -n ROCKNIX -i C0FFEE11 -C "$T/rfat.img" $((64 * 1024)) >/dev/null
mcopy -s -i "$T/rfat.img" "$T/rfat/KERNEL" "$T/rfat/SYSTEM" "$T/rfat/extlinux" "$T/rfat/device_trees" ::/
mkdir -p "$T/rst" && touch "$T/rst/.please_resize_me"
truncate -s 32M "$T/rst.img"
mkfs.ext4 -q -F -L STORAGE -U 0badc0de-0000-4000-8000-000000000001 -d "$T/rst" "$T/rst.img"
truncate -s $(( (32768 + 131072 + 65536 + 2048) * 512 )) "$T/rocknix.img"
printf 'ROCKNIX-UBOOT-MARKER' | dd of="$T/rocknix.img" bs=512 seek=64 conv=notrunc status=none
printf 'label: gpt\nstart=32768, size=131072, type=EBD0A0A2-B9E5-4433-87C0-68B6B72699C7, name="system"\nstart=163840, size=65536, type=0FC63DAF-8483-4772-8E79-3D69D8477DE4, name="storage"\n' \
  | sfdisk --quiet "$T/rocknix.img"
dd if="$T/rfat.img" of="$T/rocknix.img" bs=512 seek=32768 conv=notrunc status=none
dd if="$T/rst.img" of="$T/rocknix.img" bs=512 seek=163840 conv=notrunc status=none
gzip -k "$T/rocknix.img"

echo "building mock Kali rootfs"
R="$T/rootfs"
mkdir -p "$R/boot/rgds" "$R/etc" "$R/usr/bin"
head -c 200000 /dev/urandom > "$R/boot/rgds/Image"
head -c 50000 /dev/urandom > "$R/boot/rgds/initrd.img"
echo dtb > "$R/boot/rgds/rk3568-anbernic-rg-ds.dtb"
echo 7.3.0-rgds > "$R/boot/rgds/version"
echo "placeholder" > "$R/etc/fstab"
echo "#!/bin/sh" > "$R/usr/bin/true-ish"

# ----------------------------------------------------------- triple boot card
echo "make-sd-image (triple boot)"
"$ROOT/scripts/make-sd-image.sh" --rocknix "$T/rocknix.img.gz" --kali "$R" \
  --kali-size 64M --boot-size 96M --storage-size 48M -o "$T/out.img" 2> "$T/log" \
  || { cat "$T/log"; exit 1; }
IMG="$T/out.img"

echo "checking the triple-boot image"
json=$(sfdisk -J "$IMG")
part() { python3 -c "import json,sys; p=json.loads(sys.argv[1])['partitiontable']['partitions'][int(sys.argv[2])-1]; print(p[sys.argv[3]])" "$json" "$1" "$2"; }
p1s=$(part 1 start); p2s=$(part 2 start); p3s=$(part 3 start)
check "partition 1 (boot) starts at 16 MiB" test "$p1s" -eq 32768
check "partition 2 (STORAGE) is physically last" test "$p2s" -gt "$p3s"
check "partition 3 (Kali) sits between boot and STORAGE" test "$p3s" -gt "$p1s"
check "boot partition is marked LegacyBIOSBootable" grep -q LegacyBIOSBootable <<< "$(part 1 attrs)"
check "bootloader copied from ROCKNIX" grep -q ROCKNIX-UBOOT-MARKER <(dd if="$IMG" bs=512 skip=64 count=1 status=none)

fat="$IMG@@$((p1s * 512))"
check "FAT label kept" grep -q 'Volume label is ROCKNIX' <(mlabel -s -i "$fat" ::)
check "FAT serial kept" grep -qi 'serial number: C0FFEE11' <(minfo -i "$fat" ::)
mcopy -n -i "$fat" ::/extlinux/extlinux.conf "$T/extlinux.conf"
mcopy -n -i "$fat" ::/SYSTEM "$T/SYSTEM"
check "ROCKNIX files copied intact" cmp -s "$T/SYSTEM" "$T/rfat/SYSTEM"
check "Kali kernel on boot partition" mdir -i "$fat" ::/kali/Image
check "switch script on boot partition" mdir -i "$fat" ::/rgds/rgds-switch-os
conf="$T/extlinux.conf"
check "DEFAULT is ROCKNIX" grep -qx 'DEFAULT ROCKNIX' "$conf"
check "exactly one DEFAULT" test "$(grep -ci '^[[:space:]]*default' "$conf")" -eq 1
check "MENU/TIMEOUT lines removed" bash -c "! grep -Eiq '^[[:space:]]*(menu|timeout)' '$conf'"
check "ROCKNIX entry unchanged" grep -q 'APPEND boot=LABEL=ROCKNIX disk=LABEL=STORAGE quiet' "$conf"
check "ROCKNIX FDT line unchanged" grep -q '^  FDT /device_trees/rk3568-anbernic-rg-ds.dtb' "$conf"
kali_uuid=$(part 3 uuid)
check "Kali root=PARTUUID matches partition 3" grep -qi "root=PARTUUID=$kali_uuid" "$conf"
check "Kali uses 'devicetree' (untouched by ROCKNIX updates)" grep -q 'devicetree /kali/rk3568-anbernic-rg-ds.dtb' "$conf"

dd if="$IMG" of="$T/p2.img" bs=512 skip="$p2s" count="$(part 2 size)" status=none
dd if="$IMG" of="$T/p3.img" bs=512 skip="$p3s" count="$(part 3 size)" status=none
check "STORAGE label kept" grep -q 'volume name: *STORAGE' <(dumpe2fs -h "$T/p2.img" 2>/dev/null)
check "STORAGE UUID kept" grep -q '0badc0de-0000-4000-8000-000000000001' <(dumpe2fs -h "$T/p2.img" 2>/dev/null)
check "resize flag removed" bash -c "! debugfs -R 'stat /.please_resize_me' '$T/p2.img' 2>&1 | grep -q '^Inode:'"
check "ROCKNIX port 'Switch to Kali' added" bash -c "debugfs -R 'ls -l /roms/ports' '$T/p2.img' 2>/dev/null | grep -q 'Switch to Kali.sh'"
check "STORAGE fsck clean" e2fsck -fn "$T/p2.img"
check "Kali fsck clean" e2fsck -fn "$T/p3.img"
check "Kali fstab mounts the ROCKNIX boot partition" bash -c "debugfs -R 'cat /etc/fstab' '$T/p3.img' 2>/dev/null | grep -q 'LABEL=ROCKNIX  /boot/rgds-multiboot'"
check "Kali multiboot.conf records triple mode" bash -c "debugfs -R 'cat /etc/rgds/multiboot.conf' '$T/p3.img' 2>/dev/null | grep -q MODE=triple"

# ----------------------------------------------------------- switcher on it
echo "checking rgds-switch-os against the generated config"
mkdir -p "$T/bootdir/extlinux" && cp "$conf" "$T/bootdir/extlinux/"
sw() { RGDS_BOOT_DIR="$T/bootdir" sh "$ROOT/overlay/usr/local/sbin/rgds-switch-os" "$@" --no-reboot; }
sw kali >/dev/null
check "switch to kali" grep -qx 'DEFAULT KALI' "$T/bootdir/extlinux/extlinux.conf"
sw rocknix >/dev/null
check "switch to rocknix" grep -qx 'DEFAULT ROCKNIX' "$T/bootdir/extlinux/extlinux.conf"
check "still one DEFAULT after switching" test "$(grep -ci '^[[:space:]]*default' "$T/bootdir/extlinux/extlinux.conf")" -eq 1
check "unknown target is refused" bash -c "! RGDS_BOOT_DIR='$T/bootdir' sh '$ROOT/overlay/usr/local/sbin/rgds-switch-os' windows --no-reboot 2>/dev/null"
check "status lists both entries" bash -c "RGDS_BOOT_DIR='$T/bootdir' sh '$ROOT/overlay/usr/local/sbin/rgds-switch-os' status | grep -q '\* ROCKNIX (default)'"
check "switching changes nothing but DEFAULT" \
  diff <(grep -v -i '^default' "$conf") <(grep -v -i '^default' "$T/bootdir/extlinux/extlinux.conf")

# ----------------------------------------------------------- Kali-only card
echo "make-sd-image (Kali only)"
printf 'MAINLINE-UBOOT' > "$T/u-boot-rockchip.bin"
"$ROOT/scripts/make-sd-image.sh" --uboot "$T/u-boot-rockchip.bin" --kali "$R" \
  --kali-size 64M -o "$T/kali.img" 2> "$T/log2" || { cat "$T/log2"; exit 1; }
json=$(sfdisk -J "$T/kali.img")
check "two partitions" test "$(python3 -c "import json,sys; print(len(json.loads(sys.argv[1])['partitiontable']['partitions']))" "$json")" -eq 2
check "mainline U-Boot at sector 64" grep -q MAINLINE-UBOOT <(dd if="$T/kali.img" bs=512 skip=64 count=1 status=none)
fat="$T/kali.img@@$(( $(part 1 start) * 512 ))"
mcopy -n -i "$fat" ::/extlinux/extlinux.conf "$T/k.conf"
check "Kali-only DEFAULT is KALI" grep -qx 'DEFAULT KALI' "$T/k.conf"
check "Kali-only boot label RGDSBOOT" grep -q 'Volume label is RGDSBOOT' <(mlabel -s -i "$fat" ::)

echo
if ((fails)); then echo "$fails check(s) failed"; exit 1; fi
echo "all checks passed"
