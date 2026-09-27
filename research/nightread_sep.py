#!/usr/bin/env python3
"""nightread_sep.py — 任意角度的分鏡溝／頁邊偵測（SEP，separator）。

`frame_line_mask` 只認水平／垂直的長直線，斜的格框認不到 ⇒ 斜格溝、被出血畫面打斷的溝、畫到頁緣的頁邊
都進不了留白路徑，三檔一律留場景灰（DECISIONS「背景填黑三檔」的 ⚠️ 斜向分鏡溝）。這個模組直接從像素
找「任意角度的格框線」，再找「兩條近平行框線夾住的整條白」＝分鏡溝，以及「頁緣到框線之間沒有畫的白」＝頁邊。

資料流（`build_sep` → `separators`）：

    灰階頁 g
      ├ 候選框線像素   暗（< DARK_TH）且距白 ≤ NEAR_WHITE_R（框線至少一側鄰白溝／白頁邊）
      ├ Hough 累加     θ 0.5° 一格、ρ 1px；沿 ρ 3 格和；θ 環狀的 NMS 取峰
      ├ 沿線走訪切段   法向 ±WALK_WIN0 內有候選就算命中；允許 GAP 斷口；命中率 ≥ 0.6 的段
      ├ 各段 PCA 精修  只在該段附近重走、重擬合兩次，再用窄窗（±WALK_WIN）定端點與命中率 ≥ FILL_MIN
      ├ 去重／共線分組 同一條框線被泡／狀聲詞／出血人物打斷的幾段併成一組（多段區間）
      ├ 泡框排除       線上取樣點一半以上落在泡 ⊕7 內 ⇒ 是泡／說明框的外框，不是格框
      ├ 平行線對＝溝帶 夾角 ≤ PAIR_ANG、法向距 GAP_MIN–溝寬上限；沿線逐站量剖面白度（3 站滑窗），
      │                有框線證據段、補橋段、端延伸各用自己的門；白緣直線度（MAD）、含字比例、
      │                第三條平行線（排線家族）都會否決；只收「兩側都碰到框線」的頁外連通白
      ├ 頁邊           從頁緣沿軸向往內走，途中只經白／字／泡，在 MARGIN_FRAC 內撞到頁邊框線（或其
      │                封口延長線）的那一段白；行程要連成段、淡網點／漸層與出血格內部都否決；
      │                最後過 texture_veto2（有線稿的白不是頁邊）
      │                ・斷框線：被狀聲詞／出血物蓋斷、每截都短於最短框線長的框線，由第二趟短段偵測＋共線串接
      │                  補回（只給頁邊與頁邊否決的格線遮罩，不進溝帶／出血過濾）
      │                ・補列：伸進頁邊的物件擋住的幾列（兩側都是頁邊行程、缺口 ≤ OCC_GAP_FRAC）也算頁邊，
      │                  物件後面、框線前、與頁邊白相連的白一起塗
      ├ 溝網連通       溝帶必須經由自己的走廊接上頁邊／頁緣／已收的溝帶，孤立在格內的平行線夾白丟掉
      └ 圖層（build_sep）泡 ⊕7 扣掉；單條溝被泡吃掉過半整條不塗；< SEP_MIN_CC 的碎塊不塗；
                       **人物遮罩不扣**——SEP 畫在人物之上，compose 最後的人物還原也跳過 SEP
    → sep（要塗的溝＋頁邊）

compose 裡的位置：開頭就算好；留白路徑 texture_veto2 之後給出血格過濾（nightread_bleed.py）當結構證據；
貼紙層之前用 `paint_gutter` 塗上（過濾器拿不掉它）；人物還原 `restore &= ~sep`。開關 `NIGHTREAD_SEP`。

「格溝壓過人物」（使用者 2026-09-27 提議）：人物遮罩經測地收邊＋中值平滑，會越過格框線長進溝裡 7–15px，
47 頁有 42 頁因此在溝邊留灰帶、窄溝（8–12px）被吃過半整條不塗。溝／頁邊是畫面的**外面**，人物不可能在那裡；
框線被出血人物打斷的地方本來就不成溝（剖面白度與兩側框線證據擋掉），所以讓 SEP 贏是安全的——代價是
一個畫在格溝上的守護框（ch34_006 白領，見 DECISIONS）。

⚠️ Kotlin 移植的逐位元陷阱（python 行為就是規格，照做；各處註解標 ⚠️KT）：
  1. `np.round` 與 python 內建 `round()` 都是**銀行家捨入**（half-to-even）：Kotlin 用 `Math.rint`，不能用
     `Math.round`／`roundToInt`（最短框線長 `int(round(LEN_FRAC·短邊))`、頁邊行程下限、取樣座標、點陣端點都吃這個）。
     `np.arange(t0, t1+1, 2.0)`（泡框取樣）的點數＝ceil((stop−start)/step)、值＝start + i·step。
  2. Hough 的 ρ 在 **float32** 算（x·cosθ + y·sinθ，cos/sin 先轉 float32），再 half-to-even 捨入。
  3. NMS 的最大值濾波：θ 軸**環狀**（0° 與 179.5° 相鄰，ρ 不翻號——幾何上不嚴格，但這就是規格），
     ρ 軸界外視為 0。
  4. 取峰的排序 `np.argsort(-votes)` 是**不穩定**排序（numpy 預設 introsort），同票的順序由實作決定；
     截在 max_peaks＝400 時同票跨界會挑到不同的峰，後面 dedupe／group_lines 的「同長度保持輸入順序」也吃這個順序。
     Kotlin 要逐峰比對順序才能逐位元；實務上先比溝帶遮罩。
  5. `cv2.distanceTransform(DIST_L2, 3)` 是 3×3 chamfer 近似（a=0.955、b=1.3693），不是精確歐氏：
     NET_THICK 的粗黑塊判定吃這個值。
  6. `cv2.line`（thickness > 1）與 `cv2.fillPoly` 的點陣化規則（框線點陣、延長線、走廊）要照 cv2。
  7. PCA 用 `np.cov`＋`np.linalg.eigh`，主軸方向正規化成 d.x ≥ 0（d.x≈0 時 d.y ≥ 0）。
  8. 補列的框線位置內插在 double 算（`da + (db − da)·(r − k + 1)/(e − k + 1)`），與整數 d 直接比；
     `_behind` 用 4 連通（`cv2.connectedComponents(..., connectivity=4)`）。
"""
import os

import cv2
import numpy as np

# ── 共用門檻（與 nightread.py 同值；這裡不 import nightread 以免循環相依）────────────────
WHITE_TH = 235          # 「白」的灰階下限（同 nightread.WHITE_TH）
DARK_TH = 100           # 框線「暗」的灰階上限（同 nightread.FRAME_DARK_TH）

# ── 框線段偵測 ──────────────────────────────────────────────────────────────
LEN_FRAC = 0.15         # 框線最短長度＝短邊×此（至少 60px）；格框線是尺畫長線，效果線／線稿多半短於此
NEAR_WHITE_R = 3        # 候選框線像素＝暗 ∧ 距白 ≤ 此（方核）：框線至少一側鄰白溝／白頁邊，線稿內部的暗不進 Hough
TH_STEP = 0.5           # Hough 角度解析度（度）
WALK_WIN0 = 4           # 初走訪與 PCA 精修時的法向半窗（px）：容 Hough 1° 內的偏角
WALK_WIN = 2            # 精修後定端點的法向半窗（px）
GAP = 8                 # 走訪容許的斷口（px）：框線被網點／抗鋸齒打出的小缺口
FILL_MIN = 0.85         # 定稿段的命中率下限（初切段只要 0.6；尺畫框線 ≈1.0）
PEAK_NMS_T = 3          # 取峰 NMS 半窗：θ ±3 格（±1.5°）
PEAK_NMS_R = 6          # 取峰 NMS 半窗：ρ ±6px
PEAK_MAX = 400          # 最多取這麼多個峰（票數由高到低）⚠️KT 陷阱 4
PEAK_VOTE_FRAC = 0.8    # 峰的票數下限＝此×最短框線長

# ── 共線分組／去重 ───────────────────────────────────────────────────────────
DEDUPE_ANG = 2.0        # 去重：夾角 ≤ 此（度）…
DEDUPE_OFF = 6.0        # …且端點到對方直線 ≤ 此 px、沿線重疊 ⇒ 同一條線的兩次偵測，併成聯集
GROUP_ANG = 1.0         # 共線分組：夾角 ≤ 此（度）…
GROUP_OFF = 6.0         # …且端點到群組直線 ≤ 此 px ⇒ 同一條被打斷的框線（多段區間）

