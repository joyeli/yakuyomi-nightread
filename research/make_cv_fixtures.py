#!/usr/bin/env python3
"""make_cv_fixtures.py — 產 Cv.kt 的 parity fixture。

對一塊真實頁面（128×128 灰階）跑每個 cv2 原語，把輸入與輸出寫成簡單二進位，
給 `nightread/src/test/kotlin` 的 JVM 測試逐項比對。Kotlin 端沒有 OpenCV，
這些檔案就是「cv2 說的正確答案」。

格式（little-endian）：
    int32 w, int32 h, int32 kind      kind: 0=mask(uint8 0/1), 1=gray(uint8), 2=float32
    payload                            w*h 個元素

用法：python3 make_cv_fixtures.py [-o 輸出夾]
"""
import argparse
import os
import struct
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
PAGES = os.path.join(os.path.dirname(HERE), "fixtures", "pages")
DEFAULT_OUT = os.path.join(os.path.dirname(HERE), "nightread", "src", "test", "resources", "cv")

N = 128          # fixture 邊長：夠大到含連通元件/洞/邊界，夠小到 repo 不肥


def write(path, arr, kind):
    h, w = arr.shape
    with open(path, "wb") as f:
        f.write(struct.pack("<iii", w, h, kind))
        if kind == 0:
            f.write((arr > 0).astype(np.uint8).tobytes())
        elif kind == 1:
            f.write(arr.astype(np.uint8).tobytes())
        else:
            f.write(arr.astype("<f4").tobytes())


