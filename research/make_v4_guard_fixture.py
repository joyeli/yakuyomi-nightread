#!/usr/bin/env python3
"""make_v4_guard_fixture.py — 規則版本 4 守門用的合成頁 syn_v4（`RulesVersionGuardTest`，2026-10-05）。

公開 fixture 的 11 頁動不到規則版本 4 的四條（灰虛線補黑、沒有字框的手寫字畫亮、淡線外圈貼線形、譯後頁去字區旁的小塊不塗），
syn_more 只動得到前一條半（補黑 49 px、外圈 1,337 px），版本守門抓不到其他條的改動。這頁是畫出來的（不含任何真實作品），
一頁同時用到四條，外加兩個修法（淡小記號留灰、只清新畫亮的字旁的描亮邊）：
  - 大格：淺灰底（228）上一個較暗的圓形光影（202，加 ±1 固定亂數紋後仍 ≥ 200；交界被 σ4 Canny 當成調子邊 ⇒ 塗黑區在交界留一圈灰虛線：縫補黑）；
  - 大格裡一組粗黑手寫數字「44」（字遮罩有、字框沒有：像粗墨筆畫 ⇒ 畫亮）；旁邊另一組有字框的字（版本 3 本來就畫亮，
    它旁邊的描亮邊照版本 3 不動）；
  - 「人物」＝橢圓（人物遮罩＝填滿，輪廓墨線），右緣伸出一條淡的單線（一條線：外圈只留 3 px），線旁一顆淡小點（P 類記號：
    留 3 px）；
  - 小格（< 1.5% 頁面、比大格暗一階）：左半是去字遮罩（syn_v4_inpaint.png；譯後頁的 `.yakuyomi/<頁>.mask.png`）⇒ 這一小塊不塗。
輸出：
  nightread/src/test/resources/page/syn_v4_{gray,chroma,seg,char,inpaint}.png、syn_v4_regions.txt
  research/out/syn_guard/（研究端跑 run_page 用：syn_v4.png、syn_v4_char.png、syn_v4.npz、syn_v4.json、syn_v4_inpaint.png、jobs_v4.json）
用法：python3 make_v4_guard_fixture.py
"""
import json
import os

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), "nightread", "src", "test", "resources", "page")
OUT = os.path.join(HERE, "out", "syn_guard")
PNG9 = [cv2.IMWRITE_PNG_COMPRESSION, 9]
W, H = 960, 1280


