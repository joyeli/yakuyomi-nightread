#!/usr/bin/env python3
"""nightread_obj.py — 「更多」背景物件規則（2026-10-03 使用者拍板，規則版本 3；只在「更多」MORE_RULE 生效，「標準」逐像素不變）。

使用者原則：「塗黑不用看白不白，以有沒有物件判斷」。原型 rec（research/out/more_v3，gitignore：PROTO.md／VERIFY.md、
decide/）＝V1 ＋ 否決門檻 25 ‰ ＋ 拿掉漸層暗端斜坡。兩個機制，都在貼紙層之後、灰圈收細與泡重繪之前：

  V（否決 A2 多塗的白）：「更多」比「只塗標準（L2）那幾顆」多塗黑的連通塊（≥ VETO_MIN_AREA px），把白（≥ WHITE_TH）
     跨細線閉合（橢圓半徑 VETO_RC）成「超區」，超區外擴 2 px 內的否決證據 ‰ > VETO_EPM ＝夾在物件之間的白（牆板、桌面、
     地磚）⇒ 還原成只塗標準的樣子。塊（補洞後）裡有字區中心＝字幕框／旁白框 ⇒ 不否決。
  L（亮背景區塗黑，白與淺色調一樣）：σ5 後亮度 ≥ T、沒交代過（人物、泡、字、格框線）、還沒黑的連通塊（≥ AMIN 整頁），
     整區判有沒有物件（細暗線＋亮記號 ‰、調子邊界 ‰、二次曲面殘差、調子邊緣 ‰、平均彩度、最大內切半徑、孤立亮記號數）；
     過門的區只從離證據 > RLOC px 的核心塗起，往外最多長 RLOC＋6 px、再貼回線邊 SEAL＋3 px（不穿過證據）；拿掉三種塊：
     白的塊跨細線閉合後超區證據 > CTX_EPM、灰海裡的孤島（小於 ISLAND_MAX 又 ISLAND_TOUCH px 內碰不到塗黑的）、貼著人物的
     小塊（外緣 > HUG_MAX 貼人物）。塗法同貼紙：塗 BG、緊鄰線／人物／格框線的帶描亮（STROKE_OBJ_V）、四周大多被塗黑的字
     筆畫畫亮（墨度 1.4 次方，同泡裡的字）。

**確定性寫法（Kotlin `BgObjects.kt` 逐像素照這份）**：原型的浮點運算全部換成整數或固定順序：
  - 高斯模糊＝整數核（exp 權重 ×65536 四捨五入、中心補足和＝65536；核長同 cv2 浮點版 round(8σ+1)|1）的兩趟可分離卷積
    （BORDER_REFLECT_101），結果四捨五入到 Q16（灰階 ×65536 的整數）；cv2.sepFilter2D 的 float64 在這個範圍內是整數精確。
  - blackhat／tophat、線核平均（8 方向線上像素的和 vs 門檻 × 點數）、亮度門檻全在 Q16 整數上比；Canny 吃 Q16 取整數部分
    的 uint8；調子邊緣＝Q16 上的 3×3 Sobel 平方和 vs 門檻平方（float64 精確）；中值是 cv2.medianBlur（uint8）。
  - 距離＝5×5 chamfer 關 IPP（同 nightread_ring）；‰／平均／中位全用整數比；二次曲面殘差＝正規方程（numpy 成對加總）＋
    固定順序的高斯消去；字筆畫的亮度是 256 格查表（向下取整）；字塊標號照掃描首見順序重編（同 Kotlin Cv.ccStats）。
  與原型（浮點）在 47 頁差的像素與成因見 docs/DECISIONS.md「「更多」背景物件規則」。

開關（預設開；只在 NIGHTREAD_MORE=1 時有作用）：NIGHTREAD_OBJ=0 ＝ 規則版本 2 的「更多」（逐像素相同）；
NIGHTREAD_OBJ_VETO=0 ／ NIGHTREAD_OBJ_LT=0 只關 V ／ L（消融用）。
"""
import math
import os

import cv2
import numpy as np

import nightread_ring

OBJ_ON = os.environ.get("NIGHTREAD_OBJ", "1") == "1"
OBJ_VETO = os.environ.get("NIGHTREAD_OBJ_VETO", "1") == "1"
OBJ_LT = os.environ.get("NIGHTREAD_OBJ_LT", "1") == "1"