def main():
    ap = argparse.ArgumentParser(description="產 Cv.kt 的 parity fixture")
    ap.add_argument("-o", "--outdir", default=DEFAULT_OUT)
    a = ap.parse_args()
    os.makedirs(a.outdir, exist_ok=True)

    src = os.path.join(PAGES, "ch34_011.jpg")
    if not os.path.exists(src):
        sys.exit(f"找不到測試頁：{src}")
    full = cv2.imread(src, cv2.IMREAD_GRAYSCALE)
    # 取一塊同時含泡框、文字、人物、白背景的區域
    g = full[300:300 + N, 250:250 + N].copy()
    m = (g >= 235).astype(np.uint8)

    o = a.outdir
    write(os.path.join(o, "gray_in.bin"), g, 1)
    write(os.path.join(o, "mask_in.bin"), m, 0)

    # ── 結構元素：直接把核本身寫出來（Kotlin 端比對光柵化）
    for s in (3, 5, 7, 11, 15, 21, 33):
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (s, s))
        write(os.path.join(o, f"ellipse_{s}.bin"), k, 0)

    # ── 形態學
    for s in (3, 7, 15, 25):
        k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (s, s))
        write(os.path.join(o, f"dilate_{s}.bin"), cv2.dilate(m, k), 0)
        write(os.path.join(o, f"erode_{s}.bin"), cv2.erode(m, k), 0)
        write(os.path.join(o, f"open_{s}.bin"), cv2.morphologyEx(m, cv2.MORPH_OPEN, k), 0)
        write(os.path.join(o, f"close_{s}.bin"), cv2.morphologyEx(m, cv2.MORPH_CLOSE, k), 0)
    k3 = np.ones((3, 3), np.uint8)
    write(os.path.join(o, "dilate_rect3_it2.bin"), cv2.dilate(m, k3, iterations=2), 0)

    # ── 灰階形態學（ink_line_mask 用 7×7 ellipse blackhat）
    k7 = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))
    write(os.path.join(o, "blackhat_7.bin"), cv2.morphologyEx(g, cv2.MORPH_BLACKHAT, k7), 1)

    # ── 連通元件（8 與 4 連通都測；標號順序也要一致）
    for conn in (4, 8):
        n, lab, stats, _ = cv2.connectedComponentsWithStats(m, conn)
        write(os.path.join(o, f"cc{conn}_labels.bin"), lab.astype(np.float32), 2)
        st = np.zeros((n, 5), np.float32)
        st[:, 0] = stats[:, cv2.CC_STAT_LEFT]
        st[:, 1] = stats[:, cv2.CC_STAT_TOP]
        st[:, 2] = stats[:, cv2.CC_STAT_WIDTH]
        st[:, 3] = stats[:, cv2.CC_STAT_HEIGHT]
        st[:, 4] = stats[:, cv2.CC_STAT_AREA]
        write(os.path.join(o, f"cc{conn}_stats.bin"), st, 2)

    # ── 距離變換
    write(os.path.join(o, "dist_l2.bin"), cv2.distanceTransform(m, cv2.DIST_L2, 5), 2)

    # ── 濾波
    gf = g.astype(np.float32)
    for sig in (0.7, 1.5, 3.0):
        write(os.path.join(o, f"gauss_{sig}.bin"), cv2.GaussianBlur(gf, (0, 0), sig), 2)
    for kk in (3, 15):
        write(os.path.join(o, f"box_{kk}.bin"), cv2.blur(gf, (kk, kk)), 2)

    # ── 縮放
    write(os.path.join(o, "area_half.bin"), cv2.resize(gf, (N // 2, N // 2),
                                                       interpolation=cv2.INTER_AREA), 2)
    write(os.path.join(o, "nearest_double.bin"),
          cv2.resize(m, (N * 2, N * 2), interpolation=cv2.INTER_NEAREST), 0)

    # ── 洞（1px 邊框 + floodFill）
    ff = np.pad(m, 1)
    mm = np.zeros((ff.shape[0] + 2, ff.shape[1] + 2), np.uint8)
    cv2.floodFill(ff, mm, (0, 0), 2)
    write(os.path.join(o, "holes.bin"), (ff[1:-1, 1:-1] == 0).astype(np.uint8), 0)

    # ── 中值（遮罩平滑用 15）
    write(os.path.join(o, "median_15.bin"), (cv2.medianBlur(m * 255, 15) > 127).astype(np.uint8), 0)

    # ── 測地生長（snap_charmask 的核心：dilate(step) ∩ within 重複 iters 次）
    seed = np.zeros_like(m)
    seed[N // 2 - 8:N // 2 + 8, N // 2 - 8:N // 2 + 8] = 1
    seed &= m
    within = m.copy()
    # ⚠️ 直接呼叫管線的實作，別自己重寫迴圈——我曾經照著錯誤的理解手寫 fixture，
    # 結果 Kotlin「通過」測試卻長了四倍遠
    sys.path.insert(0, HERE)
    import nightread as pipeline
    cur = pipeline.geodesic_grow(seed > 0, within > 0, 10, step=4).astype(np.uint8)
    write(os.path.join(o, "geodesic.bin"), cur, 0)
    write(os.path.join(o, "geodesic_seed.bin"), seed, 0)

    # ── 輪廓總長（容差比對用）
    cs, _ = cv2.findContours(m, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
    perim = float(sum(cv2.arcLength(c, True) for c in cs))
    with open(os.path.join(o, "scalars.txt"), "w", encoding="utf-8") as f:
        f.write(f"contourLength {perim:.6f}\n")
        f.write(f"maskCount {int(m.sum())}\n")

    sep_fixtures(o, g, m)
    print(f"→ {o}（{len(os.listdir(o))} 個檔）")


def sep_fixtures(o, g, m):
    """格溝偵測（nightread_sep.py）用到的 cv2／numpy／scipy 原語：Cv.line／fillPoly／distanceChamfer／
    maxFilterWrapRows／npArgsort／npSum／eigh2 的正確答案。案例用固定亂數種子產生，重跑逐位元相同。"""
    rng = np.random.default_rng(20260927)
    S = 48                                              # 繪圖案例的畫布邊長；多個案例縱向疊成一張

    # ── cv2.line（LINE_8、thickness 1／3／7／17；端點含影像外、零長度、近水平／近垂直）
    lines = [(5, 5, 40, 12, 1), (3, 44, 44, 3, 1), (-10, 20, 60, 25, 1), (24, 24, 24, 24, 1),
             (5, 5, 40, 12, 3), (10, 40, 12, 2, 3), (24, 24, 24, 24, 3), (-5, -5, 30, 50, 3),
             (8, 30, 40, 31, 7), (40, 5, 6, 45, 7), (20, 20, 20, 20, 7), (-20, 10, 70, 14, 7),
             (5, 24, 43, 24, 17), (24, 3, 25, 45, 17), (30, 30, 30, 30, 17), (-8, 50, 55, -6, 17)]
    for _ in range(16):
        x0, y0, x1, y1 = (int(v) for v in rng.integers(-15, S + 15, 4))
        lines.append((x0, y0, x1, y1, int(rng.choice([1, 3, 7, 17]))))
    big = np.zeros((S * len(lines), S), np.uint8)
    with open(os.path.join(o, "line_cases.txt"), "w", encoding="utf-8") as f:
        for i, (x0, y0, x1, y1, t) in enumerate(lines):
            c = np.zeros((S, S), np.uint8)
            cv2.line(c, (x0, y0), (x1, y1), 1, t)
            big[i * S:(i + 1) * S] = c
            f.write(f"{x0} {y0} {x1} {y1} {t}\n")
    write(os.path.join(o, "line.bin"), big, 0)

    # ── cv2.fillPoly（單一多邊形、LINE_8；凸／凹、跨出影像、退化成線、極小）
    polys = [[(5, 5), (40, 8), (35, 40), (8, 30)], [(-10, 10), (30, -5), (60, 30), (20, 60)],
             [(24, 24), (24, 24), (25, 25), (24, 25)], [(5, 5), (40, 40), (5, 40), (40, 5)],
             [(10, 10), (38, 10), (38, 11), (10, 11)], [(3, 20), (45, 21), (44, 22), (2, 21)],
             [(-30, -30), (80, -30), (80, 80), (-30, 80)], [(10, 40), (20, 5), (30, 40), (20, 30)]]
    for _ in range(16):
        polys.append([(int(rng.integers(-12, S + 12)), int(rng.integers(-12, S + 12))) for _ in range(4)])
    big = np.zeros((S * len(polys), S), np.uint8)
    with open(os.path.join(o, "fillpoly_cases.txt"), "w", encoding="utf-8") as f:
        for i, pts in enumerate(polys):
            c = np.zeros((S, S), np.uint8)
            cv2.fillPoly(c, [np.array(pts, np.int32)], 1)
            big[i * S:(i + 1) * S] = c
            f.write(" ".join(f"{x} {y}" for x, y in pts) + "\n")
    write(os.path.join(o, "fillpoly.bin"), big, 0)

    # ── cv2.distanceTransform(DIST_L2, 3／5)：關掉 IPP＝OpenCV 自己的定點 chamfer（逐位元）；開著 IPP 的值另存（容差）
    ipp = cv2.ipp.useIPP()
    cv2.ipp.setUseIPP(False)
    write(os.path.join(o, "chamfer3.bin"), cv2.distanceTransform(m, cv2.DIST_L2, 3), 2)
    write(os.path.join(o, "chamfer5.bin"), cv2.distanceTransform(m, cv2.DIST_L2, 5), 2)
    cv2.ipp.setUseIPP(True)
    write(os.path.join(o, "chamfer3_ipp.bin"), cv2.distanceTransform(m, cv2.DIST_L2, 3), 2)
    write(os.path.join(o, "chamfer5_ipp.bin"), cv2.distanceTransform(m, cv2.DIST_L2, 5), 2)
    cv2.ipp.setUseIPP(ipp)

    # ── scipy maximum_filter（θ 環狀、ρ 常數 0）＝ Hough 取峰的 NMS
    from scipy.ndimage import maximum_filter
    acc = rng.integers(0, 12, (24, 50)).astype(np.float32)
    write(os.path.join(o, "maxfilt_in.bin"), acc, 2)
    write(os.path.join(o, "maxfilt_out.bin"),
          maximum_filter(acc, size=(7, 13), mode=("wrap", "constant"), cval=0).astype(np.float32), 2)

    # ── np.argsort（int32、大量同值 ⇒ 看不穩定排序的同值順序）
    v = (-rng.integers(0, 40, 3000)).astype(np.int32)
    write(os.path.join(o, "argsort_in.bin"), v.reshape(1, -1).astype(np.float32), 2)
    write(os.path.join(o, "argsort_out.bin"), np.argsort(v).reshape(1, -1).astype(np.float32), 2)

    # ── np.sum（float64 成對加總）與 np.linalg.eigh（2×2）：純量用 repr 寫成文字（逐位元）
    with open(os.path.join(o, "npsum.txt"), "w", encoding="utf-8") as f:
        for n in (3, 8, 13, 128, 131, 700):
            a = rng.normal(size=n) * np.exp(rng.normal(size=n) * 4)
            f.write(repr(float(a.sum())) + " " + " ".join(repr(float(x)) for x in a) + "\n")
    with open(os.path.join(o, "eigh2.txt"), "w", encoding="utf-8") as f:
        cases = [(4.0, 0.0, 1.0), (1.0, 0.0, 4.0), (2.0, 0.0, 2.0), (3.0, 1.0, 3.0), (0.0, 0.0, 5.0)]
        for _ in range(120):
            a, c = float(rng.random() * 10.0 ** rng.integers(-2, 6)), float(rng.random() * 10.0 ** rng.integers(-2, 6))
            k = int(rng.integers(0, 3))
            b = 0.0 if k == 0 else float((rng.random() - 0.5) * (1e-15 * a if k == 1 else 10.0 ** rng.integers(-3, 5)))
            cases.append((a, b, c))
        for a, b, c in cases:
            w, vv = np.linalg.eigh(np.array([[a, b], [b, c]]))
            f.write(" ".join(repr(float(x)) for x in (a, b, c, w[0], w[1], vv[0, 0], vv[1, 0], vv[0, 1], vv[1, 1])) + "\n")


if __name__ == "__main__":
    main()
