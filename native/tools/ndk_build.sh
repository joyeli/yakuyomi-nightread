#!/usr/bin/env bash
# 用 NDK（28.2.13676358）的 CMake 工具鏈把 native/ 編成 arm64-v8a：核心、JNI 物件、靜態測試執行檔（給 qemu-aarch64）。
# native-guard 的 FMA 掃描（fma_scan.sh）與手機晶片模擬（arm64_qemu.sh）都用它的產物。
#
# 用法：native/tools/ndk_build.sh [建置目錄（預設 native/build/ndk-arm64）] [Debug|Release（預設 Debug：驗證強制 -O2 蓋過 -O0）]
# NDK：ANDROID_NDK_ROOT／ANDROID_NDK_HOME，沒設就找 $ANDROID_HOME（或 $ANDROID_SDK_ROOT）/ndk/28.2.13676358。
# CMake：PATH 上的 cmake（≥ 3.22.1）。WSL 本機（NDK 只有 windows-x86_64 版）時自動改用 Windows SDK 的 cmake.exe／ninja.exe。
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$here/build/ndk-arm64}"
type="${2:-Debug}"
NDK_VER=28.2.13676358

ndk="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [ -z "$ndk" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -z "$sdk" ] && [ -d /mnt/c/Users ] && sdk="$(ls -d /mnt/c/Users/*/AppData/Local/Android/Sdk 2>/dev/null | head -1)"
  ndk="$sdk/ndk/$NDK_VER"
fi
[ -f "$ndk/build/cmake/android.toolchain.cmake" ] || { echo "找不到 NDK：$ndk" >&2; exit 2; }

args=(-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static
      -DCMAKE_BUILD_TYPE="$type" -DNR_STATIC_TESTS=ON -DNR_BUILD_TESTS=ON -DNR_HOST_JNI=OFF)

if [ -d "$ndk/toolchains/llvm/prebuilt/linux-x86_64" ]; then
  cmake -S "$here" -B "$out" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" "${args[@]}"
  cmake --build "$out" -j "$(nproc)"
else
  # WSL：Windows 版 NDK 的 clang 只能由 Windows 的 cmake／ninja 驅動，路徑要換成 Windows 形式
  sdkdir="$(dirname "$(dirname "$ndk")")"
  cm="$(ls -d "$sdkdir"/cmake/*/bin | sort | tail -1)"
  w() { wslpath -w "$1"; }
  mkdir -p "$out"
  "$cm/cmake.exe" -S "$(w "$here")" -B "$(w "$out")" -G Ninja -DCMAKE_MAKE_PROGRAM="$(w "$cm/ninja.exe")" \
    -DCMAKE_TOOLCHAIN_FILE="$(w "$ndk/build/cmake/android.toolchain.cmake")" "${args[@]}"
  "$cm/cmake.exe" --build "$(w "$out")" -j 16
fi
echo "arm64 產物：$out"