# ── 分鏡溝：兩條近平行框線之間、橫跨全白的帶 ─────────────────────────────────────
PAIR_ANG = 5.0          # 兩線夾角上限（度）
GAP_MIN = 6             # 兩線法向距下限（px；太近＝同一條粗框線的兩緣）
EDGE_SKIP = 4           # 剖面白度只看離兩線各 > 此 px 的內部（框線本身＋抗鋸齒）；窄溝自動縮到 ¼ 溝寬
PROF_WHITE = 0.9        # 單一剖面內部白佔比下限
PROF_WHITE_BRIDGE = 0.98    # 補橋段（至少一側沒有框線證據）的剖面：幾乎全白才算（出血人物／白衣跨溝時擋下）
SHORT_BRIDGE = 24       # 核心內 ≤ 此站數的補橋段視為框線偵測小斷口（用 PROF_WHITE）
PROF_GAP_FILL = 2       # 剖面連續性：≤ 此站數的失敗小缺口（前後都過）補起來
PASS_MIN = 0.6          # 有證據段裡通過的剖面比例下限（整帶多半是乾淨白＝溝，不是畫）
STRIP_EXT_FRAC = 0.15   # 溝帶端延伸上限（短邊×此）：斜溝通到頁邊的楔形尖端、溝口
BRIDGE_FRAC = 0.30      # 共線斷口補橋上限（短邊×此；同 nightread.GFC_CLOSE_FRAC）
EDGE_MAD_MAX = 1.0      # 白緣直線度（溝兩側白緣法向位置的 MAD·1.4826，逐段去線性趨勢）上限（px）
TEXT_DIL = 3            # 文字筆畫外擴（方核半徑）
TEXT_PROF_MAX = 0.2     # 含字剖面比例上限（超過＝字框／說明框，不是溝）
FAMILY_ANG = 10.0       # 第三條近平行長線的夾角上限（效果線／排線家族；放射狀效果線相鄰夾角可達數度）
FAMILY_REACH = 1.5      # 在 a 外側或 b 外側 ≤ 此×溝寬內有第三條平行長線 ⇒ 排線，不是溝
WMAX_FRAC = 0.09        # 溝寬上限（短邊×此）：實測真溝 1.0–1.3%（直）、2.5–3.2%（橫）、斜向寬帶 6.3%／7.9%；
                        # 次寬的候選（demo01 10.7%）是畫面內的平行線、已被剖面白度擋下
OVL_FRAC = 0.5          # 兩線重疊長度下限＝此×最短框線長
EXT_DARK_SKIP = 8       # 端延伸／框線延長：先跨過端點處的格角暗像素（≤ 此 px）
BUB_LINE_R = 7          # 框線貼泡判定：泡遮罩外擴此 px（方核半徑）
BUB_LINE_MAX = 0.5      # 線上取樣點（每 2px）落在外擴泡遮罩內的比例 ≥ 此 ⇒ 泡／說明框外框，不當格框
                        # （c371_009：方形說明框左緣曾被當頁邊框線 → 效果線之間被塗成條紋）

# ── 溝網連通 ────────────────────────────────────────────────────────────────
NET_TOUCH = 2           # 碰到頁邊帶／頁緣／已收的溝帶（外擴此 px）才算接上網
NET_THICK = 5           # 粗黑塊判定：暗像素距離變換 ≥ 此（筆畫寬 ≥ 10px：狀聲詞、實心黑可通行）⚠️KT 陷阱 5
NET_EXT_FRAC = 0.15     # 溝帶可沿自身走廊（兩框線之間、沿線延伸 ≤ 短邊×此）經白像素接上網

# ── 頁邊：頁緣到框線之間、途中沒有畫的白 ───────────────────────────────────────
MARGIN_RUN_FRAC = 0.015     # 頁邊行程必須連成 ≥ 短邊×此 的列／行段（效果線之間的細白縫＝畫，不是頁邊）
MARGIN_LIGHT_FRAC = 0.02    # 頁邊行程內「亮但不白」像素比例上限（淡網點／漸層＝畫）
MARGIN_LIGHT_MIN = 3        # …至少容許這麼多顆（頁緣掃描雜訊）
FAR_PROBE = 14              # 撞到溝框線後往前探這麼多 px 看另一側是不是溝（是 ⇒ 這段白在出血格內，不是頁邊）
MARGIN_OK_TH = 200          # 頁邊行程的可通行亮度下限（塗色仍只塗 ≥ WHITE_TH）
MARGIN_FRAC = 0.12          # 頁邊最大深度（短邊×此；同 nightread.SAFE_GUTTER_DEPTH）
FL_R = 3                    # 框線點陣化的半寬（給 texture_veto2 當格線遮罩、給出血過濾當框線結構）
FL_HIT_R = 8                # 頁邊「撞到框線」判定的半寬：擬合線落在框線鄰白那一緣，粗框線（≤ 8px）另一緣也要算
MARGIN_EDGE_ANG = 30.0      # 已成溝的框線（trusted）當頁邊框線：與頁緣方向夾角上限
MARGIN_AXIS_ANG = 5.0       # 其餘框線當頁邊框線：必須幾乎平行頁緣（格子外框沿版心；斜的排線／效果線不是）
MARGIN_FILL_MIN = 0.95      # 其餘框線的命中率下限（尺畫框線 ≈1.0）
# 斷框線與補列（c371_001 第二排左框：「ザ」的橫畫＋白描邊把框線蓋斷成 150／159px 兩截，各自 < 最短框線長 203）
MARGIN_OCC = os.environ.get("NIGHTREAD_MARGIN_OCC", "1") != "0"       # 斷框線（第二趟短段偵測＋共線串接）
MARGIN_CLOSE = os.environ.get("NIGHTREAD_MARGIN_CLOSE", "1") != "0"   # 補列（伸進頁邊的物件擋住的列）
OCC_PIECE_FRAC = 0.5        # 斷框線的每一截 ≥ 最短框線長×此（整條的證據量仍要 ≥ 最短框線長）
OCC_GAP_FRAC = 0.05         # 斷口上限（短邊×此）：斷框線相鄰兩截之間、補列的缺口長度都用這個
MARGIN_HALO_R = 2           # 補列的淡網點判準扣掉暗像素（< DARK_TH）外擴此 px（方核半徑）內的暈：暈屬於擋路的物件

# ── 圖層（build_sep：compose 用的最終遮罩）───────────────────────────────────────
SEP_BUB_DIL = 7         # 泡遮罩外擴（方核邊長 7）：SEP 讓開泡與泡框
PAIR_SUB_MAX = 0.5      # 單條溝被外擴泡遮罩吃掉 > 此比例 ⇒ 整條不塗（剩下的會是梯子狀碎段）；頁邊不受影響
SEP_MIN_CC = 150        # 扣掉泡之後小於此 px 的連通塊不塗（避免斑點）


# ── 框線段偵測 ──────────────────────────────────────────────────────────────

def candidate_pixels(g):
    """候選框線像素：暗且距白 ≤ NEAR_WHITE_R（方核膨脹）。"""
    dark = g < DARK_TH
    white = (g >= WHITE_TH).astype(np.uint8)
    k = np.ones((2 * NEAR_WHITE_R + 1,) * 2, np.uint8)
    nearw = cv2.dilate(white, k) > 0
    return dark & nearw


def hough(M, th_step=TH_STEP):
    """直線 Hough 累加：acc[θ, ρ+D]。⚠️KT 陷阱 1、2：ρ 在 float32 算、half-to-even 捨入。"""
    ys, xs = np.nonzero(M)
    H, W = M.shape
    D = int(np.ceil(np.hypot(H, W)))
    thetas = np.deg2rad(np.arange(0, 180, th_step))
    acc = np.zeros((len(thetas), 2 * D + 1), np.int32)
    xf, yf = xs.astype(np.float32), ys.astype(np.float32)
    for i, t in enumerate(thetas):
        # cos/sin 顯式轉 float32：numpy 1.x 對 float64 純量 × float32 陣列本來就這樣算（value-based casting），
        # 顯式寫出來讓 numpy 2（NEP 50 會升成 float64）也得到同一份累加器
        r = np.round(xf * np.float32(np.cos(t)) + yf * np.float32(np.sin(t))).astype(np.int64) + D
        acc[i] = np.bincount(r, minlength=2 * D + 1)
    return acc, thetas, D


