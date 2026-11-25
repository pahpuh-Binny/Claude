#!/usr/bin/env bash
# Build a Kali Linux (kali-rolling, arm64) root filesystem for the RG DS.
#
#   sudo scripts/build-kali-rootfs.sh [options]
#
#   --out DIR            output directory (default out/kali-rootfs)
#   --kernel-debs DIR    install the kernel .debs from build-kernel.sh (default out/kernel)
#   --kali-kernel        use Kali's own linux-image-arm64 instead (only works once
#                        Kali's kernel ships the RG DS device tree)
#   --profile NAME       headless | minimal | default | full (default: default)
#                          headless = kali-linux-core, no desktop
#                          minimal  = + Xfce desktop and handheld tweaks
#                          default  = + kali-tools-top10
#                          full     = + kali-linux-default
#   --user NAME / --password PW   login (default kali / kali; change it!)
#   --hostname NAME      (default rgds-kali)
#   --locale L / --timezone TZ    (default en_US.UTF-8 / UTC)
#   --enable-ssh         start the SSH server at boot (off by default, as on Kali)
#   --mirror URL         (default http://http.kali.org/kali)
#
# Needs: mmdebstrap, the Kali archive keyring, and (on x86 hosts) qemu-user-static
# with binfmt_misc. Easiest is to run it inside a kalilinux/kali-rolling
# container; see docs/BUILDING.md.
# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

OUT="$RGDS_ROOT/out/kali-rootfs"
KDEBS="$RGDS_ROOT/out/kernel"
KALI_KERNEL=0
PROFILE=default
USERNAME=kali
PASSWORD=kali
HOSTNAME_=rgds-kali
LOCALE=en_US.UTF-8
TIMEZONE=UTC
ENABLE_SSH=0
MIRROR=http://http.kali.org/kali
KEYRING=${KALI_KEYRING:-/usr/share/keyrings/kali-archive-keyring.gpg}

while (($#)); do
  case $1 in
    --out) OUT=$2; shift 2 ;;
    --kernel-debs) KDEBS=$2; shift 2 ;;
    --kali-kernel) KALI_KERNEL=1; shift ;;
    --profile) PROFILE=$2; shift 2 ;;
    --user) USERNAME=$2; shift 2 ;;
    --password) PASSWORD=$2; shift 2 ;;
    --hostname) HOSTNAME_=$2; shift 2 ;;
    --locale) LOCALE=$2; shift 2 ;;
    --timezone) TIMEZONE=$2; shift 2 ;;
    --enable-ssh) ENABLE_SSH=1; shift ;;
    --mirror) MIRROR=$2; shift 2 ;;
    -h|--help) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
done

need_root
need mmdebstrap chroot mount umount
[[ -f $KEYRING ]] || die "Kali archive keyring not found at $KEYRING.
       Build inside a kalilinux/kali-rolling container (docs/BUILDING.md) or set KALI_KEYRING."
if [[ $(uname -m) != aarch64 && ! -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ]]; then
  die "arm64 binaries cannot run on this host. Install qemu-user-static and binfmt-support."
fi
if ((!KALI_KERNEL)); then
  compgen -G "$KDEBS/linux-image-*.deb" >/dev/null || die "no kernel packages in $KDEBS (run scripts/build-kernel.sh, or pass --kali-kernel)"
fi

base_pkgs=(kali-linux-core systemd-sysv initramfs-tools sudo locales tzdata
           network-manager wpasupplicant bluez rfkill firmware-realtek
           openssh-server cloud-guest-utils dosfstools e2fsprogs fdisk
           python3 python3-evdev alsa-utils ca-certificates curl zstd)
desktop_pkgs=(kali-desktop-xfce onboard xinput x11-xserver-utils)
optional_pkgs=(firmware-linux-free mesa-utils pkexec)
case $PROFILE in
  headless) desktop_pkgs=(); optional_pkgs=(firmware-linux-free) ;;
  minimal) ;;
  default) optional_pkgs+=(kali-tools-top10) ;;
  full) optional_pkgs+=(kali-linux-default) ;;
  *) die "unknown profile '$PROFILE'" ;;
esac

[[ ! -e $OUT ]] || [[ -z $(ls -A "$OUT" 2>/dev/null) ]] || die "$OUT is not empty; remove it or pick another --out"
mkdir -p "$OUT"
R=$(cd "$OUT" && pwd)

log "bootstrapping kali-rolling (arm64) into $R"
mmdebstrap --mode=root --architectures=arm64 --variant=apt \
  --keyring="$KEYRING" \
  --components="main contrib non-free non-free-firmware" \
  --include="$(IFS=,; echo "${base_pkgs[*]}")" \
  kali-rolling "$R" "deb $MIRROR kali-rolling main contrib non-free non-free-firmware"

