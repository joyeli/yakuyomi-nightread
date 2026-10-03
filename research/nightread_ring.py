#!/usr/bin/env python3
"""nightread_ring.py — 人物外那圈灰收細（折衷版；2026-10-03 使用者拍板，兩檔都套）。

病：背景塗黑停在「人物安全邊」外面，人物與黑之間留一圈 15–20 px 的灰（47 頁圈寬中位 16.8 px）。那圈灰有四個來源：
背景塗了又被人物遮罩還原（收邊區）、貼紙核心填色離人物 CORE_RELEASE_PAD 內不填、泡外圈讓開人物、留白帶被人物自己的墨
判成「有線稿」而否決。

規則（研究 research/out/thin_ring/compromise，gitignore；Kotlin `Ring.kt` 逐像素照這份）：只在「真的有畫出來的輪廓線把背景
跟人物隔開」的地方，讓已經塗黑的背景長到人物的輪廓線；沒有輪廓線的地方維持現在的寬度（認領的像素保持人物還原＝場景灰）。

一、可認領像素 D（＝「到墨線」版 F）：紙白、不在人物原輸出、不是泡（泡、偽泡、被人物扣掉的泡），而且屬於
    1. 背景填色塗了、又被人物遮罩還原的（rev）；
    2. 貼紙核心填色選中、只因離人物 CORE_RELEASE_PAD 內而沒填的（扣掉「被吃前景白」保護區）；泡外圈在讓開人物之前的範圍
       （wh；限「現在是人物還原區，或沒被任何一層塗過」）；
    3. 留白帶被線稿否決、而「不把人物遮罩裡的墨算線稿」重算就不會被否決的（gv；同上限制、人物遮罩外擴 RING_R_OUT 內、
       而且夾在黑與人物之間：到種子黑的 5×5 chamfer ＋ 到人物原輸出的 5×5 chamfer ≤ RING_GV_GAP）；
    4. 貼紙的前景描邊帶落在人物還原區的（bd）。
    再扣掉人物原輸出閉運算（橢圓半徑 RING_RAW_CLOSE）多出來的窄凹口。
二、輪廓證據（只跟灰階與人物原輸出有關，一頁算一次、各檔共用；`evidence`）：
    1. 深墨：5×5 方窗（邊界複製）內 (255 − 灰階) 的總和 ≥ RING_INK_SUM（957＝ceil(0.15·25·255)），而且灰階 < RING_INK_MAX_GRAY。
    2. 人物原輸出先補面積 < RING_HOLE_MAX、不碰頁緣的洞（8 連通，見 fill_small_holes），取 4 鄰邊界像素（十字核侵蝕，頁外當前景）。
       邊界點離「落在 raw 內 RING_EV_IN px 或 raw 外 RING_EV_OUT px 帶裡的深墨」在橢圓半徑 max(EV_IN, EV_OUT) 內＝有輪廓。
    3. 前面要是開闊的背景：有輪廓的邊界點沿外法向走 RING_RAY_FROM..RING_RAY_TO px，又碰到 raw 的不算。
    4. 連續：有輪廓的邊界點閉運算（橢圓半徑 RING_GAP_CLOSE）補回落在缺口上的邊界點；剩下沒輪廓的邊界點外擴（橢圓半徑
       RING_BREAK_PAD），範圍內的也算沒輪廓。
    5. 背景側：p 到「有輪廓的邊界點」的精確歐氏距離 ≤ p 到 raw 的精確歐氏距離（p 最近的人物邊是有輪廓的那一段）。
    6. 空白紙：淡筆觸＝灰階 < RING_FAINT_GRAY、不是深墨（窗和 < RING_INK_SUM）、離深墨 > RING_FAINT_INK_PAD（橢圓）；
       RING_FAINT_WIN² 方窗（邊界複製）內淡筆觸 ≥ RING_FAINT_MIN 個的地方不認領。
三、生長：從種子（留得下來的背景黑、8 連通塊 ≥ RING_SEED_MIN px）在「D ∧ 證據」裡 4 連通長 ≤ RING_STEPS 步。
四、收尾：黑（種子 ∪ 認領）做半徑 RING_OPEN 的開運算，認領裡放不進圓盤的細指頭不要；開運算後只留經認領像素與種子
    4 連通相連的（RING_SEED_CONN；開運算削斷細頸後剩下的孤立黑塊不要，回到現在的灰）。
認領到的像素填 BG、從人物還原遮罩拿掉。

「只留相連」是整合時加的（研究版 zhe.py 沒有、它的查核 VERIFY.md 第 9 節提的選項）：47 頁 × 五檔共 14 個不同的孤立黑塊
（標準 5 塊 1,065 px、更多 7 塊 1,201 px、完整管線 13 塊 1,678 px：c362_014 斜窄溝兩段、其餘是 33–255 px 的小黑點），
成品差全在塊與它外擴 3 px（還原遮罩羽化的範圍）內，守護框五檔不變；逐塊看過都是背景上的孤立碎黑，拿掉後與四周的灰一致。
NIGHTREAD_RING_SEEDCONN=0 ＝ zhe.py（47 頁 × 五檔逐像素相同）。

浮點與 Kotlin：深墨與淡筆觸都是整數和；兩種距離變換都關掉 IPP（`_dist_noipp`）：精確歐氏＝整數平方距離開根號（＝Kotlin
Cv.distanceL2），5×5 chamfer＝OpenCV 自己的定點版（＝Kotlin Cv.distanceChamfer 逐位元）。研究版（zhe.py）在多執行緒下跑，
cv2 走的正是這兩條；單執行緒時精確歐氏會走 IPP 的浮點近似，背景側的相等比較會翻（c371_015 量到 3,410 px）。
外法向不用 cv2 的 float32 GaussianBlur＋Sobel（SIMD 加總順序不定、Kotlin 對不上），改成這裡寫死順序的 float64 逐點計算
（`outward_normals`）；47 頁 × 五檔的有輪廓邊界點與研究版（cv2）逐像素相同。

開關（預設開）：NIGHTREAD_RING=0 ＝ 加入前的輸出（逐像素相同）；NIGHTREAD_RING_SEEDCONN=0 ＝ 研究版 zhe.py（不做「只留相連」）。
"""
import math
import os

