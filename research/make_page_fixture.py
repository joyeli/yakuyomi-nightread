#!/usr/bin/env python3
"""make_page_fixture.py — 產整頁的 Kotlin parity fixture。

把一頁的**管線輸入**（灰階、文字筆畫遮罩、文字區、人物遮罩）與 Python 的**期望輸出**
存成 PNG，給 `nightread` 模組的 JVM 測試比對。灰階存 PNG 而不是沿用原圖 JPEG，是因為
Kotlin 端沒有 cv2，讓兩邊從同一份 8-bit 資料出發才排除掉色彩轉換的 ±1 差異。

用法：NIGHTREAD_CHARMASK=<遮罩夾> python3 make_page_fixture.py [頁名...] -r <結果夾>
"""
import argparse
import glob
import json
import os
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
PAGES = os.path.join(os.path.dirname(HERE), "fixtures", "pages")
DEFAULT_OUT = os.path.join(os.path.dirname(HERE), "nightread", "src", "test", "resources", "page")


def main():
    ap = argparse.ArgumentParser(description="產整頁 parity fixture")
    ap.add_argument("pages", nargs="*", default=["ch34_011"])
    ap.add_argument("-r", "--results", default=os.path.join(HERE, "out", "v11"))
    ap.add_argument("-o", "--outdir", default=DEFAULT_OUT)
    a = ap.parse_args()
    os.makedirs(a.outdir, exist_ok=True)
    cm_dir = os.environ.get("NIGHTREAD_CHARMASK", "")
    if not cm_dir:
        sys.exit("要設 NIGHTREAD_CHARMASK")

    for name in a.pages:
        src = glob.glob(os.path.join(PAGES, name + ".*"))[0]
        bgr = cv2.imread(src, cv2.IMREAD_COLOR)
        g = cv2.imread(src, cv2.IMREAD_GRAYSCALE)
        chroma = (bgr.max(axis=2).astype(np.int16) - bgr.min(axis=2)).clip(0, 255).astype(np.uint8)
        seg = cv2.imread(os.path.join(a.results, f"{name}_seg.png"), cv2.IMREAD_GRAYSCALE)
        cm = cv2.imread(os.path.join(cm_dir, f"{name}_char.png"), cv2.IMREAD_GRAYSCALE)
        if cm.shape != g.shape:
            cm = cv2.resize(cm, (g.shape[1], g.shape[0]), interpolation=cv2.INTER_NEAREST)
        final = cv2.imread(os.path.join(a.results, f"{name}_final.png"), cv2.IMREAD_GRAYSCALE)
        with open(os.path.join(a.results, f"{name}_regions.json"), encoding="utf-8") as f:
            meta = json.load(f)

        o = a.outdir
        cv2.imwrite(os.path.join(o, f"{name}_gray.png"), g)
        cv2.imwrite(os.path.join(o, f"{name}_chroma.png"), chroma)
        cv2.imwrite(os.path.join(o, f"{name}_seg.png"), (seg > 127).astype(np.uint8) * 255)
        cv2.imwrite(os.path.join(o, f"{name}_char.png"), (cm > 127).astype(np.uint8) * 255)
        cv2.imwrite(os.path.join(o, f"{name}_expected.png"), final)
        # 中間遮罩：分歧時用來定位是哪個階段開始不一樣
        for stage in ("gutter", "bubble"):
            m = cv2.imread(os.path.join(a.results, f"{name}_{stage}.png"), cv2.IMREAD_GRAYSCALE)
            if m is not None:
                cv2.imwrite(os.path.join(o, f"{name}_{stage}.png"), (m > 127).astype(np.uint8) * 255)
        with open(os.path.join(o, f"{name}_regions.txt"), "w", encoding="utf-8") as f:
            for r in meta["regions"]:
                x0, y0, x1, y1 = r["bbox"]
                f.write(f"{x0} {y0} {x1} {y1}\n")
        print(f"{name}: {g.shape[1]}×{g.shape[0]}，{len(meta['regions'])} 個文字區")
    print(f"→ {a.outdir}")


if __name__ == "__main__":
    main()