mounted=()
borrowed_dns=0
cleanup() {
  local i
  for ((i = ${#mounted[@]} - 1; i >= 0; i--)); do
    umount "${mounted[i]}" 2>/dev/null || umount -l "${mounted[i]}" 2>/dev/null || true
  done
  rm -f "$R/usr/sbin/policy-rc.d"
  if [[ -e $R/etc/resolv.conf.rgds || -L $R/etc/resolv.conf.rgds ]]; then
    mv -f "$R/etc/resolv.conf.rgds" "$R/etc/resolv.conf"
  elif ((borrowed_dns)); then
    rm -f "$R/etc/resolv.conf"
  fi
  borrowed_dns=0
}
trap cleanup EXIT
mnt() { mount "$@" && mounted+=("${*: -1}"); }
mnt -t proc proc "$R/proc"
mnt -t sysfs sys "$R/sys"
mnt --bind /dev "$R/dev"
mnt -t devpts devpts "$R/dev/pts"

# No services starting inside the chroot; borrow the host's DNS.
printf '#!/bin/sh\nexit 101\n' > "$R/usr/sbin/policy-rc.d"
chmod 755 "$R/usr/sbin/policy-rc.d"
if [[ -e $R/etc/resolv.conf || -L $R/etc/resolv.conf ]]; then mv "$R/etc/resolv.conf" "$R/etc/resolv.conf.rgds"; fi
cp -L /etc/resolv.conf "$R/etc/resolv.conf"
borrowed_dns=1

in_chroot() { chroot "$R" /usr/bin/env DEBIAN_FRONTEND=noninteractive LC_ALL=C "$@"; }

log "installing the RG DS overlay"
cp -a "$RGDS_ROOT/overlay/." "$R/"
mkdir -p "$R/usr/share/rgds-multiboot/rocknix-ports" "$R/boot/rgds-multiboot"
cp -a "$RGDS_ROOT/rocknix/ports/." "$R/usr/share/rgds-multiboot/rocknix-ports/"
if ((KALI_KERNEL)); then
  # Distribution kernels build the SD driver as a module: keep a full initramfs.
  printf 'MODULES=most\nCOMPRESS=zstd\n' > "$R/etc/initramfs-tools/conf.d/rgds.conf"
fi

if ((${#desktop_pkgs[@]})); then
  log "installing desktop: ${desktop_pkgs[*]}"
  in_chroot apt-get install -y "${desktop_pkgs[@]}"
fi
for p in "${optional_pkgs[@]}"; do
  log "installing $p"
  in_chroot apt-get install -y "$p" || warn "could not install $p; continuing without it"
done

log "installing the kernel"
if ((KALI_KERNEL)); then
  in_chroot apt-get install -y linux-image-arm64
else
  mkdir -p "$R/tmp/rgds-kernel"
  cp "$KDEBS"/linux-image-*.deb "$R/tmp/rgds-kernel/"
  compgen -G "$KDEBS/linux-headers-*.deb" >/dev/null && cp "$KDEBS"/linux-headers-*.deb "$R/tmp/rgds-kernel/"
  in_chroot sh -c 'apt-get install -y /tmp/rgds-kernel/*.deb'
  rm -rf "$R/tmp/rgds-kernel"
fi
in_chroot /usr/local/sbin/rgds-kernel-sync || die "kernel staging failed (see message above)"
for f in Image initrd.img rk3568-anbernic-rg-ds.dtb; do
  [[ -s $R/boot/rgds/$f ]] || die "missing /boot/rgds/$f in the rootfs after kernel install"
done

log "configuring system"
echo "$HOSTNAME_" > "$R/etc/hostname"
cat > "$R/etc/hosts" <<EOF
127.0.0.1	localhost
127.0.1.1	$HOSTNAME_
::1		localhost ip6-localhost ip6-loopback
EOF
sed -i "s/^# *\($LOCALE\)/\1/" "$R/etc/locale.gen"
grep -q "^$LOCALE" "$R/etc/locale.gen" || echo "$LOCALE ${LOCALE#*.}" >> "$R/etc/locale.gen"
in_chroot locale-gen
in_chroot update-locale "LANG=$LOCALE"
ln -sf "/usr/share/zoneinfo/$TIMEZONE" "$R/etc/localtime"
echo "$TIMEZONE" > "$R/etc/timezone"

# make-sd-image.sh rewrites the boot partition label in here for each card.
cat > "$R/etc/fstab" <<'EOF'
LABEL=KALI      /                     ext4  defaults,noatime,errors=remount-ro  0 1
LABEL=ROCKNIX   /boot/rgds-multiboot  vfat  defaults,noatime,umask=0022,nofail,x-systemd.device-timeout=10s  0 2
EOF

shell=/bin/bash
[[ -x $R/usr/bin/zsh ]] && shell=/usr/bin/zsh
groups=()
for g in sudo netdev audio video input plugdev bluetooth dialout wireshark kaboxer; do
  in_chroot getent group "$g" >/dev/null && groups+=("$g")
done
if ! in_chroot id "$USERNAME" >/dev/null 2>&1; then
  in_chroot useradd -m -s "$shell" -G "$(IFS=,; echo "${groups[*]}")" "$USERNAME"
fi
echo "$USERNAME:$PASSWORD" | in_chroot chpasswd

if [[ -d $R/usr/share/glib-2.0/schemas ]]; then in_chroot glib-compile-schemas /usr/share/glib-2.0/schemas; fi
in_chroot systemctl enable rgds-sd-setup.service serial-getty@ttyGS0.service
in_chroot systemctl enable rgds-padmouse.service
in_chroot systemctl enable NetworkManager.service || true
if ((ENABLE_SSH)); then in_chroot systemctl enable ssh.service; else in_chroot systemctl disable ssh.service 2>/dev/null || true; fi

log "cleaning up"
in_chroot apt-get clean
rm -f "$R"/etc/ssh/ssh_host_*_key "$R"/etc/ssh/ssh_host_*_key.pub   # regenerated on first boot
: > "$R/etc/machine-id"
rm -f "$R/var/lib/dbus/machine-id"
cat > "$R/etc/rgds/build-info" <<EOF
built=$(date -u +%Y-%m-%dT%H:%M:%SZ)
profile=$PROFILE
kernel=$(cat "$R/boot/rgds/version")
kernel_source=$( ((KALI_KERNEL)) && echo kali || echo rgds-mainline)
EOF

cleanup
trap - EXIT
log "done: $R ($(du -sh "$R" | cut -f1)). Next: scripts/make-sd-image.sh"
[[ $PASSWORD == kali ]] && warn "the login is $USERNAME / kali. Change it with passwd on first boot."
exit 0
