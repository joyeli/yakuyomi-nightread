package li.joye.yakuyomi.nightread

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 「更多」效果線（2026-10-03 使用者裁定 2 A：集中線／速度線／「井」字紋不算物件，規則版本 3）的整頁效果線場＝研究端
 * `research/nightread_fx.py`，逐位元照它（同一套整數／固定順序寫法）。只給 [BgObjects] 用（「標準」不經過這裡）。
 *
 * 1. 細暗線（扣 X3）細化成骨架（Zhang–Suen）；穿越數 ≥ 3 的骨架點＝交叉點，去掉交叉點 3×3 外擴後 8 連通取分支（掃描首見序）；
 *    直的分支（PCA 垂距均方根 ≤ [EffectLineParams.segSd]）共線串接（union-find，根＝集合最小索引）成「線」。
 * 2. 長線找線族：貪婪地取「最多線長經過同一點」的匯聚點（最長 [EffectLineParams.vpTop] 條線的兩兩交點當候選）。
 * 3. 效果線族＝成員夠多、角展 ≥ 30°、外粗內細（漸細比）、有自由端。平行族這一輪整類不收。
 * 4. 族的地盤＝成員線（cv2.line）橢圓外擴；地盤內方向與族一致（結構張量）的墨、成員線旁的墨、地盤裡的小記號＝效果墨。
 *
 * 不用三角函數做決定（主軸＝二倍角向量的半角公式、角展＝外積／內積、方向門檻＝二倍角內積比 cos），加總固定順序，結構張量全整數
 * （Q16 四捨五入），所以與研究端（numpy）逐位元相同、也不隨裝置的 libm 變。
 */
internal object EffectLines {

    private const val Q = 65536

    /** 整頁效果線場（只有收下了效果線族才有）。遮罩是 [Ring.packBits] 的 1 bit/px。 */
    class Field(
        /** 效果墨（對齊的墨 ∪ 地盤裡的小記號）。 */
        val fxe: LongArray,
        /** 效果墨橢圓外擴 [EffectLineParams.excl]。 */
        val fxeD: LongArray,
        /** 效果墨橢圓外擴 [EffectLineParams.exclT]。 */
        val fxeT: LongArray,
        /** 地盤。 */
        val terr: LongArray,
        /** 成員線（1 px）。 */
        val memline: LongArray,
        /** 成員線像素（掃描序，遞增）與它們的 8 連通標號（1 起，數「碰到幾條成員線」用）。 */
        val memIdx: IntArray,
        val memLab: IntArray,
        /** 地盤的外接框 [terrX0, terrX1)×[terrY0, terrY1)。 */
        val terrX0: Int, val terrY0: Int, val terrX1: Int, val terrY1: Int,
    ) {
        /** 像素 [i] 是成員線就回傳它的標號，否則 0。 */
        fun memLabel(i: Int): Int {
            val k = java.util.Arrays.binarySearch(memIdx, i)
            return if (k >= 0) memLab[k] else 0
        }
    }

    internal class Line(val ax: Double, val ay: Double, val bx: Double, val by: Double, val ux: Double, val uy: Double, val len: Double)

    internal class Family(val px: Double, val py: Double, val mem: IntArray, val spreadOk: Boolean) {
        var taper = 0.0
        var taperFrac = 0.0
        var nFree = 0
        var nEnds = 0
        var ok = false
    }

    // ── Zhang–Suen 細化 ───────────────────────────────────────────────