import cv2
import numpy as np

RING_ON = os.environ.get("NIGHTREAD_RING", "1") == "1"

BG = 16
WHITE_TH = 235
# 一、可認領像素（F）
RING_R_OUT = 18         # gv 只看人物遮罩外擴此 px 內（CORE_RELEASE_PAD 16 ＋ 2）
RING_SEED_MIN = 200     # 種子黑塊的最小面積（8 連通塊）：零星黑點不當種子
RING_GV_GAP = 24        # gv：到種子黑 ＋ 到人物原輸出（5×5 chamfer）≤ 此
RING_RAW_CLOSE = 6      # 人物原輸出的窄凹口（寬 < 2·此）不認領
RING_STEPS = 64         # 從種子最多長幾步（4 連通）
# 二、輪廓證據（折衷）
RING_INK_BOX = 5        # 深墨：方窗邊長
RING_INK_SUM = 957      # 深墨：窗內 (255−g) 總和下限＝ceil(0.15·5·5·255)
RING_INK_MAX_GRAY = 200 # 深墨：中心灰階上限
RING_HOLE_MAX = 256     # 人物原輸出補洞的面積上限（只給證據用）
RING_EV_IN = 8          # 深墨落在 raw 內此 px …
RING_EV_OUT = 3         # … 或 raw 外此 px 的帶裡才算輪廓
RING_RAY_FROM = 3       # 外法向射線從第幾 px 開始看
RING_RAY_TO = 24        # 走到第幾 px
RING_RAY_SIGMA = 2.0    # 外法向：raw 高斯模糊的 σ
RING_GAP_CLOSE = 6      # 連續：有輪廓邊界點的閉運算半徑
RING_BREAK_PAD = 12     # 連續：沒輪廓邊界點的外擴半徑
RING_FAINT_GRAY = 225   # 淡筆觸：灰階上限
RING_FAINT_INK_PAD = 2  # 淡筆觸：離深墨的外擴半徑
RING_FAINT_WIN = 21     # 淡筆觸：計數方窗邊長
RING_FAINT_MIN = 7      # 淡筆觸：窗內 ≥ 此個就不認領（＝ceil(0.015·21·21)）
RING_OPEN = 3           # 收尾開運算半徑
RING_SEED_CONN = os.environ.get("NIGHTREAD_RING_SEEDCONN", "1") == "1"   # 開運算後只留與種子 4 連通相連的認領