def _max_filter_theta_wrap(s, nt, nr):
    """(2nt+1)×(2nr+1) 最大值濾波：θ 軸環狀、ρ 軸界外當 0。

    ＝原型的 `scipy.ndimage.maximum_filter(s, size=(2nt+1, 2nr+1), mode=("wrap", "constant"))`（cval 0）；
    s ≥ 0 所以「界外當 0」等於「界外不算」。改用 cv2 免 scipy 依賴，47 頁累加器逐元素驗過相同。
    票數 < 2^24，轉 float32 做膨脹是精確的。⚠️KT 陷阱 3。"""
    p = np.concatenate([s[-nt:], s, s[:nt]], axis=0).astype(np.float32)
    k = np.ones((2 * nt + 1, 2 * nr + 1), np.uint8)
    m = cv2.dilate(p, k, borderType=cv2.BORDER_CONSTANT, borderValue=0)
    return m[nt:nt + s.shape[0]].astype(np.int32)


def peaks(acc, min_votes, nms_t=PEAK_NMS_T, nms_r=PEAK_NMS_R, max_peaks=PEAK_MAX):
    """Hough 取峰：沿 ρ 3 格和（容 3px 厚的鄰白帶）→ 局部最大 → 票數由高到低取前 max_peaks。"""
    a = acc.astype(np.int32)
    s = a.copy(); s[:, 1:] += a[:, :-1]; s[:, :-1] += a[:, 1:]
    mf = _max_filter_theta_wrap(s, nms_t, nms_r)
    pk = (s == mf) & (s >= min_votes)
    ti, ri = np.nonzero(pk)
    order = np.argsort(-s[ti, ri])[:max_peaks]          # ⚠️KT 陷阱 4：不穩定排序＋截斷
    return [(int(ti[j]), int(ri[j]), int(s[ti[j], ri[j]])) for j in order]


def walk(M, p0, d, n, tmin, tmax, win):
    """沿 p0 + t·d 走訪，法向 ±win 內有候選像素就算命中。回傳 t 陣列、命中、逐點命中表與取樣座標。
    ⚠️KT 陷阱 1：取樣座標 half-to-even 捨入。"""
    H, W = M.shape
    ts = np.arange(tmin, tmax + 1)
    offs = np.arange(-win, win + 1)
    px = p0[0] + ts[:, None] * d[0] + offs[None, :] * n[0]
    py = p0[1] + ts[:, None] * d[1] + offs[None, :] * n[1]
    xi, yi = np.round(px).astype(int), np.round(py).astype(int)
    inb = (xi >= 0) & (xi < W) & (yi >= 0) & (yi < H)
    v = np.zeros(xi.shape, bool)
    v[inb] = M[yi[inb], xi[inb]]
    hit = v.any(axis=1)
    return ts, hit, v, xi, yi


def runs(hit, gap, min_len, fill_min):
    """命中序列 → 允許 gap 斷口的連續段；回傳 [(i0,i1)]（含端點，皆為命中）。"""
    idx = np.nonzero(hit)[0]
    out = []
    if len(idx) == 0:
        return out
    s = idx[0]; prev = idx[0]
    for i in idx[1:]:
        if i - prev > gap + 1:
            out.append((s, prev)); s = i
        prev = i
    out.append((s, prev))
    res = []
    for a, b in out:
        L = b - a + 1
        if L >= min_len and hit[a:b + 1].mean() >= fill_min:
            res.append((a, b))
    return res


def line_param(theta, rho):
    n = np.array([np.cos(theta), np.sin(theta)])
    d = np.array([-np.sin(theta), np.cos(theta)])
    p0 = n * rho
    return p0, d, n


def t_range(p0, d, H, W):
    """直線與頁框相交的 t 範圍（進出頁框的兩點，向外取整）。"""
    ts = []
    for k, lim in ((0, W - 1), (1, H - 1)):
        if abs(d[k]) > 1e-9:
            for edge in (0, lim):
                ts.append((edge - p0[k]) / d[k])
    ts = sorted(ts)
    cand = []
    for t in ts:
        x, y = p0 + t * d
        if -1 <= x <= W and -1 <= y <= H:
            cand.append(t)
    if len(cand) < 2:
        return None
    return int(np.floor(min(cand))), int(np.ceil(max(cand)))


def fit_pca(xs, ys):
    """點集主軸：回傳 (質心, 方向 d, 法向 n)。d 正規化成 d.x ≥ 0（d.x≈0 時 d.y ≥ 0）。⚠️KT 陷阱 7。"""
    m = np.array([xs.mean(), ys.mean()])
    c = np.cov(np.vstack([xs - m[0], ys - m[1]]))
    w, v = np.linalg.eigh(c)
    d = v[:, 1]                                   # 主軸（eigh 特徵值遞增，最後一個最大）
    if d[0] < 0 or (abs(d[0]) < 1e-9 and d[1] < 0):
        d = -d
    n = np.array([d[1], -d[0]])
    return m, d / np.linalg.norm(d), n / np.linalg.norm(n)


def _hits_xy(v, xi, yi, a, b):
    sel = v[a:b + 1]
    return xi[a:b + 1][sel].astype(float), yi[a:b + 1][sel].astype(float)


def detect_segments(g, min_len=None, axis_tol=None, final_win=WALK_WIN, hough_cache=None):
    """Hough 峰 → 沿線走訪切段 → 每一段**各自** PCA 精修兩次 → 最終窄窗走訪定端點與命中率。
    （各段獨立精修：同一條 ρ 上的幾段共線框線不會被最長那段的微小斜率拖偏。）

    預設參數＝主偵測。斷框線那一趟（`occluded_frames`）：[min_len] 切段長度下限（峰的票數門檻仍用最短框線長 L）、
    [axis_tol] 只走與水平／垂直夾角 ≤ 此度的峰、[final_win] 定稿走訪的法向半窗；[hough_cache] dict，兩趟共用
    候選像素／累加器／峰。"""
    H, W = g.shape
    L = max(60, int(round(LEN_FRAC * min(H, W))))
    Lr = L if min_len is None else min_len
    hc = {} if hough_cache is None else hough_cache
    if "pk" not in hc:
        M = candidate_pixels(g)
        acc, thetas, D = hough(M)
        hc.update(M=M, thetas=thetas, D=D, pk=peaks(acc, min_votes=int(PEAK_VOTE_FRAC * L)))
    M, thetas, D, pk = hc["M"], hc["thetas"], hc["D"], hc["pk"]
    segs = []
    offs = np.arange(-final_win, final_win + 1)
    for ti, ri, votes in pk:
        th, rho = thetas[ti], ri - D
        if axis_tol is not None:
            tdeg = ti * TH_STEP                           # 峰的 θ 格號 × 0.5°（精確，不經弧度來回）
            if min(tdeg, 180.0 - tdeg, abs(tdeg - 90.0)) > axis_tol:
                continue
        p0, d, n = line_param(th, rho)
        tr = t_range(p0, d, H, W)
        if tr is None:
            continue
        ts, hit, v, xi, yi = walk(M, p0, d, n, tr[0], tr[1], WALK_WIN0)
        for a, b in runs(hit, GAP, int(0.7 * Lr), 0.6):
            xs, ys = _hits_xy(v, xi, yi, a, b)
            if len(xs) < 20:
                continue
            m, d2, n2 = fit_pca(xs, ys)
            proj = (xs - m[0]) * d2[0] + (ys - m[1]) * d2[1]
            lo_t, hi_t = int(np.floor(proj.min())) - 2 * GAP, int(np.ceil(proj.max())) + 2 * GAP
            ok = True
            for _ in range(2):                           # 只在這段附近（±2·GAP）重走、重擬合
                ts2, hit2, v2, xi2, yi2 = walk(M, m, d2, n2, lo_t, hi_t, WALK_WIN0)
                rr = runs(hit2, GAP, int(0.7 * Lr), 0.6)
                if not rr:
                    ok = False; break
                a3, b3 = max(rr, key=lambda r: r[1] - r[0])
                xs, ys = _hits_xy(v2, xi2, yi2, a3, b3)
                m, d2, n2 = fit_pca(xs, ys)
                proj = (xs - m[0]) * d2[0] + (ys - m[1]) * d2[1]
                lo_t, hi_t = int(np.floor(proj.min())) - 2 * GAP, int(np.ceil(proj.max())) + 2 * GAP
            if not ok:
                continue
            ts2, hit2, v2, xi2, yi2 = walk(M, m, d2, n2, lo_t, hi_t, final_win)
            for a2, b2 in runs(hit2, GAP, Lr, FILL_MIN):
                t0, t1 = ts2[a2], ts2[b2]
                P0 = m + t0 * d2; P1 = m + t1 * d2
                vv = v2[a2:b2 + 1]; hh = vv.any(axis=1)
                cen = (vv * offs[None, :]).sum(axis=1)[hh] / vv.sum(axis=1)[hh]
                res = float(np.sqrt(np.mean((cen - np.median(cen)) ** 2)))
                segs.append(dict(p0=P0, p1=P1, d=d2, n=n2, m=m, t0=t0, t1=t1,
                                 fill=float(hh.mean()), res=res,
                                 wid=float(vv.sum(axis=1)[hh].mean())))
    return dedupe(segs)


