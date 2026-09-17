#!/usr/bin/env python3
"""make_showcase.py — 夜讀成果展示圖（5 階段）。

用途有二：**檢驗成果**（一眼看出哪頁哪個階段出問題）與 **GitHub 效果展示**。

五個階段的敘事刻意這樣排：
  1. 原圖
  2. 管線看到什麼          ← 人物／氣泡／文字，說明這不是全域濾鏡
  3. 硬反相                ← **對照組一**：業界另一條路線。紙白參與構圖（臉的亮部、留白都用
                             紙白畫）⇒ 反相後臉變黑、頭髮變白，直接毀畫面。這是本專案的數學結論。
  4. 只套亮度曲線           ← **對照組二**：濾鏡能做到的極限（整頁灰濛濛，泡仍刺眼）
  5. 還剩什麼沒處理         ← 診斷：紅＝原圖是白但成品還亮著
  6. 夜讀成品              ← 收尾在成果
第 3、4 格是整組圖的關鍵：把「為什麼現有做法都不夠」變成不用解釋的事。
第 5 格放在成品前面，讓讀者先知道「要處理的是什麼」再看結果。

用法：
  python3 make_showcase.py              # 全 fixture 頁 → out/showcase/<page>_{A,B}.png
  python3 make_showcase.py ch34_010 -o out/x --layout A
"""
import argparse
import glob
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402
import nightread as nr  # noqa: E402

PAGES = ["ch34_006", "ch34_010", "ch34_011", "ch34_014", "ch34_015",
         "demo01", "demo02", "demo03", "demo04", "demo05", "demo06"]
BGC = (26, 26, 26)
SPEC = [("orig",   "1. Original",              "the source page"),
        ("masks",  "2. What the pipeline sees", "characters / bubbles / text"),
        ("invert", "3. Hard invert",           "faces go black, hair goes white"),
        ("curve",  "4. Tone curve only",       "the best a filter can do"),
        ("resid",  "5. What is left bright",   "red = white the rebuild must still handle"),
        ("final",  "6. Night reading",         "region-aware rebuild")]


def build_steps(page, result_dir):
    """產五張階段圖（BGR）。result_dir 需含 <page>_final.png 與 _bubble.png。"""
    p = sorted(glob.glob(os.path.join(paths.SANDBOX_TEST, page + ".*")))[0]
    img = cv2.imread(p)
    g, _ = nr.normalize_paper(cv2.imread(p, 0), img)
    _, regions, seg = nr.detect(img)
    cm = nr.load_charmask(p, g.shape)
    if cm is not None:
        cm = nr.trim_charmask(cm, g)
        if nr.CHAR_SNAP > 0:
            cm = nr.snap_charmask(cm, g)
        if nr.MASK_SMOOTH > 0:
            cm = nr.smooth_charmask(cm, g)
    else:
        cm = np.zeros(g.shape, bool)
    bub = cv2.imread(os.path.join(result_dir, f"{page}_bubble.png"), 0) > 0
    fin = cv2.imread(os.path.join(result_dir, f"{page}_final.png"))
    fing = cv2.imread(os.path.join(result_dir, f"{page}_final.png"), 0)

    # 2. 遮罩：原圖淡化當底（否則標示看不清），人物橘、氣泡藍、文字紅，加輪廓線
    def outline(m, w=3):
        return cv2.morphologyEx(m.astype(np.uint8), cv2.MORPH_GRADIENT, np.ones((w, w), np.uint8)) > 0
    masks = (img * 0.55 + 255 * 0.45).astype(np.uint8)
    masks[bub] = (masks[bub] * 0.55 + np.array([80, 190, 255]) * 0.45).astype(np.uint8)
    masks[cm] = (masks[cm] * 0.70 + np.array([255, 150, 80]) * 0.30).astype(np.uint8)
    masks[outline(cm)] = (255, 130, 50)
    masks[outline(bub)] = (40, 170, 255)
    masks[seg] = (50, 50, 230)

    # 3. 硬反相：業界另一條路線，用來展示它為什麼行不通
    invert = cv2.cvtColor(255 - g, cv2.COLOR_GRAY2BGR)
    # 4. 只套 lin8 曲線＝濾鏡極限（不做任何分區重繪）
    curve = cv2.cvtColor(np.clip(8 + g.astype(np.float32) * 132 / 255, 0, 255).astype(np.uint8),
                         cv2.COLOR_GRAY2BGR)
    # 4. 殘白：原圖是白、成品仍亮
    resid = (g >= nr.WHITE_TH) & (fing >= 110)
    rv = img.copy()
    rv[resid] = (rv[resid] * 0.35 + np.array([70, 70, 255]) * 0.65).astype(np.uint8)
    return ({"orig": img, "masks": masks, "invert": invert, "curve": curve,
             "resid": rv, "final": fin}, resid.mean() * 100)


