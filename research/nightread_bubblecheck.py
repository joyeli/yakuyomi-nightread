#!/usr/bin/env python3
"""nightread_bubblecheck.py — 「白泡」量測：成品裡字附近仍是白底的面積。

  IN  ＝在氣泡遮罩內、非筆畫、原白、成品仍亮  → 偵測到卻沒填（機制 bug）
  OUT ＝不在氣泡遮罩、字筆畫外擴 20px 內、非筆畫、原白、成品仍亮 → 沒被當成泡（分類漏）
兩者皆以 ≥1500px 的連通塊計（小碎塊是行間白/描邊縫，不算）。
用法：python3 nightread_bubblecheck.py out/nr_xxx [out/nr_yyy ...]
"""
import glob, os, sys
import cv2, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa

PAGES = ["ch34_006", "ch34_010", "ch34_011", "ch34_014", "ch34_015",
         "demo01", "demo02", "demo03", "demo04", "demo05", "demo06"]
K3 = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))
K20 = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (41, 41))


def measure(d, verbose=False):
    tin = tout = 0
    blobs = []
    for n in PAGES:
        b = cv2.imread(f"{d}/{n}_bubble.png", 0) > 0
        f = cv2.imread(f"{d}/{n}_final.png", 0)
        g = cv2.imread(sorted(glob.glob(os.path.join(paths.SANDBOX_TEST, n + ".*")))[0], 0)
        seg = cv2.imread(f"{d}/{n}_seg.png", 0) > 0
        segd = cv2.dilate(seg.astype(np.uint8), K3) > 0
        near = cv2.dilate(seg.astype(np.uint8), K20) > 0
        white = g >= 235
        for tag, m in (("IN", b & ~segd & white & (f >= 110)),
                       ("OUT", near & ~b & ~segd & white & (f >= 110))):
            nn, lab, st, _ = cv2.connectedComponentsWithStats(m.astype(np.uint8), 8)
            for i in range(1, nn):
                a = int(st[i, cv2.CC_STAT_AREA])
                if a >= 1500:
                    blobs.append((a, n, tag, tuple(int(v) for v in st[i, :4])))
                    if tag == "IN": tin += a
                    else: tout += a
    if verbose:
        for a, n, tag, (x, y, w, h) in sorted(blobs, reverse=True)[:12]:
            print(f"    {a:7d} {n:10s} {tag:4s} x{x} y{y} {w}x{h}")
    return tin, tout, blobs


if __name__ == "__main__":
    for d in sys.argv[1:]:
        tin, tout, blobs = measure(d)
        print(f"== {d}: 白泡 IN {tin} px / OUT {tout} px（塊數 {len(blobs)}）==")