def seg_angle(s):
    return np.degrees(np.arctan2(s["d"][1], s["d"][0])) % 180.0


def dist_point_line(p, s):
    return float(np.dot(p - s["m"], s["n"]))


def dedupe(segs, ang_tol=DEDUPE_ANG, off_tol=DEDUPE_OFF):
    """幾乎共線且重疊的段只留一條（最長的，延伸成聯集）。sorted 是穩定排序：同長度保持峰的順序。"""
    segs = sorted(segs, key=lambda s: -(s["t1"] - s["t0"]))
    keep = []
    for s in segs:
        dup = False
        for k in keep:
            da = abs(seg_angle(s) - seg_angle(k)); da = min(da, 180 - da)
            if da > ang_tol:
                continue
            if abs(dist_point_line(s["p0"], k)) > off_tol or abs(dist_point_line(s["p1"], k)) > off_tol:
                continue
            # 投影到 k 的方向看重疊
            a0 = np.dot(s["p0"] - k["m"], k["d"]); a1 = np.dot(s["p1"] - k["m"], k["d"])
            lo, hi = min(a0, a1), max(a0, a1)
            if hi >= k["t0"] - 5 and lo <= k["t1"] + 5:
                k["t0"] = min(k["t0"], lo); k["t1"] = max(k["t1"], hi)
                k["p0"] = k["m"] + k["t0"] * k["d"]; k["p1"] = k["m"] + k["t1"] * k["d"]
                dup = True
                break
        if not dup:
            keep.append(dict(s))
    return keep


# ── 共線分組：被對白框／狀聲詞／出血人物打斷的同一條框線併成一條（多段區間）────────

def group_lines(segs):
    segs = sorted(segs, key=lambda s: -(s["t1"] - s["t0"]))
    groups = []
    for s in segs:
        for gp in groups:
            da = abs(seg_angle(s) - gp["ang"]); da = min(da, 180 - da)
            if da > GROUP_ANG:
                continue
            if abs(np.dot(s["p0"] - gp["m"], gp["n"])) > GROUP_OFF or abs(np.dot(s["p1"] - gp["m"], gp["n"])) > GROUP_OFF:
                continue
            a0 = np.dot(s["p0"] - gp["m"], gp["d"]); a1 = np.dot(s["p1"] - gp["m"], gp["d"])
            gp["iv"].append((min(a0, a1), max(a0, a1))); gp["segs"].append(s)
            break
        else:
            groups.append(dict(m=s["m"].copy(), d=s["d"].copy(), n=s["n"].copy(), ang=seg_angle(s),
                               iv=[(float(s["t0"]), float(s["t1"]))], segs=[s]))
    for gp in groups:
        iv = sorted(gp["iv"]); mg = [list(iv[0])]
        for a, b in iv[1:]:
            if a <= mg[-1][1] + 1:
                mg[-1][1] = max(mg[-1][1], b)
            else:
                mg.append([a, b])
        gp["iv"] = [tuple(x) for x in mg]
        gp["len"] = sum(b - a for a, b in gp["iv"])
    return groups


def occluded_frames(g, hough_cache=None):
    """頁邊斷框線：被狀聲詞／出血物蓋斷的框線，每截都短於最短框線長 L，主偵測一截都收不到。
    第二趟（共用 Hough）只走近軸向的峰、切段下限降到 OCC_PIECE_FRAC×L、定稿走訪用 ±WALK_WIN0（粗框線 ≥ 5px 只有兩緣是候選
    像素，擬合中心 ±2 的窄窗在縮放／抗鋸齒下會漏掉一緣、命中率掉到 0.93）；共線分組後，相鄰兩截斷口 ≤ OCC_GAP_FRAC×短邊的
    串成一條，**至少兩截、各截長度和 ≥ L** 才收——證據量與主偵測的一條框線相同，只是中間被蓋住。
    只給 margin_mask（撞到框線的粗帶）與頁邊否決的格線遮罩用，不進溝帶／出血過濾。回傳群組（同 group_lines 的格式）。"""
    H, W = g.shape; sh = min(H, W)
    L = max(60, int(round(LEN_FRAC * sh)))
    gap_max = int(round(OCC_GAP_FRAC * sh))                   # ⚠️KT 陷阱 1
    pieces = detect_segments(g, min_len=int(round(OCC_PIECE_FRAC * L)), axis_tol=MARGIN_AXIS_ANG + 1.0,
                             final_win=WALK_WIN0, hough_cache=hough_cache)
    out = []
    for gp in group_lines(pieces):
        chains = [[gp["iv"][0]]]
        for a, b in gp["iv"][1:]:
            if a - chains[-1][-1][1] <= gap_max:
                chains[-1].append((a, b))
            else:
                chains.append([(a, b)])
        for ch in chains:
            tot = sum(b - a for a, b in ch)
            if len(ch) < 2 or tot < L:
                continue
            lo, hi = ch[0][0], ch[-1][1]
            segs = [s for s in gp["segs"]
                    if min(np.dot(s["p0"] - gp["m"], gp["d"]), np.dot(s["p1"] - gp["m"], gp["d"])) >= lo - 1
                    and max(np.dot(s["p0"] - gp["m"], gp["d"]), np.dot(s["p1"] - gp["m"], gp["d"])) <= hi + 1]
            out.append(dict(m=gp["m"], d=gp["d"], n=gp["n"], ang=gp["ang"], iv=list(ch), segs=segs, len=tot))
    return out


def bridged(iv, bridge):
    """區間之間的斷口 ≤ bridge 就補起來。"""
    out = [list(iv[0])]
    for a, b in iv[1:]:
        if a - out[-1][1] <= bridge:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return out


def intersect(A, B):
    out = []
    for a0, a1 in A:
        for b0, b1 in B:
            lo, hi = max(a0, b0), min(a1, b1)
            if hi > lo:
                out.append((lo, hi))
    return out


# ── 分鏡溝：兩條近平行框線之間、橫跨全白的帶 ─────────────────────────────────────

def _line_offset(gp, P):
    return float(np.dot(P - gp["m"], gp["n"]))


def _proj_iv(src, dst):
    """src 群組的區間端點投影到 dst 的方向座標。"""
    out = []
    for t0, t1 in src["iv"]:
        u0 = float(np.dot(src["m"] + t0 * src["d"] - dst["m"], dst["d"]))
        u1 = float(np.dot(src["m"] + t1 * src["d"] - dst["m"], dst["d"]))
        out.append((min(u0, u1), max(u0, u1)))
    return sorted(out)


def _in_iv(u, ivs, pad=2.0):
    m = np.zeros(u.shape, bool)
    for t0, t1 in ivs:
        m |= (u >= t0 - pad) & (u <= t1 + pad)
    return m


def pair_geom(a, b, wmax):
    """兩群組能不能夾成一條溝：夾角 ≤ PAIR_ANG、b 的所有端點都在 a 的同一側、法向距 GAP_MIN–wmax。"""
    da = abs(a["ang"] - b["ang"]); da = min(da, 180 - da)
    if da > PAIR_ANG:
        return None
    offs = []
    for t0, t1 in b["iv"]:
        for t in (t0, t1):
            offs.append(_line_offset(a, b["m"] + t * b["d"]))
    offs = np.array(offs)
    if not (np.all(offs > 0) or np.all(offs < 0)):
        return None
    if np.abs(offs).min() < GAP_MIN or np.abs(offs).max() > wmax:
        return None
    return dict(da=float(da), sg=1.0 if offs[0] > 0 else -1.0, gap=float(np.abs(offs).mean()))