def page():
    g = np.full((H, W), 255, np.uint8)
    cm = np.zeros((H, W), np.uint8)
    seg = np.zeros((H, W), np.uint8)
    inp = np.zeros((H, W), np.uint8)
    regions = []
    # 大格
    ax0, ay0, ax1, ay1 = 24, 24, W - 25, 900
    g[ay0:ay1 + 1, ax0:ax1 + 1] = 228
    # 圓形光影（較暗的一階）
    cv2.circle(g, (300, 300), 90, 202, -1, lineType=cv2.LINE_AA)
    # 沒有字框的粗黑手寫數字
    txt = np.zeros((H, W), np.uint8)
    cv2.putText(txt, "44", (560, 330), cv2.FONT_HERSHEY_SIMPLEX, 3.2, 255, 13, lineType=cv2.LINE_AA)
    g = np.where(txt > 0, np.minimum(g, (228 - (txt.astype(np.int32) * 216) // 255)).astype(np.uint8), g)
    seg |= (cv2.dilate((txt > 127).astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))) > 0).astype(np.uint8)
    # 有字框的字（版本 3 本來就畫亮）
    t2 = np.zeros((H, W), np.uint8)
    cv2.putText(t2, "OK", (600, 520), cv2.FONT_HERSHEY_SIMPLEX, 2.4, 255, 9, lineType=cv2.LINE_AA)
    g = np.where(t2 > 0, np.minimum(g, (228 - (t2.astype(np.int32) * 216) // 255)).astype(np.uint8), g)
    # 字遮罩比墨細一圈（DBNet 的字區不一定蓋滿墨）：墨的灰邊落在字塊邊上、離塗黑區 4 px 內 ⇒ 字旁有描亮邊
    s2 = cv2.erode((t2 > 127).astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
    seg |= s2.astype(np.uint8)
    ys, xs = np.nonzero(s2)
    regions.append(dict(bbox=[int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1]))
    # 人物：橢圓（遮罩＝填滿，輪廓墨線 3 px）＋右緣一條淡的單線＋線旁一顆淡小點
    cv2.ellipse(cm, (260, 700), (110, 150), 0, 0, 360, 255, -1)
    cv2.ellipse(g, (260, 700), (110, 150), 0, 0, 360, 30, 3, lineType=cv2.LINE_AA)
    cv2.line(g, (368, 690), (430, 640), 140, 2, lineType=cv2.LINE_AA)
    cv2.rectangle(g, (412, 666), (414, 668), 150, -1)
    # 格框線
    cv2.rectangle(g, (ax0, ay0), (ax1, ay1), 0, 3)
    # 小格（< 1.5% 頁面；橫的框線夠長才認得是格框線）：淺灰底，左半是去字區
    # 底色比大格暗一階（紙色正規化後 < 235：不是白元件，貼紙層不收，只有亮背景區塗黑會動它）
    bx0, by0, bx1, by1 = 24, 930, 236, 1018
    g[by0:by1 + 1, bx0:bx1 + 1] = 200
    cv2.rectangle(g, (bx0, by0), (bx1, by1), 0, 3)
    inp[by0 + 10:by1 - 10, bx0 + 12:bx0 + 100] = 255
    # ±1 灰階的固定亂數紋：畫出來的對稱邊緣梯度一樣大，Canny 的非極大值抑制在平手時 OpenCV（IPP）與 Kotlin 取捨不同；
    # 真頁沒有這種平手（47 頁 σ4 Canny 逐像素相同），合成頁加一點紋把平手拆掉，研究端與 Kotlin 才逐像素可比
    rng = np.random.default_rng(20261005)
    g = np.clip(g.astype(np.int16) + rng.integers(-1, 2, g.shape, dtype=np.int16), 0, 255).astype(np.uint8)
    return g, cm > 127, seg > 0, inp > 127, regions


def main():
    g, cm, seg, inp, regions = page()
    os.makedirs(RES, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    cv2.imwrite(os.path.join(RES, "syn_v4_gray.png"), g, PNG9)
    cv2.imwrite(os.path.join(RES, "syn_v4_chroma.png"), np.zeros_like(g), PNG9)
    cv2.imwrite(os.path.join(RES, "syn_v4_seg.png"), seg.astype(np.uint8) * 255, PNG9)
    cv2.imwrite(os.path.join(RES, "syn_v4_char.png"), cm.astype(np.uint8) * 255, PNG9)
    cv2.imwrite(os.path.join(RES, "syn_v4_inpaint.png"), inp.astype(np.uint8) * 255, PNG9)
    with open(os.path.join(RES, "syn_v4_regions.txt"), "w") as f:
        for r in regions:
            f.write(" ".join(str(v) for v in r["bbox"]) + "\n")
    p = os.path.join(OUT, "syn_v4.png")
    cv2.imwrite(p, g, PNG9)
    cv2.imwrite(os.path.join(OUT, "syn_v4_char.png"), cm.astype(np.uint8) * 255, PNG9)
    cv2.imwrite(os.path.join(OUT, "syn_v4_inpaint.png"), inp.astype(np.uint8) * 255, PNG9)
    np.savez_compressed(os.path.join(OUT, "syn_v4.npz"), seg=seg)
    json.dump(regions, open(os.path.join(OUT, "syn_v4.json"), "w"))
    json.dump([dict(name="syn_v4", page=p, char_dir=OUT, det_json=os.path.join(OUT, "syn_v4.json"),
                    det_npz=os.path.join(OUT, "syn_v4.npz"), inpaint=os.path.join(OUT, "syn_v4_inpaint.png"), group="syn")],
              open(os.path.join(OUT, "jobs_v4.json"), "w"), indent=1)
    print("syn_v4", g.shape, "人物", int(cm.sum()), "字遮罩", int(seg.sum()), "去字區", int(inp.sum()), "字框", len(regions))


if __name__ == "__main__":
    main()
