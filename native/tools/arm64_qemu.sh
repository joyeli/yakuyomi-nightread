#!/usr/bin/env bash
# 手機晶片模擬：NDK clang 編的 arm64 靜態測試檔，在 qemu-aarch64（使用者模式）上跑數值核心。
#   1. nr_core_tests：已知值、不變量、型別、頁 API（在 256 KB 堆疊的執行緒上）。
#   2. nr_golden_check：對 JUnit 寫下的 Java 答案逐塊比（純量函式每個數百萬組、陣列、整表、像素）。
# CPU 用 cortex-a57（armv8.0 基本指令）：產品只准 armv8-a，用了更新的指令會在這裡直接 SIGILL。
# qemu 下的時間沒有意義，只看結果。
#
# 用法：native/tools/arm64_qemu.sh <golden 目錄> [NDK 建置目錄（預設 native/build/ndk-arm64，沒有就先建）]
#   golden 目錄：先在主機跑 JUnit 並加 -PgoldenOut=<目錄>（Gradle）或 -Dnightread.goldenOut=<目錄>。
#   qemu：環境變數 QEMU_AARCH64，否則 PATH 上的 qemu-aarch64-static／qemu-aarch64。
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
golden="${1:?用法：arm64_qemu.sh <golden 目錄> [NDK 建置目錄]}"
out="${2:-$here/build/ndk-arm64}"
q="${QEMU_AARCH64:-$(command -v qemu-aarch64-static || command -v qemu-aarch64 || true)}"
[ -n "$q" ] || { echo "找不到 qemu-aarch64（設 QEMU_AARCH64）" >&2; exit 2; }
cpu="${QEMU_CPU:-cortex-a57}"

[ -x "$out/nr_golden_check" ] || bash "$here/tools/ndk_build.sh" "$out"
file "$out/nr_core_tests" | grep -q "ARM aarch64" || { echo "$out/nr_core_tests 不是 arm64 執行檔" >&2; exit 2; }

echo "== qemu-aarch64 -cpu $cpu nr_core_tests"
"$q" -cpu "$cpu" "$out/nr_core_tests" | tail -3
echo "== qemu-aarch64 -cpu $cpu nr_golden_check $golden"
"$q" -cpu "$cpu" "$out/nr_golden_check" -j "${JOBS:-$(nproc)}" "$golden"