def pair_strip(g, a, b, geo, ovl_min, bridge, textd=None, outside=None):
    """一對平行框線之間的溝帶。沿 a 的方向逐站（1px）量剖面白度，回傳 (遮罩 或 None, 統計)。"""
    H, W = g.shape
    sg = geo["sg"]
    ub = _proj_iv(b, a)
    U = [iv for iv in intersect(bridged(a["iv"], bridge), bridged(ub, bridge)) if iv[1] - iv[0] >= ovl_min]
    st = dict(ang=geo["da"], gap=geo["gap"], ovl=0.0, npass=0, nvalid=0, ntext=0, px=0, mad_a=[], mad_b=[],
              nbridge_ok=0, nbridge=0)
    if not U:
        st.update(mad_a=99.0, mad_b=99.0, pass_frac=0.0, text_frac=0.0, why="noovl")
        return None, st
    full = np.zeros((H, W), bool)
    sb = 1.0 if np.dot(a["m"] - b["m"], b["n"]) > 0 else -1.0
    skip = float(np.clip(0.25 * geo["gap"], 1.0, EDGE_SKIP))
    # 端延伸：只有一側框線還在（另一側是溝口／頁邊）時，沿溝往外逐站延伸，剖面全白才前進、第一站不過就停。
    hull = bridged(sorted(list(bridged(a["iv"], bridge)) + list(bridged(ub, bridge))), 0.0)
    E = STRIP_EXT_FRAC * min(H, W)
    for idx_u, (lo_core, hi_core) in enumerate(U):
        hl = [h for h in hull if h[0] <= lo_core + 1 and h[1] >= hi_core - 1]
        hlo, hhi = (hl[0] if hl else (lo_core, hi_core))
        U[idx_u] = (lo_core, hi_core, max(lo_core - E, hlo), min(hi_core + E, hhi))
    for lo_core, hi_core, lo, hi in U:
        pa0 = a["m"] + lo * a["d"]; pa1 = a["m"] + hi * a["d"]

        def on_b(P):
            s = -np.dot(P - b["m"], b["n"]) / np.dot(a["n"], b["n"])
            return P + s * a["n"]
        pb0, pb1 = on_b(pa0), on_b(pa1)
        xs = np.array([pa0[0], pa1[0], pb0[0], pb1[0]]); ys = np.array([pa0[1], pa1[1], pb0[1], pb1[1]])
        x0, x1 = max(0, int(np.floor(xs.min())) - 1), min(W, int(np.ceil(xs.max())) + 2)
        y0, y1 = max(0, int(np.floor(ys.min())) - 1), min(H, int(np.ceil(ys.max())) + 2)
        if x1 <= x0 or y1 <= y0:
            continue
        yy, xx = np.mgrid[y0:y1, x0:x1]
        px = xx - a["m"][0]; py = yy - a["m"][1]
        u = px * a["d"][0] + py * a["d"][1]
        va = sg * (px * a["n"][0] + py * a["n"][1])
        vb = sb * ((xx - b["m"][0]) * b["n"][0] + (yy - b["m"][1]) * b["n"][1])
        quad = (u >= lo) & (u <= hi) & (va >= 0) & (vb >= 0)
        if not quad.any():
            continue
        inner = quad & (va > skip) & (vb > skip)
        wt = g[y0:y1, x0:x1] >= WHITE_TH
        nb = int(hi - lo) + 1
        ubin = np.clip(np.round(u - lo).astype(int), 0, nb - 1)        # ⚠️KT 陷阱 1
        cnt = np.bincount(ubin[inner], minlength=nb)
        cw = np.bincount(ubin[inner & wt], minlength=nb)
        uu = lo + np.arange(nb)
        ev = _in_iv(uu, a["iv"]) & _in_iv(uu, ub)          # 兩側都有真框線證據
        # 白度用 3 站滑窗：斜的窄溝逐站像素數少、抗鋸齒一顆就掉到 0.9 以下 ⇒ 逐站判會變梯子狀（ch34_006/015 實見）
        c3 = cnt.astype(np.float64); w3 = cw.astype(np.float64)
        c3 = c3 + np.r_[0, c3[:-1]] + np.r_[c3[1:], 0]; w3 = w3 + np.r_[0, w3[:-1]] + np.r_[w3[1:], 0]
        valid = (cnt >= 1) & (c3 >= 4)
        frac = w3 / np.maximum(c3, 1)
        core = (uu >= lo_core) & (uu <= hi_core)
        # 有證據＝PROF_WHITE；核心內補橋＝PROF_WHITE_BRIDGE（整段全過才收）；端延伸＝PROF_WHITE（逐站、第一站不過就停）
        # 窄溝（內部只剩 ~8px）時一顆抗鋸齒就掉到 0.89：每站容許 ≤1 顆非白（3 站窗 ≤ 3 顆）與比例門取寬者
        few = (c3 - w3) <= 3
        ok = valid & np.where(ev | ~core, (frac >= PROF_WHITE) | few, (frac >= PROF_WHITE_BRIDGE) | ((c3 - w3) <= 1))
        if textd is not None:
            tx = np.bincount(ubin[quad & textd[y0:y1, x0:x1]], minlength=nb) > 0
            st["ntext"] += int((tx & valid & ev).sum())
            ok &= ~tx
        # 小缺口（≤ PROF_GAP_FILL 站、前後都過）補起來：避免溝帶被零星一站切成斷續；含字的站不補
        k = 0
        while k < nb:
            if ok[k] or not valid[k]:
                k += 1; continue
            e = k
            while e < nb and not ok[e] and valid[e]:
                e += 1
            if k > 0 and e < nb and ok[k - 1] and ok[e] and e - k <= PROF_GAP_FILL and not (textd is not None and tx[k:e].any()):
                ok[k:e] = True
            k = max(e, k + 1)
        # 補橋段（核心區間內）：整段全過才收（出血人物／白衣跨過溝時，任何一站不過就整段不補）
        k = 0
        while k < nb:
            if ev[k] or not core[k]:
                k += 1; continue
            e = k
            while e < nb and not ev[e] and core[e]:
                e += 1
            if e - k <= SHORT_BRIDGE:     # 短斷口（框線偵測本身的小斷）：用一般白度門、不必 0.98
                okk = valid[k:e] & ((frac[k:e] >= PROF_WHITE) | few[k:e])
                if textd is not None:
                    okk &= ~tx[k:e]
                ok[k:e] = okk
            run_ok = bool(ok[k:e][valid[k:e]].all()) if valid[k:e].any() else False
            st["nbridge"] += 1; st["nbridge_ok"] += int(run_ok)
            if not run_ok:
                ok[k:e] = False
            k = e
        # 端延伸：從核心邊界往外逐站，先跨過格角的框線像素（≤ EXT_DARK_SKIP 站），遇到第一個不過（或無效）就停
        kc = np.nonzero(core)[0]
        if len(kc):
            keep = core.copy()
            for start, step in ((kc[0] - 1, -1), (kc[-1] + 1, 1)):
                k = start; skipped = 0
                while 0 <= k < nb and not ok[k] and skipped < EXT_DARK_SKIP:
                    k += step; skipped += 1
                while 0 <= k < nb and ok[k]:
                    keep[k] = True; k += step
            ok &= keep
            st["ext"] = st.get("ext", 0) + int((ok & ~core).sum())
        st["ovl"] += hi_core - lo_core; st["npass"] += int((ok & ev).sum()); st["nvalid"] += int((valid & ev).sum())
        # 白緣：每站最靠近 a／b 的白像素的法向距（量直線度用）
        big = 1e9
        ea = np.full(nb, big); eb = np.full(nb, big)
        sel = quad & wt
        np.minimum.at(ea, ubin[sel], va[sel]); np.minimum.at(eb, ubin[sel], vb[sel])
        for e_, key in ((ea, "mad_a"), (eb, "mad_b")):
            m_ = ok & (e_ < big)
            st[key].append((uu[m_], e_[m_]))
        mk = quad & wt & ok[ubin]
        if outside is not None:
            mk &= outside[y0:y1, x0:x1]
        # 只收「兩側都碰到框線」（離 a、b 各 ≤ skip+3）的白連通塊
        n, lb = cv2.connectedComponents(mk.astype(np.uint8), 8)
        ta = np.unique(lb[mk & (va <= skip + 3)]); tb = np.unique(lb[mk & (vb <= skip + 3)])
        both = np.intersect1d(ta[ta > 0], tb[tb > 0])
        mk = np.isin(lb, both)
        full[y0:y1, x0:x1] |= mk

    def mad(vs, ivs):
        """逐段（未補橋的真框線區間）算白緣 MAD，再依站數加權平均——補橋口與共線段間的微小錯位不算彎。"""
        if not vs:
            return 99.0
        u_ = np.concatenate([x[0] for x in vs]); e_ = np.concatenate([x[1] for x in vs])
        tot, wsum = 0.0, 0
        for t0, t1 in ivs:
            sel = (u_ >= t0 + 2) & (u_ <= t1 - 2)
            if sel.sum() < 10:
                continue
            v = e_[sel]; uu_ = u_[sel]
            # 去線性趨勢：群組參考線與成員段可有 ≤GROUP_ANG 的微小夾角，沿線會線性漂移，不算彎
            A_ = np.vstack([uu_ - uu_.mean(), np.ones_like(uu_)]).T
            coef, *_ = np.linalg.lstsq(A_, v, rcond=None)
            r = v - A_ @ coef
            tot += float(1.4826 * np.median(np.abs(r - np.median(r)))) * int(sel.sum()); wsum += int(sel.sum())
        return tot / wsum if wsum else 99.0
    st["mad_a"], st["mad_b"] = mad(st["mad_a"], a["iv"]), mad(st["mad_b"], ub)
    st["pass_frac"] = st["npass"] / max(st["nvalid"], 1)
    st["text_frac"] = st["ntext"] / max(st["nvalid"], 1)
    st["px"] = int(full.sum())
    st["geo"] = (a, b, sg, sb, [(u_[0], u_[1]) for u_ in U])
    st["why"] = ("pass" if st["pass_frac"] < PASS_MIN else
                 "mad" if max(st["mad_a"], st["mad_b"]) > EDGE_MAD_MAX else
                 "text" if st["text_frac"] > TEXT_PROF_MAX else
                 "empty" if st["px"] == 0 else "")
    if st["why"]:
        return None, st
    return full, st


