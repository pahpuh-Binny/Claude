#!/bin/sh
# Runs the native engine tests on a Linux host under AddressSanitizer,
# UndefinedBehaviorSanitizer and ThreadSanitizer.
set -e
cd "$(dirname "$0")"
OUT=${TMPDIR:-/tmp}/uvc-native-tests
mkdir -p "$OUT"
CC=${CC:-clang}
$CC -g -O1 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Ifake -I../../main/cpp \
    -fsanitize=address,undefined -fno-omit-frame-pointer -pthread \
    -o "$OUT/test_asan" test_uvc_stream.c
echo "== AddressSanitizer + UBSan"
"$OUT/test_asan"
$CC -g -O1 -Wall -Wno-unused-parameter -Wno-unused-function -Ifake -I../../main/cpp \
    -fsanitize=thread -pthread -o "$OUT/test_tsan" test_uvc_stream.c
echo "== ThreadSanitizer"
"$OUT/test_tsan"
