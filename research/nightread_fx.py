#!/usr/bin/env python3
"""nightread_fx.py — 「更多」效果線（2026-10-03 使用者裁定 2 A：集中線／速度線／「井」字紋不算物件），規則版本 3。

只給 nightread_obj.py（「更多」背景物件規則）用；「標準」不經過這裡。整頁一次（nightread_obj.context 呼叫 field）：
  1. 細暗線（ink 扣 X3）細化成骨架（Zhang–Suen，兩個子步驟各自先算全部刪除點再刪）；穿越數 ≥ 3 的骨架點＝交叉點，
     去掉交叉點的 3×3 外擴後 8 連通取分支；直的分支（PCA 垂距均方根 ≤ SEG_SD）→ 共線串接（union-find）成「線」。
  2. 長線（≥ LMIN·sH）找線族：貪婪地取「最多線長經過同一點」的匯聚點（最長 VP_TOP 條線的兩兩交點當候選），拿掉成員再找。
  3. 效果線族＝成員 ≥ NMIN、繞匯聚點的角展 ≥ SPREAD°、外粗內細（漸細比中位 ≥ TAPER、> 1.3 的成員佔 ≥ TAPER_FRAC）、
     自由端（線尾消失在紙白裡）≥ FREE_MIN 且 ≥ FREE_FRAC。平行族（角展 < 30°：牆板豎線、欄杆）這一輪整類不收。
  4. 族的地盤＝成員線（1 px，cv2.line）橢圓外擴 TERR·sH；地盤內方向與族一致（結構張量線方向對匯聚點夾角 ≤ ATOL°、一致度
     ≥ COH、離匯聚點 ≥ VP_NEAR）的墨＋成員線 LINE_R px 內的墨＝效果墨；地盤內的小記號（墨連通塊長邊 ≤ MARK·sH）也算。
回傳 dict（沒有效果線族時 None）。區的判定、否決的例外與塗法在 nightread_obj（veto_blocks、region_rows、light_fill）。

原型＝research/out/more_v3/fx/src/nightread_fx.py（gitignore；FX.md）。**確定性寫法（Kotlin `EffectLines.kt` 逐位元照這份）**：
  - 不用三角函數做決定：分支／線的主軸方向用二倍角向量的半角公式（只用 sqrt）；角展用「最大角間隙 > 330°」等價的外積／
    內積判斷（排序用菱形角）；結構張量的方向門檻用二倍角內積比 cos 20°；常數 cos 值寫成字面值（兩邊同一個 double）。
  - 加總固定順序（逐項依序加）；PCA 動差用整數和（座標減第一點）；hypot 改 sqrt(dx²+dy²)。
  - 結構張量：blackhat（Q16 整數）→ 整數高斯 σ1（兩趟，每趟四捨五入回 Q16）→ 3×3 Sobel（整數）→ 平方／乘積右移 16
    （四捨五入）→ 整數高斯 σ4（同上）；只在地盤外接框外擴 21 px 的窗裡算（窗內地盤上的值與整頁算相同）。
  - 自由端的灰階＝整數高斯 σ1（Q16）四捨五入到整數（原型 cv2.GaussianBlur uint8）。
與原型（浮點）的差見 docs/DECISIONS.md「「更多」效果線與閃光」。
"""
import math

import cv2
import numpy as np