# ── V：否決 ──
VETO_EPM = 25.0         # 超區證據 ‰ 上限（原型 15；VERIFY 改 25：c362_009 男孩背後紙白 21.6、字幕框 21.3 救回，b 類最低 26.7）
VETO_RC = 5             # 超區＝白跨細線閉合的橢圓半徑
VETO_MIN_AREA = 300     # 多塗的塊 ≥ 此 px 才看
VETO_EV_DIL = 2         # 超區外擴此 px 內的證據
# ── L：亮背景區 ──
T = 150                 # σ5 後亮度下限（Q16 比）
AMIN = 0.002            # 區最小整頁佔比
LINE = 20.0             # 區內細暗線＋亮記號 ‰ 上限
CAN4 = 12.0             # 區內調子邊界（σ4 Canny）‰ 上限
FIT = 9.0               # 二次曲面殘差（σ2.5 亮度、灰階單位）上限
TONE = 215.0            # 調子邊緣（中值 7 → σ3 → Sobel）‰ 上限
TONE_GRAD = 0.8         # 調子邊緣的梯度門檻（灰階／px，乘 1920／頁高、頁高夾 0.5–2 倍）
CHROMA = 6.0            # 平均彩度上限（彩頁不動）
THICK = 24.0            # 區的最大內切半徑（5×5 chamfer）下限：兩線夾住的細長條（桌緣、牆板）不塗
MARKS = 4               # 孤立亮記號（閃光）≥ 此個…
MARKS_DEN = 4.0         # …且每 10 萬 px ≥ 此 ⇒ 整區留灰（效果記號算物件）
RLOC = 24               # 核心＝離證據 > 此 px
CORE_MIN = 0.0008       # 含核心 ≥ 此整頁佔比的塊才塗
SEAL = 4                # 證據封縫閉合半徑
CTX_EPM = 25.0          # 白塊（σ2.5 中位亮度 ≥ CTX_WHITE）的超區證據 ‰ 上限
CTX_WHITE = 230
ISLAND_MAX = 0.015      # 孤島／貼人物檢查只對整頁佔比 < 此的塊
ISLAND_TOUCH = 12       # 碰黑的距離（px）
ISLAND_TOUCH_MIN = 20   # 碰黑像素 < 此＝孤島
HUG_MAX = 0.6           # 外緣貼人物遮罩的比例上限
LONG_LEN = 80           # 長線（外接框長邊 ≥ 此 px）…
LONG_R = 40             # …四周此 px 不當核心
# ── 證據 ──
BH_TH = 20              # 細暗線：σ2 blackhat（橢圓半徑 5）> 此…
LINE_R = 12             # …且 8 方向 17 px 線核平均 > 此
BRIGHT_TH = 12          # 亮記號：σ1.5 tophat > 此…
BRIGHT_LR_NUM, BRIGHT_LR_DEN = 36, 5    # …且 8 方向 11 px 線核平均 > 36/5＝7.2
DOT = 10                # 網點：連通塊外接框長邊 < 此 px 不算（亮記號 8）
EV_MIN_AREA = 15        # 證據連通塊面積下限
CANNY_LO, CANNY_HI = 15, 40     # σ2 Canny（否決證據）
CANNY4_LO, CANNY4_HI = 10, 25   # σ4 Canny（調子邊界）

DARK_G = 63             # 「已經黑」＝塗成 BG 的像素 ∪ 原圖灰階 ≤ 此（預設場景曲線 ≤ 40 的上限；與亮度偏好、墨線增亮無關）

BG = 16
INK = 240
STROKE_OBJ_V = 220
WHITE_TH = 235
Q = 65536               # Q16


def ell(r):
    return cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1,) * 2)


def dil(m, r):
    if r <= 0:
        return m.astype(bool)
    return cv2.dilate(m.astype(np.uint8), ell(r)) > 0


def fill_holes(M):
    """M ∪ M 的洞（從外框 4 連通到不了的背景；外圍補 1 px 0 再從角落灌水）。"""
    tmp = np.zeros((M.shape[0] + 2, M.shape[1] + 2), np.uint8)
    tmp[1:-1, 1:-1] = M
    ff = np.zeros((tmp.shape[0] + 2, tmp.shape[1] + 2), np.uint8)
    cv2.floodFill(tmp, ff, (0, 0), 2)
    return tmp[1:-1, 1:-1] != 2


