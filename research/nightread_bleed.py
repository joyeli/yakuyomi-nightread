#!/usr/bin/env python3
"""nightread_bleed.py — 出血格過濾：留白帶裡「其實是出血格畫面」的塊拿掉。

出血格＝畫面直接延伸到頁邊、沒畫格框的格子。它的天空、地面、桌布和頁邊的白同一個白元件，留白路徑
（頁邊帶 ≤ 短邊×SAFE_GUTTER_DEPTH、沿格框線切開、texture_veto2 否決有線稿的塊）只剩線稿密度一道防線；
雲、地面線、桌緣這類稀疏線稿過得了密度門，於是在距格線 ~160px 內沿線稿被切成鋸齒黑塊（「撕口」）。
47 頁實測 215 塊撕口 → 83 塊（c362_012 天空／廣場、c371_007 桌布／地磚全數清掉）。

做法：留白帶（veto 之後）的每個 8 連通塊，看它的**外圈**（5px 橢圓膨脹、扣掉頁緣 4px）碰到什麼。
外圈逐像素歸一類，優先序由高到低：

    FR  框線（frame_line_mask ∪ 任意角度框線點陣，⊕ 9×9 方核）
    SP  分鏡溝／頁邊（SEP 扣泡之前的 sep_pre，⊕ 5×5 方核）
    BB  泡（⊕ 橢圓 r6）          ┐
    TX  文字筆畫（⊕ 橢圓 r6）    ├ 中性物：不算證據
    CH  人物（⊕ 橢圓 r8）        ┘
    VT  被 texture_veto2 挖掉的白（veto 前有、veto 後沒有）＝有線稿的白
    DC  同一個留白元件、但超出深度帶的白＝深入格內的白
    WO  其他白
    AR  非白（線稿、網點）

    inf       ＝ 外圈扣掉中性物（BB/TX/CH）後的比例
    frame_inf ＝ (FR+SP) / inf     ＝ 邊界有多少是格框／溝
    art_inf   ＝ (VT+DC+AR) / inf  ＝ 邊界有多少是畫

決策（依序，第一條成立就定）：

    ns = 0（整塊都在 SEP 裡）           留  allsep   SEP 之後照塗，拿掉也沒用
    面積 ≥ 3% 頁                         留  net      整片溝網
    與文字區（bbox ⊕10）重疊 ≥ 15%       留  text     字旁的留白（demo01 字旁黑塊的殘留就是這條）
    頁邊條檢驗通過（margin_strip）       留  margin   從頁緣走到一條近乎筆直的暗線、途中乾淨
    frame_inf ≥ 0.6                      留  framed   邊界多半是格框／溝
    不碰頁緣 ∧ FR+SP = 0 ∧ BB < 0.3      拿掉 island  畫中孤島：外圈完全沒碰到框線／溝／頁邊
    inf < 0.15                           留  enclosed 被人物／泡／字包住（證據不足）
    art_inf ≥ 0.45                       拿掉 art     邊界近半是畫
    其餘                                 留  weak

只拿掉、不新增；SEP（nightread_sep.py）在過濾之後照塗，過濾器拿不掉溝與頁邊。開關 `NIGHTREAD_BLEED`。

⚠️ Kotlin 移植的逐位元陷阱：
  1. 決策用的比例（txt、FR、SP、BB、inf、frame_inf、art_inf）先 `round(·, 3)` **再**比門檻——FR+SP == 0 其實是
     「兩者都 < 0.0005」。python `round` 對**精確的二進位值**做 half-to-even：Kotlin 要用
     `BigDecimal(double).setScale(3, RoundingMode.HALF_EVEN)`（不是 `BigDecimal.valueOf`，那走最短十進位字串，
     在恰好 .xxx5 的值上會不同）。
  2. `margin_strip` 的 depth 先 `round(·, 1)` 再比（整數的中位數只會是 .0／.5，這個捨入實際上不動值）；
     斜率／截距用中位數（`np.median`，偶數個取中間兩個平均）。
  3. 橢圓核是 `cv2.getStructuringElement(MORPH_ELLIPSE)` 的形狀（不是精確圓盤）。
  4. 頁邊條的 Theil–Sen 取樣點 `np.linspace(0, n−1, k).astype(int)`：numpy 算 i·step + 0（最後一點強制＝n−1）再截斷，
     Kotlin 要照同一式子，否則 i·step 落在 4.999… 的點會取到不同列。
  5. 塊的標號用 cv2 8 連通；決策只看塊本身，與標號順序無關。
"""
import cv2
import numpy as np

