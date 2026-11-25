# Convenience targets. See README.md and docs/BUILDING.md.
#   make test                                  lint + tests (needs root for the image test)
#   make kernel uboot                          build kernel packages / U-Boot
#   sudo make rootfs [PROFILE=default]         build the Kali rootfs
#   sudo make image ROCKNIX=path/to/ROCKNIX.img.gz [OUT=rgds-triple.img]
#   sudo make image-kali [OUT=rgds-kali.img]   Kali-only card (Android stays on eMMC)

PROFILE ?= default
OUT ?= rgds-triple.img
SHELL_FILES := scripts/*.sh scripts/lib/common.sh tests/*.sh android/*.sh rocknix/ports/*.sh \
	overlay/usr/local/sbin/* overlay/usr/local/bin/rgds-display-setup \
	overlay/etc/kernel/postinst.d/* overlay/etc/initramfs/post-update.d/*

.PHONY: test lint kernel uboot rootfs image image-kali

test: lint
	python3 -m unittest discover -s tests -p 'test_*.py'
	bash tests/test-kernel-sync.sh
	bash tests/test-dts-override.sh
	bash tests/test-make-sd-image.sh

lint:
	shellcheck -x -P SCRIPTDIR $(SHELL_FILES)
	python3 -m py_compile overlay/usr/local/bin/rgds-padmouse

kernel:
	scripts/build-kernel.sh

uboot:
	scripts/build-uboot.sh

rootfs:
	scripts/build-kali-rootfs.sh --profile $(PROFILE)

image:
	@test -n "$(ROCKNIX)" || { echo "set ROCKNIX=path/to/ROCKNIX image"; exit 1; }
	scripts/make-sd-image.sh --rocknix "$(ROCKNIX)" --kali out/kali-rootfs -o "$(OUT)"

image-kali:
	scripts/make-sd-image.sh --uboot out/uboot/u-boot-rockchip.bin --kali out/kali-rootfs -o "$(if $(filter rgds-triple.img,$(OUT)),rgds-kali.img,$(OUT))"