def chamfer5(m):
    """5×5 chamfer（m 前景到最近背景，關 IPP＝Kotlin Cv.distanceChamfer 逐位元）。"""
    return nightread_ring._dist_noipp(m.astype(np.uint8), 5)


# ── 確定性原語 ─────────────────────────────────────────────────────────────

def gauss_w(sigma):
    """整數高斯核（和＝65536）：exp(−d²/2σ²) 逐項加總正規化 ×65536 四捨五入（半進位），中心補足。核長＝round(8σ+1)|1。"""
    ks = int(round(sigma * 8 + 1)) | 1
    c = ks // 2
    k = [math.exp(-((i - c) * (i - c)) / (2.0 * sigma * sigma)) for i in range(ks)]
    t = 0.0
    for v in k:
        t += v
    w = [int(math.floor(v / t * Q + 0.5)) for v in k]
    w[c] += Q - sum(w)
    return w


def gauss_q16(u8, sigma):
    """uint8 影像的整數高斯（BORDER_REFLECT_101）→ Q16（灰階 ×65536，四捨五入）int32。"""
    w = np.array(gauss_w(sigma), np.float64)
    v = cv2.sepFilter2D(u8.astype(np.float64), cv2.CV_64F, w, w, borderType=cv2.BORDER_REFLECT_101)
    return np.floor((v + 32768.0) / Q).astype(np.int32)


def line_offsets(L):
    """8 方向（0、π/8 … 7π/8）L px 線核的位置（dy, dx），同原型 _line_kernels（每方向去重、列優先序）。"""
    c = L // 2
    out = []
    for t in np.arange(0, np.pi, np.pi / 8):
        k = np.zeros((L, L), bool)
        for u in np.linspace(-c, c, 4 * L):
            k[int(round(c + u * np.sin(t))), int(round(c + u * np.cos(t)))] = True
        ys, xs = np.nonzero(k)
        out.append(list(zip((ys - c).tolist(), (xs - c).tolist())))
    return out


_OFF17 = line_offsets(17)
_OFF11 = line_offsets(11)


def line_mean_gt(img, offs, num, den):
    """任一方向的線核平均 > num/den：Σ_{線上} img ×den > num ×點數（BORDER_REFLECT_101；整數）。"""
    H, W = img.shape
    c = max(max(abs(dy), abs(dx)) for o in offs for dy, dx in o)
    p = np.pad(img.astype(np.int64), c, mode="reflect")
    hit = np.zeros((H, W), bool)
    for o in offs:
        s = np.zeros((H, W), np.int64)
        for dy, dx in o:
            s += p[c + dy:c + dy + H, c + dx:c + dx + W]
        hit |= s * den > num * len(o)
    return hit


def morph_q(img, op):
    """Q16 整數影像的灰階形態學（橢圓半徑 5；float32 存 < 2²⁴ 的整數，精確）。"""
    return cv2.morphologyEx(img.astype(np.float32), op, ell(5)).astype(np.int32)


def raster_relabel(lb, n):
    """標號照掃描首見順序重編（＝Kotlin Cv.ccStats 的順序）。"""
    flat = lb.ravel()
    nz = np.flatnonzero(flat)
    if nz.size == 0:
        return lb
    labs = flat[nz]
    u, first = np.unique(labs, return_index=True)
    order = np.argsort(first, kind="stable")
    remap = np.zeros(n, np.int32)
    remap[u[order]] = np.arange(1, len(u) + 1, dtype=np.int32)
    return remap[lb]


def text_lut():
    """字筆畫亮度查表：BG ＋ clip(((255−g)/255)^1.4 − 0.35)/0.65, 0, 1) ×(INK − BG)，向下取整（float64）。"""
    out = []
    for g in range(256):
        av = ((255.0 - g) / 255.0) ** 1.4
        a3 = min(1.0, max(0.0, (av - 0.35) / 0.65))
        out.append(float(math.floor(BG + a3 * (INK - BG))))
    return np.array(out, np.float32)


_TEXT_LUT = text_lut()


