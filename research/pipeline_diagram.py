#!/usr/bin/env python3
"""pipeline_diagram.py — 管線視覺化：一頁在每個階段看到什麼。

產 docs/img/pipeline.webp：原圖 ／ 文字筆畫 ／ 人物遮罩 ／ 留白 ／ 氣泡 ／ 成品，
六格橫排，每格標題說明那一步在判斷什麼。與 make_showcase.py 不同——那張是給讀者看
「效果」，這張是給實作者看「管線內部」。

用法：NIGHTREAD_CHARMASK=<遮罩夾> python3 pipeline_diagram.py [頁名] [-r 結果夾]
"""
import argparse
import glob
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

PAGES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "fixtures", "pages")
DOCS_IMG = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs", "img")

TINT_TEXT = (90, 90, 245)     # BGR：文字筆畫＝紅
TINT_CHAR = (235, 160, 70)    # 人物＝藍
TINT_GUTTER = (110, 200, 110)  # 留白＝綠
TINT_BUBBLE = (70, 190, 245)  # 氣泡＝橙

SPEC = [
    ("orig",   "1. Source page",      "white paper, black ink"),
    ("text",   "2. Text strokes",     "DBNet per-pixel mask"),
    ("char",   "3. Characters",       "the never-paint region"),
    ("gutter", "4. Gutter",           "margins between panels"),
    ("bubble", "5. Bubbles",          "containers that hold text"),
    ("final",  "6. Night reading",    "each region rebuilt"),
]


def tint(base_bgr, mask, colour, alpha=0.62):
    out = base_bgr.copy().astype(np.float32)
    c = np.array(colour, np.float32)
    out[mask] = out[mask] * (1.0 - alpha) + c * alpha
    return out.astype(np.uint8)


def panel(img, label, sub, width, font=cv2.FONT_HERSHEY_SIMPLEX):
    s = width / img.shape[1]
    im = cv2.resize(img, (width, int(round(img.shape[0] * s))), interpolation=cv2.INTER_AREA)
    bar = np.full((62, width, 3), 24, np.uint8)
    cv2.putText(bar, label, (14, 26), font, 0.62, (255, 255, 255), 1, cv2.LINE_AA)
    cv2.putText(bar, sub, (14, 48), font, 0.46, (165, 165, 165), 1, cv2.LINE_AA)
    return np.vstack([bar, im])


def build(name, results):
    src = glob.glob(os.path.join(PAGES, name + ".*"))[0]
    orig = cv2.imread(src)
    grey = cv2.cvtColor(orig, cv2.COLOR_BGR2GRAY)
    pale = cv2.cvtColor((grey.astype(np.float32) * 0.45 + 140).clip(0, 255).astype(np.uint8),
                        cv2.COLOR_GRAY2BGR)

    def load(suffix):
        m = cv2.imread(os.path.join(results, f"{name}_{suffix}.png"), cv2.IMREAD_GRAYSCALE)
        return None if m is None else m > 127

    cm_dir = os.environ.get("NIGHTREAD_CHARMASK", "")
    cm = cv2.imread(os.path.join(cm_dir, f"{name}_char.png"), cv2.IMREAD_GRAYSCALE) if cm_dir else None
    if cm is not None and cm.shape != grey.shape:
        cm = cv2.resize(cm, (grey.shape[1], grey.shape[0]), interpolation=cv2.INTER_NEAREST)

    views = {
        "orig": orig,
        "text": tint(pale, load("seg"), TINT_TEXT),
        "char": tint(pale, cm > 127, TINT_CHAR) if cm is not None else pale,
        "gutter": tint(pale, load("gutter"), TINT_GUTTER),
        "bubble": tint(pale, load("bubble"), TINT_BUBBLE),
        "final": cv2.imread(os.path.join(results, f"{name}_final.png")),
    }
    w = 430
    cols = [panel(views[k], lab, sub, w) for k, lab, sub in SPEC]
    h = max(c.shape[0] for c in cols)
    cols = [cv2.copyMakeBorder(c, 0, h - c.shape[0], 0, 0, cv2.BORDER_CONSTANT, value=(24, 24, 24))
            for c in cols]
    sep = np.full((h, 8, 3), 24, np.uint8)
    row = cols[0]
    for c in cols[1:]:
        row = np.hstack([row, sep, c])
    return cv2.copyMakeBorder(row, 10, 10, 10, 10, cv2.BORDER_CONSTANT, value=(24, 24, 24))


def main():
    ap = argparse.ArgumentParser(description="管線視覺化圖")
    ap.add_argument("page", nargs="?", default="ch34_011")
    ap.add_argument("-r", "--results", default=os.path.join(paths.OUT, "nightread", "v11"))
    ap.add_argument("-o", "--out", default=os.path.join(DOCS_IMG, "pipeline.webp"))
    a = ap.parse_args()
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    img = build(a.page, a.results)
    cv2.imwrite(a.out, img, [cv2.IMWRITE_WEBP_QUALITY, 92])
    print(f"→ {a.out}  {img.shape[1]}×{img.shape[0]}")


if __name__ == "__main__":
    main()
