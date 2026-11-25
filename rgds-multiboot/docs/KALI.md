# Kali on the RG DS

## Controls

`rgds-padmouse` (a system service) turns the controls into a mouse and keyboard,
including at the login screen:

| Control | Does |
|---|---|
| Left stick | move pointer |
| Right stick | scroll |
| A | left click |
| B | right click |
| X | middle click |
| Y | Space |
| Start / Select | Enter / Esc |
| L1 / R1 | Backspace / Tab |
| L2 / R2 (hold) | Ctrl / Shift |
| L3 / R3 | Alt / Super |
| D-pad | arrow keys |
| **Home** | turn mouse mode off/on (for apps that read the gamepad) |

Change it in `/etc/rgds/padmouse.conf`, then `sudo systemctl restart rgds-padmouse`.

The on-screen keyboard (Onboard) pops up when a text field is focused. You can
also tap its floating icon. Both screens take touch input. The login screen
shows the keyboard too.

## Screens

`rgds-display-setup` puts the top screen above the bottom one, with the panel on
the bottom (primary) screen, and binds each touch panel to its own screen.

- Screens swapped? `rgds-display-setup --swap` (saved per user).
- Panel sideways? Set `TOP_ROTATE` / `BOTTOM_ROTATE` in
  `~/.config/rgds/display.conf` (`left`, `right`, `inverted`).
- One screen only, or mirrored: `LAYOUT=bottom-only | top-only | mirror`.

## Switching OS

- **Reboot into ROCKNIX** / **Shut down for Android**: in the app menu (System).
- Terminal: `sudo rgds-switch-os rocknix | kali | android | status`.

## Networking and Wi-Fi testing

The built-in Wi-Fi is a Realtek RTL8821CS (`rtw88_8821cs`). It is fine for
normal use. Monitor mode and packet injection on it are limited, so for Wi-Fi
assessments plug a supported USB adapter into the USB-C port with an OTG adapter
or hub. The rgds kernel includes drivers for the common monitor-mode chipsets:
Atheros AR9271 (`ath9k_htc`), Ralink/MediaTek RT2800, MT7601U, MT76x0U, MT76x2U,
MT7921U/MT7925U, and Realtek RTL8187 and RTL88xxAU/CU/BU.

If Bluetooth does not come up, the board may need Realtek's
`rtl_bt/rtl8821cs_config.bin`. ROCKNIX ships one; copy it into `/lib/firmware/rtl_bt/`.

Only test networks and devices you own or have written permission to assess.

## Rescue console over USB

The kernel loads `g_serial`, so plugging the RG DS into a PC gives a serial port
(`/dev/ttyACM0` on Linux, a COM port on Windows) with a login prompt:

```bash
screen /dev/ttyACM0 115200
```

This works even if the display or desktop is broken.

## SSH

Off by default, as on Kali. Enable with `sudo systemctl enable --now ssh` or
build with `--enable-ssh`. Host keys are generated on first boot.

## Updates

`sudo apt update && sudo apt full-upgrade` works normally. Kernel updates are
copied to the boot partition automatically (see HOW-IT-WORKS.md). To rebuild
your own kernel, run `scripts/build-kernel.sh` on a PC and install the `.deb` with
`sudo apt install ./linux-image-*.deb`.

## Troubleshooting

| Symptom | Try |
|---|---|
| Card boots ROCKNIX although you picked Kali | The Kali entry failed to load, so U-Boot fell back. Check `kali/` on the FAT partition from a PC. |
| Black screens in Kali | Use the USB serial console; check `journalctl -b` and `dmesg \| grep -i dsi`. |
| Touch hits the wrong screen | `rgds-display-setup --swap`, or fix `TOP_TOUCH_BUS` / `BOTTOM_TOUCH_BUS`. |
| Pointer drifts | Raise `deadzone` in `/etc/rgds/padmouse.conf`. |
| STORAGE did not grow | `journalctl -u rgds-sd-setup` |