WHITE_TH = 235          # 「白」的灰階下限（同 nightread.WHITE_TH）

# ── 外圈與歸類 ───────────────────────────────────────────────────────────────
RING_R = 5              # 塊的外圈＝橢圓 r5 膨脹 − 塊本身
RING_EDGE = 4           # 外圈扣掉頁緣這麼多 px（頁緣外沒有證據，不能算成「沒碰到框線」）
PIECE_PAD = 8           # 逐塊運算的 bbox 外擴（≥ RING_R，外圈才不會被裁掉）
FR_DIL = 9              # FR：框線（水平垂直 ∪ 任意角度）方核外擴邊長
SP_DIL = 5              # SP：溝／頁邊（sep_pre）方核外擴邊長
BB_R = 6                # BB：泡 橢圓外擴半徑
TX_R = 6                # TX：文字筆畫 橢圓外擴半徑
CH_R = 8                # CH：人物 橢圓外擴半徑
TEXT_BOX_PAD = 10       # 文字區 bbox 外擴（txt 重疊比例用）
TOUCH_PX = 2            # 塊的 bbox 距頁緣 ≤ 此 px ＝ 碰頁緣

# ── 決策門檻（sb1；47 頁 veto 後共 417 塊：拿掉 248＝孤島 145＋畫 103，留 169）──────────────────
NET_FRAC = 0.03         # 面積 ≥ 此×頁 ＝ 溝網本體，留
TXT_KEEP = 0.15         # 與文字區重疊 ≥ 此，留
FRAME_KEEP = 0.6        # frame_inf ≥ 此，留（邊界多半是格框／溝）
ISLAND_BB_MAX = 0.3     # 孤島規則的泡比例上限：被泡圍住一半以上的不是孤島（泡外圈的白）
ENCLOSED_MAX = 0.15     # inf < 此 ＝ 被中性物包住、證據不足，留
ART_DROP = 0.45         # art_inf ≥ 此，拿掉（邊界近半是畫）

# ── 頁邊條檢驗（margin_strip）─────────────────────────────────────────────────
MARGIN_OK = 200         # 停點＝第一個比此暗的像素（或中性物）
MARGIN_FRAC = 0.12      # 最大深度＝短邊×此（同 nightread.SAFE_GUTTER_DEPTH）
MARGIN_LIGHT_MAX_FRAC = 0.02    # 途中亮而非白（200–234：淡網點／漸層／雲）≤ max(MARGIN_LIGHT_MIN, 此×深度)
MARGIN_LIGHT_MIN = 3
MARGIN_MIN_ROWS = 30    # 資訊列（停在暗點上的列）至少這麼多；停點直線的跨度也要 ≥ 此
MARGIN_NEU_LOOK = 8     # 停點往內這麼多 px 內碰到中性物也算停在中性物上（泡框／人物輪廓常在外擴遮罩外）
MARGIN_MIN_DEPTH = 4    # 頁邊深度（停點中位數）下限
MARGIN_COVER = 0.7      # 停點落在擬合直線 ±2px 內的列 ≥ 此比例
MARGIN_ANG = 5.0        # 停點直線與頁緣夾角上限（度）
MARGIN_DARK = 190       # 停點亮度中位數上限：是線不是漸層


def ell(r):
    return cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))


