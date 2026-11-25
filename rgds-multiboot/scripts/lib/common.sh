# shellcheck shell=bash disable=SC2034  # the board constants are used by the scripts that source this
# Shared helpers for the rgds-multiboot build scripts. Source, don't execute.

set -euo pipefail

RGDS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export RGDS_ROOT

# Facts about the board, kept in one place.
RGDS_DTB_NAME="rk3568-anbernic-rg-ds.dtb"
RGDS_DTS_PATH="arch/arm64/boot/dts/rockchip/rk3568-anbernic-rg-ds.dts"
# Rockchip loaders live at sector 64 (32 KiB); partitions must start after them.
RGDS_LOADER_SECTOR=64
RGDS_MIN_PART_START=32768   # 16 MiB, in 512-byte sectors

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*" >&2; }
warn() { printf '\033[1;33mWARN:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

need() {
  local missing=() t
  for t in "$@"; do command -v "$t" >/dev/null 2>&1 || missing+=("$t"); done
  ((${#missing[@]} == 0)) || die "missing tools: ${missing[*]} (see docs/BUILDING.md for the package list)"
}

need_root() {
  [[ $EUID -eq 0 ]] || die "this step must run as root (use sudo)"
}

# Parse sizes like 512M, 20G, 1.5G or plain bytes into bytes.
to_bytes() {
  local v=${1^^}
  case $v in
    *K|*KB|*KIB) awk -v n="${v%%K*}" 'BEGIN{printf "%d", n*1024}' ;;
    *M|*MB|*MIB) awk -v n="${v%%M*}" 'BEGIN{printf "%d", n*1024*1024}' ;;
    *G|*GB|*GIB) awk -v n="${v%%G*}" 'BEGIN{printf "%d", n*1024*1024*1024}' ;;
    *[0-9]) printf '%d' "$v" ;;
    *) die "cannot parse size '$1'" ;;
  esac
}

# Round a byte count up to a whole number of MiB.
round_mib() { echo $(( ($1 + 1048575) / 1048576 * 1048576 )); }

# Decompress (if needed) $1 into directory $2 and print the resulting path.
unpack_image() {
  local src=$1 dir=$2 base
  base=$(basename "$src")
  case $src in
    *.gz)  log "decompressing $base"; gzip -dc "$src" > "$dir/${base%.gz}"; echo "$dir/${base%.gz}" ;;
    *.xz)  log "decompressing $base"; xz -dc "$src" > "$dir/${base%.xz}"; echo "$dir/${base%.xz}" ;;
    *.zst) log "decompressing $base"; zstd -qdc "$src" > "$dir/${base%.zst}"; echo "$dir/${base%.zst}" ;;
    *)     echo "$src" ;;
  esac
}

# Fetch a URL to a file unless it is already there.
fetch() {
  local url=$1 out=$2
  [[ -s $out ]] && { log "using cached $(basename "$out")"; return; }
  log "downloading $url"
  curl -fL --retry 4 --retry-delay 5 -o "$out.part" "$url"
  mv "$out.part" "$out"
}

cross_compile_prefix() {
  if [[ $(uname -m) == aarch64 ]]; then echo ""; else echo "${CROSS_COMPILE:-aarch64-linux-gnu-}"; fi
}
