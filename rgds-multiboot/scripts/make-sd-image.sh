#!/usr/bin/env bash
# Assemble a microSD card image for the Anbernic RG DS.
#
# Triple boot: Android stays on internal storage, ROCKNIX + Kali go on the card
#   sudo scripts/make-sd-image.sh --rocknix ROCKNIX-RK3566.*.img.gz --kali out/kali-rootfs -o rgds-triple.img
#
# Dual boot: Android on internal storage + Kali on the card
#   sudo scripts/make-sd-image.sh --uboot out/uboot/u-boot-rockchip.bin --kali out/kali-rootfs -o rgds-kali.img
#
# Options:
#   --rocknix IMG        ROCKNIX image for the RG DS (.img, .img.gz, .img.xz)
#   --kali DIR|TAR       Kali rootfs from build-kali-rootfs.sh (directory or tarball)
#   --uboot FILE         bootloader to use (u-boot-rockchip.bin). Default with
#                        --rocknix: keep ROCKNIX's own bootloader.
#   --default kali|rocknix   OS that boots until you switch (default: rocknix,
#                        or kali on a Kali-only card)
#   --kali-size SIZE     Kali partition size (default: rootfs + 30% + 2G, min 8G)
#   --boot-size SIZE     shared FAT boot partition (default 2G, or 512M Kali-only)
#   --storage-size SIZE  ROCKNIX STORAGE size inside the image (default 1G).
#                        Kali's first boot grows it to fill the rest of the card.
#   --cmdline "ARGS"     extra kernel arguments for Kali
#   -o, --output FILE    image to write (sparse); --force to overwrite
#
# No loop devices are used, so this also works in containers and WSL.
# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

ROCKNIX="" KALI="" UBOOT="" DEFAULT_OS="" OUTPUT="" FORCE=0 EXTRA_CMDLINE=""
KALI_SIZE="" BOOT_SIZE="" STORAGE_SIZE=1G

while (($#)); do
  case $1 in
    --rocknix) ROCKNIX=$2; shift 2 ;;
    --kali) KALI=$2; shift 2 ;;
    --uboot) UBOOT=$2; shift 2 ;;
    --default) DEFAULT_OS=$2; shift 2 ;;
    --kali-size) KALI_SIZE=$2; shift 2 ;;
    --boot-size) BOOT_SIZE=$2; shift 2 ;;
    --storage-size) STORAGE_SIZE=$2; shift 2 ;;
    --cmdline) EXTRA_CMDLINE=$2; shift 2 ;;
    -o|--output) OUTPUT=$2; shift 2 ;;
    --force) FORCE=1; shift ;;
    -h|--help) sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
done

[[ -n $KALI ]] || die "--kali is required"
[[ -n $OUTPUT ]] || die "-o/--output is required"
[[ -n $ROCKNIX || -n $UBOOT ]] || die "a Kali-only card needs --uboot (build it with scripts/build-uboot.sh)"
[[ ! -e $OUTPUT || $FORCE == 1 ]] || die "$OUTPUT exists (use --force to overwrite)"
TRIPLE=0; [[ -n $ROCKNIX ]] && TRIPLE=1
[[ -n $DEFAULT_OS ]] || DEFAULT_OS=$( ((TRIPLE)) && echo rocknix || echo kali )
[[ $DEFAULT_OS == kali || ($DEFAULT_OS == rocknix && $TRIPLE == 1) ]] || die "--default must be kali or rocknix (rocknix needs --rocknix)"

need_root
need sfdisk mkfs.vfat mcopy mlabel minfo mkfs.ext4 debugfs dumpe2fs truncate dd python3 du
export MTOOLS_SKIP_CHECK=1

