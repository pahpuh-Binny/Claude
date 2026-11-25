#!/usr/bin/env bash
# Check that config/kernel/rgds-usb-otg.dtsi compiles when appended to a board
# device tree the way build-kernel.sh does it (mock tree with the same labels).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d "${TMPDIR:-/tmp}/rgds-dts.XXXXXX")
trap 'rm -rf "$T"' EXIT
if ! command -v dtc >/dev/null || ! command -v cpp >/dev/null; then echo "skipped: needs dtc and cpp"; exit 0; fi

cat > "$T/board.dts" <<'DTS'
/dts-v1/;
/ {
	model = "mock RG DS";
	usb_host0_xhci: usb@fcc00000 { compatible = "snps,dwc3"; dr_mode = "peripheral"; };
	usb2phy0: usb2phy@fe8a0000 { usb2phy0_otg: otg-port { #phy-cells = <0>; }; };
	pmic { regulators { otg_switch: OTG_SWITCH { regulator-name = "otg_switch"; }; }; };
};
DTS
{ printf '\n/* rgds-multiboot: usb otg */\n'; cat "$ROOT/config/kernel/rgds-usb-otg.dtsi"; } >> "$T/board.dts"
cpp -nostdinc -undef -x assembler-with-cpp -P "$T/board.dts" > "$T/pre.dts"
dtc -q -I dts -O dtb -o "$T/board.dtb" "$T/pre.dts"
dtc -q -I dtb -O dts "$T/board.dtb" > "$T/out.dts"
if grep -q 'dr_mode = "otg"' "$T/out.dts" && grep -q 'phy-supply' "$T/out.dts"; then
  echo "all checks passed"
else
  echo "FAIL: override not applied"; exit 1
fi