_K4 = cv2.getStructuringElement(cv2.MORPH_CROSS, (3, 3))


def _ell(r):
    return cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1,) * 2)


def _dist_noipp(m, mask):
    """cv2.distanceTransform(m, DIST_L2, mask)，關掉 IPP。pip 版 cv2 內建 IPP：5×5 chamfer 的 IPP 版是浮點累加、權重不同；
    精確歐氏（DIST_MASK_PRECISE）的 IPP 版只在單執行緒（cv2.setNumThreads(1)）時走，是浮點近似（47 頁量到差 6e-5，相等比較會翻）。
    關掉 IPP 後 chamfer＝OpenCV 自己的定點版（＝Kotlin Cv.distanceChamfer 逐位元）、精確歐氏＝整數平方距離開根號（＝Kotlin
    Cv.distanceL2），與執行緒數無關。"""
    prev = cv2.ipp.useIPP()
    cv2.ipp.setUseIPP(False)
    try:
        return cv2.distanceTransform(m, cv2.DIST_L2, mask)
    finally:
        cv2.ipp.setUseIPP(prev)


def _chamfer5(m):
    """5×5 chamfer（m 前景到最近背景）。"""
    return _dist_noipp(m, 5)


def _edt(m):
    """m 的前景到最近背景的精確歐氏距離（float32）。"""
    return _dist_noipp(m.astype(np.uint8), cv2.DIST_MASK_PRECISE)


def fill_small_holes(raw, maxa):
    """raw 的洞（¬raw 的 8 連通塊）面積 < maxa、外框不碰頁緣的併進 raw。
    ⚠️ 8 連通：研究版寫的是 `connectedComponentsWithStats(m, 4)`，但 cv2 的第二個位置參數是 labels 輸出、不是 connectivity，
    實際走的是預設 8 連通（RESULT／RING 的文字寫 4 連通）。產品照程式碼（＝研究版的實際輸出）用 8。"""
    n, lb, st, _ = cv2.connectedComponentsWithStats((~raw).astype(np.uint8), connectivity=8)
    H, W = raw.shape
    small = np.zeros(n, bool)
    for i in range(1, n):
        x, y, w, h, a = st[i]
        if a < maxa and x > 0 and y > 0 and x + w < W and y + h < H:
            small[i] = True
    return raw | small[lb]


def _gauss_kernel(sigma):
    """cv2 float 影像的核長（round(σ·8+1)|1）＋ getGaussianKernel 的公式（float64、純量 exp、依序加總後正規化；Kotlin 用
    StrictMath.exp，σ＝2 的 17 個值與這裡逐位元相同，RingParityTest 守）。"""
    ks = int(round(sigma * 8 + 1)) | 1
    c = -0.5 / (sigma * sigma)
    t = [math.exp(c * x * x) for x in (i - (ks - 1) * 0.5 for i in range(ks))]
    s = 0.0
    for v in t:
        s += v
    return [v / s for v in t]


def _refl(i, n):
    """BORDER_REFLECT_101（|i| < n 的範圍內）。"""
    i = np.abs(i)
    return np.where(i >= n, 2 * (n - 1) - i, i)


def outward_normals(raw, ys, xs, sigma):
    """邊界點 (ys, xs) 的外法向（單位向量，float64）＝ −Sobel(Gauss_σ(raw))，逐點、加總順序寫死（Kotlin `Ring.normals` 同序）：
      高斯（核長 k、半徑 r＝k//2、反射 101）：H(y', x) = Σ_j K[j]·raw[y', x+j−r]（j 由小到大、從 0.0 起加），
                                               B(y, x)  = Σ_i K[i]·H(y+i−r, x)；
      Sobel：gx = (hx(y−1) + 2·hx(y)) + hx(y+1)，hx(y') = B(y', x+1) − B(y', x−1)；
             gy = s(y+1) − s(y−1)，s(y') = (B(y', x−1) + 2·B(y', x)) + B(y', x+1)（鄰點也反射 101）；
      n = (−gx, −gy) / (√(gx²+gy²) + 1e-9)。"""
    H, W = raw.shape
    k = _gauss_kernel(sigma)
    ks = len(k)
    r = ks // 2
    rf = raw.astype(np.float64)
    dys = np.array([-1, -1, -1, 0, 0, 0, 1, 1, 1])
    dxs = np.array([-1, 0, 1, -1, 0, 1, -1, 0, 1])
    ny_ = _refl(ys[:, None] + dys[None], H)
    nx_ = _refl(xs[:, None] + dxs[None], W)
    key = (ny_ * W + nx_).ravel()
    uk, inv = np.unique(key, return_inverse=True)
    py, px = uk // W, uk % W
    b = np.zeros(len(uk))
    for i in range(ks):
        rows = _refl(py + i - r, H)
        h = np.zeros(len(uk))
        for j in range(ks):
            h += k[j] * rf[rows, _refl(px + j - r, W)]
        b += k[i] * h
    v = b[inv].reshape(-1, 9)
    hx = [v[:, 3 * a + 2] - v[:, 3 * a] for a in range(3)]
    gx = (hx[0] + 2.0 * hx[1]) + hx[2]
    s = [(v[:, 3 * a] + 2.0 * v[:, 3 * a + 1]) + v[:, 3 * a + 2] for a in range(3)]
    gy = s[2] - s[0]
    nx, ny = -gx, -gy
    nn = np.sqrt(nx * nx + ny * ny) + 1e-9
    return nx / nn, ny / nn


