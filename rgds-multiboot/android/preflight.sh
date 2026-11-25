#!/usr/bin/env bash
# Read-only check: can this RG DS take a newer Android as a Generic System
# Image (GSI)? Changes nothing on the device.
#
#   android/preflight.sh            (RG DS on USB with USB debugging enabled)
#
# Writes the full report to android-preflight-<date>.txt as well.
set -euo pipefail
command -v adb >/dev/null || { echo "adb not found (install Android platform-tools)" >&2; exit 1; }

state=$(adb get-state 2>/dev/null || true)
[[ $state == device ]] || { echo "No device. Enable Settings > Developer options > USB debugging, connect, and accept the prompt." >&2; exit 1; }

REPORT="android-preflight-$(date +%Y%m%d-%H%M%S).txt"
PROPS=$(adb shell getprop | tr -d '\r')
prop() { sed -n "s/^\[$1\]: \[\(.*\)\]$/\1/p" <<< "$PROPS" | head -n 1; }

pass=0 warn=0 fail=0
out() { printf '%s\n' "$*" | tee -a "$REPORT"; }
res() {  # res PASS|WARN|FAIL "message"
  case $1 in PASS) pass=$((pass + 1)) ;; WARN) warn=$((warn + 1)) ;; FAIL) fail=$((fail + 1)) ;; esac
  out "  [$1] $2"
}

: > "$REPORT"
out "RG DS Android preflight  $(date)"
out ""
out "Device:   $(prop ro.product.manufacturer) $(prop ro.product.model) ($(prop ro.product.device)), platform $(prop ro.board.platform)"
out "Android:  $(prop ro.build.version.release) (SDK $(prop ro.build.version.sdk)), $(prop ro.build.type), patch $(prop ro.build.version.security_patch)"
out "Vendor:   Android $(prop ro.vendor.build.version.release), VNDK $(prop ro.vndk.version), first API $(prop ro.product.first_api_level)"
out "Kernel:   $(adb shell uname -r | tr -d '\r')"
out "Build:    $(prop ro.build.fingerprint)"
out ""
out "Checks for flashing a GSI:"

if [[ $(prop ro.treble.enabled) == true ]]; then
  res PASS "Project Treble enabled"
else
  res FAIL "Project Treble not enabled: a GSI cannot boot on this vendor image"
fi

abis=$(prop ro.product.cpu.abilist)
if [[ $abis == *arm64-v8a* ]]; then res PASS "64-bit ARM ($abis): use an arm64 GSI"; else res FAIL "no arm64-v8a ABI ($abis)"; fi

if [[ $(prop ro.boot.dynamic_partitions) == true ]]; then
  res PASS "dynamic partitions: flash the GSI from fastbootd (adb reboot fastboot)"
else
  res WARN "no dynamic partitions: the GSI must fit the fixed system partition"
fi

slot=$(prop ro.boot.slot_suffix)
if [[ -n $slot ]]; then res PASS "A/B device, current slot $slot"; else res PASS "A-only device (single system partition)"; fi

vbs=$(prop ro.boot.verifiedbootstate)
locked=$(prop ro.boot.flash.locked)
case $vbs in
  orange) res PASS "bootloader unlocked (verified boot state orange)" ;;
  green)  res WARN "verified boot is enforcing (green): unlock or flash a vbmeta with verification disabled first" ;;
  "")     res WARN "verified boot state not reported; Rockchip builds often ship with AVB off" ;;
  *)      res WARN "verified boot state: $vbs" ;;
esac
if [[ $locked == 1 ]]; then res WARN "ro.boot.flash.locked=1: 'fastboot flashing unlock' may be needed (wipes data)"; fi

vndk=$(prop ro.vndk.version)
if [[ -n $vndk && $vndk =~ ^[0-9]+$ ]]; then
  if ((vndk >= 34)); then
    res PASS "vendor is Android 14+ (VNDK $vndk): Android 15/16 GSIs are the realistic targets"
  else
    res WARN "vendor VNDK $vndk is old for current GSIs"
  fi
  res WARN "Android 17 GSIs on an Android 14 vendor image are untested on the RG DS; expect to fall back to 16"
fi

ndisp=$(adb shell dumpsys display 2>/dev/null | tr -d '\r' | grep -c 'mDisplayId=' || true)
out "  [INFO] displays reported by Android: $ndisp. A GSI does not carry Anbernic's dual-screen"
out "         tweaks: the second screen may stay blank or only work in apps that use it."
out ""
out "Result: $pass pass, $warn warning, $fail fail."
if ((fail)); then
  out "Verdict: a GSI will not work on this firmware. Stay on stock Android 14 or GammaOS."
else
  out "Verdict: a GSI is worth trying. FIRST back up the whole eMMC: android/backup-emmc.sh"
fi
echo "Report saved to $REPORT"