SEG_SD = 1.2            # 直分支：骨架點到主軸的均方根距離上限（px）
SEG_MIN = 8             # 分支至少幾個骨架點
COS_ANG = 0.9945218953682733    # 串接：方向差上限 cos 6°
PERP = 2.5              # 串接：互相垂距上限（px）
GAP = 20.0              # 串接：沿線間隙上限（px）
LMIN = 30.0             # 長線（px，×sH）
COS_VP = 0.9986295347545738     # 線族：線方向與「中點→匯聚點」夾角上限 cos 3°
VP_TOP = 120            # 候選交點只用最長的這麼多條線
NFAM = 4                # 最多找幾族
NMIN = 8                # 效果線族成員下限
COS_SPREAD = 0.8660254037844387     # 角展下限 30°（cos 30°）
TAPER = 1.3             # 漸細比（外 40% ÷ 內 40% 帶內墨量）中位數下限
TAPER_FRAC = 0.45       # 漸細比 > 1.3 的成員佔比下限
FREE_MIN = 2            # 自由端至少幾個
FREE_FRAC = 0.1         # 自由端 ÷（自由＋擋住）下限
RAY0, RAY1 = 4, 16      # 自由端：沿線往外看 t＝RAY0..RAY1 px
RAY_G = 215             # 自由端：σ1 灰階 < 此＝擋住（255 − 40）
TERR = 48               # 地盤：成員線外擴（px，×sH）
COS_ATOL2 = 0.9396926207859084      # 效果墨：線方向對匯聚點夾角 ≤ 10°（二倍角 cos 20°）
COH = 0.5               # 效果墨：結構張量一致度下限
EPS_J = 0.065536        # 一致度分母的 1e−6（灰階² 單位）換到張量的單位（×65536）
SI = 4.0                # 結構張量積分 σ
VP_NEAR = 24            # 離匯聚點這麼近的墨方向不可靠，不算效果墨
MARK = 16               # 小記號：墨連通塊外接框長邊 ≤ 此（px，×sH）
LINE_R = 3              # 成員線外擴這麼多 px 內的墨算效果墨
MARGIN = 21             # 結構張量窗：σ1 半徑 4 ＋ Sobel 1 ＋ σ4 半徑 16
Q = 65536


def _gauss_w(sigma):
    """同 nightread_obj.gauss_w（避免循環 import）。"""
    ks = int(round(sigma * 8 + 1)) | 1
    c = ks // 2
    k = [math.exp(-((i - c) * (i - c)) / (2.0 * sigma * sigma)) for i in range(ks)]
    t = 0.0
    for v in k:
        t += v
    w = [int(math.floor(v / t * Q + 0.5)) for v in k]
    w[c] += Q - sum(w)
    return w


# ── Zhang–Suen 細化 ─────────────────────────────────────────────────────────

def thin(m):
    """Zhang–Suen（逐步平行）：每個子步驟先算全部刪除點再刪，一整輪沒刪就停；影像外當 0。"""
    img = np.pad(m.astype(np.uint8), 1)
    while True:
        changed = False
        for step in (0, 1):
            P = img
            p2 = P[:-2, 1:-1]; p3 = P[:-2, 2:]; p4 = P[1:-1, 2:]; p5 = P[2:, 2:]
            p6 = P[2:, 1:-1]; p7 = P[2:, :-2]; p8 = P[1:-1, :-2]; p9 = P[:-2, :-2]
            c = P[1:-1, 1:-1]
            B = p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9
            seq = [p2, p3, p4, p5, p6, p7, p8, p9, p2]
            A = np.zeros_like(c)
            for k in range(8):
                A += ((seq[k] == 0) & (seq[k + 1] == 1)).astype(np.uint8)
            if step == 0:
                cond = (p2 * p4 * p6 == 0) & (p4 * p6 * p8 == 0)
            else:
                cond = (p2 * p4 * p8 == 0) & (p2 * p6 * p8 == 0)
            rm = (c == 1) & (B >= 2) & (B <= 6) & (A == 1) & cond
            if rm.any():
                c[rm] = 0
                changed = True
        if not changed:
            break
    return img[1:-1, 1:-1] > 0