def _ray_ok(raw, ys, xs):
    """邊界點沿外法向走 RING_RAY_FROM..RING_RAY_TO px 都沒碰到 raw（取樣點 rint、半數取偶；出界不算碰到）。"""
    nx, ny = outward_normals(raw, ys, xs, RING_RAY_SIGMA)
    H, W = raw.shape
    ok = np.ones(len(ys), bool)
    for t in range(RING_RAY_FROM, RING_RAY_TO + 1):
        px = np.rint(xs + nx * t).astype(np.int64)
        py = np.rint(ys + ny * t).astype(np.int64)
        inb = (px >= 0) & (px < W) & (py >= 0) & (py < H)
        hit = np.zeros(len(ys), bool)
        hit[inb] = raw[py[inb], px[inb]]
        ok &= ~hit
    return ok


def evidence(g, char_raw, dbg=None):
    """頁面級的「可以收細」遮罩＝背景側 ∧ 空白紙（規則二）。只跟灰階 g 與人物原輸出有關。"""
    dk = (255 - g.astype(np.int32)).astype(np.float32)
    s5 = cv2.boxFilter(dk, -1, (RING_INK_BOX, RING_INK_BOX), normalize=False, borderType=cv2.BORDER_REPLICATE)
    ink = (s5 >= RING_INK_SUM) & (g < RING_INK_MAX_GRAY)
    notdeep = s5 < RING_INK_SUM
    # 空白紙（規則二-6）
    faint = (g < RING_FAINT_GRAY) & notdeep & ~(cv2.dilate(ink.astype(np.uint8), _ell(RING_FAINT_INK_PAD)) > 0)
    cnt = cv2.boxFilter(faint.astype(np.float32), -1, (RING_FAINT_WIN, RING_FAINT_WIN), normalize=False,
                        borderType=cv2.BORDER_REPLICATE)
    paper = cnt < RING_FAINT_MIN
    raw = fill_small_holes(char_raw, RING_HOLE_MAX)
    if not raw.any():
        return np.zeros(g.shape, bool)
    din = _edt(raw)
    dout = _edt(~raw)
    band = (raw & (din <= RING_EV_IN)) | (~raw & (dout <= RING_EV_OUT))
    bnd = raw & ~(cv2.erode(raw.astype(np.uint8), _K4) > 0)
    conf = bnd & (cv2.dilate((ink & band).astype(np.uint8), _ell(max(RING_EV_IN, RING_EV_OUT))) > 0)
    ys, xs = np.nonzero(conf)
    if len(ys):
        okr = _ray_ok(raw, ys, xs)
        conf[ys[~okr], xs[~okr]] = False
    if RING_GAP_CLOSE > 0:
        conf |= bnd & ~conf & (cv2.morphologyEx(conf.astype(np.uint8), cv2.MORPH_CLOSE, _ell(RING_GAP_CLOSE)) > 0)
    noc = bnd & ~conf
    conf2 = conf & ~(cv2.dilate(noc.astype(np.uint8), _ell(RING_BREAK_PAD)) > 0) if noc.any() else conf
    if conf2.any():
        side = _edt(~conf2) <= dout
    else:
        side = np.zeros(g.shape, bool)
    if dbg is not None:
        dbg.update(ring_conf=conf2, ring_side=side, ring_paper=paper)
    return side & paper