    /** Zhang–Suen（逐步平行）＝研究端 `thin`：每個子步驟先算全部刪除點再刪，一整輪沒刪就停；影像外當 0。只掃前景像素。 */
    internal fun thin(m: Mask): Mask {
        val w = m.w
        val h = m.h
        val img = m.data.copyOf()
        var nfg = 0
        for (v in img) if (v) nfg++
        val fg = IntArray(nfg)
        run { var k = 0; for (i in img.indices) if (img[i]) fg[k++] = i }
        val rm = IntArray(nfg)
        fun px(x: Int, y: Int): Int = if (x < 0 || y < 0 || x >= w || y >= h || !img[y * w + x]) 0 else 1
        while (true) {
            var changed = false
            for (step in 0..1) {
                var nr = 0
                for (k in 0 until nfg) {
                    val i = fg[k]
                    val x = i % w
                    val y = i / w
                    val p2 = px(x, y - 1); val p3 = px(x + 1, y - 1); val p4 = px(x + 1, y); val p5 = px(x + 1, y + 1)
                    val p6 = px(x, y + 1); val p7 = px(x - 1, y + 1); val p8 = px(x - 1, y); val p9 = px(x - 1, y - 1)
                    val b = p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9
                    if (b < 2 || b > 6) continue
                    var a = 0
                    if (p2 == 0 && p3 == 1) a++
                    if (p3 == 0 && p4 == 1) a++
                    if (p4 == 0 && p5 == 1) a++
                    if (p5 == 0 && p6 == 1) a++
                    if (p6 == 0 && p7 == 1) a++
                    if (p7 == 0 && p8 == 1) a++
                    if (p8 == 0 && p9 == 1) a++
                    if (p9 == 0 && p2 == 1) a++
                    if (a != 1) continue
                    val cond = if (step == 0) p2 * p4 * p6 == 0 && p4 * p6 * p8 == 0 else p2 * p4 * p8 == 0 && p2 * p6 * p8 == 0
                    if (cond) rm[nr++] = i
                }
                if (nr > 0) {
                    for (j in 0 until nr) img[rm[j]] = false
                    changed = true
                    var k2 = 0
                    for (k in 0 until nfg) if (img[fg[k]]) fg[k2++] = fg[k]
                    nfg = k2
                }
            }
            if (!changed) break
        }
        return Mask(w, h, img)
    }

    /** 骨架點的穿越數（8 鄰環 p2..p9 上 0→1 的次數）＝研究端 `crossings`。 */
    private fun crossing(sk: BooleanArray, w: Int, h: Int, i: Int): Int {
        val x = i % w
        val y = i / w
        fun px(xx: Int, yy: Int): Boolean = xx >= 0 && yy >= 0 && xx < w && yy < h && sk[yy * w + xx]
        val r = booleanArrayOf(px(x, y - 1), px(x + 1, y - 1), px(x + 1, y), px(x + 1, y + 1), px(x, y + 1), px(x - 1, y + 1),
            px(x - 1, y), px(x - 1, y - 1))
        var a = 0
        for (k in 0 until 8) if (!r[k] && r[(k + 1) and 7]) a++
        return a
    }

    /** 二倍角向量 (d, b) → 單位方向 (cos θ, sin θ)，θ＝½·atan2(b, d) ∈ (−π/2, π/2]；只用 sqrt（研究端 `half_dir`）。零向量 → (1, 0)。 */
    internal fun halfDir(d: Double, b: Double): DoubleArray {
        val r = sqrt(d * d + b * b)
        if (r == 0.0) return doubleArrayOf(1.0, 0.0)
        return if (d >= 0.0) {
            val ux = sqrt((r + d) / (2.0 * r))
            doubleArrayOf(ux, b / (2.0 * r * ux))
        } else {
            val uy0 = sqrt((r - d) / (2.0 * r))
            val uy = if (b >= 0.0) uy0 else -uy0
            doubleArrayOf(abs(b) / (2.0 * r * uy0), uy)
        }
    }