def margin_strip(m, g, neutral):
    """頁邊條檢驗：塊碰頁緣的每一邊，沿頁緣逐列從頁緣往內走。停點＝第一個「暗（g < MARGIN_OK）或中性物」像素；
    停點往內 MARGIN_NEU_LOOK px 內碰到中性物也算中性。停在中性物上的列不計（泡／人物蓋住框線是常態），
    停在暗點上的列＝資訊列，要求：深度 ≤ 短邊×MARGIN_FRAC、途中亮而非白夠少、停點沿頁緣近乎一直線
    （夾角 ≤ MARGIN_ANG、殘差 ≤ 2px 的列 ≥ MARGIN_COVER）、停點夠暗、資訊列與直線跨度 ≥ MARGIN_MIN_ROWS、
    深度 ≥ MARGIN_MIN_DEPTH。任一邊通過就回 True。"""
    H, W = g.shape; sh = min(H, W); maxd = int(MARGIN_FRAC * sh)
    for side in ("top", "bottom", "left", "right"):
        if side == "top": cont = m[0:3, :].any(axis=0); G = g; N = neutral
        elif side == "bottom": cont = m[H - 3:H, :].any(axis=0); G = g[::-1, :]; N = neutral[::-1, :]
        elif side == "left": cont = m[:, 0:3].any(axis=1); G = g.T; N = neutral.T
        else: cont = m[:, W - 3:W].any(axis=1); G = g[:, ::-1].T; N = neutral[:, ::-1].T
        ts = np.nonzero(cont)[0]
        if len(ts) < 12:
            continue
        sub = G[:maxd + 1, ts]; nsub = N[:maxd + 1, ts]
        stop = (sub < MARGIN_OK) | nsub
        anyb = stop.any(axis=0)
        d = np.where(anyb, np.argmax(stop, axis=0), maxd + 1)
        kk = np.arange(len(ts))
        dcl = np.clip(d, 0, maxd)
        # 停點是中性物本身、或中性物的外框（停點往前 MARGIN_NEU_LOOK px 內碰到中性區）
        look = np.zeros(len(ts), bool)
        for q in range(0, MARGIN_NEU_LOOK + 1):
            look |= nsub[np.clip(dcl + q, 0, maxd), kk]
        neut = anyb & look
        light = (sub >= MARGIN_OK) & (sub < WHITE_TH) & ~nsub
        lc = np.cumsum(light, axis=0)
        nl = np.where(d > 0, lc[np.clip(d - 1, 0, maxd), kk], 0)
        clean = nl <= np.maximum(MARGIN_LIGHT_MIN, MARGIN_LIGHT_MAX_FRAC * d)
        info_rows = anyb & ~neut & (d <= maxd)
        n_inf = int(info_rows.sum())
        cand = info_rows & clean
        if n_inf < MARGIN_MIN_ROWS or cand.sum() < 12:
            continue
        tt = ts[cand].astype(float); dd = d[cand].astype(float)
        # Theil–Sen 斜率：最多 60 個等距取樣點兩兩斜率（沿頁緣相距 ≥ 8 列）的中位數
        idx = np.linspace(0, len(tt) - 1, min(len(tt), 60)).astype(int)
        sl = [(dd[b] - dd[a]) / (tt[b] - tt[a]) for i, a in enumerate(idx) for b in idx[i + 1:] if tt[b] - tt[a] >= 8]
        if not sl:
            continue
        s_ = float(np.median(sl)); c_ = float(np.median(dd - s_ * tt))
        res = np.abs(dd - (s_ * tt + c_)); inl = res <= 2.0
        cover = float(inl.sum()) / n_inf
        stopv = sub[np.clip(d[cand][inl], 0, maxd), np.nonzero(cand)[0][inl]]
        dark = float(np.median(stopv)) if inl.any() else 255
        ang = float(np.degrees(np.arctan(abs(s_))))
        span = float(tt[inl].max() - tt[inl].min() + 1) if inl.any() else 0
        depth = round(float(np.median(dd[inl])), 1) if inl.any() else -1
        if (cover >= MARGIN_COVER and ang <= MARGIN_ANG and dark <= MARGIN_DARK and span >= MARGIN_MIN_ROWS
                and depth >= MARGIN_MIN_DEPTH):
            return True
    return False