def _panel(img, title, sub, W):
    h, w = img.shape[:2]
    im2 = cv2.resize(img, (W, int(h * W / w)), interpolation=cv2.INTER_AREA)
    bar = np.full((48, W, 3), BGC, np.uint8)
    cv2.putText(bar, title, (12, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.58, (245, 245, 245), 1, cv2.LINE_AA)
    cv2.putText(bar, sub, (12, 39), cv2.FONT_HERSHEY_SIMPLEX, 0.43, (145, 145, 145), 1, cv2.LINE_AA)
    return np.vstack([bar, im2])


def compose(im, layout="A"):
    """A＝5 格橫排（README 橫幅）；B＝上排 2 大格＋下排 3 格（細節看得清）。"""
    if layout == "A":
        ps = [_panel(im[k], t, s, 320) for k, t, s in SPEC]
        h = max(x.shape[0] for x in ps)
        ps = [cv2.copyMakeBorder(x, 0, h - x.shape[0], 0, 0, cv2.BORDER_CONSTANT, value=BGC) for x in ps]
        gap = np.full((h, 10, 3), BGC, np.uint8)
        out = np.hstack([q for i, x in enumerate(ps) for q in ([x] if i == 0 else [gap, x])])
    else:
        # B：上下各三格。上排＝輸入與管線理解，下排＝兩個對照組與成果（含診斷）。
        rows = []
        for grp in (SPEC[:3], SPEC[3:]):
            ps = [_panel(im[k], t, s, 470) for k, t, s in grp]
            h = max(x.shape[0] for x in ps)
            ps = [cv2.copyMakeBorder(x, 0, h - x.shape[0], 0, 0, cv2.BORDER_CONSTANT, value=BGC) for x in ps]
            gp = np.full((h, 10, 3), BGC, np.uint8)
            rows.append(np.hstack([ps[0], gp, ps[1], gp, ps[2]]))
        W = max(r.shape[1] for r in rows)
        rows = [cv2.copyMakeBorder(r, 0, 0, 0, W - r.shape[1], cv2.BORDER_CONSTANT, value=BGC) for r in rows]
        out = np.vstack([rows[0], np.full((10, W, 3), BGC, np.uint8), rows[1]])
    return cv2.copyMakeBorder(out, 10, 10, 10, 10, cv2.BORDER_CONSTANT, value=BGC)


def main():
    ap = argparse.ArgumentParser(description="夜讀成果展示圖")
    ap.add_argument("pages", nargs="*", default=PAGES)
    ap.add_argument("-o", "--outdir", default=os.path.join(paths.OUT, "showcase"))
    ap.add_argument("-r", "--result", default=os.path.join(paths.OUT, "v8"))
    ap.add_argument("--layout", choices=["A", "B", "both"], default="both")
    a = ap.parse_args()
    os.makedirs(a.outdir, exist_ok=True)
    for page in a.pages:
        im, resid = build_steps(page, a.result)
        for lay in (["A", "B"] if a.layout == "both" else [a.layout]):
            fp = os.path.join(a.outdir, f"{page}_{lay}.png")
            cv2.imwrite(fp, compose(im, lay), [cv2.IMWRITE_PNG_COMPRESSION, 6])
        print(f"{page:10s} 殘白 {resid:5.1f}%")
    print(f"→ {a.outdir}")


if __name__ == "__main__":
    main()
