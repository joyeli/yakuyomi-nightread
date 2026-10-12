#!/usr/bin/env python3
"""native-guard：讀 CMake 的 compile_commands.json，確認本 repo 每個原始檔的實際編譯指令守住逐位元規則。

- 最後一個 -O 旗標必須是 -O2（蓋過 AGP／CMake Debug 的 -O0、Release 的 -O3；-O0 的 C++ 在 debug APK 會比 Kotlin 還慢）。
- 必須有 -ffp-contract=off -fno-fast-math -fwrapv -fno-strict-aliasing -fno-builtin，且它們之後不得再出現
  -ffp-contract=fast／on、-ffast-math、-Ofast、-fstrict-aliasing、-fbuiltin。
- arm64：-march 只能是 armv8-a（不開 +dotprod、+fp16 等擴充）。
- 不得有 -fopenmp。

用法：check_compile_flags.py <建置目錄>...（例如 native/build/ndk-arm64 native/build/host）
"""
import json
import os
import shlex
import sys

REQUIRED = ["-ffp-contract=off", "-fno-fast-math", "-fwrapv", "-fno-strict-aliasing", "-fno-builtin"]
UNDO = {
    "-ffp-contract=off": ["-ffp-contract=fast", "-ffp-contract=on", "-ffp-contract=fast-honor-pragmas", "-ffast-math", "-Ofast"],
    "-fno-fast-math": ["-ffast-math", "-Ofast", "-funsafe-math-optimizations", "-fassociative-math"],
    "-fwrapv": ["-fno-wrapv", "-ftrapv"],
    "-fno-strict-aliasing": ["-fstrict-aliasing"],
    "-fno-builtin": ["-fbuiltin"],
}


def args_of(entry):
    if "arguments" in entry:
        return entry["arguments"]
    cmd = entry["command"]
    # Windows 版 cmake 產生的指令含反斜線路徑：不當跳脫字元
    return shlex.split(cmd, posix=os.name != "nt" and "\\" not in cmd)


def check(build_dir):
    path = os.path.join(build_dir, "compile_commands.json")
    with open(path, encoding="utf-8") as f:
        entries = json.load(f)
    bad = []
    n = 0
    for e in entries:
        src = e["file"].replace("\\", "/")
        if "/native/" not in src or "/build/" in src:
            continue
        n += 1
        a = args_of(e)
        olevels = [x for x in a if x.startswith("-O")]
        if not olevels or olevels[-1] != "-O2":
            bad.append(f"{src}: 最後的 -O 是 {olevels[-1] if olevels else '（沒有）'}")
        for r in REQUIRED:
            if r not in a:
                bad.append(f"{src}: 缺 {r}")
                continue
            last = max(i for i, x in enumerate(a) if x == r)
            for u in UNDO[r]:
                if u in a[last + 1:]:
                    bad.append(f"{src}: {r} 之後又出現 {u}")
        for x in a:
            if x.startswith("-march=") and ("aarch64" in " ".join(a) or "arm64" in " ".join(a)) and x != "-march=armv8-a":
                bad.append(f"{src}: arm64 的 {x}（只准 armv8-a）")
            if x.startswith("-fopenmp"):
                bad.append(f"{src}: {x}")
    return n, bad


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    total_bad = 0
    for d in sys.argv[1:]:
        n, bad = check(d)
        if n == 0:
            print(f"{d}: compile_commands.json 裡沒有 native/ 的原始檔")
            total_bad += 1
        for b in bad:
            print("  " + b)
        total_bad += len(bad)
        print(f"{d}: {n} 個原始檔，{'全部符合' if not bad else f'{len(bad)} 個問題'}")
    return 1 if total_bad else 0


if __name__ == "__main__":
    sys.exit(main())