def family_hit(groups, i, j, geo, a, b):
    """第三條平行長線落在 a 外側或 b 外側 ≤ FAMILY_REACH×溝寬 內、且沿線有重疊 ⇒ 排線／效果線家族。"""
    w = geo["gap"]; sg = geo["sg"]
    ua = a["iv"]; ubb = _proj_iv(b, a)
    lo = max(min(x[0] for x in ua), min(x[0] for x in ubb)); hi = min(max(x[1] for x in ua), max(x[1] for x in ubb))
    for k, c in enumerate(groups):
        if k in (i, j):
            continue
        da = abs(a["ang"] - c["ang"]); da = min(da, 180 - da)
        if da > FAMILY_ANG:
            continue
        uc = _proj_iv(c, a)
        clo, chi = min(x[0] for x in uc), max(x[1] for x in uc)
        if min(hi, chi) - max(lo, clo) < 0.5 * (hi - lo):
            continue
        o = np.mean([sg * _line_offset(a, c["m"] + t * c["d"]) for iv in c["iv"] for t in iv])
        if -FAMILY_REACH * w <= o < -GAP_MIN or w + GAP_MIN < o <= (1 + FAMILY_REACH) * w:
            return k
    return None


def outside_white(g):
    """碰得到頁邊的白連通元件（8 連通）——溝／頁邊一定連到頁外緣。"""
    white = (g >= WHITE_TH).astype(np.uint8)
    n, lab = cv2.connectedComponents(white, 8)
    border = np.unique(np.concatenate([lab[0, :], lab[-1, :], lab[:, 0], lab[:, -1]]))
    border = border[border > 0]
    return np.isin(lab, border)


def separator_mask(g, groups, wmax, ovl_min, bridge, seg=None):
    """所有群組兩兩配對 → 溝帶。回傳 (溝帶聯集, 逐對統計)。長的那條當 a（逐站量測的參考方向）。"""
    H, W = g.shape
    out = np.zeros((H, W), bool)
    textd = None
    if seg is not None:
        textd = cv2.dilate(seg.astype(np.uint8), np.ones((2 * TEXT_DIL + 1,) * 2, np.uint8)) > 0
    outside = outside_white(g)
    pairs = []
    for i in range(len(groups)):
        for j in range(i + 1, len(groups)):
            ii, jj = (i, j) if groups[i]["len"] >= groups[j]["len"] else (j, i)
            a, b = groups[ii], groups[jj]
            geo = pair_geom(a, b, wmax)
            if geo is None:
                continue
            fam = family_hit(groups, ii, jj, geo, a, b)
            mk, st = pair_strip(g, a, b, geo, ovl_min, bridge, textd, outside)
            st["i"], st["j"] = i, j
            if fam is not None and mk is not None:
                st["why"] = f"family{fam}"; mk = None
            st["acc"] = mk is not None
            if mk is not None:
                st["mask"] = mk
            pairs.append(st)
            if mk is not None:
                out |= mk
    return out, pairs


# ── 溝網連通 ────────────────────────────────────────────────────────────────

def corridor(shape, geo, ext):
    """溝帶的走廊：兩條框線之間、方向區間各往外延伸 ext 的平行四邊形（點陣）。⚠️KT 陷阱 1、6。"""
    H, W = shape
    a, b, sg, sb, U = geo
    out = np.zeros((H, W), bool)
    for lo, hi in U:
        lo2, hi2 = lo - ext, hi + ext
        pts = []
        for u in (lo2, hi2):
            P = a["m"] + u * a["d"]
            s_ = -np.dot(P - b["m"], b["n"]) / np.dot(a["n"], b["n"])
            pts += [P, P + s_ * a["n"]]
        poly = np.array([pts[0], pts[2], pts[3], pts[1]])
        m = np.zeros((H, W), np.uint8)
        cv2.fillPoly(m, [np.round(poly).astype(np.int32)], 1)
        out |= m > 0
    return out


def network_filter(pairs, margin, g):
    """溝帶一定是「溝網」的一段：從頁緣／頁邊出發，沿彼此相接的溝帶一路連進來。
    接法＝溝帶本身、或沿自己的走廊（兩框線之間往兩端延伸）經白像素，碰到頁邊／頁緣帶／已收的溝帶。
    孤立在格內的平行線夾白（標題裝飾雙線、格內的窗框）接不上網 ⇒ 丟（pairs 就地改 acc=False）。"""
    H, W = g.shape
    white = g >= WHITE_TH
    root = margin.copy()
    e = NET_TOUCH + 2
    root[:e, :] = True; root[-e:, :] = True; root[:, :e] = True; root[:, -e:] = True
    k = np.ones((2 * NET_TOUCH + 1,) * 2, np.uint8)
    ext = NET_EXT_FRAC * min(H, W)
    # 可通行＝白，或粗黑塊（筆畫寬 ≥ 2·NET_THICK：狀聲詞、實心黑）連同其抗鋸齒邊。細線（框線 2–8px、線稿）照樣擋。
    dark = (g < DARK_TH).astype(np.uint8)
    core = cv2.distanceTransform(dark, cv2.DIST_L2, 3) >= NET_THICK        # ⚠️KT 陷阱 5：chamfer 3×3
    passable = white | (cv2.dilate(core.astype(np.uint8), np.ones((2 * NET_THICK + 1,) * 2, np.uint8)) > 0)
    todo = [q for q in pairs if q.get("acc")]
    for q in todo:
        cor = corridor((H, W), q["geo"], ext)
        reach = (cor & passable) | q["mask"]
        n, lb = cv2.connectedComponents(reach.astype(np.uint8), 8)
        ids = np.unique(lb[q["mask"]]); ids = ids[ids > 0]
        q["reach"] = np.isin(lb, ids)
    grown = True
    while grown:                                   # 逐輪擴張：接上網的溝帶本身成為新的根
        grown = False
        rest = []
        for q in todo:
            if (cv2.dilate(q["reach"].astype(np.uint8), k) > 0)[root].any():
                root |= q["mask"]; q["net"] = True; grown = True
            else:
                rest.append(q)
        todo = rest
    for q in todo:
        q["net"] = False; q["acc"] = False; q["why"] = "isolated"
    out = np.zeros((H, W), bool)
    for q in pairs:
        if q.get("acc"):
            out |= q["mask"]
    return out


# ── 頁邊：頁緣到框線之間、途中沒有畫的白 ───────────────────────────────────────

def frame_raster(g, groups, r=FL_R):
    """偵測到的框線（只取真證據區間，不含補橋）點陣化成遮罩：線 ±r 內的非白像素。⚠️KT 陷阱 1、6。"""
    H, W = g.shape
    fl = np.zeros((H, W), np.uint8)
    for gp in groups:
        for t0, t1 in gp["iv"]:
            p0 = gp["m"] + t0 * gp["d"]; p1 = gp["m"] + t1 * gp["d"]
            cv2.line(fl, tuple(int(round(v)) for v in p0), tuple(int(round(v)) for v in p1), 1, 2 * r + 1)
    return (fl > 0) & (g < WHITE_TH)


def _first_true(m, axis, reverse):
    """沿 axis 從頁緣往內第一個 True 的距離；整條都 False 回長度。"""
    a = np.flip(m, axis=axis) if reverse else m
    L = a.shape[axis]
    anyv = a.any(axis=axis)
    idx = np.argmax(a, axis=axis)
    return np.where(anyv, idx, L)


