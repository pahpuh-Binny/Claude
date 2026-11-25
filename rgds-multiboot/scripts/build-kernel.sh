#!/usr/bin/env bash
# Build a mainline Linux kernel for the Anbernic RG DS as Debian packages
# (used by the Kali root filesystem) plus a bare Image and device tree.
#
#   scripts/build-kernel.sh [--version 7.3] [--src DIR] [--out DIR] [--no-otg]
#
# Environment: CROSS_COMPILE (default aarch64-linux-gnu- on non-arm64 hosts),
# JOBS (default: number of CPUs).
# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

VERSION=${KERNEL_VERSION:-7.3}
SRC=""
OUT="$RGDS_ROOT/out/kernel"
OTG=1
JOBS=${JOBS:-$(nproc)}

while (($#)); do
  case $1 in
    --version) VERSION=$2; shift 2 ;;
    --src) SRC=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --no-otg) OTG=0; shift ;;
    -h|--help) usage 0 ;;
    *) warn "unknown option $1"; usage 1 ;;
  esac
done

need make gcc bc bison flex perl dpkg-deb curl xz
CC_PREFIX=$(cross_compile_prefix)
[[ -z $CC_PREFIX ]] || need "${CC_PREFIX}gcc"

mkdir -p "$OUT" "$RGDS_ROOT/out/cache"
OUT=$(cd "$OUT" && pwd)

if [[ -z $SRC ]]; then
  major=${VERSION%%.*}
  tarball="$RGDS_ROOT/out/cache/linux-$VERSION.tar.xz"
  fetch "https://cdn.kernel.org/pub/linux/kernel/v$major.x/linux-$VERSION.tar.xz" "$tarball"
  SRC="$RGDS_ROOT/out/src/linux-$VERSION"
  if [[ ! -d $SRC ]]; then
    mkdir -p "$RGDS_ROOT/out/src"
    log "extracting linux-$VERSION"
    tar -C "$RGDS_ROOT/out/src" -xf "$tarball"
  fi
fi
SRC=$(cd "$SRC" && pwd)

DTS="$SRC/$RGDS_DTS_PATH"
[[ -f $DTS ]] || die "$RGDS_DTS_PATH is not in this kernel ($SRC). The RG DS device tree
       landed upstream in 2026; use a newer kernel (--version) or a tree that carries it."

# USB-C dual-role override, applied once (idempotent).
if ((OTG)); then
  if grep -q 'rgds-multiboot: usb otg' "$DTS"; then
    log "USB OTG override already applied"
  elif grep -q 'otg_switch:' "$DTS" \
     && grep -rqs 'usb_host0_xhci:' "$SRC/arch/arm64/boot/dts/rockchip/" \
     && grep -rqs 'usb2phy0_otg:' "$SRC/arch/arm64/boot/dts/rockchip/"; then
    log "applying USB-C dual-role override to the RG DS device tree"
    { printf '\n/* rgds-multiboot: usb otg */\n'; cat "$RGDS_ROOT/config/kernel/rgds-usb-otg.dtsi"; } >> "$DTS"
  else
    warn "device tree labels for the OTG override were not found; leaving USB-C as mainline has it"
  fi
fi

BUILD="$SRC/build-rgds"
mk() { make -C "$SRC" O="$BUILD" ARCH=arm64 CROSS_COMPILE="$CC_PREFIX" "$@"; }

log "configuring (arm64 defconfig + config/kernel/rgds.config)"
mk defconfig
"$SRC/scripts/kconfig/merge_config.sh" -m -O "$BUILD" "$BUILD/.config" "$RGDS_ROOT/config/kernel/rgds.config"
mk olddefconfig

# Fail loudly if anything the handheld cannot work without got dropped.
critical=(MMC_DW_ROCKCHIP EXT4_FS DRM_ROCKCHIP ROCKCHIP_VOP2 ROCKCHIP_DW_MIPI_DSI
          DRM_PANEL_JADARD_JD9365DA_H3 TOUCHSCREEN_GOODIX KEYBOARD_GPIO KEYBOARD_ADC
          JOYSTICK_ADC MFD_RK8XX_I2C SND_SOC_RK817 RTW88_8821CS INPUT_UINPUT)
missing=()
for sym in "${critical[@]}"; do
  grep -Eq "^CONFIG_${sym}=(y|m)$" "$BUILD/.config" || missing+=("$sym")
done
((${#missing[@]} == 0)) || die "kernel config is missing: ${missing[*]}"

log "building kernel packages with $JOBS jobs"
stamp="$BUILD/.rgds-build-start"
touch "$stamp"
mk -j"$JOBS" bindeb-pkg DPKG_FLAGS=-d LOCALVERSION=""

# bindeb-pkg drops the .deb files next to the source or build tree.
find "$SRC/.." "$BUILD/.." -maxdepth 1 -name 'linux-*.deb' -newer "$stamp" \
  -exec mv -f {} "$OUT/" \;
cp "$BUILD/arch/arm64/boot/Image" "$OUT/Image"
cp "$BUILD/arch/arm64/boot/dts/rockchip/$RGDS_DTB_NAME" "$OUT/$RGDS_DTB_NAME"
cp "$BUILD/.config" "$OUT/config"

ls "$OUT"/linux-image-*.deb >/dev/null 2>&1 || die "no linux-image .deb was produced"
log "done: $(find "$OUT" -maxdepth 1 -type f -printf '%f ')"
