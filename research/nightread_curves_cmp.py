#!/usr/bin/env python3
"""nightread_curves_cmp.py — 場景曲線 A/B 對照圖：把 nightread_batch 各曲線輸出夾的
<頁>_final.png 併成一張橫向對照（原圖｜d2｜knee｜lin｜lin8），外加逐頁量測表。

先跑：for c in d2 knee lin lin8; NIGHTREAD_CURVE=$c nightread_batch.py -o out/nr_$c
再跑：python3 nightread_curves_cmp.py [-o out/nr_cmp] [頁名...]

量測（只在「畫面區」＝非氣泡非留白，用 d2 輸出夾的遮罩反推）：
  ink_ct   墨↔紙對比：P95(畫面亮度) - P5(畫面亮度)（動態範圍代理）
  mean     畫面區平均亮度（整體暗度）
"""
import argparse
import glob
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

CURVES = ["d2", "knee", "lin", "lin8"]
DEFAULT_PAGES = ["demo01", "demo02", "demo03", "demo04", "demo05", "demo06",
                 "ch34_006", "ch34_010", "ch34_011", "ch34_014", "ch34_015"]
COL_W = 760          # 每欄寬（5 欄 ≈ 3800px，看得清網點又不至於巨檔）
LABEL_H = 46


def label_bar(w, text):
    bar = np.full((LABEL_H, w, 3), 24, np.uint8)
    cv2.putText(bar, text, (12, LABEL_H - 14), cv2.FONT_HERSHEY_SIMPLEX,
                1.0, (230, 230, 230), 2, cv2.LINE_AA)
    return bar


def col(img, text):
    h, w = img.shape[:2]
    s = COL_W / w
    img = cv2.resize(img, (COL_W, int(h * s)), interpolation=cv2.INTER_AREA)
    if img.ndim == 2:
        img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)
    return np.vstack([label_bar(COL_W, text), img])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pages", nargs="*", default=DEFAULT_PAGES)
    ap.add_argument("-o", "--outdir", default=os.path.join(paths.OUT, "nr_cmp"))
    a = ap.parse_args()
    os.makedirs(a.outdir, exist_ok=True)

    print(f"{'page':10s} {'curve':5s} {'mean':>6s} {'ink_ct':>7s}")
    for name in a.pages:
        src = sorted(glob.glob(os.path.join(paths.SANDBOX_TEST, name + ".*")))
        if not src:
            print(f"{name}: 找不到原圖，跳過"); continue
        orig = cv2.imread(src[0])
        # 畫面區遮罩＝非氣泡非留白（拿 d2 夾的遮罩；曲線不影響遮罩、四夾相同）
        mdir = os.path.join(paths.OUT, "nr_d2")
        bub = cv2.imread(os.path.join(mdir, f"{name}_bubble.png"), 0)
        gut = cv2.imread(os.path.join(mdir, f"{name}_gutter.png"), 0)
        scene = None
        if bub is not None and gut is not None:
            scene = (bub == 0) & (gut == 0)
        cols = [col(orig, f"{name} original")]
        for c in CURVES:
            fp = os.path.join(paths.OUT, f"nr_{c}", f"{name}_final.png")
            im = cv2.imread(fp)
            if im is None:
                print(f"{name}: 缺 {fp}，跳過該欄"); continue
            g = cv2.cvtColor(im, cv2.COLOR_BGR2GRAY)
            if scene is not None and scene.shape == g.shape:
                v = g[scene]
                mean, ct = float(v.mean()), float(np.percentile(v, 95) - np.percentile(v, 5))
            else:
                mean, ct = float(g.mean()), float(np.percentile(g, 95) - np.percentile(g, 5))
            print(f"{name:10s} {c:5s} {mean:6.1f} {ct:7.1f}")
            cols.append(col(im, f"{c}  mean={mean:.0f} ct={ct:.0f}"))
        h = max(x.shape[0] for x in cols)
        cols = [cv2.copyMakeBorder(x, 0, h - x.shape[0], 0, 4, cv2.BORDER_CONSTANT,
                                   value=(24, 24, 24)) for x in cols]
        out = np.hstack(cols)
        fp = os.path.join(a.outdir, f"{name}_curves.png")
        cv2.imwrite(fp, out, [cv2.IMWRITE_PNG_COMPRESSION, 6])
        print(f"  → {fp}")


if __name__ == "__main__":
    main()