def group_fill(gp):
    """群組的長度加權命中率。"""
    L = sum(s["t1"] - s["t0"] for s in gp["segs"])
    return sum(s["fill"] * (s["t1"] - s["t0"]) for s in gp["segs"]) / max(L, 1)


def extensions(g, gp, maxd):
    """框線兩端沿線方向延伸：先跨過格角的暗像素，再只走白，撞到暗像素或頁緣才算「封口」、
    封口距離 ≤ maxd 才收。＝把溝口（橫溝通到頁邊的缺口）與頁角用框線的延長線封起來。
    ⚠️KT 陷阱：這裡用 python 內建 round()（也是 half-to-even）。"""
    H, W = g.shape
    out = []
    for t0, t1 in gp["iv"]:
        for t_end, sgn in ((t0, -1.0), (t1, 1.0)):
            P = gp["m"] + t_end * gp["d"]
            k = 1
            while k <= EXT_DARK_SKIP:                     # 跨格角
                x, y = P + sgn * k * gp["d"]
                xi, yi = int(round(x)), int(round(y))
                if not (0 <= xi < W and 0 <= yi < H) or g[yi, xi] >= WHITE_TH:
                    break
                k += 1
            start = k; closed = False
            while k <= start + maxd:
                x, y = P + sgn * k * gp["d"]
                xi, yi = int(round(x)), int(round(y))
                if not (0 <= xi < W and 0 <= yi < H):
                    closed = True; break                  # 頁緣封口
                if g[yi, xi] < WHITE_TH:
                    closed = True; break                  # 撞到下一條框線／畫
                k += 1
            if closed and k > start:
                out.append((P + sgn * start * gp["d"], P + sgn * k * gp["d"]))
    return out


def _raster_lines(shape, lines, r=FL_R):
    fl = np.zeros(shape, np.uint8)
    for p0, p1 in lines:
        cv2.line(fl, tuple(int(round(v)) for v in p0), tuple(int(round(v)) for v in p1), 1, 2 * r + 1)
    return fl > 0


def _open1d(b, L):
    """一維開運算＝丟掉長度 < L 的連續 True 段（長段原樣保留、不削邊）。"""
    out = b.copy()
    k = 0; n = len(b)
    while k < n:
        if not b[k]:
            k += 1; continue
        e = k
        while e < n and b[e]:
            e += 1
        if e - k < L:
            out[k:e] = False
        k = e
    return out


def _close_rows(hit0, d, maxd, gap, ok):
    """補列：兩側都是頁邊行程、長 ≤ gap 的一段未中列裡，在框線前（或框線帶內）就被擋下的列——d ≤ maxd、
    d ≤ 兩側 d 的內插 + FL_HIT_R——且 ok(r) 成立 ⇒ 也算頁邊。＝伸進頁邊的物件（狀聲詞、出血的頭髮／手）擋住的那幾列：
    頁緣到物件之間仍是頁邊的紙白。回傳 (補後的 hit, reach：補上的列＝max(d, 內插)，其餘 −1)。⚠️KT 陷阱 8。"""
    out = hit0.copy()
    reach = np.full(len(hit0), -1.0)
    n = len(hit0); k = 0
    while k < n:
        if hit0[k]:
            k += 1; continue
        e = k
        while e < n and not hit0[e]:
            e += 1
        if k > 0 and e < n and e - k <= gap:
            da, db = float(d[k - 1]), float(d[e])
            for r in range(k, e):
                di = da + (db - da) * (r - k + 1) / (e - k + 1)
                if d[r] <= maxd and d[r] <= di + FL_HIT_R and ok(r):
                    out[r] = True
                    reach[r] = max(float(d[r]), di)
        k = e
    return out, reach


def _behind(white_ok, seed_side, band_side):
    """補上的列：物件後面、框線（內插位置）前的白，只收與這一側頁邊種子 4 連通的（被物件圍住的白不收）。⚠️KT 陷阱 8。"""
    n, lb = cv2.connectedComponents((white_ok & band_side).astype(np.uint8), connectivity=4)
    ids = np.unique(lb[seed_side & white_ok])
    ids = ids[ids > 0]
    return np.isin(lb, ids)


def margin_mask(g, groups, seg=None, bubble=None, maxd=None, trusted=(), strip_pre=None, extra=()):
    """頁邊＝從頁緣沿軸向往內、途中只經過白／字／泡、在 maxd 內撞到一條頁邊框線（或其封口延長線）的那一段白。
    [extra]＝斷框線（`occluded_frames`）。"""
    H, W = g.shape
    if maxd is None:
        maxd = MARGIN_FRAC * min(H, W)
    white = g >= WHITE_TH
    okm = g >= MARGIN_OK_TH                  # 行程可通行：亮像素（紙面雜訊／頁緣掃描邊 230 上下不算畫）
    txd = np.zeros((H, W), bool)
    if seg is not None:
        txd = cv2.dilate(seg.astype(np.uint8), np.ones((2 * TEXT_DIL + 1,) * 2, np.uint8)) > 0
        okm |= txd
    bub = np.zeros((H, W), bool)
    if bubble is not None and bubble.any():
        bub = cv2.dilate(bubble.astype(np.uint8), np.ones((7, 7), np.uint8)) > 0
        okm |= bub

    def off(gp, target):
        da = abs(gp["ang"] - target); return min(da, 180 - da)

    def pick(target):
        sel = []
        for k, gp in enumerate(groups):
            if k in trusted:
                if off(gp, target) <= MARGIN_EDGE_ANG: sel.append(gp)
            elif off(gp, target) <= MARGIN_AXIS_ANG and group_fill(gp) >= MARGIN_FILL_MIN:
                sel.append(gp)
        ex = [gp for gp in extra if off(gp, target) <= MARGIN_AXIS_ANG and group_fill(gp) >= MARGIN_FILL_MIN]
        return sel, ex
    fls = {}
    for key, target in (("v", 90.0), ("h", 0.0)):
        gs, ex = pick(target)
        lines, exts = [], []
        for gp in gs:
            for t0, t1 in gp["iv"]:
                lines.append((gp["m"] + t0 * gp["d"], gp["m"] + t1 * gp["d"]))
            exts += extensions(g, gp, maxd)
        # 斷框線的各截只進「撞到框線」的粗帶（±FL_HIT_R 內的非白）：不畫穿白的細線、不做延長線、斷口不補——常與主偵測的
        # 同一條框線重複、擬合差零點幾度，細線畫在白裡會讓行程提早 1–2px 停；斷口那幾列交給補列
        xl = [(gp["m"] + t0 * gp["d"], gp["m"] + t1 * gp["d"]) for gp in ex for t0, t1 in gp["iv"]]
        fls[key] = (_raster_lines((H, W), lines + xl, r=FL_HIT_R) & ~white) | _raster_lines((H, W), lines + exts, r=1)
    nonok = ~okm
    light = okm & ~white & ~txd & ~bub       # 亮但不白（200–234）：零星＝紙面雜訊，成片＝淡網點／漸層（畫）
    trl = [groups[k] for k in trusted if k < len(groups)]
    fl_tr = _raster_lines((H, W), [(gp["m"] + t0 * gp["d"], gp["m"] + t1 * gp["d"]) for gp in trl for t0, t1 in gp["iv"]],
                          r=FL_HIT_R) & ~white
    sp = strip_pre if strip_pre is not None else np.zeros((H, W), bool)
    run_min = max(3, int(round(MARGIN_RUN_FRAC * min(H, W))))
    close_gap = int(round(OCC_GAP_FRAC * min(H, W)))           # ⚠️KT 陷阱 1
    white_ok = white & ~bub
    l2c = []

    def light2():
        # 補列的淡網點判準：暗像素（< DARK_TH）的抗鋸齒暈屬於擋路的物件，不算淡網點（整頁只算一次、要用才算）
        if not l2c:
            halo = cv2.dilate((g < DARK_TH).astype(np.uint8), np.ones((2 * MARGIN_HALO_R + 1,) * 2, np.uint8)) > 0
            l2c.append(light & ~halo)
        return l2c[0]
    seeds = np.zeros((H, W), bool)
    yy = np.arange(H)[:, None]; xx = np.arange(W)[None, :]
    # 左／右：逐列從頁緣往內，第一個「非白非字非泡、或框線（含延長線）」的像素
    for rev in (False, True):
        stop = nonok | fls["v"]
        d = _first_true(stop, 1, rev)
        xe = np.clip((W - 1 - d) if rev else d, 0, W - 1)
        dist = (W - 1 - xx) if rev else xx
        rows = np.arange(H)
        hit0 = (d <= maxd) & (d < W) & fls["v"][rows, xe]
        # 行程內「亮但不白」過多＝淡網點／漸層（畫），不是頁邊
        lc = np.cumsum(np.flip(light, 1) if rev else light, axis=1)
        nl = lc[rows, np.clip(d - 1, 0, W - 1)] * (d > 0)
        lim = np.maximum(MARGIN_LIGHT_MIN, MARGIN_LIGHT_FRAC * d)
        hit0 &= nl <= lim
        # 撞到的是溝的框線、而溝在框線另一側 ⇒ 這段白在格子那一側（出血格的內部），不是頁邊
        sgn = -1 if rev else 1
        far = np.zeros(H, bool)
        for k in range(2, FAR_PROBE + 1):
            far |= sp[rows, np.clip(xe + sgn * k, 0, W - 1)]
        badfar = fl_tr[rows, xe] & far
        hit0 &= ~badfar
        reach = None
        if MARGIN_CLOSE:
            def ok(r, d=d, lim=lim, badfar=badfar, rev=rev):
                run = light2()[r, W - d[r]:] if rev else light2()[r, :d[r]]
                return not badfar[r] and int(run.sum()) <= lim[r]
            hit0, reach = _close_rows(hit0, d, maxd, close_gap, ok)
        hit = _open1d(hit0, run_min)
        sd = (dist < d[:, None]) & hit[:, None]
        if reach is not None and (hit & (reach >= 0)).any():
            dr = np.where(hit & (reach >= 0), reach, d.astype(float))
            sd |= _behind(white_ok, sd, (dist < dr[:, None]) & hit[:, None])
        seeds |= sd
    # 上／下：逐行同理
    for rev in (False, True):
        stop = nonok | fls["h"]
        d = _first_true(stop, 0, rev)
        ye = np.clip((H - 1 - d) if rev else d, 0, H - 1)
        dist = (H - 1 - yy) if rev else yy
        cols = np.arange(W)
        hit0 = (d <= maxd) & (d < H) & fls["h"][ye, cols]
        lc = np.cumsum(np.flip(light, 0) if rev else light, axis=0)
        nl = lc[np.clip(d - 1, 0, H - 1), cols] * (d > 0)
        lim = np.maximum(MARGIN_LIGHT_MIN, MARGIN_LIGHT_FRAC * d)
        hit0 &= nl <= lim
        sgn = -1 if rev else 1
        far = np.zeros(W, bool)
        for k in range(2, FAR_PROBE + 1):
            far |= sp[np.clip(ye + sgn * k, 0, H - 1), cols]
        badfar = fl_tr[ye, cols] & far
        hit0 &= ~badfar
        reach = None
        if MARGIN_CLOSE:
            def ok(c, d=d, lim=lim, badfar=badfar, rev=rev):
                run = light2()[H - d[c]:, c] if rev else light2()[:d[c], c]
                return not badfar[c] and int(run.sum()) <= lim[c]
            hit0, reach = _close_rows(hit0, d, maxd, close_gap, ok)
        hit = _open1d(hit0, run_min)
        sd = (dist < d[None, :]) & hit[None, :]
        if reach is not None and (hit & (reach >= 0)).any():
            dr = np.where(hit & (reach >= 0), reach, d.astype(float))
            sd |= _behind(white_ok, sd, (dist < dr[None, :]) & hit[None, :])
        seeds |= sd
    return seeds & white & ~bub


