#!/usr/bin/env bash
# Tests for overlay/usr/local/sbin/rgds-kernel-sync on a fake rootfs.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SYNC="$ROOT/overlay/usr/local/sbin/rgds-kernel-sync"
T=$(mktemp -d "${TMPDIR:-/tmp}/rgds-ksync.XXXXXX")
trap 'rm -rf "$T"' EXIT
fails=0
check() { local d=$1; shift; if "$@" >/dev/null 2>&1; then echo "  ok   $d"; else echo "  FAIL $d"; fails=$((fails + 1)); fi; }

R="$T/root"
mkdir -p "$R/boot" "$R/usr/lib/linux-image-7.2.0-rgds/rockchip" "$R/usr/lib/linux-image-7.3.0-rgds/rockchip"
printf 'old-kernel' > "$R/boot/vmlinuz-7.2.0-rgds"
printf 'new-kernel-image' > "$T/Image"; gzip -c "$T/Image" > "$R/boot/vmlinuz-7.3.0-rgds"
printf 'initrd-7.3' > "$R/boot/initrd.img-7.3.0-rgds"
printf 'dtb-7.2' > "$R/usr/lib/linux-image-7.2.0-rgds/rockchip/rk3568-anbernic-rg-ds.dtb"
printf 'dtb-7.3' > "$R/usr/lib/linux-image-7.3.0-rgds/rockchip/rk3568-anbernic-rg-ds.dtb"

echo "rgds-kernel-sync"
RGDS_ROOTFS="$R" "$SYNC" >/dev/null
check "picks the newest kernel" grep -qx 7.3.0-rgds "$R/boot/rgds/version"
check "decompresses a gzip vmlinuz to Image" cmp -s "$T/Image" "$R/boot/rgds/Image"
check "stages the initrd" grep -q initrd-7.3 "$R/boot/rgds/initrd.img"
check "stages the RG DS dtb" grep -q dtb-7.3 "$R/boot/rgds/rk3568-anbernic-rg-ds.dtb"
check "boot partition untouched when not mounted" test ! -e "$R/boot/rgds-multiboot/kali"

mkdir -p "$R/boot/rgds-multiboot/extlinux" && touch "$R/boot/rgds-multiboot/extlinux/extlinux.conf"
RGDS_ROOTFS="$R" "$SYNC" 7.2.0-rgds >/dev/null
check "explicit version is honoured" grep -qx 7.2.0-rgds "$R/boot/rgds-multiboot/kali/version"
check "uncompressed vmlinuz copied as-is" grep -q old-kernel "$R/boot/rgds-multiboot/kali/Image"
check "keeps last initrd if the version has none" grep -q initrd-7.3 "$R/boot/rgds-multiboot/kali/initrd.img"
check "no temporary files left" bash -c "! ls '$R/boot/rgds-multiboot/kali/'*.new"

rm "$R/usr/lib/linux-image-7.3.0-rgds/rockchip/rk3568-anbernic-rg-ds.dtb"
check "refuses a kernel without the RG DS dtb" bash -c "! RGDS_ROOTFS='$R' '$SYNC' 7.3.0-rgds"
check "previous kernel stays installed after a refusal" grep -q old-kernel "$R/boot/rgds-multiboot/kali/Image"
check "postinst hook never fails the package install" env PATH=/usr/bin:/bin sh "$ROOT/overlay/etc/kernel/postinst.d/zz-rgds-multiboot" 9.9.9

if ((fails)); then echo "$fails check(s) failed"; exit 1; fi
echo "all checks passed"
