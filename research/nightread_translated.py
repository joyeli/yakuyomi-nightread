#!/usr/bin/env python3
"""nightread_translated.py — 譯文頁的夜讀：驗證「共用翻譯素材」能省掉多少、代價是什麼。

產品路徑是這樣的：頁面先翻譯（偵測 → OCR → 翻譯 → 去字 → 排版），成品頁貼著譯文；
夜讀接在後面，對**成品頁**重建。前段的東西能不能借過來用，決定夜讀要不要再跑一次偵測：

  A 全部重測   對成品頁重跑 DBNet，拿它的文字區與筆畫遮罩（基準）
  B 借文字區   文字區從翻譯素材來，筆畫遮罩仍由 DBNet 測
  C 全部借用   文字區從素材來，筆畫遮罩用「排版器知道自己畫在哪」的精確版 → 完全不跑 DBNet

C 是理想形狀（省掉整個偵測），但精確遮罩與偵測遮罩的行為不同：DBNet 的 seg 是文字**區域**，
裡面有六成是字周圍的泡白；精確遮罩只有筆畫。夜讀靠低墨度壓黑處理前者，換成後者會怎樣，
只有量了才知道。

排版器的輸出這裡用「文字區內夠黑的像素」模擬——譯文是純色黑字，這個近似很接近真實。

用法：NIGHTREAD_CHARMASK=<遮罩夾> python3 nightread_translated.py <譯文頁> [-o 輸出夾]
"""
import argparse
import json
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nightread as N  # noqa: E402

TEXT_INK_TH = 128       # 「這是譯文筆畫」的灰階上限（譯文是純色黑字，不必抓得很寬）


def precise_text_mask(g, regions, th=TEXT_INK_TH):
    """模擬排版器輸出的譯文筆畫遮罩：文字區 bbox 內夠黑的像素。

    限制在 bbox 內是必要的——不限制就會把泡框、線稿全抓進來。
    """
    m = np.zeros(g.shape, bool)
    for r in regions:
        x0, y0, x1, y1 = r["bbox"]
        x0, y0 = max(0, x0), max(0, y0)
        x1, y1 = min(g.shape[1], x1), min(g.shape[0], y1)
        if x1 <= x0 or y1 <= y0:
            continue
        m[y0:y1, x0:x1] |= g[y0:y1, x0:x1] < th
    return m


def readability(final_path, page_path, seg, bubble_path):
    """泡內字的可讀性：只看**真正的筆畫**（原圖夠黑），不然會被 seg 裡的泡白稀釋。"""
    f = cv2.imread(final_path, cv2.IMREAD_GRAYSCALE)
    o = cv2.imread(page_path, cv2.IMREAD_GRAYSCALE)
    bub = cv2.imread(bubble_path, cv2.IMREAD_GRAYSCALE) > 127
    ink = seg & bub & (o < 100)
    ground = bub & ~seg
    if ink.sum() < 100 or ground.sum() < 100:
        return None
    return {
        "ink": float(f[ink].mean()),
        "ground": float(f[ground].mean()),
        "contrast": float(f[ink].mean() - f[ground].mean()),
        "inkPx": int(ink.sum()),
    }


def main():
    ap = argparse.ArgumentParser(description="譯文頁的夜讀：素材共用 A/B/C")
    ap.add_argument("page", help="翻譯成品頁")
    ap.add_argument("-o", "--outdir", default=os.path.join(N.OUT_DEFAULT, "translated"))
    a = ap.parse_args()
    os.makedirs(a.outdir, exist_ok=True)
    name = os.path.splitext(os.path.basename(a.page))[0]

    img = cv2.imread(a.page)
    g = cv2.imread(a.page, cv2.IMREAD_GRAYSCALE)

    # 先測一次，當作「翻譯素材裡存的文字區」——產品上這份是翻譯階段就算好的，夜讀不必再測
    print("測一次取得文字區（模擬翻譯素材）…", flush=True)
    _, regions, det_seg = N.detect(img)
    print(f"  {len(regions)} 個文字區")

    variants = {
        "A_detect_all": (None, None),
        "B_share_regions": (regions, None),
        "C_share_all": (regions, precise_text_mask(g, regions)),
    }
    rows = []
    for tag, (rg, sg) in variants.items():
        out = os.path.join(a.outdir, tag)
        print(f"\n── {tag}", flush=True)
        N.run_page(a.page, outdir=out, regions=rg, seg=sg)
        used_seg = sg if sg is not None else det_seg
        r = readability(
            os.path.join(out, f"{name}_final.png"), a.page, used_seg,
            os.path.join(out, f"{name}_bubble.png"),
        )
        f = cv2.imread(os.path.join(out, f"{name}_final.png"), cv2.IMREAD_GRAYSCALE)
        rows.append({
            "variant": tag,
            "segPx": int(used_seg.sum()),
            "bright": round(float((f >= 110).mean() * 100), 2),
            **({k: round(v, 1) for k, v in r.items()} if r else {}),
        })

    print(f"\n{'variant':18s} {'seg px':>9s} {'亮區':>7s} {'字':>7s} {'泡底':>7s} {'對比':>7s}")
    for r in rows:
        print(f"{r['variant']:18s} {r['segPx']:9d} {r['bright']:6.2f}% "
              f"{r.get('ink', 0):7.1f} {r.get('ground', 0):7.1f} {r.get('contrast', 0):+7.1f}")
    with open(os.path.join(a.outdir, "compare.json"), "w", encoding="utf-8") as fp:
        json.dump(rows, fp, ensure_ascii=False, indent=2)
    print(f"\n→ {a.outdir}")


if __name__ == "__main__":
    main()
