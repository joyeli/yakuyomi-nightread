#!/usr/bin/env python3
"""make_seal_fixture.py — 漏泡封縫（v3）的 repo 內合成 fixture：`ch34_011_gapA`。

只由 repo 內既有的測試資源（`nightread/src/test/resources/page/ch34_011_*`）合成，不用任何外部頁：
ch34_011 的灰階上，把「ちょっと待ってください」泡（右下，白元件 bbox [602,870,1099,1235]）與右側留白之間最近的那段框線
（(614,1138)–(619,1134)）抹白成 2 px 寬的通道（[GAP] 12 px）。泡的白因此與留白連成同一個大元件 ⇒ 加入前（HEAD）整顆拒收
（L1 泡內紙白 0% 變黑、泡遮罩 202,730 px）；封縫把 ≤ 2 px 的縫封起來，泡自成一塊、走原本的泡路徑（L1 99.98% 變黑）。

輸入給 run_page 的頁＝灰階三通道 PNG（彩度 0；ch34_011 原本的彩度資源也全 0）、seg／文字區／人物遮罩＝ch34_011 的資源檔。
輸出（寫進測試資源夾，Kotlin `BubbleSealParityTest` 比對）：
  ch34_011_gapA_expected.png  成品（預設參數；L1 在這頁與預設逐像素相同）
  ch34_011_gapA_bubble.png    泡遮罩（含封縫救回的泡，人物修剪後；同 run_page 的 _bubble.png）
  ch34_011_gapA_gutter.png    留白遮罩
  ch34_011_gapA_sealed.png    只有封縫才新增的泡（build_bubble_mask 的 local_out["mask"]）

用法：python3 make_seal_fixture.py [-o 輸出夾]（預設＝測試資源夾）
"""
import argparse
import contextlib
import io
import os
import shutil
import sys
import tempfile

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), "nightread", "src", "test", "resources", "page")
PAGE = "ch34_011"
TAG = "gapA"
# 抹白的像素 (x, y)：泡元件與留白最近的兩點 (619,1134)–(614,1138) 連線，沿線每點再加一顆（橫向為主 ⇒ 加下方一顆）＝2 px 寬
GAP = [(614, 1138), (614, 1139), (615, 1137), (615, 1138), (616, 1136), (616, 1137),
       (617, 1136), (617, 1137), (618, 1135), (618, 1136), (619, 1134), (619, 1135)]


def main():
    ap = argparse.ArgumentParser(description="產漏泡封縫的合成 fixture")
    ap.add_argument("-o", "--outdir", default=RES)
    a = ap.parse_args()
    for k in ("NIGHTREAD_STICKER_MODE", "NIGHTREAD_PB", "NIGHTREAD_HM", "NIGHTREAD_BUBBLE_SEAL_R"):
        if k in os.environ:
            sys.exit(f"{k} 有設：fixture 要用預設參數產")
    sys.path.insert(0, HERE)
    import nightread as N

    g = cv2.imread(os.path.join(RES, f"{PAGE}_gray.png"), cv2.IMREAD_GRAYSCALE)
    for x, y in GAP:
        g[y, x] = 255
    seg = cv2.imread(os.path.join(RES, f"{PAGE}_seg.png"), cv2.IMREAD_GRAYSCALE) > 127
    with open(os.path.join(RES, f"{PAGE}_regions.txt"), encoding="utf-8") as f:
        regions = [{"bbox": [int(v) for v in line.split()]} for line in f if line.strip()]

    captured = {}
    orig = N.build_bubble_mask

    def wrapped(*args, **kw):
        r = orig(*args, **kw)
        captured["sealed"] = kw["local_out"]["mask"]
        return r

    N.build_bubble_mask = wrapped
    stem = f"{PAGE}_{TAG}"
    with tempfile.TemporaryDirectory() as tmp:
        page = os.path.join(tmp, f"{stem}.png")
        cv2.imwrite(page, cv2.merge([g, g, g]))
        shutil.copy(os.path.join(RES, f"{PAGE}_char.png"), os.path.join(tmp, f"{stem}_char.png"))
        N.CHARMASK_DIR = tmp
        out = os.path.join(tmp, "out")
        with contextlib.redirect_stdout(io.StringIO()):
            N.run_page(page, outdir=out, regions=regions, seg=seg)
        os.makedirs(a.outdir, exist_ok=True)
        shutil.copy(os.path.join(out, f"{stem}_final.png"), os.path.join(a.outdir, f"{stem}_expected.png"))
        for k in ("bubble", "gutter"):
            m = cv2.imread(os.path.join(out, f"{stem}_{k}.png"), cv2.IMREAD_GRAYSCALE) > 127
            cv2.imwrite(os.path.join(a.outdir, f"{stem}_{k}.png"), m.astype(np.uint8) * 255)
        cv2.imwrite(os.path.join(a.outdir, f"{stem}_sealed.png"), captured["sealed"].astype(np.uint8) * 255)
    print(f"{stem}: 封縫救回 {int(captured['sealed'].sum())} px → {a.outdir}")


if __name__ == "__main__":
    main()
