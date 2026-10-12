#!/usr/bin/env bash
# 主機（x86_64 Linux／WSL）建置：核心＋原生測試（ctest，在 256 KB 堆疊的執行緒上）＋JUnit 用的 libnightread_host.so。
#
# 用法：native/tools/build_host.sh [建置目錄（預設 native/build/host）] [額外的 cmake 參數…]
#   例：native/tools/build_host.sh native/build/asan -DNR_SANITIZE=address,undefined
#       native/tools/build_host.sh native/build/tsan -DNR_SANITIZE=thread     # TSan：見 README（新核心要關 ASLR）
# 需要：cmake ≥ 3.22.1、gcc／clang（C++17）、JAVA_HOME 指向 JDK（只用它的 jni.h；沒有就不建 .so、只跑 ctest）。
# 接著跑 JUnit：./gradlew :nightread:testDebugUnitTest -PtestGroup=native -PnativeLib=native/build/host/libnightread_host.so
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$here/build/host}"
shift || true
cmake -S "$here" -B "$out" -DCMAKE_BUILD_TYPE=Debug "$@"
cmake --build "$out" -j "$(nproc)"
(cd "$out" && ctest --output-on-failure)
if [ -f "$out/libnightread_host.so" ]; then echo "JUnit 用：$out/libnightread_host.so"; fi