def claimable(g, out, scene_keep, restore, char_raw, charmask, bub, bg_paint, withheld, rest_pre, gv_wh, stk_band):
    """規則一：可認領像素 D 與種子（留得下來的背景黑、8 連通塊面積 ≥ RING_SEED_MIN）。回傳 (D, seed)。"""
    W = g >= WHITE_TH
    black = bg_paint & ~restore                                         # 留得下來的背景黑
    nb, lb, st, _ = cv2.connectedComponentsWithStats(black.astype(np.uint8), connectivity=8)    # 8 連通：見 fill_small_holes
    big = np.zeros(nb, bool)
    big[1:] = st[1:, cv2.CC_STAT_AREA] >= RING_SEED_MIN
    seed = big[lb]
    unp = W & ~bub & (out == scene_keep)                                # 沒被任何一層塗過的紙白
    ok = W & ~char_raw & ~bub
    ru = restore | unp
    D = ok & ((bg_paint & restore) | ((withheld | rest_pre) & ru) | (stk_band & restore))
    gv = gv_wh & ru & ok
    if gv.any():
        gv &= cv2.dilate(charmask.astype(np.uint8), _ell(RING_R_OUT)) > 0
        if gv.any():
            d_b = _chamfer5((~seed).astype(np.uint8))
            d_r = _chamfer5((~char_raw).astype(np.uint8))
            D |= gv & (d_b + d_r <= RING_GV_GAP)
    if RING_RAW_CLOSE > 0 and D.any():
        D &= ~(cv2.morphologyEx(char_raw.astype(np.uint8), cv2.MORPH_CLOSE, _ell(RING_RAW_CLOSE)) > 0)
    return D, seed


def grow(allowed, seed):
    """規則三、四：從種子在 allowed 裡 4 連通長 ≤ RING_STEPS 步，再對黑（種子 ∪ 認領）開運算、只留與種子相連的。回傳認領遮罩。"""
    cl = np.zeros(allowed.shape, bool)
    if not allowed.any():
        return cl
    ys, xs = np.nonzero(allowed)
    H, W = allowed.shape
    y0, y1 = max(0, ys.min() - 1), min(H, ys.max() + 2)          # 種子只透過貼著 allowed 的像素起作用
    x0, x1 = max(0, xs.min() - 1), min(W, xs.max() + 2)
    a = allowed[y0:y1, x0:x1]
    within = (seed[y0:y1, x0:x1] | a).astype(np.uint8)
    cur = (seed[y0:y1, x0:x1] & (within > 0)).astype(np.uint8)
    for _ in range(RING_STEPS):
        nxt = cv2.dilate(cur, _K4) & within
        if np.array_equal(nxt, cur):
            break
        cur = nxt
    cl[y0:y1, x0:x1] = (cur > 0) & a
    if RING_OPEN > 0 and cl.any():
        cl &= cv2.morphologyEx((seed | cl).astype(np.uint8), cv2.MORPH_OPEN, _ell(RING_OPEN)) > 0
    if RING_SEED_CONN and cl.any():
        n, lb = cv2.connectedComponents((seed | cl).astype(np.uint8), connectivity=4)
        has = np.zeros(n, bool)
        has[np.unique(lb[seed])] = True
        has[0] = False
        cl &= has[lb]
    return cl


def apply(out, restore, g, scene_keep, char_raw, charmask, bub, bg_paint, withheld, rest_pre, gv_wh, stk_band, diag=None):
    """人物還原前呼叫：認領的像素填 BG、從還原遮罩拿掉（就地改 out，回傳新的 restore）。[diag] 給 dict 就存 ring_D／ring_seed／
    ring_claim，有算證據時另存 ring_conf／ring_side／ring_paper。"""
    D, seed = claimable(g, out, scene_keep, restore, char_raw, charmask, bub, bg_paint, withheld, rest_pre, gv_wh, stk_band)
    cl = np.zeros(g.shape, bool)
    dbg = {} if diag is not None else None
    if D.any():
        cl = grow(D & evidence(g, char_raw, dbg), seed)
        if cl.any():
            out[cl] = BG
            restore = restore & ~cl
    if diag is not None:
        diag.update(ring_D=D, ring_seed=seed, ring_claim=cl)
        diag.update(dbg)
    return restore
