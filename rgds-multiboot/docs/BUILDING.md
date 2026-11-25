# Building

All steps run on a Linux PC: Debian/Ubuntu/Kali, x86-64 or arm64. WSL2 works.
`make-sd-image.sh` uses no loop devices, so containers work too.

| Step | Script | Time | Disk |
|---|---|---|---|
| Kernel | `scripts/build-kernel.sh` | 20-60 min | ~6 GB |
| U-Boot (Kali-only card) | `scripts/build-uboot.sh` | 2 min | ~1 GB |
| Kali rootfs | `sudo scripts/build-kali-rootfs.sh` | 30-90 min on x86 (emulated) | ~10 GB (default profile) |
| Card image | `sudo scripts/make-sd-image.sh` | 5-10 min | image size, kept sparse |

Downloads are cached in `out/cache/`; outputs go to `out/`.

## Host packages

```bash
sudo apt install build-essential bc bison flex libssl-dev libelf-dev dpkg-dev \
  gcc-aarch64-linux-gnu curl xz-utils swig python3-pyelftools python3-setuptools \
  mmdebstrap qemu-user-static binfmt-support \
  fdisk dosfstools mtools e2fsprogs zstd
```

`build-kali-rootfs.sh` also needs Kali's archive keyring at
`/usr/share/keyrings/kali-archive-keyring.gpg`. Kali has it already. On other
hosts, use the container route below (or set `KALI_KEYRING=`).

## Container route (any Linux host, recommended for the rootfs)

```bash
# once per boot of the host: let it run arm64 binaries
docker run --privileged --rm tonistiigi/binfmt --install arm64

docker run --rm -it --privileged -v "$PWD":/work -w /work kalilinux/kali-rolling bash -c '
  apt-get update &&
  apt-get install -y mmdebstrap qemu-user-static kali-archive-keyring \
     fdisk dosfstools mtools e2fsprogs python3 zstd &&
  scripts/build-kali-rootfs.sh --profile default'
```

## Kernel: `scripts/build-kernel.sh`

```
--version 7.3     kernel.org release to build. It must contain
                  rk3568-anbernic-rg-ds.dts (current mainline does; the script
                  stops with a clear message if the chosen version does not)
--src DIR         use an existing kernel tree instead of downloading
--out DIR         default out/kernel
--no-otg          keep mainline's gadget-only USB-C (see HOW-IT-WORKS.md)
```

It starts from arm64 `defconfig`, merges `config/kernel/rgds.config`, and stops
if any driver the handheld needs is missing (display, touch, buttons, PMIC,
audio, Wi-Fi). Outputs: `linux-image-*.deb` (installed into Kali), plus `Image`,
the `.dtb` and the final `config` for reference.

## U-Boot: `scripts/build-uboot.sh`

Only for a Kali-only card; a triple-boot card keeps ROCKNIX's bootloader.
Mainline U-Boot has no RG DS board yet, so this uses Armbian's working recipe:
the `quartz64-a-rk3566_defconfig` (same RK817 PMIC) at `v2026.01`, with
Rockchip's `rk3568_bl31_v1.45.elf` and `rk3568_ddr_1056MHz_v1.23.bin` from rkbin.
Override with `UBOOT_TAG`, `UBOOT_DEFCONFIG`, `BL31_BLOB`, `DDR_BLOB`. Output:
`out/uboot/u-boot-rockchip.bin`.

## Kali rootfs: `scripts/build-kali-rootfs.sh`

```
--profile headless | minimal | default | full
            headless  kali-linux-core only (SSH/serial use)
            minimal   + Xfce, on-screen keyboard, dual-screen setup, gamepad mouse
            default   + kali-tools-top10
            full      + kali-linux-default (large)
--kali-kernel         use Kali's linux-image-arm64 instead of the rgds kernel
                      (only once Kali's kernel ships the RG DS device tree)
--user / --password   default kali / kali
--hostname, --locale, --timezone, --enable-ssh, --mirror
```

The kernel is installed through apt, and hooks copy it to the boot partition, so
`apt upgrade` on the device keeps working. SSH host keys and the machine ID are
generated on first boot.

## Card image: `scripts/make-sd-image.sh`

```
--rocknix IMG      ROCKNIX image (.img/.gz/.xz) -> triple boot
--uboot FILE       bootloader for a Kali-only card (or to replace ROCKNIX's)
--kali DIR|TAR     Kali rootfs
--default kali|rocknix
--kali-size        default: rootfs + 30% + 2 GB (minimum 8 GB)
--boot-size        default 2 GB (512 MB Kali-only); raised automatically if needed
--storage-size     ROCKNIX STORAGE inside the image, default 1 GB; Kali grows it
                   to fill the card on its first boot
--cmdline "..."    extra Kali kernel arguments
```

The image file is sparse: `ls -l` shows the full size but it uses far less disk.
`dd` with `conv=fsync` or balenaEtcher writes it fine. Pick a card at least as
large as the image.
