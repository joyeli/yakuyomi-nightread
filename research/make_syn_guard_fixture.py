#!/usr/bin/env python3
"""make_syn_guard_fixture.py — 規則版本守門用的合成頁 syn_more（`RulesVersionGuardTest`，2026-10-04 複核 4）。

公開 fixture 的 11 頁裡，「更多」的閃光（裁定 2 C）、人物旁淡線的外圈（複核 1）、字畫亮只限有字框的字塊（複核 2）都動不到
任何一頁（把它們關掉，五頁加 ch34_015 的摘要照樣不變），規則改了也不會被抓到。這頁是畫出來的（不含任何真實作品），
一頁同時用到三條：
  - 淺色漸層天空（格內，σ5 亮度過門）上 8 顆四芒星（孤立亮記號＝閃光：關掉 C 就當物件證據）；
  - 「人物」＝橢圓身體＋圓頭（人物遮罩＝兩者填滿，輪廓墨線），身體右上伸出一隻點狀淡線畫的手（在人物遮罩外：關掉外圈就塗黑）；
  - 天空裡一團深色樹叢，字遮罩有、字框沒有（DBNet 誤報：關掉字框條件就反相畫亮）。
輸出：
  nightread/src/test/resources/page/syn_more_{gray,chroma,seg,char}.png、syn_more_regions.txt（空：沒有字框）
  research/out/syn_guard/（研究端跑 run_page 用：syn_more.png、syn_more_char.png、syn_more.npz、syn_more.json、jobs.json）
用法：python3 make_syn_guard_fixture.py
"""
import json
import math
import os

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), "nightread", "src", "test", "resources", "page")
OUT = os.path.join(HERE, "out", "syn_guard")
PNG9 = [cv2.IMWRITE_PNG_COMPRESSION, 9]
W, H = 960, 1280


def star(img, x, y, r, v=255):
    pts = []
    for k in range(8):
        a = k * math.pi / 4
        rr = r if k % 2 == 0 else r * 0.18
        pts.append([x + rr * math.cos(a), y + rr * math.sin(a)])
    cv2.fillPoly(img, [np.round(np.array(pts) * 16).astype(np.int32)], int(v), lineType=cv2.LINE_AA, shift=4)


def page():
    g = np.full((H, W), 255, np.uint8)
    # 格內：上 200 → 下 240 的直向漸層
    x0, y0, x1, y1 = 24, 24, W - 25, H - 25
    ramp = np.linspace(200.0, 240.0, y1 - y0 + 1)[:, None]
    g[y0:y1 + 1, x0:x1 + 1] = np.repeat(np.round(ramp), x1 - x0 + 1, 1).astype(np.uint8)
    # 閃光：天空上半
    for (sx, sy, sr) in ((180, 150, 16), (330, 260, 11), (520, 120, 14), (760, 210, 18), (150, 420, 12),
                         (430, 470, 15), (820, 520, 10), (640, 300, 13)):
        star(g, sx, sy, sr)
    # 人物：身體橢圓＋頭（遮罩＝填滿，輪廓墨線 3 px）
    cm = np.zeros((H, W), np.uint8)
    cv2.ellipse(cm, (300, 1030), (150, 210), 0, 0, 360, 255, -1)
    cv2.circle(cm, (300, 760), 80, 255, -1)
    cv2.ellipse(g, (300, 1030), (150, 210), 0, 0, 360, 30, 3, lineType=cv2.LINE_AA)
    cv2.circle(g, (300, 760), 80, 30, 3, lineType=cv2.LINE_AA)
    # 點狀淡線的手：身體右上緣往外伸的細長環（人物遮罩外），點半徑 1.3、間距 4 px、灰階 110
    cx, cy, ax, ay, rot = 470, 820, 16, 48, math.radians(35)
    per = 2 * math.pi * math.sqrt((ax * ax + ay * ay) / 2)
    n = int(per // 4)
    for k in range(n):
        t = 2 * math.pi * k / n
        px, py = ax * math.cos(t), ay * math.sin(t)
        qx = cx + px * math.cos(rot) - py * math.sin(rot)
        qy = cy + px * math.sin(rot) + py * math.cos(rot)
        cv2.circle(g, (int(round(qx * 16)), int(round(qy * 16))), int(round(1.3 * 16)), 110, -1, lineType=cv2.LINE_AA, shift=4)
    # 手腕：兩條點線接回身體
    for (ax0, ay0, ax1, ay1) in ((432, 858, 418, 892), (455, 868, 441, 902)):
        m = int(math.hypot(ax1 - ax0, ay1 - ay0) // 4) + 1
        for k in range(m + 1):
            qx = ax0 + (ax1 - ax0) * k / m
            qy = ay0 + (ay1 - ay0) * k / m
            cv2.circle(g, (int(round(qx * 16)), int(round(qy * 16))), int(round(1.3 * 16)), 110, -1, lineType=cv2.LINE_AA, shift=4)
    # 樹叢：一團深色圓（固定座標，不用亂數）；字遮罩＝樹叢外擴 2，沒有字框
    tree = np.zeros((H, W), np.uint8)
    for (tx, ty, tr) in ((640, 640, 9), (652, 628, 7), (628, 650, 8), (660, 652, 6), (645, 662, 7), (620, 632, 5),
                         (668, 638, 5), (636, 618, 6), (656, 672, 5), (612, 660, 4), (674, 664, 4), (646, 646, 9)):
        cv2.circle(tree, (tx, ty), tr, 255, -1, lineType=cv2.LINE_AA)
    g = np.where(tree > 127, np.minimum(g, 40), g).astype(np.uint8)
    seg = cv2.dilate((tree > 127).astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))) > 0
    # 格框線
    cv2.rectangle(g, (x0, y0), (x1, y1), 0, 3)
    return g, cm > 127, seg


def main():
    g, cm, seg = page()
    os.makedirs(RES, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    cv2.imwrite(os.path.join(RES, "syn_more_gray.png"), g, PNG9)
    cv2.imwrite(os.path.join(RES, "syn_more_chroma.png"), np.zeros_like(g), PNG9)
    cv2.imwrite(os.path.join(RES, "syn_more_seg.png"), seg.astype(np.uint8) * 255, PNG9)
    cv2.imwrite(os.path.join(RES, "syn_more_char.png"), cm.astype(np.uint8) * 255, PNG9)
    with open(os.path.join(RES, "syn_more_regions.txt"), "w") as f:
        f.write("")
    p = os.path.join(OUT, "syn_more.png")
    cv2.imwrite(p, g, PNG9)
    cv2.imwrite(os.path.join(OUT, "syn_more_char.png"), cm.astype(np.uint8) * 255, PNG9)
    np.savez_compressed(os.path.join(OUT, "syn_more.npz"), seg=seg)
    json.dump([], open(os.path.join(OUT, "syn_more.json"), "w"))
    json.dump([dict(name="syn_more", page=p, char_dir=OUT, det_json=os.path.join(OUT, "syn_more.json"),
                    det_npz=os.path.join(OUT, "syn_more.npz"), group="syn")], open(os.path.join(OUT, "jobs.json"), "w"), indent=1)
    print("syn_more", g.shape, "人物", int(cm.sum()), "字遮罩", int(seg.sum()))


if __name__ == "__main__":
    main()