    /**
     * 骨架 → 去交叉點 → 分支（掃描首見序，BFS）→ 直的分支＝研究端 `segments`。回傳每段 [ax, ay, bx, by, sd]。
     * 動差：整數和（座標減分支第一點），共變異 Sxx/n − mx²（double）；sd＝√max(λ₂, 0)。
     */
    internal fun segments(inkx: Mask, p: EffectLineParams): List<DoubleArray> {
        val w = inkx.w
        val h = inkx.h
        val sk = thin(inkx).data
        // 交叉點的 3×3 外擴
        val jd = BooleanArray(w * h)
        for (i in sk.indices) {
            if (!sk[i] || crossing(sk, w, h, i) < 3) continue
            val x = i % w
            val y = i / w
            for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) jd[yy * w + xx] = true
        }
        val br = BooleanArray(w * h) { sk[it] && !jd[it] }
        val out = ArrayList<DoubleArray>()
        var q = IntArray(256)
        for (s0 in br.indices) {
            if (!br[s0]) continue
            var qe = 0
            q[qe++] = s0
            br[s0] = false
            var qs = 0
            while (qs < qe) {
                val i = q[qs++]
                val x = i % w
                val y = i / w
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val j = yy * w + xx
                    if (br[j]) {
                        br[j] = false
                        if (qe == q.size) q = q.copyOf(q.size * 2)
                        q[qe++] = j
                    }
                }
            }
            if (qe < p.segMin) continue
            val x0 = s0 % w
            val y0 = s0 / w
            var sx = 0L; var sy = 0L; var sxx = 0L; var syy = 0L; var sxy = 0L
            for (k in 0 until qe) {
                val lx = (q[k] % w - x0).toLong()
                val ly = (q[k] / w - y0).toLong()
                sx += lx; sy += ly; sxx += lx * lx; syy += ly * ly; sxy += lx * ly
            }
            val nf = qe.toDouble()
            val mx = sx.toDouble() / nf
            val my = sy.toDouble() / nf
            val cxx = sxx.toDouble() / nf - mx * mx
            val cyy = syy.toDouble() / nf - my * my
            val cxy = sxy.toDouble() / nf - mx * my
            val tr = cxx + cyy
            val det = cxx * cyy - cxy * cxy
            val l2 = tr / 2.0 - sqrt(max(tr * tr / 4.0 - det, 0.0))
            val sd = sqrt(max(l2, 0.0))
            if (sd > p.segSd) continue
            val u = halfDir(cxx - cyy, 2.0 * cxy)
            var tmin = Double.POSITIVE_INFINITY
            var tmax = Double.NEGATIVE_INFINITY
            for (k in 0 until qe) {
                val lx = (q[k] % w - x0).toDouble()
                val ly = (q[k] / w - y0).toDouble()
                val t = (lx - mx) * u[0] + (ly - my) * u[1]
                if (t < tmin) tmin = t
                if (t > tmax) tmax = t
            }
            val cx = x0 + mx
            val cy = y0 + my
            out.add(doubleArrayOf(cx + tmin * u[0], cy + tmin * u[1], cx + tmax * u[0], cy + tmax * u[1], sd))
        }
        return out
    }

    /** 共線串接＝研究端 `chain`（union-find：根＝集合最小索引，結果與處理順序無關；線依集合最小索引排序）。 */
    internal fun chain(segs: List<DoubleArray>, p: EffectLineParams): List<Line> {
        val n = segs.size
        if (n == 0) return emptyList()
        val p0x = DoubleArray(n) { segs[it][0] }
        val p0y = DoubleArray(n) { segs[it][1] }
        val p1x = DoubleArray(n) { segs[it][2] }
        val p1y = DoubleArray(n) { segs[it][3] }
        val ll = DoubleArray(n)
        val ux = DoubleArray(n)
        val uy = DoubleArray(n)
        val mx = DoubleArray(n)
        val my = DoubleArray(n)
        for (i in 0 until n) {
            val dx = p1x[i] - p0x[i]
            val dy = p1y[i] - p0y[i]
            ll[i] = sqrt(dx * dx + dy * dy) + 1e-9
            ux[i] = dx / ll[i]
            uy[i] = dy / ll[i]
            mx[i] = (p0x[i] + p1x[i]) / 2
            my[i] = (p0y[i] + p1y[i]) / 2
        }
        val par = IntArray(n) { it }
        fun f(a0: Int): Int {
            var a = a0
            while (par[a] != a) { par[a] = par[par[a]]; a = par[a] }
            return a
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val ex = mx[j] - mx[i]
                val ey = my[j] - my[i]
                val dm = sqrt(ex * ex + ey * ey)
                if (!(dm <= (ll[j] + ll[i]) / 2 + p.gap)) continue
                if (abs(ux[i] * ux[j] + uy[i] * uy[j]) < p.cosJoin) continue
                val pp = max(
                    max(abs(-(p0x[j] - p0x[i]) * uy[i] + (p0y[j] - p0y[i]) * ux[i]), abs(-(p1x[j] - p0x[i]) * uy[i] + (p1y[j] - p0y[i]) * ux[i])),
                    max(abs(-(p0x[i] - p0x[j]) * uy[j] + (p0y[i] - p0y[j]) * ux[j]), abs(-(p1x[i] - p0x[j]) * uy[j] + (p1y[i] - p0y[j]) * ux[j])),
                )
                if (pp > p.perp) continue
                val tj0 = (p0x[j] - p0x[i]) * ux[i] + (p0y[j] - p0y[i]) * uy[i]
                val tj1 = (p1x[j] - p0x[i]) * ux[i] + (p1y[j] - p0y[i]) * uy[i]
                val gap = max(min(tj0, tj1) - ll[i], -max(tj0, tj1))
                if (gap > p.gap) continue
                val a = f(i)
                val b = f(j)
                if (a != b) par[max(a, b)] = min(a, b)
            }
        }
        val groups = java.util.TreeMap<Int, ArrayList<Int>>()
        for (i in 0 until n) groups.getOrPut(f(i)) { ArrayList() }.add(i)
        val lines = ArrayList<Line>(groups.size)
        for ((_, mem) in groups) {
            val np = 2 * mem.size
            val px = DoubleArray(np)
            val py = DoubleArray(np)
            for ((k, q) in mem.withIndex()) { px[k] = p0x[q]; py[k] = p0y[q]; px[mem.size + k] = p1x[q]; py[mem.size + k] = p1y[q] }
            var sx = 0.0
            var sy = 0.0
            for (k in 0 until np) { sx += px[k]; sy += py[k] }
            val cx = sx / np
            val cy = sy / np
            var zr = 0.0
            var zi = 0.0
            for (q in mem) {
                val wq = ll[q]
                zr += wq * (ux[q] * ux[q] - uy[q] * uy[q])
                zi += wq * (2.0 * ux[q] * uy[q])
            }
            val v = halfDir(zr, zi)
            var tmin = 0.0
            var tmax = 0.0
            for (k in 0 until np) {
                val t = (px[k] - cx) * v[0] + (py[k] - cy) * v[1]
                if (k == 0 || t < tmin) tmin = t
                if (k == 0 || t > tmax) tmax = t
            }
            lines.add(Line(cx + tmin * v[0], cy + tmin * v[1], cx + tmax * v[0], cy + tmax * v[1], v[0], v[1], tmax - tmin))
        }
        return lines
    }

    /** 菱形角 ∈ [0, 4)（與 atan2(y, x) mod 2π 同序）＝研究端 `_pseudo_angle`。 */
    private fun pseudoAngle(x: Double, y: Double): Double {
        if (x == 0.0 && y == 0.0) return 0.0
        return if (y >= 0.0) {
            if (x >= 0.0) y / (x + y) else 1.0 - x / (-x + y)
        } else {
            if (x < 0.0) 2.0 - y / (-x - y) else 3.0 + x / (x - y)
        }
    }

    /** 角展 ≥ 30° ⟺ 依角度排序後沒有一個間隙 > 330°＝研究端 `spread_ok`。 */
    internal fun spreadOk(vx: DoubleArray, vy: DoubleArray, cosSpread: Double): Boolean {
        val n = vx.size
        if (n < 2) return false
        val o = (0 until n).sortedBy { pseudoAngle(vx[it], vy[it]) }
        for (k in 0 until n) {
            val a = o[(k + 1) % n]
            val b = o[k]
            val ax = vx[a]; val ay = vy[a]; val bx = vx[b]; val by = vy[b]
            val cr = ax * by - ay * bx
            val dt = ax * bx + ay * by
            val na = sqrt(ax * ax + ay * ay)
            val nb = sqrt(bx * bx + by * by)
            val small = dt > cosSpread * na * nb && (if (k == n - 1) cr >= 0.0 else cr > 0.0)
            if (small) return false
        }
        return true
    }

    /** 貪婪找匯聚點＝研究端 `families`。 */
    internal fun families(lines: List<Line>, p: EffectLineParams): List<Family> {
        val nl = lines.size
        if (nl < 2) return emptyList()
        val mx = DoubleArray(nl) { (lines[it].ax + lines[it].bx) / 2 }
        val my = DoubleArray(nl) { (lines[it].ay + lines[it].by) / 2 }
        val alive = BooleanArray(nl) { true }
        val fams = ArrayList<Family>()
        repeat(p.maxFamilies) {
            val idx = (0 until nl).filter { alive[it] }.toIntArray()
            if (idx.size < 2) return fams
            val m0 = DoubleArray(idx.size) { mx[idx[it]] }
            val m1 = DoubleArray(idx.size) { my[idx[it]] }
            val u0 = DoubleArray(idx.size) { lines[idx[it]].ux }
            val u1 = DoubleArray(idx.size) { lines[idx[it]].uy }
            val wv = DoubleArray(idx.size) { lines[idx[it]].len }
            val order = (0 until idx.size).sortedWith(compareBy<Int>({ -wv[it] }, { it })).take(p.vpTop)
            var bestS = 0.0
            var bestPx = 0.0
            var bestPy = 0.0
            var found = false
            var bestOk: BooleanArray? = null
            val ok = BooleanArray(idx.size)
            for (ii in order.indices) {
                val i = order[ii]
                for (jj in ii + 1 until order.size) {
                    val j = order[jj]
                    val d = u0[i] * u1[j] - u1[i] * u0[j]
                    if (abs(d) < 1e-3) continue
                    val t = ((m0[j] - m0[i]) * u1[j] - (m1[j] - m1[i]) * u0[j]) / d
                    val px = m0[i] + t * u0[i]
                    val py = m1[i] + t * u1[i]
                    var s = 0.0
                    for (k in idx.indices) {
                        val dvx = px - m0[k]
                        val dvy = py - m1[k]
                        val dn = sqrt(dvx * dvx + dvy * dvy) + 1e-9
                        val o = abs((dvx * u0[k] + dvy * u1[k]) / dn) >= p.cosVp
                        ok[k] = o
                        if (o) s += wv[k]
                    }
                    if (s > bestS) {
                        bestS = s; bestPx = px; bestPy = py; found = true
                        bestOk = ok.copyOf()
                    }
                }
            }
            if (!found) return fams
            val bo = bestOk!!
            val mem = idx.filterIndexed { k, _ -> bo[k] }.toIntArray()
            val vx = DoubleArray(mem.size) { mx[mem[it]] - bestPx }
            val vy = DoubleArray(mem.size) { my[mem[it]] - bestPy }
            fams.add(Family(bestPx, bestPy, mem, spreadOk(vx, vy, p.cosSpread)))
            for (k in mem) alive[k] = false
        }
        return fams
    }

    /** 成員線外 40% 與內 40% 的帶內墨量比＝研究端 `taper`：回傳 (中位數, > 1.3 的佔比)。dk＝max(250 − g, 0)（X3 上 0）。 */
    private fun taper(lines: List<Line>, fam: Family, g: Gray, x3: Mask): DoubleArray {
        val w = g.w
        val h = g.h
        val rat = ArrayList<Double>()
        for (k in fam.mem) {
            val l = lines[k]
            var ax = l.ax; var ay = l.ay; var bx = l.bx; var by = l.by
            val da = (ax - fam.px) * (ax - fam.px) + (ay - fam.py) * (ay - fam.py)
            val db = (bx - fam.px) * (bx - fam.px) + (by - fam.py) * (by - fam.py)
            if (da < db) { var t = ax; ax = bx; bx = t; t = ay; ay = by; by = t }
            val ex = bx - ax
            val ey = by - ay
            val ln = sqrt(ex * ex + ey * ey)
            val len = ln.toInt()
            if (len < 2) continue
            val n = len + 1
            if (n < 20) continue
            val ux = ex / ln
            val uy = ey / ln
            val nx = -uy
            val ny = ux
            val prof = LongArray(n)
            for (o in -4..4) {
                for (t in 0 until n) {
                    val xs = min(max(Math.rint((ax + t * ux) + o * nx).toLong(), 0L), (w - 1).toLong()).toInt()
                    val ys = min(max(Math.rint((ay + t * uy) + o * ny).toLong(), 0L), (h - 1).toLong()).toInt()
                    val i = ys * w + xs
                    if (!x3.data[i]) prof[t] += max(250 - g.data[i], 0).toLong()
                }
            }
            val no = (0.4 * n).toInt()
            val ni = (0.6 * n).toInt()
            var so = 0L
            for (t in 0 until no) so += prof[t]
            var si = 0L
            for (t in ni until n) si += prof[t]
            val om = so.toDouble() / no
            val im = si.toDouble() / (n - ni)
            rat.add(om / max(1.0, im))
        }
        if (rat.isEmpty()) return doubleArrayOf(0.0, 0.0)
        val r = rat.sorted()
        val m = r.size
        val med = if (m % 2 == 1) r[m / 2] else (r[m / 2 - 1] + r[m / 2]) / 2.0
        var c = 0
        for (v in rat) if (v > 1.3) c++
        return doubleArrayOf(med, c.toDouble() / m)
    }

    /**
     * 自由端＝研究端 `free_ends`：成員線兩端沿線往外、左右偏 −1／0／+1（最近鄰 floor(v+0.5)）；碰到 X3 或出界＝不明；任一點
     * σ1 灰階（Q16 四捨五入）< [EffectLineParams.rayG] 而且不在小記號 5×5 外擴內＝擋住；否則＝自由。回傳 (自由端數, 自由＋擋住數)。
     */
    private fun freeEnds(lines: List<Line>, fam: Family, g: Gray, x3: Mask, sm: Mask, k1: IntArray, p: EffectLineParams): IntArray {
        val w = g.w
        val h = g.h
        val lim = p.rayG.toLong() * Q - 32768L
        var fr = 0
        var tot = 0
        for (k in fam.mem) {
            val l = lines[k]
            for (end in 0..1) {
                val px = if (end == 0) l.ax else l.bx
                val py = if (end == 0) l.ay else l.by
                val vx = if (end == 0) -l.ux else l.ux
                val vy = if (end == 0) -l.uy else l.uy
                var unk = false
                var blk = false
                for (t in p.ray0..p.ray1) {
                    for (o in -1..1) {
                        val xi = floor(px + t * vx - o * vy + 0.5).toInt()
                        val yi = floor(py + t * vy + o * vx + 0.5).toInt()
                        if (xi < 0 || yi < 0 || xi >= w || yi >= h || x3.data[yi * w + xi]) { unk = true; break }
                        if (BgObjects.gaussAt(g, k1, xi, yi) < lim && !nearSmall(sm, xi, yi)) blk = true
                    }
                    if (unk) break
                }
                if (unk) continue
                tot++
                if (!blk) fr++
            }
        }
        return intArrayOf(fr, tot)
    }

    /** 小記號 5×5 方核外擴（研究端 `smd`）在 (x, y) 的值。 */
    private fun nearSmall(sm: Mask, x: Int, y: Int): Boolean {
        for (yy in max(0, y - 2)..min(sm.h - 1, y + 2)) for (xx in max(0, x - 2)..min(sm.w - 1, x + 2)) if (sm.data[yy * sm.w + xx]) return true
        return false
    }

    /** 細暗線（扣 X3）的 8 連通塊裡外接框長邊 ≤ [lim] 的（小記號）。逐塊 BFS（不配整頁標號）。 */
    private fun smallMarks(inkx: Mask, lim: Double): Mask {
        val w = inkx.w
        val h = inkx.h
        val out = Mask(w, h)
        val seen = BooleanArray(w * h)
        var q = IntArray(256)
        for (s0 in 0 until w * h) {
            if (!inkx.data[s0] || seen[s0]) continue
            var qe = 0
            q[qe++] = s0
            seen[s0] = true
            var qs = 0
            var x0 = s0 % w; var x1 = x0; var y0 = s0 / w; var y1 = y0
            while (qs < qe) {
                val i = q[qs++]
                val x = i % w
                val y = i / w
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val j = yy * w + xx
                    if (inkx.data[j] && !seen[j]) {
                        seen[j] = true
                        if (qe == q.size) q = q.copyOf(q.size * 2)
                        q[qe++] = j
                    }
                }
            }
            if (max(x1 - x0 + 1, y1 - y0 + 1) <= lim) for (k in 0 until qe) out.data[q[k]] = true
        }
        return out
    }

    // ── 結構張量（整數）─────────────────────────────────────────────────

    /**
     * 二維整數高斯（先橫後直，每趟 Σ w·v 後四捨五入右移 16；REFLECT_101）＝研究端 `_conv_q`。[a] 是 rows×cols。
     * 對稱核：兩邊對稱的兩個值先相加再乘一次（Long 整數運算，與加總順序無關）。
     */
    private fun convQ(a: LongArray, rows: Int, cols: Int, k: IntArray): LongArray {
        val c = k.size / 2
        val h1 = LongArray(rows * cols)
        val xi = IntArray(cols + 2 * c) { Cv.reflect101(it - c, cols) }
        val line = LongArray(cols + 2 * c)
        val kc = k[c].toLong()
        for (y in 0 until rows) {
            val b = y * cols
            for (i in line.indices) line[i] = a[b + xi[i]]
            for (x in 0 until cols) {
                val m = x + c
                var acc = kc * line[m]
                for (j in 1..c) acc += k[c + j].toLong() * (line[m - j] + line[m + j])
                h1[b + x] = (acc + 32768L) shr 16
            }
        }
        val out = LongArray(rows * cols)
        for (y in 0 until rows) {
            val ob = y * cols
            val sc = y * cols
            for (x in 0 until cols) out[ob + x] = kc * h1[sc + x]
            for (j in 1..c) {
                val kj = k[c + j].toLong()
                if (kj == 0L) continue
                val sa = Cv.reflect101(y - j, rows) * cols
                val sb = Cv.reflect101(y + j, rows) * cols
                for (x in 0 until cols) out[ob + x] += kj * (h1[sa + x] + h1[sb + x])
            }
            for (x in 0 until cols) out[ob + x] = (out[ob + x] + 32768L) shr 16
        }
        return out
    }

    /**
     * 結構張量＝研究端 `tensor`，在窗 [wx0,wx1)×[r0,r1)（列是頁面座標）上算：blackhat（[cand] 上，其餘 0）→ σ1 → 3×3 Sobel →
     * 平方／乘積右移 16（四捨五入）→ σ[EffectLineParams.si]。回傳 (Jxx, Jyy, Jxy)，各 (r1−r0)×(wx1−wx0)。
     */
    private fun tensor(bh: Gray, cand: Mask, wx0: Int, wx1: Int, r0: Int, r1: Int, k1: IntArray, k4: IntArray): Array<LongArray> {
        val pw = bh.w
        val cols = wx1 - wx0
        val rows = r1 - r0
        val a = LongArray(rows * cols)
        for (y in 0 until rows) for (x in 0 until cols) {
            val i = (y + r0) * pw + x + wx0
            if (cand.data[i]) a[y * cols + x] = bh.data[i].toLong()
        }
        val f = convQ(a, rows, cols, k1)
        val pxx = LongArray(rows * cols)
        val pyy = LongArray(rows * cols)
        val pxy = LongArray(rows * cols)
        for (y in 0 until rows) {
            val ym = Cv.reflect101(y - 1, rows) * cols
            val y0 = y * cols
            val yp = Cv.reflect101(y + 1, rows) * cols
            for (x in 0 until cols) {
                val xm = Cv.reflect101(x - 1, cols)
                val xp = Cv.reflect101(x + 1, cols)
                val gx = (f[ym + xp] + 2 * f[y0 + xp] + f[yp + xp]) - (f[ym + xm] + 2 * f[y0 + xm] + f[yp + xm])
                val gy = (f[yp + xm] + 2 * f[yp + x] + f[yp + xp]) - (f[ym + xm] + 2 * f[ym + x] + f[ym + xp])
                pxx[y0 + x] = (gx * gx + 32768L) shr 16
                pyy[y0 + x] = (gy * gy + 32768L) shr 16
                pxy[y0 + x] = (gx * gy + 32768L) shr 16
            }
        }
        return arrayOf(convQ(pxx, rows, cols, k4), convQ(pyy, rows, cols, k4), convQ(pxy, rows, cols, k4))
    }

    /** 結構張量與「像素→匯聚點」(dx, dy) 的判定＝研究端 `aligned_at`。 */
    private fun alignedAt(jxx: Long, jyy: Long, jxy: Long, dx: Double, dy: Double, p: EffectLineParams): Boolean {
        val dd = (jxx - jyy).toDouble()
        val b2 = 2.0 * jxy.toDouble()
        val nn = sqrt(dd * dd + b2 * b2)
        val ss = (jxx + jyy).toDouble()
        val r2 = dx * dx + dy * dy
        val q = -(dd * (dx * dx - dy * dy) + b2 * (2.0 * dx * dy))
        return nn >= p.coh * (ss + p.epsJ) && q >= p.cosAtol2 * nn * r2 && r2 >= (p.vpNear * p.vpNear).toDouble()
    }

    // ── 整頁 ─────────────────────────────────────────────────────────

    /**
     * 整頁效果線場＝研究端 `field`：[ink]＝細暗線、[x3]＝交代過⊕3＋頁緣、[bh]＝σ2 blackhat（Q16）。沒有收下的效果線族 ⇒ null。
     * [info]（可 null）收除錯紀錄。
     */
    fun field(g: Gray, ink: Mask, x3: Mask, bh: Gray, sH: Double, p: EffectLineParams, info: MutableList<String>?): Field? {
        val w = g.w
        val h = g.h
        val n = w * h
        val inkx = ink.andNot(x3)
        val segs = segments(inkx, p)
        val lines = chain(segs, p).filter { it.len >= p.lineMin * sH }
        val fams = families(lines, p)
        info?.add("nsegs=${segs.size} nlines=${lines.size}")
        if (fams.isEmpty()) return null
        val sm = smallMarks(inkx, p.mark * sH)
        val k1 = Cv.gaussW(1.0)
        val acc = ArrayList<Family>()
        for (f in fams) {
            val tp = taper(lines, f, g, x3)
            val fe = freeEnds(lines, f, g, x3, sm, k1, p)
            f.taper = tp[0]; f.taperFrac = tp[1]; f.nFree = fe[0]; f.nEnds = fe[1]
            f.ok = f.mem.size >= p.minMembers && f.spreadOk && f.taper >= p.taper && f.taperFrac >= p.taperFrac &&
                f.nFree >= p.freeMin && f.nFree >= p.freeFrac * f.nEnds
            info?.add("fam P=(${f.px},${f.py}) n=${f.mem.size} spreadOk=${f.spreadOk} taper=${f.taper} frac=${f.taperFrac} free=${f.nFree}/${f.nEnds} ok=${f.ok}")
            if (f.ok) acc.add(f)
        }
        if (acc.isEmpty()) return null
        // 成員線與地盤
        val r = max(1, Math.rint(p.terr * sH).toInt())
        val ker = Cv.ellipse(2 * r + 1)
        val memline = Mask(w, h)
        val terr = Mask(w, h)
        val ts = ArrayList<LongArray>(acc.size)
        for (f in acc) {
            val tm = Mask(w, h)
            for (i in f.mem) {
                val l = lines[i]
                Cv.line(tm, Math.rint(l.ax).toInt(), Math.rint(l.ay).toInt(), Math.rint(l.bx).toInt(), Math.rint(l.by).toInt(), 1)
            }
            memline.orInPlace(tm)
            val t = Cv.dilatePacked(tm, ker)
            terr.orInPlace(t)
            ts.add(Ring.packBits(t))
        }
        var tx0 = w; var ty0 = h; var tx1 = -1; var ty1 = -1
        for (y in 0 until h) for (x in 0 until w) if (terr.data[y * w + x]) {
            if (x < tx0) tx0 = x; if (x > tx1) tx1 = x; if (y < ty0) ty0 = y; if (y > ty1) ty1 = y
        }
        val margin = 21
        val wx0 = max(0, tx0 - margin); val wx1 = min(w, tx1 + 1 + margin)
        val wy0 = max(0, ty0 - margin); val wy1 = min(h, ty1 + 1 + margin)
        val cand = inkx.andNot(sm)
        val aligned = Mask(w, h)
        // 結構張量：分條（每條輸出 STRIP 列、上下各多算 margin 列；窗的上下緣照窗反射，與研究端整窗算逐位元相同）
        val k4 = Cv.gaussW(p.si)
        val strip = 64
        var s0 = wy0
        while (s0 < wy1) {
            val s1 = min(wy1, s0 + strip)
            // 這一條有沒有要判的像素
            var need = false
            loop@ for (y in s0 until s1) for (x in wx0 until wx1) {
                val i = y * w + x
                if (cand.data[i] && terr.data[i]) { need = true; break@loop }
            }
            if (need) {
                val r0 = max(wy0, s0 - margin)
                val r1 = min(wy1, s1 + margin)
                val jt = tensor(bh, cand, wx0, wx1, r0, r1, k1, k4)
                val cols = wx1 - wx0
                for ((fi, f) in acc.withIndex()) {
                    val tb = ts[fi]
                    for (y in s0 until s1) for (x in wx0 until wx1) {
                        val i = y * w + x
                        if (!cand.data[i] || !Ring.has(tb, i)) continue
                        val j = (y - r0) * cols + x - wx0
                        if (alignedAt(jt[0][j], jt[1][j], jt[2][j], f.px - x, f.py - y, p)) aligned.data[i] = true
                    }
                }
            }
            s0 = s1
        }
        val near = Cv.dilatePacked(memline, Cv.ellipse(2 * p.lineR + 1))
        for (i in 0 until n) {
            if (inkx.data[i] && near.data[i] && !sm.data[i]) aligned.data[i] = true
        }
        // 效果墨＝對齊的墨 ∪ 地盤裡的小記號
        val fxe = aligned
        for (i in 0 until n) if (sm.data[i] && terr.data[i]) fxe.data[i] = true
        info?.add("nmark=${(0 until n).count { sm.data[it] && terr.data[it] }} naligned=${fxe.count()}")
        // 成員線的 8 連通標號（掃描首見序；只數「幾條」用）：成員線稀疏，標號只存在成員線像素上（二分搜尋找位置）
        var nm = 0
        for (i in 0 until n) if (memline.data[i]) nm++
        val memIdx = IntArray(nm)
        run { var k = 0; for (i in 0 until n) if (memline.data[i]) memIdx[k++] = i }
        val memLab = IntArray(nm)
        var nl = 0
        var q = IntArray(256)
        for (s in 0 until nm) {
            if (memLab[s] != 0) continue
            nl++
            var qe = 0
            q[qe++] = s
            memLab[s] = nl
            var qs = 0
            while (qs < qe) {
                val i = memIdx[q[qs++]]
                val x = i % w
                val y = i / w
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val j = yy * w + xx
                    if (!memline.data[j]) continue
                    val kj = java.util.Arrays.binarySearch(memIdx, j)
                    if (memLab[kj] == 0) {
                        memLab[kj] = nl
                        if (qe == q.size) q = q.copyOf(q.size * 2)
                        q[qe++] = kj
                    }
                }
            }
        }
        return Field(
            Ring.packBits(fxe), Ring.packBits(Cv.dilatePacked(fxe, Cv.ellipse(2 * p.excl + 1))),
            Ring.packBits(Cv.dilatePacked(fxe, Cv.ellipse(2 * p.exclT + 1))), Ring.packBits(terr), Ring.packBits(memline), memIdx, memLab,
            tx0, ty0, tx1 + 1, ty1 + 1,
        )
    }
}