def crossings(sk):
    """骨架點的穿越數（8 鄰環 p2..p9 上 0→1 的次數）：≥ 3＝真交叉點（階梯點鄰點數 3 但穿越數 2，不算）。"""
    P = np.pad(sk.astype(np.uint8), 1)
    seq = [P[:-2, 1:-1], P[:-2, 2:], P[1:-1, 2:], P[2:, 2:], P[2:, 1:-1], P[2:, :-2], P[1:-1, :-2], P[:-2, :-2]]
    A = np.zeros(sk.shape, np.uint8)
    for k in range(8):
        A += ((seq[k] == 0) & (seq[(k + 1) % 8] == 1)).astype(np.uint8)
    return A


def half_dir(d, b):
    """二倍角向量 (d, b)（∝ (cos 2θ, sin 2θ)）→ 單位方向 (cos θ, sin θ)，θ ∈ (−π/2, π/2]（＝½·atan2(b, d)）；只用 sqrt。
    零向量 → (1, 0)。"""
    r = math.sqrt(d * d + b * b)
    if r == 0.0:
        return 1.0, 0.0
    if d >= 0.0:
        ux = math.sqrt((r + d) / (2.0 * r))
        uy = b / (2.0 * r * ux)
    else:
        uy0 = math.sqrt((r - d) / (2.0 * r))
        uy = uy0 if b >= 0.0 else -uy0
        ux = abs(b) / (2.0 * r * uy0)
    return ux, uy


def _raster_labels(m):
    """8 連通標號，照掃描首見順序（＝Kotlin Cv.ccStats）。"""
    n, lb = cv2.connectedComponents(m.astype(np.uint8), connectivity=8)
    flat = lb.ravel()
    nz = np.flatnonzero(flat)
    if nz.size == 0:
        return 1, lb
    u, first = np.unique(flat[nz], return_index=True)
    order = np.argsort(first, kind="stable")
    remap = np.zeros(n, np.int32)
    remap[u[order]] = np.arange(1, len(u) + 1, dtype=np.int32)
    return len(u) + 1, remap[lb]


