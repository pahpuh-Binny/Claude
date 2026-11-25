#!/usr/bin/env bash
# Build a mainline U-Boot that boots the RG DS from microSD.
#
#   scripts/build-uboot.sh [--out DIR]
#
# Only needed for a Kali-only card. A triple-boot card reuses the bootloader
# from the ROCKNIX image, which is known to boot this device.
#
# Mainline U-Boot has no RG DS board yet, so this follows Armbian's proven
# recipe for the RG DS: the Quartz64-A config (same RK817 PMIC) with
# Rockchip's RK3568 BL31 and DDR blobs. Override any of these with env vars.
# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

UBOOT_TAG=${UBOOT_TAG:-v2026.01}
UBOOT_DEFCONFIG=${UBOOT_DEFCONFIG:-quartz64-a-rk3566_defconfig}
BL31_BLOB=${BL31_BLOB:-rk35/rk3568_bl31_v1.45.elf}
DDR_BLOB=${DDR_BLOB:-rk35/rk3568_ddr_1056MHz_v1.23.bin}
RKBIN_REF=${RKBIN_REF:-master}
OUT="$RGDS_ROOT/out/uboot"
JOBS=${JOBS:-$(nproc)}

while (($#)); do
  case $1 in
    --out) OUT=$2; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done

need make gcc bison flex python3 curl tar swig
python3 -c 'import elftools' 2>/dev/null || die "python3-pyelftools is required (binman needs it)"
CC_PREFIX=$(cross_compile_prefix)
[[ -z $CC_PREFIX ]] || need "${CC_PREFIX}gcc"

CACHE="$RGDS_ROOT/out/cache"
SRCDIR="$RGDS_ROOT/out/src"
mkdir -p "$OUT" "$CACHE" "$SRCDIR"
OUT=$(cd "$OUT" && pwd)

fetch "https://github.com/u-boot/u-boot/archive/refs/tags/$UBOOT_TAG.tar.gz" "$CACHE/u-boot-$UBOOT_TAG.tar.gz"
fetch "https://github.com/rockchip-linux/rkbin/archive/$RKBIN_REF.tar.gz" "$CACHE/rkbin-$RKBIN_REF.tar.gz"

UB="$SRCDIR/u-boot-${UBOOT_TAG#v}"
RKBIN="$SRCDIR/rkbin-$RKBIN_REF"
[[ -d $UB ]] || tar -C "$SRCDIR" -xzf "$CACHE/u-boot-$UBOOT_TAG.tar.gz"
[[ -d $RKBIN ]] || tar -C "$SRCDIR" -xzf "$CACHE/rkbin-$RKBIN_REF.tar.gz"

# Fall back to the newest blob of the same kind if rkbin dropped the exact one.
pick_blob() {
  local want="$RKBIN/bin/$1" pattern=$2 alt
  if [[ -f $want ]]; then echo "$want"; return; fi
  alt=$(find "$RKBIN/bin/rk35" -name "$pattern" | sort -V | tail -n1)
  [[ -n $alt ]] || die "rkbin has no $1 and nothing matching $pattern"
  warn "$1 not in rkbin; using $(basename "$alt")"
  echo "$alt"
}
BL31=$(pick_blob "$BL31_BLOB" 'rk3568_bl31_v*.elf')
TPL=$(pick_blob "$DDR_BLOB" 'rk3568_ddr_1056MHz_v*.bin')

log "building U-Boot $UBOOT_TAG ($UBOOT_DEFCONFIG)"
make -C "$UB" CROSS_COMPILE="$CC_PREFIX" "$UBOOT_DEFCONFIG"
make -C "$UB" CROSS_COMPILE="$CC_PREFIX" BL31="$BL31" ROCKCHIP_TPL="$TPL" -j"$JOBS"

cp "$UB/u-boot-rockchip.bin" "$OUT/u-boot-rockchip.bin"
log "done: $OUT/u-boot-rockchip.bin (written at sector $RGDS_LOADER_SECTOR by make-sd-image.sh)"