def piece_features(g, band, band0, gutter, sep, sep_pre, frame_arb, frame_hv, bubble, seg, charmask, regions):
    """留白帶（band＝veto 後）的逐塊特徵。回傳 (塊標號圖, [特徵 dict])。"""
    H, W = g.shape
    FR = cv2.dilate((frame_hv | frame_arb).astype(np.uint8), np.ones((FR_DIL, FR_DIL), np.uint8)) > 0
    SP = cv2.dilate(sep_pre.astype(np.uint8), np.ones((SP_DIL, SP_DIL), np.uint8)) > 0
    BB = cv2.dilate(bubble.astype(np.uint8), ell(BB_R)) > 0
    TX = cv2.dilate(seg.astype(np.uint8), ell(TX_R)) > 0
    CH = cv2.dilate(charmask.astype(np.uint8), ell(CH_R)) > 0
    VT = band0 & ~band                      # 被 texture_veto2 挖掉的白
    DC = gutter & ~band0                    # 同留白元件、超出深度帶的白
    WO = (g >= WHITE_TH) & ~gutter          # 其他白
    cat = np.full((H, W), 8, np.uint8)      # 8 = AR（非白線稿）；由低優先往高優先蓋
    for k, M in reversed(list(enumerate([FR, SP, BB, TX, CH, VT, DC, WO]))):
        cat[M] = k
    NEU = BB | TX | CH
    names = ["FR", "SP", "BB", "TX", "CH", "VT", "DC", "WO", "AR"]
    tb = np.zeros((H, W), bool)
    q = TEXT_BOX_PAD
    for r_ in (regions or []):
        x0, y0, x1, y1 = [int(v) for v in r_["bbox"]]
        tb[max(0, y0 - q):min(H, y1 + q + 1), max(0, x0 - q):min(W, x1 + q + 1)] = True
    n, lb, st, _ = cv2.connectedComponentsWithStats(band.astype(np.uint8), 8)
    t = TOUCH_PX
    out = []
    for i in range(1, n):
        a = int(st[i, cv2.CC_STAT_AREA]); x, y, w, h = [int(v) for v in st[i, :4]]
        touch = x <= t or y <= t or x + w >= W - t or y + h >= H - t
        y0, y1, x0, x1 = max(0, y - PIECE_PAD), min(H, y + h + PIECE_PAD), max(0, x - PIECE_PAD), min(W, x + w + PIECE_PAD)
        mm = lb[y0:y1, x0:x1] == i
        margin = False
        if touch:                            # 碰頁緣的塊才做頁邊條檢驗
            full = np.zeros((H, W), bool); full[y0:y1, x0:x1] = mm
            margin = margin_strip(full, g, NEU)
        ns = int((mm & ~sep[y0:y1, x0:x1]).sum())
        ring = (cv2.dilate(mm.astype(np.uint8), ell(RING_R)) > 0) & ~mm
        e = RING_EDGE
        if y0 < e: ring[:e - y0, :] = False
        if x0 < e: ring[:, :e - x0] = False
        if y1 > H - e: ring[ring.shape[0] - (y1 - (H - e)):, :] = False
        if x1 > W - e: ring[:, ring.shape[1] - (x1 - (W - e)):] = False
        rn = max(int(ring.sum()), 1)
        cnt = np.bincount(cat[y0:y1, x0:x1][ring], minlength=9)
        fr = {nm: round(float(c) / rn, 3) for nm, c in zip(names, cnt)}      # ⚠️KT 陷阱 1：先捨入再比
        inf = rn - cnt[2] - cnt[3] - cnt[4]
        fr["inf"] = round(float(inf) / rn, 3)
        fr["frame_inf"] = round(float(cnt[0] + cnt[1]) / max(inf, 1), 3)
        fr["art_inf"] = round(float(cnt[5] + cnt[6] + cnt[8]) / max(inf, 1), 3)
        txt = float((mm & tb[y0:y1, x0:x1]).sum()) / a
        out.append(dict(i=i, area=a, ns=ns, bbox=[x, y, w, h], touch=touch, txt=round(txt, 3),
                        net=a >= NET_FRAC * H * W, margin=margin, **fr))
    return lb, out


def decide(p):
    """單塊決策 → (留｜拿掉, 理由)。見模組說明的決策表。"""
    if p["ns"] < 1: return "keep", "allsep"
    if p["net"]: return "keep", "net"
    if p["txt"] >= TXT_KEEP: return "keep", "text"
    if p["margin"]: return "keep", "margin"
    if p["frame_inf"] >= FRAME_KEEP: return "keep", "framed"
    if not p["touch"] and p["FR"] + p["SP"] == 0 and p["BB"] < ISLAND_BB_MAX: return "drop", "island"
    if p["inf"] < ENCLOSED_MAX: return "keep", "enclosed"
    if p["art_inf"] >= ART_DROP: return "drop", "art"
    return "keep", "weak"


def bleed_filter(band, band0, g, frame, seg, bubble, charmask, regions, gutter, sep_layer=None):
    """留白帶 band（texture_veto2 之後）拿掉出血格畫面塊。band0＝veto 之前、gutter＝compose 收到的留白元件。
    sep_layer＝nightread_sep.build_sep 的輸出（None＝SEP 關：溝／頁邊／任意角度框線證據一律空）。
    回傳 (新的 band, 逐塊特徵＋決策)。"""
    z = np.zeros_like(band)
    sep = sep_layer["sep"] if sep_layer is not None else z
    sep_pre = sep_layer["sep_pre"] if sep_layer is not None else z
    frame_arb = sep_layer["frame_arb"] if sep_layer is not None else z
    frame_hv = (np.asarray(frame) > 0) if frame is not None else z
    lb, pcs = piece_features(g, band, band0, gutter, sep, sep_pre, frame_arb, frame_hv,
                             np.asarray(bubble).astype(bool), np.asarray(seg).astype(bool),
                             np.asarray(charmask).astype(bool), regions)
    drop = np.zeros(len(pcs) + 1, bool)
    for p in pcs:
        d, why = decide(p)
        p["why"] = ("DROP-" if d == "drop" else "") + why
        drop[p["i"]] = d == "drop"
    return band & ~drop[lb], pcs