def segments(inkx):
    """骨架 → 去交叉點 → 分支（掃描首見序）→ 直的分支。回傳 list[(ax, ay, bx, by, sd)]。
    動差：整數和（座標減分支第一點），平均 S/n、共變異 Sxx/n − mx²（double）；主軸＝half_dir(cxx − cyy, 2cxy)；
    sd＝√max(λ₂, 0)、λ₂＝tr/2 − √max(tr²/4 − det, 0)；端點＝中心 ± 投影極值。"""
    sk = thin(inkx)
    junc = sk & (crossings(sk) >= 3)
    jd = cv2.dilate(junc.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
    br = sk & ~jd
    n, lb = _raster_labels(br)
    ys, xs = np.nonzero(lb)
    ids = lb[ys, xs]
    o = np.argsort(ids, kind="stable")
    ys, xs, ids = ys[o].astype(np.int64), xs[o].astype(np.int64), ids[o]
    bd = np.searchsorted(ids, np.arange(n + 1))
    out = []
    for i in range(1, n):
        a, b = int(bd[i]), int(bd[i + 1])
        cnt = b - a
        if cnt < SEG_MIN:
            continue
        x0, y0 = int(xs[a]), int(ys[a])
        lx = xs[a:b] - x0
        ly = ys[a:b] - y0
        sx, sy = int(lx.sum()), int(ly.sum())
        sxx, syy, sxy = int((lx * lx).sum()), int((ly * ly).sum()), int((lx * ly).sum())
        nf = float(cnt)
        mx = sx / nf
        my = sy / nf
        cxx = sxx / nf - mx * mx
        cyy = syy / nf - my * my
        cxy = sxy / nf - mx * my
        tr = cxx + cyy
        det = cxx * cyy - cxy * cxy
        l2 = tr / 2.0 - math.sqrt(max(tr * tr / 4.0 - det, 0.0))
        sd = math.sqrt(max(l2, 0.0))
        if sd > SEG_SD:
            continue
        ux, uy = half_dir(cxx - cyy, 2.0 * cxy)
        t = (lx.astype(np.float64) - mx) * ux + (ly.astype(np.float64) - my) * uy
        tmin, tmax = float(t.min()), float(t.max())
        cx, cy = x0 + mx, y0 + my
        out.append((cx + tmin * ux, cy + tmin * uy, cx + tmax * ux, cy + tmax * uy, sd))
    return out


def chain(segs):
    """共線串接（union-find：根＝集合最小索引，與處理順序無關）。回傳線清單（a, b 端點、u 單位方向、len），依集合最小索引排序。"""
    n = len(segs)
    if n == 0:
        return []
    S = np.array(segs, np.float64).reshape(-1, 5)
    p0x, p0y, p1x, p1y = S[:, 0], S[:, 1], S[:, 2], S[:, 3]
    dx = p1x - p0x
    dy = p1y - p0y
    L = np.sqrt(dx * dx + dy * dy) + 1e-9
    ux = dx / L
    uy = dy / L
    mx = (p0x + p1x) / 2
    my = (p0y + p1y) / 2
    par = list(range(n))

    def f(a):
        while par[a] != a:
            par[a] = par[par[a]]
            a = par[a]
        return a
    for i in range(n):
        ex = mx[i + 1:] - mx[i]
        ey = my[i + 1:] - my[i]
        dm = np.sqrt(ex * ex + ey * ey)
        cand = np.nonzero(dm <= (L[i + 1:] + L[i]) / 2 + GAP)[0] + i + 1
        for j in cand:
            j = int(j)
            if abs(ux[i] * ux[j] + uy[i] * uy[j]) < COS_ANG:
                continue
            pp = max(abs(-(p0x[j] - p0x[i]) * uy[i] + (p0y[j] - p0y[i]) * ux[i]),
                     abs(-(p1x[j] - p0x[i]) * uy[i] + (p1y[j] - p0y[i]) * ux[i]),
                     abs(-(p0x[i] - p0x[j]) * uy[j] + (p0y[i] - p0y[j]) * ux[j]),
                     abs(-(p1x[i] - p0x[j]) * uy[j] + (p1y[i] - p0y[j]) * ux[j]))
            if pp > PERP:
                continue
            tj0 = (p0x[j] - p0x[i]) * ux[i] + (p0y[j] - p0y[i]) * uy[i]
            tj1 = (p1x[j] - p0x[i]) * ux[i] + (p1y[j] - p0y[i]) * uy[i]
            gap = max(min(tj0, tj1) - L[i], -max(tj0, tj1))
            if gap > GAP:
                continue
            a_, b_ = f(i), f(j)
            if a_ != b_:
                par[max(a_, b_)] = min(a_, b_)
    groups = {}
    for i in range(n):
        groups.setdefault(f(i), []).append(i)
    lines = []
    for k in sorted(groups):
        mem = groups[k]
        pts = [(float(p0x[q]), float(p0y[q])) for q in mem] + [(float(p1x[q]), float(p1y[q])) for q in mem]
        sx = sy = 0.0
        for px, py in pts:
            sx += px
            sy += py
        cx = sx / len(pts)
        cy = sy / len(pts)
        zr = zi = 0.0
        for q in mem:
            w = float(L[q])
            zr += w * (float(ux[q]) * float(ux[q]) - float(uy[q]) * float(uy[q]))
            zi += w * (2.0 * float(ux[q]) * float(uy[q]))
        vx, vy = half_dir(zr, zi)
        tmin = tmax = None
        for px, py in pts:
            t = (px - cx) * vx + (py - cy) * vy
            if tmin is None or t < tmin:
                tmin = t
            if tmax is None or t > tmax:
                tmax = t
        lines.append(dict(a=(cx + tmin * vx, cy + tmin * vy), b=(cx + tmax * vx, cy + tmax * vy), u=(vx, vy),
                          len=tmax - tmin))
    return lines


def _pseudo_angle(x, y):
    """菱形角 ∈ [0, 4)：與 atan2(y, x) mod 2π 同序（單調），只用除法。零向量 → 0。"""
    if x == 0.0 and y == 0.0:
        return 0.0
    if y >= 0.0:
        return y / (x + y) if x >= 0.0 else 1.0 - x / (-x + y)
    return 2.0 - y / (-x - y) if x < 0.0 else 3.0 + x / (x - y)


def spread_ok(vx, vy):
    """角展 ≥ 30° ⟺ 依角度排序後沒有一個間隙 > 330°（＝剩下的弧 < 30°）。剩下的弧＝從間隙終點逆向轉到起點：外積 ≥ 0
    且內積 > cos30·|a||b|（非環繞的間隙要求外積 > 0：方向相同的間隙是 0 不是 360°）。成員 < 2 ⇒ 角展 0。"""
    n = len(vx)
    if n < 2:
        return False
    o = sorted(range(n), key=lambda k: _pseudo_angle(vx[k], vy[k]))
    for k in range(n):
        a, b = o[(k + 1) % n], o[k]        # 間隙 o[k] → o[k+1]；剩下的弧＝a 逆時針到 b
        ax, ay, bx, by = vx[a], vy[a], vx[b], vy[b]
        cr = ax * by - ay * bx
        dt = ax * bx + ay * by
        na = math.sqrt(ax * ax + ay * ay)
        nb = math.sqrt(bx * bx + by * by)
        small = dt > COS_SPREAD * na * nb and (cr >= 0.0 if k == n - 1 else cr > 0.0)
        if small:
            return False
    return True


def families(lines):
    """貪婪找匯聚點：每輪在還活著的線裡取最長 VP_TOP 條（穩定排序），兩兩交點當候選，分數＝方向與（中點→P）夾角 ≤ 3° 的線長和
    （依索引逐項加）；取最大者（同分取先到的），成員拿掉再找（最多 NFAM 族）。"""
    if len(lines) < 2:
        return []
    mx = np.array([(l["a"][0] + l["b"][0]) / 2 for l in lines], np.float64)
    my = np.array([(l["a"][1] + l["b"][1]) / 2 for l in lines], np.float64)
    ux = np.array([l["u"][0] for l in lines], np.float64)
    uy = np.array([l["u"][1] for l in lines], np.float64)
    wl = np.array([l["len"] for l in lines], np.float64)
    alive = np.ones(len(lines), bool)
    fams = []
    for _ in range(NFAM):
        idx = np.nonzero(alive)[0]
        if len(idx) < 2:
            break
        m0, m1, u0, u1, w = mx[idx], my[idx], ux[idx], uy[idx], wl[idx]
        order = np.argsort(-w, kind="stable")[:VP_TOP]
        best_s, best_P, best_ok = 0.0, None, None
        for ii in range(len(order)):
            i = int(order[ii])
            for jj in range(ii + 1, len(order)):
                j = int(order[jj])
                d = u0[i] * u1[j] - u1[i] * u0[j]
                if abs(d) < 1e-3:
                    continue
                t = ((m0[j] - m0[i]) * u1[j] - (m1[j] - m1[i]) * u0[j]) / d
                Px = m0[i] + t * u0[i]
                Py = m1[i] + t * u1[i]
                dvx = Px - m0
                dvy = Py - m1
                dn = np.sqrt(dvx * dvx + dvy * dvy) + 1e-9
                ok = np.abs((dvx * u0 + dvy * u1) / dn) >= COS_VP
                ws = w[ok]
                s = float(np.cumsum(ws)[-1]) if ws.size else 0.0
                if s > best_s:
                    best_s, best_P, best_ok = s, (float(Px), float(Py)), ok
        if best_P is None:
            break
        mem = idx[best_ok]
        vx = [float(mx[k] - best_P[0]) for k in mem]
        vy = [float(my[k] - best_P[1]) for k in mem]
        ang = np.sort(np.mod(np.arctan2(vy, vx), 2 * np.pi))       # 只給紀錄看（決定用 spread_ok）
        spread = float(np.rad2deg(2 * np.pi - np.diff(np.concatenate([ang, ang[:1] + 2 * np.pi])).max())) if len(ang) > 1 else 0.0
        fams.append(dict(P=best_P, mem=[int(k) for k in mem], n=int(len(mem)), spread_ok=spread_ok(vx, vy), spread=spread,
                         len=best_s))
        alive[mem] = False
    return fams


def _band_profile(dk, a, b, hw=4):
    """a→b 每 1 px 取樣、垂直方向 ±hw px 的 dk 和（座標 round-half-even、夾進影像）。"""
    ex, ey = b[0] - a[0], b[1] - a[1]
    ln = math.sqrt(ex * ex + ey * ey)
    L = int(ln)
    if L < 2:
        return np.zeros(0, np.int64)
    ux, uy = ex / ln, ey / ln
    nx, ny = -uy, ux
    H, W = dk.shape
    ts = np.arange(L + 1, dtype=np.float64)
    prof = np.zeros(L + 1, np.int64)
    for o in range(-hw, hw + 1):
        px = (a[0] + ts * ux) + o * nx
        py = (a[1] + ts * uy) + o * ny
        xs = np.clip(np.rint(px).astype(np.int64), 0, W - 1)
        ys = np.clip(np.rint(py).astype(np.int64), 0, H - 1)
        prof += dk[ys, xs]
    return prof


def taper(lines, fam, dk):
    """成員線外 40%（離匯聚點遠的那端）與內 40% 的帶內墨量比。回傳 (中位數, > 1.3 的佔比)。"""
    Px, Py = fam["P"]
    rat = []
    for k in fam["mem"]:
        a, b = lines[k]["a"], lines[k]["b"]
        da = (a[0] - Px) * (a[0] - Px) + (a[1] - Py) * (a[1] - Py)
        db = (b[0] - Px) * (b[0] - Px) + (b[1] - Py) * (b[1] - Py)
        if da < db:
            a, b = b, a
        prof = _band_profile(dk, a, b)
        n = len(prof)
        if n < 20:
            continue
        no, ni = int(0.4 * n), int(0.6 * n)
        o = float(int(prof[:no].sum())) / no
        i = float(int(prof[ni:].sum())) / (n - ni)
        rat.append(o / max(1.0, i))
    if not rat:
        return 0.0, 0.0
    r = sorted(rat)
    m = len(r)
    med = r[m // 2] if m % 2 else (r[m // 2 - 1] + r[m // 2]) / 2.0
    return med, sum(1 for v in rat if v > 1.3) / m


def free_ends(lines, fam, gq1, X3, smd):
    """成員線兩端沿線往外 t＝RAY0..RAY1 px、左右偏 −1／0／+1（最近鄰 floor(v+0.5)）：碰到 X3 或出界＝不明（不計）；任一點
    σ1 灰階 < RAY_G（Q16 四捨五入）且不在小記號外擴 2 內＝擋住；否則＝自由。回傳 (自由端數, 自由＋擋住數)。"""
    H, W = X3.shape
    lim = RAY_G * Q - 32768
    fr = tot = 0
    for k in fam["mem"]:
        l = lines[k]
        ux, uy = l["u"]
        for (px, py), (vx, vy) in ((l["a"], (-ux, -uy)), (l["b"], (ux, uy))):
            unk = blk = False
            for t in range(RAY0, RAY1 + 1):
                for o in (-1, 0, 1):
                    xi = int(math.floor(px + t * vx - o * vy + 0.5))
                    yi = int(math.floor(py + t * vy + o * vx + 0.5))
                    if xi < 0 or yi < 0 or xi >= W or yi >= H or X3[yi, xi]:
                        unk = True
                        break
                    if gq1[yi, xi] < lim and not smd[yi, xi]:
                        blk = True
                if unk:
                    break
            if unk:
                continue
            tot += 1
            fr += int(not blk)
    return fr, tot


# ── 結構張量（整數）─────────────────────────────────────────────────────────

def _conv_q(a, sigma):
    """int64 影像的整數高斯（REFLECT_101）：先橫後直，每趟 Σ w·v 後四捨五入右移 16。"""
    w = _gauss_w(sigma)
    c = len(w) // 2
    H, W = a.shape
    p = np.pad(a, ((0, 0), (c, c)), mode="reflect")
    acc = np.zeros((H, W), np.int64)
    for j in range(len(w)):
        if w[j]:
            acc += w[j] * p[:, j:j + W]
    h1 = (acc + 32768) >> 16
    p = np.pad(h1, ((c, c), (0, 0)), mode="reflect")
    acc = np.zeros((H, W), np.int64)
    for j in range(len(w)):
        if w[j]:
            acc += w[j] * p[j:j + H, :]
    return (acc + 32768) >> 16


def tensor(bhm):
    """[bhm]＝窗裡的 blackhat（Q16，int64，小記號與非墨處為 0）→ (Jxx, Jyy, Jxy)（int64）。"""
    f = _conv_q(bhm, 1.0)
    p = np.pad(f, 1, mode="reflect")
    H, W = f.shape
    gx = (p[0:H, 2:W + 2] + 2 * p[1:H + 1, 2:W + 2] + p[2:H + 2, 2:W + 2]) - (p[0:H, 0:W] + 2 * p[1:H + 1, 0:W] + p[2:H + 2, 0:W])
    gy = (p[2:H + 2, 0:W] + 2 * p[2:H + 2, 1:W + 1] + p[2:H + 2, 2:W + 2]) - (p[0:H, 0:W] + 2 * p[0:H, 1:W + 1] + p[0:H, 2:W + 2])
    jxx = _conv_q((gx * gx + 32768) >> 16, SI)
    jyy = _conv_q((gy * gy + 32768) >> 16, SI)
    jxy = _conv_q((gx * gy + 32768) >> 16, SI)
    return jxx, jyy, jxy


def aligned_at(jxx, jyy, jxy, dx, dy):
    """結構張量（像素上的值）與「像素→匯聚點」(dx, dy)：一致度 ≥ COH、線方向夾角 ≤ ATOL°、距離 ≥ VP_NEAR。
    線方向的二倍角＝atan2(2Jxy, Jxx−Jyy)＋π；夾角條件 ⟺ 二倍角單位向量內積 ≥ cos 20°（不用三角函數）。"""
    dd = (jxx - jyy).astype(np.float64)
    b2 = 2.0 * jxy.astype(np.float64)
    nn = np.sqrt(dd * dd + b2 * b2)
    ss = (jxx + jyy).astype(np.float64)
    r2 = dx * dx + dy * dy
    q = -(dd * (dx * dx - dy * dy) + b2 * (2.0 * dx * dy))
    return (nn >= COH * (ss + EPS_J)) & (q >= COS_ATOL2 * nn * r2) & (r2 >= float(VP_NEAR * VP_NEAR))


# ── 整頁 ───────────────────────────────────────────────────────────────────

def field(g, ink, X3, bh, sH, gq1, info=None):
    """整頁效果線場。[ink]＝細暗線、[X3]＝交代過⊕3＋頁緣、[bh]＝σ2 blackhat（Q16）、[gq1]＝σ1 亮度（Q16）。
    沒有收下的效果線族 ⇒ None；有 ⇒ dict(aligned, marks, memline, terr, fams, lines)。"""
    H, W = g.shape
    inkx = ink & ~X3
    segs = segments(inkx)
    lines = [l for l in chain(segs) if l["len"] >= LMIN * sH]
    fams = families(lines)
    if info is not None:
        info["nsegs"] = len(segs)
        info["nlines"] = len(lines)
    if not fams:
        if info is not None:
            info["fams"] = []
        return None
    dk = (np.maximum(250 - g.astype(np.int64), 0) * (~X3)).astype(np.int64)
    n_, lb_, st_, _ = cv2.connectedComponentsWithStats(inkx.astype(np.uint8), connectivity=8)
    ext = np.maximum(st_[:, cv2.CC_STAT_WIDTH], st_[:, cv2.CC_STAT_HEIGHT])
    small = np.zeros(n_, bool)
    small[1:] = ext[1:] <= MARK * sH
    sm = small[lb_]
    smd = cv2.dilate(sm.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0
    acc = []
    for f in fams:
        tm, tf = taper(lines, f, dk)
        nf, nt = free_ends(lines, f, gq1, X3, smd)
        f.update(taper=tm, taper_frac=tf, nfree=nf, nends=nt)
        f["ok"] = (f["n"] >= NMIN and f["spread_ok"] and tm >= TAPER and tf >= TAPER_FRAC and nf >= FREE_MIN
                   and nf >= FREE_FRAC * nt)
        if f["ok"]:
            acc.append(f)
    if info is not None:
        info["fams"] = [dict(P=[round(f["P"][0], 1), round(f["P"][1], 1)], n=f["n"], spread=round(f["spread"], 1),
                             spread_ok=bool(f["spread_ok"]), taper=round(f["taper"], 3), taper_frac=round(f["taper_frac"], 3),
                             free=f"{f['nfree']}/{f['nends']}", ok=bool(f["ok"])) for f in fams]
    if not acc:
        return None
    r = max(1, int(round(TERR * sH)))
    ker = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1,) * 2)
    tms, Ts = [], []
    memline = np.zeros((H, W), np.uint8)
    terr = np.zeros((H, W), bool)
    for f in acc:
        tm_ = np.zeros((H, W), np.uint8)
        for i in f["mem"]:
            l = lines[i]
            cv2.line(tm_, (int(round(l["a"][0])), int(round(l["a"][1]))), (int(round(l["b"][0])), int(round(l["b"][1]))), 1, 1)
        memline |= tm_
        T = cv2.dilate(tm_, ker) > 0
        Ts.append(T)
        terr |= T
    # 結構張量窗：地盤外接框外擴 MARGIN（夾進頁面）
    ty, tx = np.nonzero(terr)
    y0, y1 = max(0, int(ty.min()) - MARGIN), min(H, int(ty.max()) + 1 + MARGIN)
    x0, x1 = max(0, int(tx.min()) - MARGIN), min(W, int(tx.max()) + 1 + MARGIN)
    cand = inkx & ~sm
    bhm = np.where(cand[y0:y1, x0:x1], bh[y0:y1, x0:x1], 0).astype(np.int64)
    jxx, jyy, jxy = tensor(bhm)
    ys, xs = np.nonzero(cand)
    aligned = np.zeros((H, W), bool)
    for f, T in zip(acc, Ts):
        sel = T[ys, xs]
        yk, xk = ys[sel], xs[sel]
        Px, Py = f["P"]
        dx = Px - xk.astype(np.float64)
        dy = Py - yk.astype(np.float64)
        ok = aligned_at(jxx[yk - y0, xk - x0], jyy[yk - y0, xk - x0], jxy[yk - y0, xk - x0], dx, dy)
        aligned[yk[ok], xk[ok]] = True
    near = cv2.dilate(memline, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * LINE_R + 1,) * 2)) > 0
    aligned |= inkx & near & ~sm
    marks = sm & terr
    if info is not None:
        info["nmark"] = int(marks.sum())
        info["naligned"] = int(aligned.sum())
    return dict(aligned=aligned, marks=marks, memline=memline > 0, terr=terr, fams=acc, lines=lines)
