#!/usr/bin/env python3
"""make_more_fixture.py — 「更多」新規則 A2 的逐元件 parity fixture（給 Kotlin `MoreRuleParityTest`）。

讀 nightread.py 以「更多」設定（L3 的環境變數 ＋ NIGHTREAD_MORE=1）跑出的 `<頁>_regions.json`，把貼紙審計裡
  - 安全網每個候選實際沒過的門（`gates`）→ `G x0 y0 x1 y1 門,門,…`（沒有＝`-`）
  - A2 的每個候選判定（`more`）→ `M x0 y0 x1 y1 src skip soft ok why rfs rfi fnc charf lOut lIn area`（量了特徵的；
    skip＝`-`，why 空＝`-`）或 `M x0 y0 x1 y1 src skip`（hug／gates 被跳過的）
寫成 `<頁>_more.txt` 到測試資源（元件以 bbox 表示：cv2 與 Kotlin 的元件標號在少數頁差 1，bbox 才穩）。
charf 用 repr（17 位，逐位元往返）；rfs／rfi／fnc 是研究端已四捨五入的值。

用法：python3 make_more_fixture.py <結果夾（含 <頁>_regions.json）> 頁名... [-o 輸出夾]
"""
import argparse
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.join(os.path.dirname(HERE), "nightread", "src", "test", "resources", "page")


def main():
    ap = argparse.ArgumentParser(description="產「更多」A2 的逐元件 parity fixture")
    ap.add_argument("results")
    ap.add_argument("pages", nargs="+")
    ap.add_argument("-o", "--outdir", default=DEFAULT_OUT)
    a = ap.parse_args()
    for name in a.pages:
        meta = json.load(open(os.path.join(a.results, f"{name}_regions.json"), encoding="utf-8"))
        lines = []
        for m in sorted(meta["sticker"], key=lambda m: m["bbox"]):
            b = " ".join(str(int(v)) for v in m["bbox"])
            if not m.get("gut"):
                lines.append(f"G {b} {','.join(m['gates']) or '-'}")
            d = m.get("more")
            if d is None:
                continue
            if "skip" in d:
                lines.append(f"M {b} {d['src']} {d['skip']}")
            else:
                lines.append(f"M {b} {d['src']} - {int(d['soft'])} {int(d['ok'])} {d['why'] or '-'} "
                             f"{d['rfs']!r} {d['rfi']!r} {d['fnc']!r} {d['charf']!r} {d['lOut']} {d['lIn']} {d['area']}")
        out = os.path.join(a.outdir, f"{name}_more.txt")
        with open(out, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        print(out, len(lines))


if __name__ == "__main__":
    main()