def solve6(M, b):
    """6×6 線性方程（部分選主元高斯消去，固定順序；主元為 0 的那一維係數取 0）。"""
    A = [list(M[i]) + [b[i]] for i in range(6)]
    for col in range(6):
        piv = col
        for r in range(col + 1, 6):
            if abs(A[r][col]) > abs(A[piv][col]):
                piv = r
        if piv != col:
            A[col], A[piv] = A[piv], A[col]
        p = A[col][col]
        if p == 0.0:
            continue
        for r in range(col + 1, 6):
            f = A[r][col] / p
            if f != 0.0:
                for k in range(col, 7):
                    A[r][k] = A[r][k] - f * A[col][k]
    c = [0.0] * 6
    for col in range(5, -1, -1):
        p = A[col][col]
        if p == 0.0:
            c[col] = 0.0
            continue
        s = A[col][6]
        for k in range(col + 1, 6):
            s = s - A[col][k] * c[k]
        c[col] = s / p
    return c


def quadfit(gq, ys, xs):
    """亮度（Q16）對二次曲面的殘差 RMS（灰階）。點依掃描序、步長 n//20000 取樣；座標減平均除 500；正規方程。"""
    n0 = len(gq)
    if n0 < 50:
        return 0.0
    s = max(1, n0 // 20000)
    gv = gq[::s].astype(np.float64) / Q
    y = ys[::s].astype(np.float64)
    x = xs[::s].astype(np.float64)
    n = len(gv)
    y = (y - np.sum(y) / n) / 500.0
    x = (x - np.sum(x) / n) / 500.0
    cols = [np.ones(n), x, y, x * x, x * y, y * y]
    M = [[0.0] * 6 for _ in range(6)]
    for i in range(6):
        for j in range(i, 6):
            M[i][j] = M[j][i] = float(np.sum(cols[i] * cols[j]))
    b = [float(np.sum(cols[i] * gv)) for i in range(6)]
    c = solve6(M, b)
    pred = c[0] + c[1] * x
    pred = pred + c[2] * y
    pred = pred + c[3] * cols[3]
    pred = pred + c[4] * cols[4]
    pred = pred + c[5] * cols[5]
    r = gv - pred
    return math.sqrt(float(np.sum(r * r)) / n)


def blackish(out, g):
    """「已經黑」：這時已經塗成 BG（留白、格溝、貼紙）或原圖本來就暗（灰階 ≤ DARK_G）。原型是 out ≤ 40——吃場景曲線與墨線增亮
    的浮點值（Kotlin 的場景曲線四捨五入、增亮在半解析度估背景，與研究端差 1 階）、也隨亮度偏好變；改成只看結構與原圖。"""
    return (out == BG) | (g <= DARK_G)


def _nodots(m, mind=DOT):
    n, lb, st, _ = cv2.connectedComponentsWithStats(m.astype(np.uint8), 8)
    keep = np.zeros(n, bool)
    keep[1:] = np.maximum(st[1:, cv2.CC_STAT_WIDTH], st[1:, cv2.CC_STAT_HEIGHT]) >= mind
    return keep[lb]


def _min_area(m, a):
    n, lb, st, _ = cv2.connectedComponentsWithStats(m.astype(np.uint8), 8)
    keep = np.zeros(n, bool)
    keep[1:] = st[1:, cv2.CC_STAT_AREA] >= a
    return keep[lb]


# ── 整頁量測（與檔位無關）───────────────────────────────────────────────────

def context(g, chroma, charmask, char_raw, bubble, seg, frame, regions):
    """交代過的遮罩 X、物件證據 E（細暗線｜σ4 Canny｜亮記號，扣 X 外擴 3 與頁緣 6 px、去 < 15 px）、否決證據 Ev2
    （細暗線不經線核｜σ2 Canny）、調子邊緣、亮度門檻、長線帶、σ2.5 亮度（Q16）。[bubble]＝修剪前的泡（含封縫救回的）。"""
    H, W = g.shape
    gb2 = gauss_q16(g, 2.0)
    bh = morph_q(gb2, cv2.MORPH_BLACKHAT)
    bh_on = bh > BH_TH * Q
    ink = _nodots(bh_on & line_mean_gt(bh, _OFF17, LINE_R * Q, 1))
    gb15 = gauss_q16(g, 1.5)
    th = morph_q(gb15, cv2.MORPH_TOPHAT)
    bright = _nodots((th > BRIGHT_TH * Q) & line_mean_gt(th, _OFF11, BRIGHT_LR_NUM * Q, BRIGHT_LR_DEN), 8)
    can = _nodots(cv2.Canny((gb2 >> 16).astype(np.uint8), CANNY_LO, CANNY_HI) > 0)
    del gb2, gb15, th
    can4 = cv2.Canny((gauss_q16(g, 4.0) >> 16).astype(np.uint8), CANNY4_LO, CANNY4_HI) > 0
    light = gauss_q16(g, 5.0) >= T * Q
    gmed = gauss_q16(cv2.medianBlur(g, 7), 3.0).astype(np.float64)
    dx = cv2.Sobel(gmed, cv2.CV_64F, 1, 0, ksize=3)
    dy = cv2.Sobel(gmed, cv2.CV_64F, 0, 1, ksize=3)
    del gmed
    sH = min(2.0, max(0.5, H / 1920.0))
    tg = TONE_GRAD / sH * 8.0 * Q
    tone = dx * dx + dy * dy > tg * tg
    del dx, dy
    X = charmask | char_raw | dil(bubble, 6) | dil(seg, 3) | dil(frame, 2)
    pe = np.zeros((H, W), bool)
    pe[:6] = pe[-6:] = True
    pe[:, :6] = pe[:, -6:] = True
    X3 = dil(X, 3) | pe
    E = _min_area((ink | can4 | bright) & ~X3, EV_MIN_AREA)
    Ev2 = _min_area((_nodots(bh_on) | can) & ~X3, EV_MIN_AREA)
    n3, lb3, st3, _ = cv2.connectedComponentsWithStats((ink & ~X3).astype(np.uint8), 8)
    lg = np.zeros(n3, bool)
    lg[1:] = np.maximum(st3[1:, cv2.CC_STAT_WIDTH], st3[1:, cv2.CC_STAT_HEIGHT]) >= LONG_LEN
    longz = dil(lg[lb3], LONG_R)
    gb25 = gauss_q16(g, 2.5)
    return dict(regions=regions, longz=longz, X=X, X3=X3, E=E, Ev2=Ev2, bright=bright, ink=ink, tone=tone,
                tone_e=can4, gb25=gb25, light=light, chroma=chroma, seg=seg, frame=frame, charmask=charmask,
                char_raw=char_raw, bubble=bubble)


# ── V：否決 A2 多塗的白 ─────────────────────────────────────────────────────

def veto_blocks(g, extra, std_dark, ctx, diag=None):
    """[extra]＝更多比「只塗標準」多塗黑的像素（貼紙層）；回傳否決遮罩（要還原成只塗標準的塊）。"""
    H, W_ = g.shape
    out = np.zeros((H, W_), bool)
    if not extra.any():
        return out
    Xs = dil(ctx["charmask"] | ctx["char_raw"], 3) | dil(ctx["bubble"], 7) | dil(ctx["frame"], 3)
    sd = std_dark & ~extra
    Wm = (g >= WHITE_TH) & ~sd
    Wc = (cv2.morphologyEx(Wm.astype(np.uint8), cv2.MORPH_CLOSE, ell(VETO_RC)) > 0) & ~Xs & ~sd
    n, lb = cv2.connectedComponents(Wc.astype(np.uint8), 8)
    nbk, lbk, stk, _ = cv2.connectedComponentsWithStats(extra.astype(np.uint8), 8)
    E = ctx["Ev2"] & ~dil(sd, 2)
    info = []
    for i in range(1, nbk):
        if stk[i, cv2.CC_STAT_AREA] < VETO_MIN_AREA:
            continue
        x, y, w, h = [int(v) for v in stk[i, :4]]
        M = lbk == i
        ids = np.unique(lb[M & Wc])
        ids = ids[ids > 0]
        if ids.size == 0:
            continue
        S = np.isin(lb, ids)
        e_ = int((E & dil(S, VETO_EV_DIL)).sum())
        sa = int(S.sum())
        v = 1000.0 * e_ > VETO_EPM * max(1, sa)
        if v:
            # 字幕框／旁白框：塊（補洞後）裡有字區中心＝字寫在這片白上，這片白是容器不是背景 ⇒ 不否決
            Mf_ = fill_holes(M[y:y + h, x:x + w])
            for r_ in ctx["regions"]:
                bx0, by0, bx1, by1 = [int(q) for q in r_["bbox"]]
                cx_, cy_ = (bx0 + bx1) // 2 - x, (by0 + by1) // 2 - y
                if 0 <= cx_ < w and 0 <= cy_ < h and Mf_[cy_, cx_]:
                    v = False
                    break
        info.append(dict(bbox=[x, y, w, h], area=int(stk[i, cv2.CC_STAT_AREA]), sarea=sa, ev=e_,
                         epm=round(1000.0 * e_ / max(1, sa), 1), veto=bool(v)))
        if v:
            out |= M
    if diag is not None:
        diag["obj_veto"] = info
    return out


# ── L：亮背景區塗黑 ─────────────────────────────────────────────────────────

def _isolated_marks(bm, gs, inks):
    """亮記號裡「孤立」的（四周 4 px 沒有暗（< 128）、沒有細暗線）個數：閃光、星點。"""
    n, lb, st, _ = cv2.connectedComponentsWithStats(bm.astype(np.uint8), 8)
    if n <= 1:
        return 0
    near = dil((gs < 128) | inks, 4)
    bad = np.zeros(n, bool)
    bad[np.unique(lb[near & bm])] = True
    return int((~bad[1:]).sum())


def _grow(seed, within, iters, step=4):
    """測地生長：每批 step 次 3×3 膨脹後才與 within 交集（＝nightread.geodesic_grow／Kotlin Cv.geodesicGrow）。"""
    k = np.ones((3, 3), np.uint8)
    cur = (seed & within).astype(np.uint8)
    w8 = within.astype(np.uint8)
    done = 0
    while done < iters:
        nn = min(step, iters - done)
        nxt = cv2.dilate(cur, k, iterations=nn) & w8
        done += nn
        if (nxt == cur).all():
            break
        cur = nxt
    return cur > 0


def region_rows(g, ctx, L, H, W_):
    """L 的每個連通塊（≥ AMIN 整頁）量特徵、判過門；回傳 (passed 遮罩, 逐區紀錄)。"""
    n, lb, st, _ = cv2.connectedComponentsWithStats(L.astype(np.uint8), 8)
    amin = AMIN * H * W_
    passed = np.zeros((H, W_), bool)
    rows = []
    gb, X3 = ctx["gb25"], ctx["X3"]
    for i in range(1, n):
        if st[i, cv2.CC_STAT_AREA] < amin:
            continue
        x, y, w, h = [int(v) for v in st[i, :4]]
        x0, y0, x1, y1 = max(0, x - 8), max(0, y - 8), min(W_, x + w + 8), min(H, y + h + 8)
        sl = (slice(y0, y1), slice(x0, x1))
        M = lb[sl] == i
        Mm = fill_holes(M) & ~X3[sl]
        Me = cv2.erode(Mm.astype(np.uint8), ell(2)) > 0
        Ae = max(1, int(Me.sum()))
        Mi = cv2.erode(M.astype(np.uint8), ell(3)) > 0
        if int(Mi.sum()) < 200:
            Mi = M
        ys, xs = np.nonzero(Mi)
        nl = int(((ctx["ink"][sl] | ctx["bright"][sl]) & Me).sum())
        nc = int((ctx["tone_e"][sl] & Me).sum())
        nt = int((ctx["tone"][sl] & Me).sum())
        fit = quadfit(gb[sl][Mi], ys, xs)
        area = int(M.sum())
        csum = int(ctx["chroma"][sl][M].sum(dtype=np.int64)) if ctx["chroma"] is not None else 0
        thick = float(chamfer5(np.pad(M, 1)).max())
        marks = _isolated_marks(ctx["bright"][sl] & Mm, g[sl], ctx["ink"][sl])
        ok_marks = not (marks >= MARKS and 1e5 * marks >= MARKS_DEN * area)
        ok = (1000.0 * nl <= LINE * Ae and 1000.0 * nc <= CAN4 * Ae and fit <= FIT and 1000.0 * nt <= TONE * Ae
              and thick >= THICK and csum <= CHROMA * area and ok_marks)
        rows.append(dict(bbox=[x, y, w, h], area=int(st[i, cv2.CC_STAT_AREA]), line=round(1000.0 * nl / Ae, 1),
                         can4=round(1000.0 * nc / Ae, 1), tone=round(1000.0 * nt / Ae, 1), fit=round(fit, 2),
                         chroma=round(csum / area, 1), thick=round(thick, 1), marks=int(marks), ok=bool(ok),
                         raw=[nl, nc, nt, Ae, fit, thick, csum, marks]))
        if ok:
            passed[sl] |= M
    return passed, rows


def light_fill(out, g, ctx, diag=None, dark=None):
    """在貼紙層（含否決）之後的 [out] 上，把無物件的亮背景塗黑（就地改 out 並回傳）。[dark]＝「已經黑」的覆寫（函式層 parity
    用：餵 Kotlin 那一邊的；None＝blackish(out, g)）。"""
    H, W_ = g.shape
    X, X3, E = ctx["X"], ctx["X3"], ctx["E"]
    if dark is None:
        dark = blackish(out, g)
    L0 = ctx["light"] & ~X & ~dark
    L = cv2.morphologyEx(L0.astype(np.uint8), cv2.MORPH_OPEN, ell(2)) > 0
    passed, rows = region_rows(g, ctx, L, H, W_)
    if diag is not None:
        diag["obj_rows"] = rows
    if not passed.any():
        return out
    Ev = E | (ctx["tone_e"] & ~X3)
    core = passed & (chamfer5(~Ev) > RLOC) & ~ctx["longz"]
    dEv = dil(Ev, 1)
    sealed = cv2.morphologyEx(dEv.astype(np.uint8), cv2.MORPH_CLOSE, ell(SEAL)) > 0
    B = passed & ~sealed
    nb, lbb, _, _ = cv2.connectedComponentsWithStats(B.astype(np.uint8), 8)
    cnt = np.bincount(lbb[core], minlength=nb)
    has = np.zeros(nb, bool)
    has[1:] = cnt[1:] >= CORE_MIN * H * W_
    fill = has[lbb] & core
    fill = _grow(fill, B, RLOC + 6)
    fill = _grow(fill, passed & ~dEv, SEAL + 3)
    # 脈絡：白的塊連同「跨細線閉合」的亮區算超區；超區證據 ‰ 高＝夾在物件之間的空白
    if fill.any():
        Xs = dil(ctx["charmask"] | ctx["char_raw"], 3) | dil(ctx["bubble"], 7) | dil(ctx["frame"], 3) | dark
        Sc = (cv2.morphologyEx((L0 | fill).astype(np.uint8), cv2.MORPH_CLOSE, ell(VETO_RC)) > 0) & ~Xs
        ns, lbs, sts, _ = cv2.connectedComponentsWithStats(Sc.astype(np.uint8), 8)
        Ectx = E & ~dil(dark & (g >= 128), 8)         # 塗黑的地方（原圖亮）旁邊的線不算：斜格框線
        nf, lf, sf, _ = cv2.connectedComponentsWithStats(fill.astype(np.uint8), 8)
        drop = np.zeros(nf, bool)
        info = []
        for k in range(1, nf):
            x, y, w, h = [int(v) for v in sf[k, :4]]
            fk = lf[y:y + h, x:x + w] == k
            vals = np.sort(ctx["gb25"][y:y + h, x:x + w][fk].astype(np.int64))
            m_ = len(vals)
            med2 = 2 * int(vals[m_ // 2]) if m_ % 2 else int(vals[m_ // 2 - 1]) + int(vals[m_ // 2])
            if med2 < 2 * CTX_WHITE * Q:
                continue                                   # 只看白的塊（調子漸層的物件由整區門檻管）
            ids = np.unique(lbs[y:y + h, x:x + w][fk & Sc[y:y + h, x:x + w]])
            ids = ids[ids > 0]
            if ids.size == 0:
                continue
            bx0 = int(sts[ids, 0].min()); by0 = int(sts[ids, 1].min())
            bx1 = int((sts[ids, 0] + sts[ids, 2]).max()); by1 = int((sts[ids, 1] + sts[ids, 3]).max())
            y0_, y1_, x0_, x1_ = max(0, by0 - 3), by1 + 3, max(0, bx0 - 3), bx1 + 3
            Ss = np.isin(lbs[y0_:y1_, x0_:x1_], ids)
            e_ = int((Ectx[y0_:y1_, x0_:x1_] & dil(Ss, 2)).sum())
            sa = int(Ss.sum())
            info.append(dict(bbox=[x, y, w, h], area=int(sf[k, cv2.CC_STAT_AREA]), sarea=sa, ev=e_,
                             epm=round(1000.0 * e_ / max(1, sa), 1)))
            if 1000.0 * e_ > CTX_EPM * max(1, sa):
                drop[k] = True
        if diag is not None:
            diag["obj_ctx"] = info
        fill &= ~drop[lf]
    # 孤島：小塊又碰不到任何已經塗黑的地方（格溝、貼紙；泡不算）＝灰海裡的黑洞；或外緣多半貼著人物
    if fill.any():
        nf, lf, sf, _ = cv2.connectedComponentsWithStats(fill.astype(np.uint8), 8)
        drop = np.zeros(nf, bool)
        cmk = ctx["charmask"] | ctx["char_raw"]
        blk = dark & (g >= 128) & ~dil(ctx["bubble"], 8)
        info = []
        for k in range(1, nf):
            if sf[k, cv2.CC_STAT_AREA] >= ISLAND_MAX * H * W_:
                continue
            x, y, w, h = [int(v) for v in sf[k, :4]]
            p_ = ISLAND_TOUCH + 2
            y0_, y1_, x0_, x1_ = max(0, y - p_), min(H, y + h + p_), max(0, x - p_), min(W_, x + w + p_)
            ck = lf[y0_:y1_, x0_:x1_] == k
            t = int((dil(ck, ISLAND_TOUCH) & blk[y0_:y1_, x0_:x1_]).sum())
            per = ck & ~(cv2.erode(ck.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0)
            ph = int((per & dil(cmk[y0_:y1_, x0_:x1_], 4)).sum())
            ps = max(1, int(per.sum()))
            info.append(dict(bbox=[x, y, w, h], area=int(sf[k, cv2.CC_STAT_AREA]), touch=t, hug=round(ph / ps, 3)))
            if t < ISLAND_TOUCH_MIN or ph > HUG_MAX * ps:
                drop[k] = True
        if diag is not None:
            diag["obj_island"] = info
        fill &= ~drop[lf]
    if diag is not None:
        diag["obj_fill"] = fill.copy()
    if not fill.any():
        return out
    out[fill] = BG
    # 描亮邊（同貼紙的前景白描邊）：fill 內、緊鄰「細暗線／暗（< 100）／人物／格框線」（還沒黑的）的帶
    r = int(np.clip(round(0.0035 * min(H, W_)), 4, 7))
    F = ~fill & (ctx["ink"] | (g < 100) | ctx["charmask"] | ctx["frame"]) & ~dark
    band = dil(F, r) & fill & ~dil(ctx["seg"], 2)
    out[band] = STROKE_OBJ_V
    # 字（DBNet 筆畫）四周大多被塗黑的：筆畫畫亮、字縫填黑（同泡裡的字）
    sd = dil(ctx["seg"], 3) & ~ctx["bubble"] & ~ctx["charmask"]
    nt, lt, _, _ = cv2.connectedComponentsWithStats(sd.astype(np.uint8), 8)
    if nt > 1:
        lt = raster_relabel(lt, nt)
        ringm = dil(sd, 6) & ~sd
        lt_d = cv2.dilate(lt.astype(np.float32), ell(6)).astype(np.int32)   # 外圈像素歸給 6 px 內標號最大的字塊
        tot = np.bincount(lt_d[ringm], minlength=nt)
        hit = np.bincount(lt_d[ringm & fill], minlength=nt)
        okt = np.zeros(nt, bool)
        okt[1:] = 2 * hit[1:] >= np.maximum(tot[1:], 1)
        txt = okt[lt]
        if txt.any():
            out[txt] = _TEXT_LUT[g[txt]]
    if diag is not None:
        diag["obj_band"] = band
        diag["obj_txt"] = txt if nt > 1 else np.zeros((H, W_), bool)
    return out
