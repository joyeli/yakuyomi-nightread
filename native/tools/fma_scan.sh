#!/usr/bin/env bash
# native-guard：用反組譯確認夜讀 C++ 核心沒有被編譯器合成乘加（FMA），也沒有 OpenMP 參照。
#   1. NDK arm64 的物件檔（ndk_build.sh 的產物）：不得有 fmadd／fmsub／fnmadd／fnmsub／fmla／fmls。
#      （NDK clang 預設 -ffp-contract=on 會合成；arm64 原型在格溝找線段 1,922 次呼叫裡有 4 次對不上，就是它。）
#   2. 主機另用 -mfma 編一次（x86_64 預設指令集沒有 FMA，主機測試抓不到）：不得有 vfmadd／vfmsub／vfnmadd／vfnmsub。
#   3. 物件檔不得參照 __kmpc_*／omp_*（不連 OpenMP；v0.18 的 __kmp 閃退）。
# 只掃本 repo 的物件檔（核心、fdlibm、JNI、測試案例），不掃靜態執行檔（裡面的 bionic libm 本來就可能用 FMA）。
#
# 用法：native/tools/fma_scan.sh [NDK arm64 建置目錄（預設 native/build/ndk-arm64）] [主機 -mfma 建置目錄（預設 native/build/host-fma）]
# NDK 位置同 ndk_build.sh；主機 -mfma 版用 PATH 上的 cmake 與 gcc／clang 現場建。
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
ndkout="${1:-$here/build/ndk-arm64}"
fmaout="${2:-$here/build/host-fma}"
NDK_VER=28.2.13676358
fail=0

ndk="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [ -z "$ndk" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -z "$sdk" ] && [ -d /mnt/c/Users ] && sdk="$(ls -d /mnt/c/Users/*/AppData/Local/Android/Sdk 2>/dev/null | head -1)"
  ndk="$sdk/ndk/$NDK_VER"
fi
bin="$(ls -d "$ndk"/toolchains/llvm/prebuilt/*/bin | head -1)"
objdump="$bin/llvm-objdump"; [ -x "$objdump" ] || objdump="$bin/llvm-objdump.exe"
nm="$bin/llvm-nm"; [ -x "$nm" ] || nm="$bin/llvm-nm.exe"

targets="nightread_core nightread_fdlibm nightread_jni nightread_testcases"

objs_of() {   # $1＝建置目錄：列出本 repo 目標的 .o（相對路徑，Windows 版工具也吃）
  local d
  for t in $targets; do
    d="$1/CMakeFiles/$t.dir"
    [ -d "$d" ] && (cd "$1" && find "CMakeFiles/$t.dir" -name '*.o')
  done
  return 0
}

# 1. arm64
mapfile -t aobjs < <(objs_of "$ndkout")
[ "${#aobjs[@]}" -gt 0 ] || { echo "沒有 arm64 物件檔：$ndkout（先跑 ndk_build.sh）" >&2; exit 2; }
n_arm=0
for o in "${aobjs[@]}"; do
  hits="$(cd "$ndkout" && "$objdump" -d --no-show-raw-insn "$o" | grep -Ei $'\t(fmadd|fmsub|fnmadd|fnmsub|fmla|fmls)\\b' || true)"
  if [ -n "$hits" ]; then echo "FMA（arm64）：$o"; echo "$hits" | head -5; fail=1; fi
  omp="$(cd "$ndkout" && "$nm" "$o" | grep -E '__kmpc|omp_' || true)"
  if [ -n "$omp" ]; then echo "OpenMP 參照：$o"; echo "$omp" | head -5; fail=1; fi
  n_arm=$((n_arm + 1))
done
# 反面檢查：同一個檔用 NDK 預設（-ffp-contract=on）編，確定掃描真的抓得到 FMA（避免掃描本身失效卻一直綠）
cxx="$bin/clang++"; [ -x "$cxx" ] || cxx="$bin/clang++.exe"
probe="$ndkout/fma_probe"   # 放在建置目錄（Windows 版 clang 讀不到 WSL 的 /tmp）
mkdir -p "$probe"
printf 'double f(double a,double b,double c){return a*b+c;}\n' > "$probe/p.cpp"
(cd "$probe" && "$cxx" --target=aarch64-linux-android26 -O2 -c p.cpp -o p.o && "$objdump" -d p.o | grep -qEi $'\t(fmadd)') \
  || { echo "掃描自檢失敗：預設旗標的 a*b+c 沒反組譯出 fmadd" >&2; fail=1; }
rm -rf "${probe:?}"

# 1b. 照引擎方式連出來的 .so 只能匯出 JNI（Java_*、JNI_OnLoad）：核心的 nr_*、nr::、fdlibm 一律不外露
so="$ndkout/libnightread_export_probe.so"
if [ -f "$so" ]; then
  extra="$(cd "$ndkout" && "$nm" -D --defined-only libnightread_export_probe.so | awk '{print $NF}' | grep -vE '^(Java_|JNI_OnLoad)' || true)"
  if [ -n "$extra" ]; then echo "多匯出的符號："; echo "$extra" | head -10; fail=1; fi
  njni="$(cd "$ndkout" && "$nm" -D --defined-only libnightread_export_probe.so | grep -c ' Java_' || true)"
  echo "匯出檢查：JNI 符號 $njni 個，其他 $(printf '%s' "$extra" | grep -c . || true) 個"
else
  echo "沒有 $so（ndk_build.sh 會建）" >&2; fail=1
fi

# 2. 主機 -mfma
mkdir -p "$fmaout"
{ cmake -S "$here" -B "$fmaout" -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS=-mfma -DCMAKE_CXX_FLAGS=-mfma \
    -DNR_BUILD_TESTS=ON -DNR_HOST_JNI=OFF && cmake --build "$fmaout" -j "$(nproc)" --target nightread_core nightread_testcases; } \
  > "$fmaout/fma_scan_build.log" 2>&1 || { cat "$fmaout/fma_scan_build.log"; exit 2; }
printf 'double f(double a,double b,double c){return a*b+c;}\n' > "$fmaout/p.cpp"
(cd "$fmaout" && ${CXX:-c++} -O2 -mfma -ffp-contract=fast -c p.cpp -o p.o && objdump -d p.o | grep -qEi 'vfn?m(add|sub)') \
  || { echo "掃描自檢失敗：-mfma -ffp-contract=fast 的 a*b+c 沒反組譯出 vfmadd" >&2; fail=1; }
rm -f "$fmaout/p.cpp" "$fmaout/p.o"
mapfile -t hobjs < <(objs_of "$fmaout")
n_host=0
for o in "${hobjs[@]}"; do
  hits="$(cd "$fmaout" && objdump -d --no-show-raw-insn "$o" | grep -Ei 'vfn?m(add|sub)' || true)"
  if [ -n "$hits" ]; then echo "FMA（x86_64 -mfma）：$o"; echo "$hits" | head -5; fail=1; fi
  n_host=$((n_host + 1))
done

echo "fma_scan：arm64 物件 $n_arm 個、x86_64 -mfma 物件 $n_host 個；$([ $fail = 0 ] && echo '沒有 FMA、沒有 OpenMP' || echo '有問題')"
exit $fail