WORK=$(mktemp -d "${TMPDIR:-/tmp}/rgds-img.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
uuid4() { python3 -c 'import uuid; print(uuid.uuid4())'; }
sectors() { echo $(( $1 / 512 )); }

# ---------------------------------------------------------------- Kali rootfs
if [[ -d $KALI ]]; then
  ROOTFS=$(cd "$KALI" && pwd)
  if awk -v r="$ROOTFS/" 'index($2 "/", r) == 1 { found = 1 } END { exit !found }' /proc/mounts; then
    die "something is still mounted under $ROOTFS; unmount it first"
  fi
elif [[ -f $KALI ]]; then
  log "extracting $(basename "$KALI")"
  ROOTFS="$WORK/rootfs"; mkdir "$ROOTFS"
  tar --numeric-owner --xattrs --xattrs-include='*' -xpf "$KALI" -C "$ROOTFS"
else
  die "--kali $KALI is neither a directory nor a tarball"
fi
for f in Image initrd.img "$RGDS_DTB_NAME"; do
  [[ -s $ROOTFS/boot/rgds/$f ]] || die "$ROOTFS/boot/rgds/$f is missing; was the rootfs built by build-kali-rootfs.sh?"
done
ROOTFS_BYTES=$(du -sxb "$ROOTFS" | cut -f1)

# ------------------------------------------------------------- ROCKNIX source
BOOT_DIR="$WORK/boot"; mkdir -p "$BOOT_DIR/extlinux"
STORAGE_DIR="$WORK/storage"
P1S=$RGDS_MIN_PART_START
ORIG_CONF=""

if ((TRIPLE)); then
  RIMG=$(unpack_image "$ROCKNIX" "$WORK")
  read -r r1s _ r2s r2n < <(sfdisk -J "$RIMG" | python3 -c '
import json, sys
parts = json.load(sys.stdin)["partitiontable"]["partitions"]
if len(parts) < 2:
    sys.exit("expected 2 partitions")
print(parts[0]["start"], parts[0]["size"], parts[1]["start"], parts[1]["size"])
') || die "cannot read the ROCKNIX partition table (expected a boot and a STORAGE partition)"
  ((r1s > RGDS_LOADER_SECTOR)) || die "ROCKNIX partition 1 starts at sector $r1s, inside the bootloader area"
  P1S=$(( r1s > RGDS_MIN_PART_START ? r1s : RGDS_MIN_PART_START ))
  P1S=$(( (P1S + 2047) / 2048 * 2048 ))
  fat="$RIMG@@$((r1s * 512))"

  BOOT_LABEL=$(mlabel -s -i "$fat" :: 2>/dev/null | sed -n 's/^ *Volume label is \(.*[^ ]\) *$/\1/p')
  BOOT_SERIAL=$(minfo -i "$fat" :: 2>/dev/null | sed -n 's/^serial number: *\([0-9A-Fa-f]\{8\}\).*/\1/p')
  [[ -n $BOOT_LABEL ]] || die "ROCKNIX partition 1 is not a labelled FAT filesystem"
  [[ -n $BOOT_SERIAL ]] || BOOT_SERIAL=$(python3 -c 'import secrets; print(secrets.token_hex(4))')
  log "ROCKNIX boot partition: label $BOOT_LABEL, serial $BOOT_SERIAL"
  mcopy -s -n -p -i "$fat" '::/*' "$BOOT_DIR/"
  ORIG_CONF="$BOOT_DIR/extlinux/extlinux.conf"
  [[ -f $ORIG_CONF ]] || die "ROCKNIX image has no extlinux/extlinux.conf; this ROCKNIX build boots differently and is not supported"

  log "copying ROCKNIX STORAGE"
  dd if="$RIMG" of="$WORK/storage-orig.img" bs=512 skip="$r2s" count="$r2n" status=none
  ST_UUID=$(dumpe2fs -h "$WORK/storage-orig.img" 2>/dev/null | sed -n 's/^Filesystem UUID: *//p')
  ST_LABEL=$(dumpe2fs -h "$WORK/storage-orig.img" 2>/dev/null | sed -n 's/^Filesystem volume name: *//p')
  [[ -n $ST_UUID ]] || die "ROCKNIX partition 2 is not an ext4 filesystem"
  [[ $ST_LABEL == STORAGE ]] || warn "ROCKNIX partition 2 is labelled '$ST_LABEL', not STORAGE"
  mkdir -p "$STORAGE_DIR"
  debugfs -R "rdump / $STORAGE_DIR" "$WORK/storage-orig.img" >/dev/null 2>&1
  # Sized here and grown by Kali later, so ROCKNIX's own first-boot resize
  # (hard-wired to partition 2 and to wiping it) must not run.
  rm -rf "$STORAGE_DIR/lost+found" "$STORAGE_DIR/.please_resize_me"
  mkdir -p "$STORAGE_DIR/roms/ports"
  install -m 0755 "$RGDS_ROOT"/rocknix/ports/*.sh "$STORAGE_DIR/roms/ports/"
  ORIG_STORAGE_BYTES=$((r2n * 512))
else
  BOOT_LABEL=RGDSBOOT
  BOOT_SERIAL=$(python3 -c 'import secrets; print(secrets.token_hex(4))')
fi

# ------------------------------------------------------------------- sizes
MIB=1048576
if [[ -n $KALI_SIZE ]]; then
  KALI_BYTES=$(round_mib "$(to_bytes "$KALI_SIZE")")
else
  KALI_BYTES=$(round_mib $(( ROOTFS_BYTES * 13 / 10 + 2048 * MIB )))
  ((KALI_BYTES >= 8192 * MIB)) || KALI_BYTES=$((8192 * MIB))
fi
((KALI_BYTES > ROOTFS_BYTES * 11 / 10 + 32 * MIB)) || die "--kali-size is too small for a $((ROOTFS_BYTES / MIB)) MiB rootfs"

if [[ -n $BOOT_SIZE ]]; then BOOT_BYTES=$(round_mib "$(to_bytes "$BOOT_SIZE")")
elif ((TRIPLE)); then BOOT_BYTES=$((2048 * MIB))
else BOOT_BYTES=$((512 * MIB)); fi
boot_need=$(( $(du -sb "$BOOT_DIR" | cut -f1) + $(du -sb "$ROOTFS/boot/rgds" | cut -f1) * 3 + 64 * MIB ))
if ((BOOT_BYTES < boot_need)); then
  BOOT_BYTES=$(round_mib "$boot_need")
  warn "boot partition raised to $((BOOT_BYTES / MIB)) MiB to fit ROCKNIX and room for a Kali kernel update"
fi

if ((TRIPLE)); then
  STORAGE_BYTES=$(round_mib "$(to_bytes "$STORAGE_SIZE")")
  ((STORAGE_BYTES >= ORIG_STORAGE_BYTES)) || STORAGE_BYTES=$(round_mib "$ORIG_STORAGE_BYTES")
  ((STORAGE_BYTES >= 256 * MIB)) || STORAGE_BYTES=$((256 * MIB))
fi

# ------------------------------------------------------ boot partition files
KALI_PARTUUID=$(uuid4)
KALI_FSUUID=$(uuid4)
mkdir -p "$BOOT_DIR/kali" "$BOOT_DIR/rgds"
cp "$ROOTFS"/boot/rgds/{Image,initrd.img,"$RGDS_DTB_NAME"} "$BOOT_DIR/kali/"
[[ -f $ROOTFS/boot/rgds/version ]] && cp "$ROOTFS/boot/rgds/version" "$BOOT_DIR/kali/"
install -m 0644 "$RGDS_ROOT/overlay/usr/local/sbin/rgds-switch-os" "$BOOT_DIR/rgds/rgds-switch-os"

ROCKNIX_LABEL=""
if [[ -n $ORIG_CONF ]]; then
  ROCKNIX_LABEL=$(sed -n 's/^[[:space:]]*[Ll][Aa][Bb][Ee][Ll][[:space:]]\{1,\}\([^[:space:]]*\).*/\1/p' "$ORIG_CONF" | head -n 1)
  [[ -n $ROCKNIX_LABEL ]] || die "ROCKNIX extlinux.conf has no LABEL entry"
  [[ ${ROCKNIX_LABEL^^} != KALI ]] || die "ROCKNIX's boot entry is already called KALI"
fi
DEFAULT_LABEL=KALI
[[ $DEFAULT_OS == rocknix ]] && DEFAULT_LABEL=$ROCKNIX_LABEL

{
  cat <<EOF
# Managed by rgds-multiboot.
# U-Boot on the RG DS shows no menu, so the DEFAULT line picks the OS.
# Change it with rgds-switch-os (Kali), "Switch to Kali" in ROCKNIX's Ports,
# or by editing the line below on a PC. Allowed: ${ROCKNIX_LABEL:+$ROCKNIX_LABEL, }KALI.
# Do not add MENU, PROMPT 1 or TIMEOUT lines: with no screen they stall the boot.
DEFAULT $DEFAULT_LABEL
PROMPT 0

EOF
  if [[ -n $ORIG_CONF ]]; then
    # Keep ROCKNIX's entry as-is; drop only settings that would bring up an
    # (invisible) interactive menu or override our DEFAULT.
    awk '
      /^# >>> rgds-multiboot/ { skip = 1 }
      skip { if (/^# <<< rgds-multiboot/) skip = 0; next }
      /^# Managed by rgds-multiboot/ { hdr = 1 }
      {
        l = tolower($0); sub(/^[ \t]+/, "", l)
        if (l ~ /^(default|ontimeout|prompt|timeout|totaltimeout)([ \t]|$)/) next
        if (!in_label && l ~ /^menu([ \t]|$)/) next
        if (l ~ /^label([ \t]|$)/) in_label = 1
        if (hdr && (l == "" || substr(l, 1, 1) == "#") && !in_label) next
        print
      }' "$ORIG_CONF"
    echo
  fi
  cat <<EOF
# >>> rgds-multiboot kali >>>
LABEL KALI
  LINUX /kali/Image
  INITRD /kali/initrd.img
  devicetree /kali/$RGDS_DTB_NAME
  APPEND root=PARTUUID=$KALI_PARTUUID rootwait rw console=ttyS2,1500000 console=tty0 quiet${EXTRA_CMDLINE:+ $EXTRA_CMDLINE}
# <<< rgds-multiboot kali <<<
EOF
} > "$WORK/extlinux.conf"
mv "$WORK/extlinux.conf" "$BOOT_DIR/extlinux/extlinux.conf"

cat > "$BOOT_DIR/rgds/README.txt" <<EOF
RG DS multiboot card (rgds-multiboot)

What boots is set by the DEFAULT line in extlinux/extlinux.conf on this
partition. To change it from a PC, open that file and set the line to one of:
  DEFAULT ${ROCKNIX_LABEL:-<none>}   (ROCKNIX)
  DEFAULT KALI      (Kali Linux)
Android is on the console's internal storage: remove this card to start it.
EOF

# ------------------------------------------------------- filesystem images
log "building FAT boot partition ($((BOOT_BYTES / MIB)) MiB, label $BOOT_LABEL)"
mkfs.vfat -F 32 -n "$BOOT_LABEL" -i "$BOOT_SERIAL" -C "$WORK/boot.img" $((BOOT_BYTES / 1024)) >/dev/null
mapfile -t top < <(find "$BOOT_DIR" -mindepth 1 -maxdepth 1)
mcopy -s -p -i "$WORK/boot.img" "${top[@]}" ::/

ext4_opts=()
truncate -s 16M "$WORK/probe.img"
mkfs.ext4 -q -F -O ^orphan_file "$WORK/probe.img" >/dev/null 2>&1 && ext4_opts=(-O ^orphan_file)
rm -f "$WORK/probe.img"

log "building Kali partition ($((KALI_BYTES / MIB)) MiB)"
truncate -s "$KALI_BYTES" "$WORK/kali.img"
mkfs.ext4 -q -F -L KALI -U "$KALI_FSUUID" "${ext4_opts[@]}" -d "$ROOTFS" "$WORK/kali.img"

cat > "$WORK/fstab" <<EOF
# Written by rgds-multiboot make-sd-image.sh
UUID=$KALI_FSUUID  /  ext4  defaults,noatime,errors=remount-ro  0 1
LABEL=$BOOT_LABEL  /boot/rgds-multiboot  vfat  defaults,noatime,umask=0022,nofail,x-systemd.device-timeout=10s  0 2
EOF
cat > "$WORK/multiboot.conf" <<EOF
# Written by rgds-multiboot make-sd-image.sh
MODE=$( ((TRIPLE)) && echo triple || echo kali-only )
BOOT_LABEL=$BOOT_LABEL
ROCKNIX_LABEL=$ROCKNIX_LABEL
KALI_PARTUUID=$KALI_PARTUUID
EOF
debugfs -w "$WORK/kali.img" -f - >/dev/null 2>"$WORK/debugfs.log" <<EOF
cd /etc
rm fstab
write $WORK/fstab fstab
sif fstab mode 0100644
sif fstab uid 0
sif fstab gid 0
mkdir /etc/rgds
cd /etc/rgds
rm multiboot.conf
write $WORK/multiboot.conf multiboot.conf
sif multiboot.conf mode 0100644
sif multiboot.conf uid 0
sif multiboot.conf gid 0
EOF
[[ $(debugfs -R 'cat /etc/rgds/multiboot.conf' "$WORK/kali.img" 2>/dev/null) == *"KALI_PARTUUID=$KALI_PARTUUID"* ]] \
  || die "could not write the Kali fstab (see $WORK/debugfs.log)"

if ((TRIPLE)); then
  log "building ROCKNIX STORAGE ($((STORAGE_BYTES / MIB)) MiB)"
  truncate -s "$STORAGE_BYTES" "$WORK/storage.img"
  mkfs.ext4 -q -F -L STORAGE -U "$ST_UUID" "${ext4_opts[@]}" -d "$STORAGE_DIR" "$WORK/storage.img"
fi

# ---------------------------------------------------------- disk layout
# Partition numbers are not in disk order on purpose: ROCKNIX expects STORAGE
# to be partition 2, and it sits last on the card so it can grow.
P1N=$(sectors "$BOOT_BYTES")
KS=$((P1S + P1N)); KN=$(sectors "$KALI_BYTES")
if ((TRIPLE)); then
  SS=$((KS + KN)); SN=$(sectors "$STORAGE_BYTES"); END=$((SS + SN))
else
  END=$((KS + KN))
fi
TOTAL=$((END + 2048))

log "writing $OUTPUT ($((TOTAL / 2048)) MiB, sparse)"
rm -f "$OUTPUT"
truncate -s $((TOTAL * 512)) "$OUTPUT"

if [[ -n $UBOOT ]]; then
  ub_sectors=$(( ($(stat -c %s "$UBOOT") + 511) / 512 ))
  ((RGDS_LOADER_SECTOR + ub_sectors <= P1S)) || die "$UBOOT does not fit before sector $P1S"
  dd if="$UBOOT" of="$OUTPUT" bs=512 seek="$RGDS_LOADER_SECTOR" conv=notrunc status=none
else
  dd if="$RIMG" of="$OUTPUT" bs=512 skip="$RGDS_LOADER_SECTOR" seek="$RGDS_LOADER_SECTOR" \
     count=$((r1s - RGDS_LOADER_SECTOR)) conv=notrunc status=none
fi

FAT_T=EBD0A0A2-B9E5-4433-87C0-68B6B72699C7
LINUX_T=0FC63DAF-8483-4772-8E79-3D69D8477DE4
{
  echo "label: gpt"
  echo "unit: sectors"
  echo "first-lba: 34"
  echo "start=$P1S, size=$P1N, type=$FAT_T, uuid=$(uuid4), name=\"system\", attrs=\"LegacyBIOSBootable\""
  if ((TRIPLE)); then
    echo "start=$SS, size=$SN, type=$LINUX_T, uuid=$(uuid4), name=\"storage\""
    echo "start=$KS, size=$KN, type=$LINUX_T, uuid=$KALI_PARTUUID, name=\"kali\""
  else
    echo "start=$KS, size=$KN, type=$LINUX_T, uuid=$KALI_PARTUUID, name=\"kali\""
  fi
} | sfdisk --quiet --no-reread --no-tell-kernel "$OUTPUT" >/dev/null

put() { dd if="$1" of="$OUTPUT" bs=4M seek=$(($2 * 512)) oflag=seek_bytes conv=notrunc,sparse status=none; }
put "$WORK/boot.img" "$P1S"
put "$WORK/kali.img" "$KS"
((TRIPLE)) && put "$WORK/storage.img" "$SS"

sfdisk -d "$OUTPUT" >&2
log "done: $OUTPUT"
cat >&2 <<EOF

Default OS: $DEFAULT_OS. Flash it to a microSD card (all data on it is lost):
  Linux:   sudo dd if=$OUTPUT of=/dev/sdX bs=4M conv=fsync status=progress
  Windows/macOS: balenaEtcher or Rufus ("DD image" mode)
Then insert the card and power on. Remove the card to start Android.
EOF