# ── 總遮罩 ──────────────────────────────────────────────────────────────────

def separators(g, seg=None, bubble=None, frame_hv=None, veto=None):
    """回傳 (溝帶, 頁邊, 框線群組, 逐對統計)。

    veto＝texture_veto2 同款函式（頁邊要過線稿密度否決；溝帶已逐剖面驗白、不過）。
    frame_hv＝nightread.frame_line_mask 的水平／垂直格線，只併進頁邊否決的格線遮罩。"""
    H, W = g.shape; sh = min(H, W)
    hcache = {}
    segs = detect_segments(g, hough_cache=hcache); groups = group_lines(segs)
    ogroups = occluded_frames(g, hough_cache=hcache) if MARGIN_OCC else []   # 斷框線：只給頁邊
    if bubble is not None and np.any(bubble):
        # 貼著對白框／說明框外框的直線不是格框（c371_009：方形說明框的左緣被當頁邊框線 → 效果線之間被塗成條紋）
        bd = cv2.dilate(np.asarray(bubble).astype(np.uint8), np.ones((2 * BUB_LINE_R + 1,) * 2, np.uint8)) > 0

        def off_bubble(gs):
            keep = []
            for gp in gs:
                pts = [gp["m"] + t * gp["d"] for t0, t1 in gp["iv"] for t in np.arange(t0, t1 + 1, 2.0)]
                xy = np.round(np.array(pts)).astype(int)
                xy[:, 0] = np.clip(xy[:, 0], 0, W - 1); xy[:, 1] = np.clip(xy[:, 1], 0, H - 1)
                if float(bd[xy[:, 1], xy[:, 0]].mean()) < BUB_LINE_MAX:
                    keep.append(gp)
            return keep
        groups = off_bubble(groups); ogroups = off_bubble(ogroups)
    strip, pairs = separator_mask(g, groups, WMAX_FRAC * sh, OVL_FRAC * LEN_FRAC * sh, BRIDGE_FRAC * sh, seg=seg)
    trusted = set()
    strip_pre = np.zeros(g.shape, bool)
    for q in pairs:
        if q["acc"]:
            trusted |= {q["i"], q["j"]}
            strip_pre |= q["mask"]
    mar = margin_mask(g, groups, seg=seg, bubble=bubble, trusted=trusted, strip_pre=strip_pre, extra=ogroups)
    strip = network_filter(pairs, mar, g)
    if veto is not None and mar.any():
        # 斷框線也當格線隔板：否則頁邊與格內經斷口連成同一區，格內線稿的密度滲到頁邊
        fr = frame_raster(g, groups + ogroups).astype(np.uint8)
        if frame_hv is not None:
            fr |= (np.asarray(frame_hv) > 0).astype(np.uint8)
        mar = veto(mar, g, fr, seg, bubble)
    return strip, mar, groups, pairs


def build_sep(g, seg, bubble, frame=None, veto=None):
    """compose 用的 SEP 圖層。回傳 dict：

      sep       要塗的溝＋頁邊（泡 ⊕7 扣掉、被泡吃過半的整條溝丟掉、< SEP_MIN_CC 碎塊丟掉）
      sep_pre   扣泡之前的溝＋頁邊（出血過濾拿它當「溝／頁邊」結構證據）
      strip     溝帶（已過溝網連通與泡過半規則）
      margin    頁邊（已過 texture_veto2）
      frame_arb 任意角度框線點陣（出血過濾拿它當框線結構證據）
      groups / pairs / dropped  診斷用

    **人物遮罩不扣**（圖層：SEP 在人物之上）——對照實驗過的變體（L2、47 頁、溝核心內仍灰的原白）：扣加工後遮罩
    314,591 px（溝邊灰帶、窄溝整條不塗）、只扣模型原輸出 2,781 px、不扣（採用）554 px。"""
    strip, mar, groups, pairs = separators(g, seg=seg, bubble=bubble, frame_hv=frame, veto=veto)
    bub = cv2.dilate(bubble.astype(np.uint8), np.ones((SEP_BUB_DIL, SEP_BUB_DIL), np.uint8)) > 0 \
        if bubble.any() else np.zeros_like(strip)
    keep_strip = np.zeros_like(strip)
    dropped = []
    for q in pairs:
        if not q.get("acc"):
            continue
        m = q["mask"]; tot = int(m.sum())
        sub = int((m & bub).sum()) if tot else 0
        if tot and sub > PAIR_SUB_MAX * tot:
            dropped.append((q["i"], q["j"], round(sub / tot, 3)))
            continue
        keep_strip |= m
    strip = strip & keep_strip
    sep_pre = strip | mar
    sep = sep_pre & ~bub
    if sep.any():
        n_, lb_, st_, _ = cv2.connectedComponentsWithStats(sep.astype(np.uint8), 8)
        small = np.nonzero(st_[:, cv2.CC_STAT_AREA] < SEP_MIN_CC)[0]
        small = small[small > 0]
        if len(small):
            sep &= ~np.isin(lb_, small)
    return dict(sep=sep, sep_pre=sep_pre, strip=strip, margin=mar, frame_arb=frame_raster(g, groups),
                groups=groups, pairs=pairs, dropped=dropped)
