package li.joye.yakuyomi.nightread

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 任意角度的分鏡溝／頁邊偵測（SEP）——逐項移植 `research/nightread_sep.py`（python 行為就是規格）。
 *
 * [Regions.frameLineMask] 只認水平／垂直的長直線：斜格溝、被出血畫面打斷的溝、畫到頁緣的頁邊都進不了留白路徑。
 * 這裡直接從像素找任意角度的格框線，再找「兩條近平行框線夾住的整條白」＝分鏡溝、「頁緣到框線之間沒有畫的白」＝頁邊。
 *
 * 資料流（[build] → [separators]）：候選框線像素 → Hough（θ 0.5°、ρ 1px，float32）→ 取峰（θ 環狀 NMS、numpy
 * 不穩定排序截前 800）→ 沿線走訪切段 → 各段 PCA 精修（LAPACK 2×2 路徑）→ 去重／共線分組 → 泡框排除 →
 * 平行線對＝溝帶（逐站剖面白度、補橋、端延伸、白緣直線度、含字、排線家族）→ 頁邊（頁緣沿軸向走到頁邊框線）→
 * 溝網連通（chamfer 粗黑塊可通行）→ 線稿密度否決（[Texture.veto]）→ 圖層（扣泡 ⊕7、泡吃過半整條丟、碎塊丟）。
 *
 * 逐位元的地方（與 python 同輸入 ⇒ 遮罩逐像素相同，47 頁驗過）：
 *  - `np.round`／python `round` 都是半數取偶 ⇒ 一律 `Math.rint`（Hough 的 float32 ρ 用 1.5·2²³ 魔數，同為 RNE）。
 *  - Hough 的 ρ 在 float32 算（cos/sin 先轉 float32），JVM 的 Float 運算不融合乘加 ⇒ 與 numpy 同值。
 *  - 三角函數用 `StrictMath`（fdlibm，Android／桌面同值）：轉 float32 後 360 個 θ 全與 numpy 相同（累加器逐元素同）；
 *    float64 本身與 glibc 有 23 個 θ 差最後一位（直線參數 p0、走訪方向），47 頁實測不影響任何遮罩。
 *  - 取峰排序用 [Cv.npArgsort]（numpy introsort，同票順序一樣）。
 *  - PCA：均值成對加總（[Cv.npSum]）、特徵向量 [Cv.eigh2]（dlaev2）。共變異數的乘積和是逐項加（OpenBLAS dsyrk
 *    的加總順序不同）——差在最後一位，47 頁實測不影響任何遮罩（研究端換加總順序也逐像素相同）。
 *  - 框線／延長線／走廊的點陣化用 [Cv.line]／[Cv.fillPoly]（cv2 規則）；粗黑塊用 [Cv.distanceChamfer]（3×3）。
 *  - 斷框線的聯合擬合（[occGroup]）：成員命中像素依併入順序串接再 PCA（＝python `np.concatenate` 的順序）；
 *    沿聯合直線的走訪範圍是端點投影 floor／ceil；群組區間一律由成員端點投影（單成員也投影）；併入判準的端點距離
 *    量到聯合直線（每個成員、兩端），之前的粗篩用 2×groupOff 量到併入前的群組直線；重疊判準是兩端投影到併入前的
 *    群組直線、min(兩右端) − max(兩左端) > gap；[occLine] 輸出的 fill＝max(沿聯合直線, 該截自己定稿的命中率)。
 */
internal object Separators {

    /** 區間 [lo, hi]（沿線方向座標）。 */
    class Iv(val lo: Double, val hi: Double)

    /**
     * 一段定稿框線（detect_segments 的輸出）。p0/p1 與 t0/t1 會在去重時延伸。
     * [ex]／[ey]：定稿走訪的命中像素（站主序、站內法向由負到正；只有斷框線那一趟 keepHits 才存），給聯合擬合。
     */
    class Seg(
        var p0x: Double, var p0y: Double, var p1x: Double, var p1y: Double,
        val dx: Double, val dy: Double, val nx: Double, val ny: Double,
        val mx: Double, val my: Double,
        var t0: Double, var t1: Double,
        val fill: Double,
        val ex: IntArray? = null, val ey: IntArray? = null,
    ) {
        fun copy(fill: Double = this.fill) = Seg(p0x, p0y, p1x, p1y, dx, dy, nx, ny, mx, my, t0, t1, fill, ex, ey)
        val ang: Double get() = segAngle(dx, dy)
    }

    /** 共線分組：同一條被打斷的框線（多段區間 [iv]，沿 d 方向、以 m 為原點）。 */
    class Group(
        val mx: Double, val my: Double, val dx: Double, val dy: Double, val nx: Double, val ny: Double,
        val ang: Double, val iv: List<Iv>, val segs: List<Seg>,
    ) {
        /** 區間總長（逐項加，同 python sum）。 */
        val len: Double = run { var s = 0.0; for (v in iv) s += v.hi - v.lo; s }

        /** 長度加權命中率（`group_fill`）。 */
        val fill: Double = run {
            var l = 0.0
            for (s in segs) l += s.t1 - s.t0
            var f = 0.0
            for (s in segs) f += s.fill * (s.t1 - s.t0)
            f / max(l, 1.0)
        }
    }

    /** bbox 內的局部遮罩（溝帶、走廊可達區；免得每一對都配一張整頁遮罩）。 */
    class Patch(val x0: Int, val y0: Int, val w: Int, val h: Int, val data: BooleanArray) {
        fun count(): Int { var n = 0; for (v in data) if (v) n++; return n }
        fun orInto(m: Mask) {
            for (y in 0 until h) {
                val src = y * w
                val dst = (y0 + y) * m.w + x0
                for (x in 0 until w) if (data[src + x]) m.data[dst + x] = true
            }
        }
        fun countAnd(m: Mask): Int {
            var n = 0
            for (y in 0 until h) {
                val src = y * w
                val dst = (y0 + y) * m.w + x0
                for (x in 0 until w) if (data[src + x] && m.data[dst + x]) n++
            }
            return n
        }
    }

    /** 一對平行框線的判定結果（`pair_strip` 的統計＋溝網連通的狀態）。 */
    class PairRes(val i: Int, val j: Int) {
        var acc = false
        var why = ""
        var passFrac = 0.0
        var madA = 99.0
        var madB = 99.0
        var textFrac = 0.0
        var px = 0
        var mask: Patch? = null
        // 幾何（有重疊區間時才有）：a＝長的那條、b、兩側符號、核心區間
        var a: Group? = null
        var b: Group? = null
        var sg = 1.0
        var sb = 1.0
        var cores: List<Iv> = emptyList()
    }

    /** [separators] 的輸出（[frameArb]＝任意角度框線點陣，頁邊否決與出血過濾共用，只算一次）。 */
    class Result(val strip: Mask, val margin: Mask, val groups: List<Group>, val pairs: List<PairRes>, val frameArb: Mask)

    /**
     * compose 用的 SEP 圖層（`build_sep`）。
     *
     * @property sep 要塗的溝＋頁邊（泡 ⊕7 扣掉、被泡吃過半的整條溝丟掉、< [SeparatorParams.minCc] 的碎塊丟掉）
     * @property sepPre 扣泡之前的溝＋頁邊（出血過濾拿它當「溝／頁邊」結構證據）
     * @property strip 溝帶（已過溝網連通與泡過半規則）
     * @property margin 頁邊（已過線稿密度否決）
     * @property frameArb 任意角度框線點陣（出血過濾拿它當框線結構證據）
     * @property dropped 被泡吃過半而丟掉的溝對（i, j, 比例四捨五入到 3 位）
     */
    class Layer(
        val sep: Mask, val sepPre: Mask, val strip: Mask, val margin: Mask, val frameArb: Mask,
        val groups: List<Group>, val pairs: List<PairRes>, val dropped: List<Triple<Int, Int, Double>>,
    )

    // ── 小工具 ───────────────────────────────────────────────────────────────

    private val DEG = 180.0 / Math.PI                 // np.degrees：x·(180/π)
    private const val ROUND_MAGIC = 12582912f          // 1.5·2²³：float 加法把 |r| < 2²² 捨入到整數（半數取偶）
    private val RAD = Math.PI / 180.0                 // np.deg2rad：x·(π/180)

    /** `np.degrees(np.arctan2(dy, dx)) % 180.0`（python 取模：結果與除數同號）。 */
    fun segAngle(dx: Double, dy: Double): Double = (StrictMath.atan2(dy, dx) * DEG).mod(180.0)

    private fun angDiff(a: Double, b: Double): Double { val d = abs(a - b); return min(d, 180 - d) }

    /** `np.dot` 兩維：逐項乘、再相加（無 FMA，實測同 numpy）。 */
    private fun dot(ax: Double, ay: Double, bx: Double, by: Double): Double = ax * bx + ay * by

    /**
     * `Math.rint(v).toInt()`（四捨六入五成雙）。|v| < 2⁵¹ 時用加減 1.5·2⁵² 的捨入（預設捨入模式就是五成雙，結果與 rint 相同，
     * −0.0 也一樣變 0），其餘（含 NaN）退回 [Math.rint]。inline：debug APK 不內聯，`Math.rint → StrictMath.rint → copySign…`
     * 一串真呼叫讓格溝在 debug 版慢 9 倍（2026-10-06 剖析）。
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun rint(v: Double): Int =
        if (v > -2.251799813685248E15 && v < 2.251799813685248E15) ((v + 6.755399441055744E15) - 6.755399441055744E15).toInt()
        else Math.rint(v).toInt()

    /** 分段計時（除錯／效能量測用）：[debug] 非 null 時把耗時（ms）累加到 "t_[key]"。 */
    private inline fun <T> timed(debug: MutableMap<String, Any>?, key: String, block: () -> T): T {
        if (debug == null) return block()
        val t0 = System.nanoTime()
        val r = block()
        debug["t_$key"] = ((debug["t_$key"] as? Double) ?: 0.0) + (System.nanoTime() - t0) / 1e6
        return r
    }

    /** `cv2.dilate(m, np.ones((n, n)))`：方核拆成橫＋直（逐位元等價），走 lineMorph 快路。 */
    private fun dilateSquare(m: Mask, n: Int): Mask =
        if (n <= 1) m else Cv.dilate(Cv.dilate(m, Cv.rect(n, 1)), Cv.rect(1, n))

    // ── 框線段偵測 ──────────────────────────────────────────────────────────

    /** 候選框線像素：暗（< frameDarkTh）且距白 ≤ nearWhiteR（方核膨脹）。 */
    fun candidatePixels(g: Gray, p: NightReadParams): Mask {
        val white = g.ge(p.whiteTh)
        val nearw = dilateSquare(white, 2 * p.sep.nearWhiteR + 1)
        return Mask(g.w, g.h, BooleanArray(g.data.size) { g.data[it] < p.frameDarkTh && nearw.data[it] })
    }

    /** θ 表（`np.deg2rad(np.arange(0, 180, step))`）。 */
    fun thetas(p: NightReadParams): DoubleArray {
        val n = ceil(180.0 / p.sep.thStep).toInt()
        return DoubleArray(n) { (it * p.sep.thStep) * RAD }
    }

    /** Hough 累加器 acc[θ·(2D+1) + ρ+D]（int32）。ρ = rint(float32(x·cosθ + y·sinθ))。 */
    fun hough(m: Mask, th: DoubleArray): Pair<IntArray, Int> {
        val w = m.w
        val h = m.h
        val d = ceil(sqrt(h.toDouble() * h + w.toDouble() * w)).toInt()
        val nr = 2 * d + 1
        val acc = IntArray(th.size * nr)
        // 候選像素逐列壓成 CSR（列起點＋欄座標）：同一列的 y·sinθ 只算一次
        var np = 0
        for (v in m.data) if (v) np++
        val xs = IntArray(np)
        val rowStart = IntArray(h + 1)
        var k = 0
        for (y in 0 until h) {
            rowStart[y] = k
            val base = y * w
            for (x in 0 until w) if (m.data[base + x]) xs[k++] = x
        }
        rowStart[h] = k
        val xc = FloatArray(w)
        for (i in th.indices) {
            val c = StrictMath.cos(th[i]).toFloat()
            val s = StrictMath.sin(th[i]).toFloat()
            // float32 乘積各自捨入再相加＝numpy 的 xf*c + yf*s（逐元素 ufunc，不融合）
            for (x in 0 until w) xc[x] = x.toFloat() * c
            val base = i * nr + d
            for (y in 0 until h) {
                val sy = y.toFloat() * s
                for (q in rowStart[y] until rowStart[y + 1]) {
                    // 半數取偶捨入：|r| < 2²² 時加減 1.5·2²³ 由 float 加法本身做 RNE（＝np.round，免轉 double）
                    val r = xc[xs[q]] + sy
                    val ri = if (abs(r) < 4194304f) ((r + ROUND_MAGIC) - ROUND_MAGIC).toInt() else Math.rint(r.toDouble()).toInt()
                    acc[base + ri]++
                }
            }
        }
        return acc to d
    }

    /** Hough 取峰：沿 ρ 3 格和 → θ 環狀 NMS → 票數由高到低（numpy 不穩定排序）取前 peakMax。回傳 [ti, ri, votes]。 */
    fun peaks(acc: IntArray, nt: Int, nr: Int, minVotes: Int, p: NightReadParams): List<IntArray> {
        val s = IntArray(acc.size)
        for (t in 0 until nt) {
            val base = t * nr
            for (r in 0 until nr) {
                var v = acc[base + r]
                if (r > 0) v += acc[base + r - 1]
                if (r < nr - 1) v += acc[base + r + 1]
                s[base + r] = v
            }
        }
        val mf = Cv.maxFilterWrapRows(s, nt, nr, p.sep.peakNmsT, p.sep.peakNmsR)
        var n = 0
        for (i in s.indices) if (s[i] == mf[i] && s[i] >= minVotes) n++
        val idx = IntArray(n)
        val neg = IntArray(n)
        n = 0
        for (i in s.indices) if (s[i] == mf[i] && s[i] >= minVotes) { idx[n] = i; neg[n] = -s[i]; n++ }
        val order = Cv.npArgsort(neg)
        val out = ArrayList<IntArray>()
        for (j in 0 until min(p.sep.peakMax, n)) {
            val i = idx[order[j]]
            out.add(intArrayOf(i / nr, i % nr, s[i]))
        }
        return out
    }

    /**
     * 沿 p0 + t·d 走訪（t = tmin..tmax），法向 ±win 內有候選就算命中（取樣點 rint，同 np.round）。
     * 只記逐站命中；命中像素（[hitsXy]）只對切出來的段重算——整條線的逐點表大多用不到，免得每條峰配三張大陣列。
     */
    private class Walk(
        val m: Mask, val p0x: Double, val p0y: Double, val dx: Double, val dy: Double, val nx: Double, val ny: Double,
        val tmin: Int, val win: Int, val hit: BooleanArray,
    )

    private fun walk(m: Mask, p0x: Double, p0y: Double, dx: Double, dy: Double, nx: Double, ny: Double,
                     tmin: Int, tmax: Int, win: Int): Walk {
        val w = m.w
        val h = m.h
        val nt = tmax - tmin + 1
        val hit = BooleanArray(nt)
        for (k in 0 until nt) {
            val t = (tmin + k).toDouble()
            val bx = p0x + t * dx
            val by = p0y + t * dy
            for (o in -win..win) {
                val off = o.toDouble()
                val x = rint(bx + off * nx)
                val y = rint(by + off * ny)
                if (x in 0 until w && y in 0 until h && m.data[y * w + x]) { hit[k] = true; break }
            }
        }
        return Walk(m, p0x, p0y, dx, dy, nx, ny, tmin, win, hit)
    }

    /** 命中序列 → 允許 gap 斷口的連續段（含端點，皆為命中）；只留長度 ≥ minLen 且命中率 ≥ fillMin 的。 */
    private fun runs(hit: BooleanArray, gap: Int, minLen: Int, fillMin: Double): List<IntArray> {
        val raw = ArrayList<IntArray>()
        var s = -1
        var prev = -1
        for (i in hit.indices) {
            if (!hit[i]) continue
            if (s < 0) { s = i; prev = i; continue }
            if (i - prev > gap + 1) { raw.add(intArrayOf(s, prev)); s = i }
            prev = i
        }
        if (s >= 0) raw.add(intArrayOf(s, prev))
        val res = ArrayList<IntArray>()
        for (r in raw) {
            val l = r[1] - r[0] + 1
            if (l < minLen) continue
            var c = 0
            for (i in r[0]..r[1]) if (hit[i]) c++
            if (c.toDouble() / l >= fillMin) res.add(r)
        }
        return res
    }

    /** 直線與頁框相交的 t 範圍（向外取整）；不相交回 null。 */
    private fun tRange(p0x: Double, p0y: Double, dx: Double, dy: Double, h: Int, w: Int): IntArray? {
        val ts = ArrayList<Double>()
        if (abs(dx) > 1e-9) { ts.add((0 - p0x) / dx); ts.add(((w - 1) - p0x) / dx) }
        if (abs(dy) > 1e-9) { ts.add((0 - p0y) / dy); ts.add(((h - 1) - p0y) / dy) }
        ts.sort()
        val cand = ArrayList<Double>()
        for (t in ts) {
            val x = p0x + t * dx
            val y = p0y + t * dy
            if (x >= -1 && x <= w && y >= -1 && y <= h) cand.add(t)
        }
        if (cand.size < 2) return null
        return intArrayOf(floor(cand.min()).toInt(), ceil(cand.max()).toInt())
    }

    /** 點集主軸（`fit_pca`）：回傳 [mx, my, dx, dy, nx, ny]；d 正規化成 d.x ≥ 0（d.x≈0 時 d.y ≥ 0）。 */
    private fun fitPca(xs: IntArray, ys: IntArray, n: Int): DoubleArray {
        var sx = 0L
        var sy = 0L
        for (i in 0 until n) { sx += xs[i]; sy += ys[i] }
        val mx = sx.toDouble() / n                  // 整數和精確 ⇒ 與 numpy 成對加總同值
        val my = sy.toDouble() / n
        // np.cov：先減均值（成對加總的均值），乘積和 × 1/(n−1)
        val a0 = DoubleArray(n) { xs[it] - mx }
        val a1 = DoubleArray(n) { ys[it] - my }
        val av0 = Cv.npSum(a0) / n
        val av1 = Cv.npSum(a1) / n
        var s00 = 0.0
        var s01 = 0.0
        var s11 = 0.0
        for (i in 0 until n) {
            val u = a0[i] - av0
            val v = a1[i] - av1
            s00 += u * u; s01 += u * v; s11 += v * v
        }
        val f = 1.0 / (n - 1)
        val e = Cv.eigh2(s00 * f, s01 * f, s11 * f)
        var dx = e[4]
        var dy = e[5]                               // 主軸＝最大特徵值（遞增排序的最後一欄）
        if (dx < 0 || (abs(dx) < 1e-9 && dy < 0)) { dx = -dx; dy = -dy }
        val nx0 = dy
        val ny0 = -dx
        val dn = sqrt(dx * dx + dy * dy)
        val nn = sqrt(nx0 * nx0 + ny0 * ny0)
        return doubleArrayOf(mx, my, dx / dn, dy / dn, nx0 / nn, ny0 / nn)
    }

    /** 走訪結果中第 a..b 站的命中像素（站主序、偏移次序＝numpy 布林索引 `xi[a:b+1][v[a:b+1]]` 的順序）。 */
    private fun hitsXy(wk: Walk, a: Int, b: Int): Triple<IntArray, IntArray, Int> {
        val m = wk.m
        val w = m.w
        val h = m.h
        var xs = IntArray(64)
        var ys = IntArray(64)
        var n = 0
        for (k in a..b) {
            val t = (wk.tmin + k).toDouble()
            val bx = wk.p0x + t * wk.dx
            val by = wk.p0y + t * wk.dy
            for (o in -wk.win..wk.win) {
                val off = o.toDouble()
                val x = rint(bx + off * wk.nx)
                val y = rint(by + off * wk.ny)
                if (x in 0 until w && y in 0 until h && m.data[y * w + x]) {
                    if (n == xs.size) { xs = xs.copyOf(n * 2); ys = ys.copyOf(n * 2) }
                    xs[n] = x; ys[n] = y; n++
                }
            }
        }
        return Triple(xs, ys, n)
    }

    private fun projRange(xs: IntArray, ys: IntArray, n: Int, f: DoubleArray, gap: Int): IntArray {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val pr = (xs[i] - f[0]) * f[2] + (ys[i] - f[1]) * f[3]
            if (pr < lo) lo = pr
            if (pr > hi) hi = pr
        }
        return intArrayOf(floor(lo).toInt() - 2 * gap, ceil(hi).toInt() + 2 * gap)
    }

    /**
     * Hough 峰 → 沿線走訪切段 → 每一段各自 PCA 精修兩次 → 窄窗走訪定端點與命中率 → 去重。
     * [debug] 非 null 時放入 "peaks"（List<IntArray>）與 "D"。
     */
    /** 兩趟共用的候選像素／Hough／峰（`hough_cache`）。 */
    class HoughCache { var m: Mask? = null; var d: Int = 0; var pk: List<IntArray>? = null }

    /**
     * 預設參數＝主偵測。斷框線那一趟（[occludedFrames]）：[minLen] 切段長度下限（峰的票數門檻仍用最短框線長）、
     * [axisTol] 只走與水平／垂直夾角 ≤ 此度的峰（θ＝格號×thStep，精確）、[finalWin] 定稿走訪的法向半窗；[cache] 兩趟共用；
     * [keepHits] 每段另存定稿走訪的命中像素（[Seg.ex]／[Seg.ey]），給斷框線的聯合擬合。
     */
    fun detectSegments(g: Gray, p: NightReadParams, m0: Mask? = null, debug: MutableMap<String, Any>? = null,
                       minLen: Int? = null, axisTol: Double? = null, finalWin: Int = p.sep.walkWin,
                       cache: HoughCache? = null, keepHits: Boolean = false): List<Seg> {
        val h = g.h
        val w = g.w
        val l = max(60, rint(p.sep.lenFrac * min(h, w)))
        val lr = minLen ?: l
        val th = thetas(p)
        val hc = cache ?: HoughCache()
        if (hc.pk == null) {
            val m1 = m0 ?: timed(debug, "cand") { candidatePixels(g, p) }
            val (acc, d1) = timed(debug, "hough") { hough(m1, th) }
            val nr = 2 * d1 + 1
            hc.m = m1; hc.d = d1
            hc.pk = timed(debug, "peaks") { peaks(acc, th.size, nr, (p.sep.peakVoteFrac * l).toInt(), p) }
        }
        val m = hc.m!!
        val d = hc.d
        val pk = hc.pk!!
        val tWalk = System.nanoTime()
        if (minLen == null) { debug?.put("peaks", pk); debug?.put("D", d) }
        val gap = p.sep.gap
        val minRun = (0.7 * lr).toInt()
        val segs = ArrayList<Seg>()
        for (peak in pk) {
            if (axisTol != null) {
                val tdeg = peak[0] * p.sep.thStep                  // 峰的 θ 格號 × 0.5°（精確）
                if (min(min(tdeg, 180.0 - tdeg), abs(tdeg - 90.0)) > axisTol) continue
            }
            val t = th[peak[0]]
            val rho = (peak[1] - d).toDouble()
            val cs = StrictMath.cos(t)
            val sn = StrictMath.sin(t)
            val nx = cs
            val ny = sn
            val dx = -sn
            val dy = cs
            val p0x = nx * rho
            val p0y = ny * rho
            val tr = tRange(p0x, p0y, dx, dy, h, w) ?: continue
            val wk = walk(m, p0x, p0y, dx, dy, nx, ny, tr[0], tr[1], p.sep.walkWin0)
            for (r in runs(wk.hit, gap, minRun, 0.6)) {
                var (xs, ys, n) = hitsXy(wk, r[0], r[1])
                if (n < 20) continue
                var f = fitPca(xs, ys, n)
                var lohi = projRange(xs, ys, n, f, gap)
                var ok = true
                for (it in 0 until 2) {                 // 只在這段附近（±2·GAP）重走、重擬合
                    val wk2 = walk(m, f[0], f[1], f[2], f[3], f[4], f[5], lohi[0], lohi[1], p.sep.walkWin0)
                    val rr = runs(wk2.hit, gap, minRun, 0.6)
                    if (rr.isEmpty()) { ok = false; break }
                    var best = rr[0]
                    for (q in rr) if (q[1] - q[0] > best[1] - best[0]) best = q   // python max：同長取第一個
                    val hx = hitsXy(wk2, best[0], best[1])
                    xs = hx.first; ys = hx.second; n = hx.third
                    f = fitPca(xs, ys, n)
                    lohi = projRange(xs, ys, n, f, gap)
                }
                if (!ok) continue
                val wk3 = walk(m, f[0], f[1], f[2], f[3], f[4], f[5], lohi[0], lohi[1], finalWin)
                for (r2 in runs(wk3.hit, gap, lr, p.sep.fillMin)) {
                    val t0 = (lohi[0] + r2[0]).toDouble()
                    val t1 = (lohi[0] + r2[1]).toDouble()
                    var c = 0
                    for (i in r2[0]..r2[1]) if (wk3.hit[i]) c++
                    val hx = if (keepHits) hitsXy(wk3, r2[0], r2[1]) else null
                    segs.add(Seg(
                        f[0] + t0 * f[2], f[1] + t0 * f[3], f[0] + t1 * f[2], f[1] + t1 * f[3],
                        f[2], f[3], f[4], f[5], f[0], f[1], t0, t1,
                        c.toDouble() / (r2[1] - r2[0] + 1),
                        hx?.let { it.first.copyOf(it.third) }, hx?.let { it.second.copyOf(it.third) },
                    ))
                }
            }
        }
        val out = dedupe(segs, p)
        debug?.put(if (minLen == null) "t_walk" else "t_occwalk", (System.nanoTime() - tWalk) / 1e6)
        return out
    }

    /** 段 s 的範圍投影到直線 (m, d) 上（向外取整），沿線 ±walkWin0 走訪的命中率（`_fill_on`）。 */
    private fun fillOn(m: Mask, f: DoubleArray, s: Seg, p: NightReadParams): Double {
        val u0 = dot(s.p0x - f[0], s.p0y - f[1], f[2], f[3])
        val u1 = dot(s.p1x - f[0], s.p1y - f[1], f[2], f[3])
        val wk = walk(m, f[0], f[1], f[2], f[3], f[4], f[5], floor(min(u0, u1)).toInt(), ceil(max(u0, u1)).toInt(), p.sep.walkWin0)
        var c = 0
        for (v in wk.hit) if (v) c++
        return c.toDouble() / wk.hit.size
    }

    /**
     * 聯合直線（`_joint`）：成員命中像素依成員順序串接後 PCA（＝python `np.concatenate` 的順序），沿它量每一截的命中率。
     * 回傳 (直線 [mx, my, dx, dy, nx, ny], 各截命中率)。
     */
    private fun joint(mem: List<Seg>, m: Mask, p: NightReadParams): Pair<DoubleArray, List<Double>> {
        var n = 0
        for (t in mem) n += t.ex!!.size
        val xs = IntArray(n)
        val ys = IntArray(n)
        var k = 0
        for (t in mem) {
            val ex = t.ex!!
            ex.copyInto(xs, k); t.ey!!.copyInto(ys, k); k += ex.size
        }
        val f = fitPca(xs, ys, n)
        return f to mem.map { fillOn(m, f, it, p) }
    }

    /**
     * 一組成員 → 群組（`_occ_line`）：直線＝聯合直線；segs＝成員副本（fill＝max(沿聯合直線的命中率, 該截自己定稿的
     * 命中率)）；區間＝成員兩端投影到聯合直線（單成員也投影），排序後重疊（斷口 ≤ 1）併起。
     * fill 取兩者較大：頁邊的 marginFillMin 問「這一截是不是尺畫的連續框線」，一截有兩個直線假設——自己的 PCA（換緣的截
     * 會斜）與聯合直線（粗框線上可能落在另一緣）；共線與否已由併入判準把關，這裡不重複懲罰擬合落在哪一緣。
     */
    private fun occLine(mem: List<Seg>, m: Mask, p: NightReadParams): Group {
        val (f, fills) = joint(mem, m, p)
        val segs = mem.mapIndexed { i, s -> s.copy(fill = max(fills[i], s.fill)) }
        val iv = segs.map { s ->
            val a0 = dot(s.p0x - f[0], s.p0y - f[1], f[2], f[3])
            val a1 = dot(s.p1x - f[0], s.p1y - f[1], f[2], f[3])
            doubleArrayOf(min(a0, a1), max(a0, a1))
        }.sortedWith(compareBy<DoubleArray>({ it[0] }, { it[1] }))
        val mg = ArrayList<DoubleArray>()
        mg.add(iv[0].copyOf())
        for (k in 1 until iv.size) {
            val a = iv[k][0]
            val b = iv[k][1]
            if (a <= mg.last()[1] + 1) mg.last()[1] = max(mg.last()[1], b) else mg.add(doubleArrayOf(a, b))
        }
        return Group(f[0], f[1], f[2], f[3], f[4], f[5], segAngle(f[2], f[3]), mg.map { Iv(it[0], it[1]) }, segs)
    }

    /**
     * 斷框線的共線分組（`occ_group`）：聯合擬合取代 [groupLines] 的夾角門檻，回傳成員清單（依併入順序）。短段各自 PCA 的
     * 方向誤差 ∝ 1／段長（粗框線只有鄰白那一緣是候選像素，被蓋住處證據換緣，150px 一截就斜 1–2°），所以改問「有沒有一條
     * 直線同時解釋兩截的證據」：群組成員＋候選段的命中像素聯集重新 PCA，沿聯合直線 ±walkWin0 走訪每一截的範圍，每一截
     * 命中率都 ≥ fillMin、而且**每一個**成員的兩端到**聯合直線**的法向距都 ≤ groupOff 才併入，群組直線換成聯合直線。
     * 端點距離量到併入前的群組直線會把候選段自己的傾斜算成偏移（c371_001 放大 1.245×：第二排上半截擬合斜 1°，遠端點離
     * 第一排長框線 6.0px，聯合擬合根本沒機會跑）；聯合擬合之前只用 2×groupOff 粗篩。沿線與已收成員重疊 > gap 的段不併：
     * 被蓋斷的框線在線上是互不重疊的幾截，重疊的段是同一段粗框線的另一緣、或斜切的換緣段，併進來只會把聯合直線拉斜。
     * 依段長由長到短、先到先併。
     */
    private fun occGroup(pieces: List<Seg>, m: Mask, p: NightReadParams): List<List<Seg>> {
        /** 段 t 兩端投影到直線 f 上的區間 (lo, hi)（`_pj`）。 */
        fun projIv(t: Seg, f: DoubleArray): Pair<Double, Double> {
            val a0 = dot(t.p0x - f[0], t.p0y - f[1], f[2], f[3])
            val a1 = dot(t.p1x - f[0], t.p1y - f[1], f[2], f[3])
            return min(a0, a1) to max(a0, a1)
        }
        class G(var f: DoubleArray, var mem: List<Seg>)
        val groups = ArrayList<G>()
        for (s in pieces.sortedBy { -(it.t1 - it.t0) }) {
            var placed = false
            for (gp in groups) {
                val f = gp.f
                if (abs(dot(s.p0x - f[0], s.p0y - f[1], f[4], f[5])) > 2 * p.sep.groupOff ||
                    abs(dot(s.p1x - f[0], s.p1y - f[1], f[4], f[5])) > 2 * p.sep.groupOff) continue
                val (sa, sb) = projIv(s, f)
                if (gp.mem.any { t -> val (ta, tb) = projIv(t, f); min(sb, tb) - max(sa, ta) > p.sep.gap }) continue
                val mem = gp.mem + s
                val (fj, fills) = joint(mem, m, p)
                if (mem.any { t ->
                        abs(dot(t.p0x - fj[0], t.p0y - fj[1], fj[4], fj[5])) > p.sep.groupOff ||
                            abs(dot(t.p1x - fj[0], t.p1y - fj[1], fj[4], fj[5])) > p.sep.groupOff
                    }) continue
                if (fills.min() < p.sep.fillMin) continue
                gp.f = fj; gp.mem = mem
                placed = true
                break
            }
            if (!placed) groups.add(G(doubleArrayOf(s.mx, s.my, s.dx, s.dy, s.nx, s.ny), listOf(s)))
        }
        return groups.map { it.mem }
    }

    /**
     * 頁邊斷框線（`occluded_frames`）：被狀聲詞／出血物蓋斷、每截都短於最短框線長的框線。第二趟只走近軸向的峰、
     * 切段下限 occPieceFrac×L、定稿走訪 ±walkWin0；聯合擬合分組（[occGroup]）後，沿群組直線相鄰兩截斷口
     * ≤ rint(occGapFrac×短邊) 的串成一條；每一串只用自己的截重新聯合擬合（[occLine]）再量區間與命中率——同一條頁邊上
     * 遠處別格的框線（相鄰格外框常錯開 1–2px）不是這一串的證據。至少兩截、各截長度和 ≥ L 才收。
     * 只給頁邊與頁邊否決的格線遮罩，不進溝帶／出血過濾。
     */
    fun occludedFrames(g: Gray, p: NightReadParams, cache: HoughCache, debug: MutableMap<String, Any>? = null): List<Group> {
        val sh = min(g.h, g.w)
        val l = max(60, rint(p.sep.lenFrac * sh))
        val gapMax = rint(p.sep.occGapFrac * sh)
        val pieces = detectSegments(g, p, debug = debug, minLen = rint(p.sep.occPieceFrac * l),
            axisTol = p.sep.marginAxisAng + 1.0, finalWin = p.sep.walkWin0, cache = cache, keepHits = true)
        val m = cache.m!!
        val out = ArrayList<Group>()
        for (mem in occGroup(pieces, m, p)) {
            val gp = occLine(mem, m, p)
            val chains = ArrayList<ArrayList<Iv>>()
            chains.add(arrayListOf(gp.iv[0]))
            for (k in 1 until gp.iv.size) {
                val v = gp.iv[k]
                if (v.lo - chains.last().last().hi <= gapMax) chains.last().add(v) else chains.add(arrayListOf(v))
            }
            for (ch in chains) {
                if (ch.size < 2) continue
                val lo = ch.first().lo
                val hi = ch.last().hi
                val cm = mem.filterIndexed { i, _ ->
                    val q = gp.segs[i]
                    val u0 = dot(q.p0x - gp.mx, q.p0y - gp.my, gp.dx, gp.dy)
                    val u1 = dot(q.p1x - gp.mx, q.p1y - gp.my, gp.dx, gp.dy)
                    min(u0, u1) >= lo - 1 && max(u0, u1) <= hi + 1
                }
                val c = occLine(cm, m, p)                       // 這一串自己的聯合直線
                if (c.iv.size < 2 || c.len < l) continue
                out.add(c)
            }
        }
        return out
    }

    /** 幾乎共線且重疊的段只留一條（最長的，延伸成聯集）。穩定排序：同長度保持峰的順序。 */
    private fun dedupe(segs0: List<Seg>, p: NightReadParams): List<Seg> {
        val segs = segs0.sortedBy { -(it.t1 - it.t0) }
        val keep = ArrayList<Seg>()
        for (s in segs) {
            var dup = false
            for (k in keep) {
                if (angDiff(s.ang, k.ang) > p.sep.dedupeAng) continue
                if (abs(dot(s.p0x - k.mx, s.p0y - k.my, k.nx, k.ny)) > p.sep.dedupeOff ||
                    abs(dot(s.p1x - k.mx, s.p1y - k.my, k.nx, k.ny)) > p.sep.dedupeOff) continue
                val a0 = dot(s.p0x - k.mx, s.p0y - k.my, k.dx, k.dy)
                val a1 = dot(s.p1x - k.mx, s.p1y - k.my, k.dx, k.dy)
                val lo = min(a0, a1)
                val hi = max(a0, a1)
                if (hi >= k.t0 - 5 && lo <= k.t1 + 5) {
                    k.t0 = if (lo < k.t0) lo else k.t0
                    k.t1 = if (hi > k.t1) hi else k.t1
                    k.p0x = k.mx + k.t0 * k.dx; k.p0y = k.my + k.t0 * k.dy
                    k.p1x = k.mx + k.t1 * k.dx; k.p1y = k.my + k.t1 * k.dy
                    dup = true
                    break
                }
            }
            if (!dup) keep.add(s.copy())
        }
        return keep
    }

    // ── 共線分組 ────────────────────────────────────────────────────────────

    /** 被對白框／狀聲詞／出血人物打斷的同一條框線併成一組（多段區間，斷口 ≤ 1 併起）。 */
    fun groupLines(segs0: List<Seg>, p: NightReadParams): List<Group> {
        val segs = segs0.sortedBy { -(it.t1 - it.t0) }
        class G(val mx: Double, val my: Double, val dx: Double, val dy: Double, val nx: Double, val ny: Double, val ang: Double) {
            val iv = ArrayList<DoubleArray>()
            val segs = ArrayList<Seg>()
        }
        val groups = ArrayList<G>()
        for (s in segs) {
            var placed = false
            for (gp in groups) {
                if (angDiff(s.ang, gp.ang) > p.sep.groupAng) continue
                if (abs(dot(s.p0x - gp.mx, s.p0y - gp.my, gp.nx, gp.ny)) > p.sep.groupOff ||
                    abs(dot(s.p1x - gp.mx, s.p1y - gp.my, gp.nx, gp.ny)) > p.sep.groupOff) continue
                val a0 = dot(s.p0x - gp.mx, s.p0y - gp.my, gp.dx, gp.dy)
                val a1 = dot(s.p1x - gp.mx, s.p1y - gp.my, gp.dx, gp.dy)
                gp.iv.add(doubleArrayOf(min(a0, a1), max(a0, a1)))
                gp.segs.add(s)
                placed = true
                break
            }
            if (!placed) {
                val gp = G(s.mx, s.my, s.dx, s.dy, s.nx, s.ny, s.ang)
                gp.iv.add(doubleArrayOf(s.t0, s.t1))
                gp.segs.add(s)
                groups.add(gp)
            }
        }
        return groups.map { gp ->
            val iv = gp.iv.sortedWith(compareBy<DoubleArray>({ it[0] }, { it[1] }))
            val mg = ArrayList<DoubleArray>()
            mg.add(iv[0].copyOf())
            for (k in 1 until iv.size) {
                val a = iv[k][0]
                val b = iv[k][1]
                if (a <= mg.last()[1] + 1) mg.last()[1] = max(mg.last()[1], b) else mg.add(doubleArrayOf(a, b))
            }
            Group(gp.mx, gp.my, gp.dx, gp.dy, gp.nx, gp.ny, gp.ang, mg.map { Iv(it[0], it[1]) }, gp.segs)
        }
    }

    /** 區間之間的斷口 ≤ bridge 就補起來（輸入已排序）。 */
    private fun bridged(iv: List<Iv>, bridge: Double): List<Iv> {
        val out = ArrayList<DoubleArray>()
        out.add(doubleArrayOf(iv[0].lo, iv[0].hi))
        for (k in 1 until iv.size) {
            val a = iv[k].lo
            val b = iv[k].hi
            if (a - out.last()[1] <= bridge) out.last()[1] = max(out.last()[1], b) else out.add(doubleArrayOf(a, b))
        }
        return out.map { Iv(it[0], it[1]) }
    }

    private fun intersect(a: List<Iv>, b: List<Iv>): List<Iv> {
        val out = ArrayList<Iv>()
        for (x in a) for (y in b) {
            val lo = max(x.lo, y.lo)
            val hi = min(x.hi, y.hi)
            if (hi > lo) out.add(Iv(lo, hi))
        }
        return out
    }

    // ── 分鏡溝：兩條近平行框線之間、橫跨全白的帶 ─────────────────────────────

    private fun lineOffset(gp: Group, px: Double, py: Double) = dot(px - gp.mx, py - gp.my, gp.nx, gp.ny)

    /** src 群組的區間端點投影到 dst 的方向座標（排序後）。 */
    private fun projIv(src: Group, dst: Group): List<Iv> {
        val out = ArrayList<Iv>()
        for (v in src.iv) {
            val u0 = dot(src.mx + v.lo * src.dx - dst.mx, src.my + v.lo * src.dy - dst.my, dst.dx, dst.dy)
            val u1 = dot(src.mx + v.hi * src.dx - dst.mx, src.my + v.hi * src.dy - dst.my, dst.dx, dst.dy)
            out.add(Iv(min(u0, u1), max(u0, u1)))
        }
        return out.sortedWith(compareBy<Iv>({ it.lo }, { it.hi }))
    }

    private fun inIv(u: Double, ivs: List<Iv>): Boolean {
        for (v in ivs) if (u >= v.lo - 2.0 && u <= v.hi + 2.0) return true
        return false
    }

    /** 兩群組能不能夾成一條溝：夾角、b 的端點都在 a 同一側、法向距在 [gapMin, wmax]。回傳 [da, sg, gap] 或 null。 */
    private fun pairGeom(a: Group, b: Group, wmax: Double, p: NightReadParams): DoubleArray? {
        val da = angDiff(a.ang, b.ang)
        if (da > p.sep.pairAng) return null
        val offs = DoubleArray(b.iv.size * 2)
        var k = 0
        for (v in b.iv) {
            offs[k++] = lineOffset(a, b.mx + v.lo * b.dx, b.my + v.lo * b.dy)
            offs[k++] = lineOffset(a, b.mx + v.hi * b.dx, b.my + v.hi * b.dy)
        }
        if (!(offs.all { it > 0 } || offs.all { it < 0 })) return null
        val ab = DoubleArray(offs.size) { abs(offs[it]) }
        if (ab.min() < p.sep.gapMin || ab.max() > wmax) return null
        return doubleArrayOf(da, if (offs[0] > 0) 1.0 else -1.0, Cv.npSum(ab) / ab.size)
    }

    /** 第三條近平行長線落在 a 外側或 b 外側 ≤ FAMILY_REACH×溝寬 內、且沿線有重疊 ⇒ 排線家族。回傳 k 或 -1。 */
    private fun familyHit(groups: List<Group>, i: Int, j: Int, geo: DoubleArray, a: Group, b: Group, p: NightReadParams): Int {
        val w = geo[2]
        val sg = geo[1]
        val ua = a.iv
        val ubb = projIv(b, a)
        val lo = max(ua.minOf { it.lo }, ubb.minOf { it.lo })
        val hi = min(ua.maxOf { it.hi }, ubb.maxOf { it.hi })
        for ((k, c) in groups.withIndex()) {
            if (k == i || k == j) continue
            if (angDiff(a.ang, c.ang) > p.sep.familyAng) continue
            val uc = projIv(c, a)
            val clo = uc.minOf { it.lo }
            val chi = uc.maxOf { it.hi }
            if (min(hi, chi) - max(lo, clo) < 0.5 * (hi - lo)) continue
            val os = DoubleArray(c.iv.size * 2)
            var q = 0
            for (v in c.iv) {
                os[q++] = sg * lineOffset(a, c.mx + v.lo * c.dx, c.my + v.lo * c.dy)
                os[q++] = sg * lineOffset(a, c.mx + v.hi * c.dx, c.my + v.hi * c.dy)
            }
            val o = Cv.npSum(os) / os.size
            if ((-p.sep.familyReach * w <= o && o < -p.sep.gapMin) || (w + p.sep.gapMin < o && o <= (1 + p.sep.familyReach) * w)) return k
        }
        return -1
    }

    /** 碰得到頁邊的白連通元件（8 連通）。 */
    private fun outsideWhite(g: Gray, p: NightReadParams): Mask {
        // ＝白 8 連通元件中含頁緣像素者的聯集：從頁緣上的白像素做洪水填充（不必標整頁的元件）
        val w = g.w
        val h = g.h
        val out = Mask(w, h)
        val od = out.data
        var n = 0
        for (v in g.data) if (v >= p.whiteTh) n++
        val q = IntArray(n)
        var tail = 0
        fun seed(i: Int) { if (!od[i] && g.data[i] >= p.whiteTh) { od[i] = true; q[tail++] = i } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        var head = 0
        while (head < tail) {
            val i = q[head++]
            val x = i % w
            val y = i / w
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    seed(yy * w + xx)
                }
            }
        }
        return out
    }

    /** 就地拿掉 8 連通面積 < minArea 的前景塊（＝ connectedComponentsWithStats 取面積 < minArea 的標號清掉）。 */
    private fun removeSmall(m: Mask, minArea: Int) {
        val w = m.w
        val h = m.h
        val d = m.data
        var n = 0
        for (v in d) if (v) n++
        if (n == 0) return
        val seen = BooleanArray(w * h)
        val q = IntArray(n)
        for (s0 in d.indices) {
            if (!d[s0] || seen[s0]) continue
            val start = 0
            var tail = 0
            seen[s0] = true
            q[tail++] = s0
            var head = start
            while (head < tail) {
                val i = q[head++]
                val x = i % w
                val y = i / w
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val j = yy * w + xx
                        if (d[j] && !seen[j]) { seen[j] = true; q[tail++] = j }
                    }
                }
            }
            if (tail < minArea) for (k in 0 until tail) d[q[k]] = false
        }
    }

    /** 逐段（未補橋的真框線區間）算白緣 MAD（去線性趨勢），依站數加權平均；沒有樣本回 99。 */
    private fun mad(us: List<DoubleArray>, es: List<DoubleArray>, ivs: List<Iv>): Double {
        if (us.isEmpty()) return 99.0
        val total = us.sumOf { it.size }
        val u = DoubleArray(total)
        val e = DoubleArray(total)
        var k = 0
        for (q in us.indices) {
            System.arraycopy(us[q], 0, u, k, us[q].size)
            System.arraycopy(es[q], 0, e, k, es[q].size)
            k += us[q].size
        }
        var tot = 0.0
        var wsum = 0
        for (v in ivs) {
            var n = 0
            for (i in 0 until total) if (u[i] >= v.lo + 2 && u[i] <= v.hi - 2) n++
            if (n < 10) continue
            val vv = DoubleArray(n)
            val uu = DoubleArray(n)
            n = 0
            for (i in 0 until total) if (u[i] >= v.lo + 2 && u[i] <= v.hi - 2) { vv[n] = e[i]; uu[n] = u[i]; n++ }
            val mean = Cv.npSum(uu) / n
            val uc = DoubleArray(n) { uu[it] - mean }
            // 最小平方 [uc, 1]·coef ≈ vv（numpy 用 SVD；這裡解 2×2 正規方程，差在最後幾位）
            var suu = 0.0; var su = 0.0; var suv = 0.0; var sv = 0.0
            for (i in 0 until n) { suu += uc[i] * uc[i]; su += uc[i]; suv += uc[i] * vv[i]; sv += vv[i] }
            val det = suu * n - su * su
            val c0: Double
            val c1: Double
            if (det != 0.0) { c0 = (suv * n - su * sv) / det; c1 = (suu * sv - su * suv) / det } else { c0 = 0.0; c1 = sv / n }
            val r = DoubleArray(n) { vv[it] - (uc[it] * c0 + c1) }
            val med = median(r)
            val dev = DoubleArray(n) { abs(r[it] - med) }
            tot += (1.4826 * median(dev)) * n
            wsum += n
        }
        return if (wsum > 0) tot / wsum else 99.0
    }

    /** `np.median`：排序取中；偶數個取中間兩個的平均（(a+b)/2）。 */
    private fun median(a: DoubleArray): Double {
        val s = a.copyOf()
        s.sort()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** 一對平行框線之間的溝帶（`pair_strip`）：沿 a 的方向逐站（1px）量剖面白度。回傳結果寫進 [st]。 */
    private fun pairStrip(
        g: Gray, a: Group, b: Group, geo: DoubleArray, ovlMin: Double, bridge: Double,
        textd: Mask?, outside: Mask?, st: PairRes, p: NightReadParams,
    ) {
        val w = g.w
        val h = g.h
        val sg = geo[1]
        val ub = projIv(b, a)
        val u0 = intersect(bridged(a.iv, bridge), bridged(ub, bridge)).filter { it.hi - it.lo >= ovlMin }
        if (u0.isEmpty()) {
            st.madA = 99.0; st.madB = 99.0; st.passFrac = 0.0; st.textFrac = 0.0; st.why = "noovl"
            return
        }
        val sb = if (dot(a.mx - b.mx, a.my - b.my, b.nx, b.ny) > 0) 1.0 else -1.0
        val skip = min(max(0.25 * geo[2], 1.0), p.sep.edgeSkip.toDouble())
        // 端延伸的上限：只沿還有一側框線的範圍（hull），且 ≤ 短邊×STRIP_EXT_FRAC
        val hull0 = (bridged(a.iv, bridge) + bridged(ub, bridge)).sortedWith(compareBy<Iv>({ it.lo }, { it.hi }))
        val hull = bridged(hull0, 0.0)
        val ext = p.sep.stripExtFrac * min(h, w)
        val uList = u0.map { c ->
            val hl = hull.firstOrNull { it.lo <= c.lo + 1 && it.hi >= c.hi - 1 }
            val hlo = hl?.lo ?: c.lo
            val hhi = hl?.hi ?: c.hi
            doubleArrayOf(c.lo, c.hi, max(c.lo - ext, hlo), min(c.hi + ext, hhi))
        }
        var npass = 0
        var nvalid = 0
        var ntext = 0
        val edgeUa = ArrayList<DoubleArray>()
        val edgeEa = ArrayList<DoubleArray>()
        val edgeUb = ArrayList<DoubleArray>()
        val edgeEb = ArrayList<DoubleArray>()
        val parts = ArrayList<Patch>()
        val dnB = dot(a.nx, a.ny, b.nx, b.ny)
        for (uv in uList) {
            val loCore = uv[0]
            val hiCore = uv[1]
            val lo = uv[2]
            val hi = uv[3]
            val pa0x = a.mx + lo * a.dx; val pa0y = a.my + lo * a.dy
            val pa1x = a.mx + hi * a.dx; val pa1y = a.my + hi * a.dy
            val s0 = -dot(pa0x - b.mx, pa0y - b.my, b.nx, b.ny) / dnB
            val s1 = -dot(pa1x - b.mx, pa1y - b.my, b.nx, b.ny) / dnB
            val pb0x = pa0x + s0 * a.nx; val pb0y = pa0y + s0 * a.ny
            val pb1x = pa1x + s1 * a.nx; val pb1y = pa1y + s1 * a.ny
            val xmn = minOf(minOf(pa0x, pa1x), minOf(pb0x, pb1x))
            val xmx = maxOf(maxOf(pa0x, pa1x), maxOf(pb0x, pb1x))
            val ymn = minOf(minOf(pa0y, pa1y), minOf(pb0y, pb1y))
            val ymx = maxOf(maxOf(pa0y, pa1y), maxOf(pb0y, pb1y))
            val x0 = max(0, floor(xmn).toInt() - 1)
            val x1 = min(w, ceil(xmx).toInt() + 2)
            val y0 = max(0, floor(ymn).toInt() - 1)
            val y1 = min(h, ceil(ymx).toInt() + 2)
            if (x1 <= x0 || y1 <= y0) continue
            val nb = (hi - lo).toInt() + 1
            val cnt = IntArray(nb)
            val cw = IntArray(nb)
            val tx = BooleanArray(nb)
            val ea = DoubleArray(nb) { 1e9 }
            val eb = DoubleArray(nb) { 1e9 }
            var anyQuad = false
            // 第一趟：逐站統計（剖面內部像素數／白數、含字、白緣最近距離）
            for (yy in y0 until y1) {
                val py = yy - a.my
                val qy = yy - b.my
                for (xx in x0 until x1) {
                    val px = xx - a.mx
                    val u = px * a.dx + py * a.dy
                    if (u < lo || u > hi) continue
                    val va = sg * (px * a.nx + py * a.ny)
                    if (va < 0) continue
                    val vb = sb * ((xx - b.mx) * b.nx + qy * b.ny)
                    if (vb < 0) continue
                    anyQuad = true
                    val bin = min(max(rint(u - lo), 0), nb - 1)
                    val i = yy * w + xx
                    val wt = g.data[i] >= p.whiteTh
                    if (va > skip && vb > skip) { cnt[bin]++; if (wt) cw[bin]++ }
                    if (textd != null && textd.data[i]) tx[bin] = true
                    if (wt) { if (va < ea[bin]) ea[bin] = va; if (vb < eb[bin]) eb[bin] = vb }
                }
            }
            if (!anyQuad) continue
            val uu = DoubleArray(nb) { lo + it }
            val ev = BooleanArray(nb) { inIv(uu[it], a.iv) && inIv(uu[it], ub) }
            val c3 = IntArray(nb)
            val w3 = IntArray(nb)
            for (k in 0 until nb) {
                c3[k] = cnt[k] + (if (k > 0) cnt[k - 1] else 0) + (if (k < nb - 1) cnt[k + 1] else 0)
                w3[k] = cw[k] + (if (k > 0) cw[k - 1] else 0) + (if (k < nb - 1) cw[k + 1] else 0)
            }
            val valid = BooleanArray(nb) { cnt[it] >= 1 && c3[it] >= 4 }
            val frac = DoubleArray(nb) { w3[it].toDouble() / max(c3[it], 1) }
            val core = BooleanArray(nb) { uu[it] >= loCore && uu[it] <= hiCore }
            val few = BooleanArray(nb) { c3[it] - w3[it] <= 3 }
            val ok = BooleanArray(nb) {
                valid[it] && if (ev[it] || !core[it]) (frac[it] >= p.sep.profWhite || few[it])
                else (frac[it] >= p.sep.profWhiteBridge || c3[it] - w3[it] <= 1)
            }
            if (textd != null) {
                for (k in 0 until nb) {
                    if (tx[k] && valid[k] && ev[k]) ntext++
                    if (tx[k]) ok[k] = false
                }
            }
            // 小缺口（≤ PROF_GAP_FILL 站、前後都過、不含字）補起來
            var k = 0
            while (k < nb) {
                if (ok[k] || !valid[k]) { k++; continue }
                var e = k
                while (e < nb && !ok[e] && valid[e]) e++
                var txAny = false
                if (textd != null) for (q in k until e) if (tx[q]) txAny = true
                if (k > 0 && e < nb && ok[k - 1] && ok[e] && e - k <= p.sep.profGapFill && !txAny) {
                    for (q in k until e) ok[q] = true
                }
                k = max(e, k + 1)
            }
            // 補橋段（核心內、無兩側框線證據）：短斷口用一般白度門；整段全過才收
            k = 0
            while (k < nb) {
                if (ev[k] || !core[k]) { k++; continue }
                var e = k
                while (e < nb && !ev[e] && core[e]) e++
                if (e - k <= p.sep.shortBridge) {
                    for (q in k until e) {
                        var okk = valid[q] && (frac[q] >= p.sep.profWhite || few[q])
                        if (textd != null && tx[q]) okk = false
                        ok[q] = okk
                    }
                }
                var anyValid = false
                var allOk = true
                for (q in k until e) if (valid[q]) { anyValid = true; if (!ok[q]) allOk = false }
                val runOk = anyValid && allOk
                if (!runOk) for (q in k until e) ok[q] = false
                k = e
            }
            // 端延伸：從核心邊界往外，先跨過格角暗站（≤ EXT_DARK_SKIP），遇到第一個不過就停
            var kc0 = -1
            var kc1 = -1
            for (q in 0 until nb) if (core[q]) { if (kc0 < 0) kc0 = q; kc1 = q }
            if (kc0 >= 0) {
                val keep = core.copyOf()
                for ((start, step) in listOf(kc0 - 1 to -1, kc1 + 1 to 1)) {
                    var q = start
                    var skipped = 0
                    while (q in 0 until nb && !ok[q] && skipped < p.sep.extDarkSkip) { q += step; skipped++ }
                    while (q in 0 until nb && ok[q]) { keep[q] = true; q += step }
                }
                for (q in 0 until nb) if (!keep[q]) ok[q] = false
            }
            for (q in 0 until nb) {
                if (ok[q] && ev[q]) npass++
                if (valid[q] && ev[q]) nvalid++
            }
            // 白緣（量直線度用）
            run {
                var na = 0
                var nbb = 0
                for (q in 0 until nb) { if (ok[q] && ea[q] < 1e9) na++; if (ok[q] && eb[q] < 1e9) nbb++ }
                val ua = DoubleArray(na); val ra = DoubleArray(na)
                val ubv = DoubleArray(nbb); val rb = DoubleArray(nbb)
                na = 0; nbb = 0
                for (q in 0 until nb) {
                    if (ok[q] && ea[q] < 1e9) { ua[na] = uu[q]; ra[na] = ea[q]; na++ }
                    if (ok[q] && eb[q] < 1e9) { ubv[nbb] = uu[q]; rb[nbb] = eb[q]; nbb++ }
                }
                edgeUa.add(ua); edgeEa.add(ra); edgeUb.add(ubv); edgeEb.add(rb)
            }
            // 第二趟：白 ∧ 該站通過（∧ 頁外連通白），只收兩側都碰到框線的連通塊
            val bw = x1 - x0
            val bh = y1 - y0
            val mk = Mask(bw, bh)
            val touchA = BooleanArray(bw * bh)
            val touchB = BooleanArray(bw * bh)
            for (yy in y0 until y1) {
                val py = yy - a.my
                val qy = yy - b.my
                for (xx in x0 until x1) {
                    val i = yy * w + xx
                    if (g.data[i] < p.whiteTh) continue
                    if (outside != null && !outside.data[i]) continue
                    val px = xx - a.mx
                    val u = px * a.dx + py * a.dy
                    if (u < lo || u > hi) continue
                    val va = sg * (px * a.nx + py * a.ny)
                    if (va < 0) continue
                    val vb = sb * ((xx - b.mx) * b.nx + qy * b.ny)
                    if (vb < 0) continue
                    val bin = min(max(rint(u - lo), 0), nb - 1)
                    if (!ok[bin]) continue
                    val j = (yy - y0) * bw + (xx - x0)
                    mk.data[j] = true
                    if (va <= skip + 3) touchA[j] = true
                    if (vb <= skip + 3) touchB[j] = true
                }
            }
            if (!mk.any()) continue
            val cc = Cv.ccStats(mk, 8)
            val ta = BooleanArray(cc.n)
            val tb = BooleanArray(cc.n)
            for (j in mk.data.indices) {
                if (touchA[j]) ta[cc.labels[j]] = true
                if (touchB[j]) tb[cc.labels[j]] = true
            }
            val data = BooleanArray(bw * bh) { val l = cc.labels[it]; l > 0 && ta[l] && tb[l] }
            parts.add(Patch(x0, y0, bw, bh, data))
        }
        st.madA = mad(edgeUa, edgeEa, a.iv)
        st.madB = mad(edgeUb, edgeEb, ub)
        st.passFrac = npass.toDouble() / max(nvalid, 1)
        st.textFrac = ntext.toDouble() / max(nvalid, 1)
        val full = mergePatches(parts)
        st.px = full?.count() ?: 0
        st.a = a; st.b = b; st.sg = sg; st.sb = sb
        st.cores = uList.map { Iv(it[0], it[1]) }
        st.why = when {
            st.passFrac < p.sep.passMin -> "pass"
            max(st.madA, st.madB) > p.sep.edgeMadMax -> "mad"
            st.textFrac > p.sep.textProfMax -> "text"
            st.px == 0 -> "empty"
            else -> ""
        }
        if (st.why.isEmpty()) st.mask = full
    }

    private fun mergePatches(parts: List<Patch>): Patch? {
        if (parts.isEmpty()) return null
        val x0 = parts.minOf { it.x0 }
        val y0 = parts.minOf { it.y0 }
        val x1 = parts.maxOf { it.x0 + it.w }
        val y1 = parts.maxOf { it.y0 + it.h }
        val w = x1 - x0
        val out = BooleanArray(w * (y1 - y0))
        for (pt in parts) for (y in 0 until pt.h) {
            val src = y * pt.w
            val dst = (pt.y0 - y0 + y) * w + (pt.x0 - x0)
            for (x in 0 until pt.w) if (pt.data[src + x]) out[dst + x] = true
        }
        return Patch(x0, y0, w, y1 - y0, out)
    }

    /** 所有群組兩兩配對 → 溝帶（長的那條當 a）。回傳逐對結果（含被拒的，供統計／除錯）。 */
    private fun separatorPairs(g: Gray, groups: List<Group>, wmax: Double, ovlMin: Double, bridge: Double,
                               seg: Mask?, p: NightReadParams): List<PairRes> {
        // 兩張整頁遮罩只在第一對過了幾何門檻時才算（多數頁只有少數對、有的頁一對都沒有）
        val textd by lazy { seg?.let { dilateSquare(it, 2 * p.sep.textDil + 1) } }
        val outside by lazy { outsideWhite(g, p) }
        val pairs = ArrayList<PairRes>()
        for (i in groups.indices) {
            for (j in i + 1 until groups.size) {
                val ii = if (groups[i].len >= groups[j].len) i else j
                val jj = if (ii == i) j else i
                val a = groups[ii]
                val b = groups[jj]
                val geo = pairGeom(a, b, wmax, p) ?: continue
                val fam = familyHit(groups, ii, jj, geo, a, b, p)
                val st = PairRes(i, j)
                pairStrip(g, a, b, geo, ovlMin, bridge, textd, outside, st, p)
                if (fam >= 0 && st.mask != null) { st.why = "family$fam"; st.mask = null }
                st.acc = st.mask != null
                pairs.add(st)
            }
        }
        return pairs
    }

    // ── 溝網連通 ────────────────────────────────────────────────────────────

    /**
     * 溝帶的走廊：兩條框線之間、核心區間各往外延伸 ext 的平行四邊形（cv2.fillPoly 點陣）。
     * 回傳畫到的範圍 [x0, y0, x1, y1)（頂點外框外擴 2px、裁進影像；fillPoly 不會畫出頂點外框）。
     */
    private fun corridor(m: Mask, q: PairRes, ext: Double): IntArray {
        val a = q.a!!
        val b = q.b!!
        val dnB = dot(a.nx, a.ny, b.nx, b.ny)
        var bx0 = Int.MAX_VALUE; var by0 = Int.MAX_VALUE; var bx1 = Int.MIN_VALUE; var by1 = Int.MIN_VALUE
        for (c in q.cores) {
            val px = DoubleArray(4)
            val py = DoubleArray(4)
            var k = 0
            for (u in doubleArrayOf(c.lo - ext, c.hi + ext)) {
                val ptx = a.mx + u * a.dx
                val pty = a.my + u * a.dy
                val s = -dot(ptx - b.mx, pty - b.my, b.nx, b.ny) / dnB
                px[k] = ptx; py[k] = pty; k++
                px[k] = ptx + s * a.nx; py[k] = pty + s * a.ny; k++
            }
            // poly = [pts0, pts2, pts3, pts1]
            val ord = intArrayOf(0, 2, 3, 1)
            val xs = IntArray(4) { rint(px[ord[it]]) }
            val ys = IntArray(4) { rint(py[ord[it]]) }
            Cv.fillPoly(m, xs, ys)
            bx0 = min(bx0, xs.min()); bx1 = max(bx1, xs.max()); by0 = min(by0, ys.min()); by1 = max(by1, ys.max())
        }
        return intArrayOf(max(0, bx0 - 2), max(0, by0 - 2), min(m.w, bx1 + 3), min(m.h, by1 + 3))
    }

    /**
     * 溝帶一定是「溝網」的一段：從頁緣／頁邊出發，沿彼此相接的溝帶一路連進來。接法＝溝帶本身、或沿自己的走廊
     * 經可通行像素（白，或粗黑塊⊕），碰到頁邊／頁緣帶／已收的溝帶（外擴 NET_TOUCH）。孤立的 ⇒ acc=false、why="isolated"。
     *
     * 與研究端逐步等價、但全在 bbox 內算：走廊只在頂點外框內點陣化；可達區在（走廊 ∪ 溝帶）的框內做連通；
     * 「可達區 ⊕5×5 碰到根」改成可達區在自己外擴 2px 的框內膨脹後與根求交（方核對稱 ⇒ 同一個判定）。
     */
    private fun networkFilter(pairs: List<PairRes>, margin: Mask, g: Gray, p: NightReadParams): Mask {
        val w = g.w
        val h = g.h
        var todo = pairs.filter { it.acc }
        val out = Mask(w, h)
        if (todo.isEmpty()) return out
        val root = margin.copy()
        val e = p.sep.netTouch + 2
        for (y in 0 until h) {
            val base = y * w
            if (y < e || y >= h - e) { java.util.Arrays.fill(root.data, base, base + w, true); continue }
            for (x in 0 until e) root.data[base + x] = true
            for (x in w - e until w) root.data[base + x] = true
        }
        val ext = p.sep.netExtFrac * min(h, w)
        // 可通行＝白，或粗黑塊（chamfer ≥ NET_THICK，筆畫寬 ≥ 10px）連同其抗鋸齒邊
        val dark = g.lt(p.frameDarkTh)
        val dt = Cv.distanceChamfer(dark, 3)
        val core = Mask(w, h, BooleanArray(w * h) { dt.data[it] >= p.sep.netThick })
        val coreD = dilateSquare(core, 2 * p.sep.netThick + 1)
        val passable = Mask(w, h, BooleanArray(w * h) { g.data[it] >= p.whiteTh || coreD.data[it] })
        val reachD = HashMap<PairRes, Patch>()
        val cor = Mask(w, h)
        val t = p.sep.netTouch
        for (q in todo) {
            val cb = corridor(cor, q, ext)
            val mk = q.mask!!
            val x0 = min(cb[0], mk.x0); val y0 = min(cb[1], mk.y0)
            val x1 = max(cb[2], mk.x0 + mk.w); val y1 = max(cb[3], mk.y0 + mk.h)
            val bw = x1 - x0
            val bh = y1 - y0
            val rm = Mask(bw, bh)
            for (y in 0 until bh) {
                val src = (y0 + y) * w + x0
                for (x in 0 until bw) rm.data[y * bw + x] = cor.data[src + x] && passable.data[src + x]
            }
            for (y in cb[1] until cb[3]) java.util.Arrays.fill(cor.data, y * w + cb[0], y * w + cb[2], false)
            for (y in 0 until mk.h) for (x in 0 until mk.w) {
                if (mk.data[y * mk.w + x]) rm.data[(mk.y0 - y0 + y) * bw + (mk.x0 - x0 + x)] = true
            }
            val cc = Cv.ccStats(rm, 8)
            val ids = BooleanArray(cc.n)
            for (y in 0 until mk.h) for (x in 0 until mk.w) {
                if (mk.data[y * mk.w + x]) ids[cc.labels[(mk.y0 - y0 + y) * bw + (mk.x0 - x0 + x)]] = true
            }
            ids[0] = false
            // 可達區在外擴 t px 的框內膨脹（框外沒有可達像素，框內膨脹即全圖膨脹）
            val px0 = max(0, x0 - t); val py0 = max(0, y0 - t)
            val px1 = min(w, x1 + t); val py1 = min(h, y1 + t)
            val pw = px1 - px0
            val ph = py1 - py0
            val rp = Mask(pw, ph)
            for (y in 0 until bh) for (x in 0 until bw) {
                if (ids[cc.labels[y * bw + x]]) rp.data[(y0 - py0 + y) * pw + (x0 - px0 + x)] = true
            }
            val rd = dilateSquare(rp, 2 * t + 1)
            reachD[q] = Patch(px0, py0, pw, ph, rd.data)
        }
        var grown = true
        while (grown) {                              // 逐輪擴張：接上網的溝帶本身成為新的根
            grown = false
            val rest = ArrayList<PairRes>()
            for (q in todo) {
                if (reachD[q]!!.countAnd(root) > 0) {
                    q.mask!!.orInto(root)
                    grown = true
                } else {
                    rest.add(q)
                }
            }
            todo = rest
        }
        for (q in todo) { q.acc = false; q.why = "isolated" }
        for (q in pairs) if (q.acc) q.mask!!.orInto(out)
        return out
    }

    // ── 頁邊：頁緣到框線之間、途中沒有畫的白 ─────────────────────────────────

    private fun rasterLines(w: Int, h: Int, lines: List<DoubleArray>, r: Int): Mask {
        val m = Mask(w, h)
        for (l in lines) Cv.line(m, rint(l[0]), rint(l[1]), rint(l[2]), rint(l[3]), 2 * r + 1)
        return m
    }

    private fun groupLinesOf(gp: Group): List<DoubleArray> = gp.iv.map {
        doubleArrayOf(gp.mx + it.lo * gp.dx, gp.my + it.lo * gp.dy, gp.mx + it.hi * gp.dx, gp.my + it.hi * gp.dy)
    }

    /** 偵測到的框線（只取真證據區間）點陣化：線 ±flR 內的非白像素（`frame_raster`）。 */
    fun frameRaster(g: Gray, groups: List<Group>, p: NightReadParams): Mask {
        val m = rasterLines(g.w, g.h, groups.flatMap { groupLinesOf(it) }, p.sep.flR)
        for (i in m.data.indices) if (g.data[i] >= p.whiteTh) m.data[i] = false
        return m
    }

    /** 框線兩端沿線延伸：跨過格角暗像素、再只走白，撞到暗像素或頁緣且 ≤ maxd 才收（封口延長線）。 */
    private fun extensions(g: Gray, gp: Group, maxd: Double, p: NightReadParams): List<DoubleArray> {
        val w = g.w
        val h = g.h
        val out = ArrayList<DoubleArray>()
        for (v in gp.iv) {
            for ((tEnd, sgn) in listOf(v.lo to -1.0, v.hi to 1.0)) {
                val px = gp.mx + tEnd * gp.dx
                val py = gp.my + tEnd * gp.dy
                var k = 1
                while (k <= p.sep.extDarkSkip) {                   // 跨格角
                    val xi = rint(px + (sgn * k) * gp.dx)
                    val yi = rint(py + (sgn * k) * gp.dy)
                    if (!(xi in 0 until w && yi in 0 until h) || g.data[yi * w + xi] >= p.whiteTh) break
                    k++
                }
                val start = k
                var closed = false
                while (k <= start + maxd) {
                    val xi = rint(px + (sgn * k) * gp.dx)
                    val yi = rint(py + (sgn * k) * gp.dy)
                    if (!(xi in 0 until w && yi in 0 until h)) { closed = true; break }     // 頁緣封口
                    if (g.data[yi * w + xi] < p.whiteTh) { closed = true; break }         // 撞到下一條框線／畫
                    k++
                }
                if (closed && k > start) {
                    out.add(doubleArrayOf(px + (sgn * start) * gp.dx, py + (sgn * start) * gp.dy,
                        px + (sgn * k) * gp.dx, py + (sgn * k) * gp.dy))
                }
            }
        }
        return out
    }

    /**
     * 補列（`_close_rows`）：兩側都是頁邊行程、長 ≤ gap 的一段未中列裡，在框線前（或框線帶內）就被擋下的列——
     * d ≤ maxd、d ≤ 兩側 d 的內插 + flHitR——且 ok(r) ⇒ 也算頁邊。內插用 double（同 python）。
     * 回傳 reach：補上的列＝max(d, 內插)，其餘 −1；hit0 就地改。
     */
    private fun closeRows(hit0: BooleanArray, d: IntArray, maxd: Double, gap: Int, flHitR: Int, ok: (Int) -> Boolean): DoubleArray {
        val n = hit0.size
        val orig = hit0.copyOf()
        val reach = DoubleArray(n) { -1.0 }
        var k = 0
        while (k < n) {
            if (orig[k]) { k++; continue }
            var e = k
            while (e < n && !orig[e]) e++
            if (k > 0 && e < n && e - k <= gap) {
                val da = d[k - 1].toDouble()
                val db = d[e].toDouble()
                for (r in k until e) {
                    val di = da + (db - da) * (r - k + 1) / (e - k + 1)
                    if (d[r] <= maxd && d[r] <= di + flHitR && ok(r)) {
                        hit0[r] = true
                        reach[r] = max(d[r].toDouble(), di)
                    }
                }
            }
            k = e
        }
        return reach
    }

    /** `_behind`：從種子白（4 連通）在 band∧whiteOk 內漫淹，＝python「band∧whiteOk 的 4 連通元件裡含種子白的那些」。 */
    private fun behind(whiteOk: Mask, seedSide: Mask, band: Mask): Mask {
        val w = whiteOk.w
        val h = whiteOk.h
        val out = Mask(w, h)
        val q = IntArray(w * h)
        var head = 0
        var tail = 0
        for (i in out.data.indices) if (seedSide.data[i] && whiteOk.data[i] && band.data[i]) { out.data[i] = true; q[tail++] = i }
        while (head < tail) {
            val i = q[head++]
            val x = i % w
            val y = i / w
            fun push(j: Int) { if (!out.data[j] && whiteOk.data[j] && band.data[j]) { out.data[j] = true; q[tail++] = j } }
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        return out
    }

    /** 一維開運算：丟掉長度 < l 的連續 true 段。 */
    private fun open1d(b: BooleanArray, l: Int): BooleanArray {
        val out = b.copyOf()
        var k = 0
        val n = b.size
        while (k < n) {
            if (!b[k]) { k++; continue }
            var e = k
            while (e < n && b[e]) e++
            if (e - k < l) for (q in k until e) out[q] = false
            k = e
        }
        return out
    }

    /**
     * 頁邊＝從頁緣沿軸向往內、途中只經過白／字／泡、在 maxd 內撞到一條頁邊框線（或其封口延長線）的那一段白
     * （`margin_mask`）。行程要連成 ≥ run_min 的段、亮而不白的像素不能多、撞到的若是溝的框線而另一側是溝就不算。
     */
    private fun marginMask(g: Gray, groups: List<Group>, seg: Mask?, bubble: Mask?, trusted: Set<Int>,
                           stripPre: Mask, p: NightReadParams, extra: List<Group> = emptyList()): Mask {
        val w = g.w
        val h = g.h
        val sh = min(h, w)
        val maxd = p.sep.marginFrac * sh
        val white = g.ge(p.whiteTh)
        val okm = g.ge(p.sep.marginOkTh)
        var txd = Mask(w, h)
        if (seg != null) {
            txd = dilateSquare(seg, 2 * p.sep.textDil + 1)
            okm.orInPlace(txd)
        }
        var bub = Mask(w, h)
        if (bubble != null && bubble.any()) {
            bub = dilateSquare(bubble, 7)
            okm.orInPlace(bub)
        }
        fun pick(target: Double): List<Group> = groups.withIndex().filter { (k, gp) ->
            val off = angDiff(gp.ang, target)
            if (k in trusted) off <= p.sep.marginEdgeAng
            else off <= p.sep.marginAxisAng && gp.fill >= p.sep.marginFillMin
        }.map { it.value }
        fun pickExtra(target: Double): List<Group> = extra.filter { gp ->
            angDiff(gp.ang, target) <= p.sep.marginAxisAng && gp.fill >= p.sep.marginFillMin
        }
        val fls = HashMap<String, Mask>()
        for ((key, target) in listOf("v" to 90.0, "h" to 0.0)) {
            val gs = pick(target)
            val lines = ArrayList<DoubleArray>()
            val exts = ArrayList<DoubleArray>()
            for (gp in gs) {
                lines.addAll(groupLinesOf(gp))
                exts.addAll(extensions(g, gp, maxd, p))
            }
            // 斷框線的各截只進「撞到框線」的粗帶（±flHitR 內的非白）：不畫細線、不做延長線、斷口不補（交給補列）
            val xl = pickExtra(target).flatMap { groupLinesOf(it) }
            val thick = rasterLines(w, h, lines + xl, p.sep.flHitR)
            val thin = rasterLines(w, h, lines + exts, 1)
            fls[key] = Mask(w, h, BooleanArray(w * h) { (thick.data[it] && !white.data[it]) || thin.data[it] })
        }
        val light = BooleanArray(w * h) { okm.data[it] && !white.data[it] && !txd.data[it] && !bub.data[it] }
        val trl = trusted.filter { it < groups.size }.map { groups[it] }
        val flTr = rasterLines(w, h, trl.flatMap { groupLinesOf(it) }, p.sep.flHitR)
        for (i in flTr.data.indices) if (white.data[i]) flTr.data[i] = false
        val runMin = max(3, rint(p.sep.marginRunFrac * sh))
        val closeGap = rint(p.sep.occGapFrac * sh)
        val whiteOk = Mask(w, h, BooleanArray(w * h) { white.data[it] && !bub.data[it] })
        // 補列的淡網點判準：暗像素（< frameDarkTh）的抗鋸齒暈屬於擋路的物件，不算（整頁只算一次、要用才算）
        val light2 by lazy {
            val halo = dilateSquare(g.lt(p.frameDarkTh), 2 * p.sep.marginHaloR + 1)
            BooleanArray(w * h) { light[it] && !halo.data[it] }
        }
        val seeds = Mask(w, h)
        // 左／右：逐列從頁緣往內，第一個「非白非字非泡、或框線（含延長線）」的像素
        val flv = fls["v"]!!
        for (rev in listOf(false, true)) {
            val d = IntArray(h)
            val xe = IntArray(h)
            val hit0 = BooleanArray(h)
            val badfar = BooleanArray(h)
            val lim = DoubleArray(h)
            val sgn = if (rev) -1 else 1
            for (y in 0 until h) {
                val base = y * w
                var dd = w
                var nl = 0                      // 行程內（頁緣到 d−1）的亮而不白像素數
                for (k in 0 until w) {
                    val x = if (rev) w - 1 - k else k
                    if (!okm.data[base + x] || flv.data[base + x]) { dd = k; break }
                    if (light[base + x]) nl++
                }
                d[y] = dd
                val xev = min(max(if (rev) w - 1 - dd else dd, 0), w - 1)
                xe[y] = xev
                var ok = dd <= maxd && dd < w && flv.data[base + xev]
                if (dd == 0) nl = 0
                ok = ok && nl <= max(p.sep.marginLightMin.toDouble(), p.sep.marginLightFrac * dd)
                var far = false
                for (k in 2..p.sep.farProbe) if (stripPre.data[base + min(max(xev + sgn * k, 0), w - 1)]) far = true
                badfar[y] = flTr.data[base + xev] && far
                if (badfar[y]) ok = false
                lim[y] = max(p.sep.marginLightMin.toDouble(), p.sep.marginLightFrac * dd)
                hit0[y] = ok
            }
            val reach = if (p.sep.marginClose) closeRows(hit0, d, maxd, closeGap, p.sep.flHitR) { y ->
                val base = y * w
                var c = 0
                for (k in 0 until d[y]) if (light2[base + (if (rev) w - 1 - k else k)]) c++
                !badfar[y] && c <= lim[y]
            } else null
            val hit = open1d(hit0, runMin)
            val sd = Mask(w, h)
            for (y in 0 until h) {
                if (!hit[y]) continue
                val base = y * w
                for (k in 0 until d[y]) sd.data[base + (if (rev) w - 1 - k else k)] = true
            }
            if (reach != null && (0 until h).any { hit[it] && reach[it] >= 0 }) {
                val band = Mask(w, h)
                for (y in 0 until h) {
                    if (!hit[y]) continue
                    val dr = if (reach[y] >= 0) reach[y] else d[y].toDouble()
                    val base = y * w
                    var k = 0
                    while (k < w && k < dr) { band.data[base + (if (rev) w - 1 - k else k)] = true; k++ }
                }
                sd.orInPlace(behind(whiteOk, sd, band))
            }
            seeds.orInPlace(sd)
        }
        // 上／下：逐行同理
        val flh = fls["h"]!!
        for (rev in listOf(false, true)) {
            val d = IntArray(w)
            val hit0 = BooleanArray(w)
            val badfar = BooleanArray(w)
            val lim = DoubleArray(w)
            val sgn = if (rev) -1 else 1
            for (x in 0 until w) {
                var dd = h
                var nl = 0
                for (k in 0 until h) {
                    val y = if (rev) h - 1 - k else k
                    val i = y * w + x
                    if (!okm.data[i] || flh.data[i]) { dd = k; break }
                    if (light[i]) nl++
                }
                d[x] = dd
                val yev = min(max(if (rev) h - 1 - dd else dd, 0), h - 1)
                var ok = dd <= maxd && dd < h && flh.data[yev * w + x]
                if (dd == 0) nl = 0
                ok = ok && nl <= max(p.sep.marginLightMin.toDouble(), p.sep.marginLightFrac * dd)
                var far = false
                for (k in 2..p.sep.farProbe) if (stripPre.data[min(max(yev + sgn * k, 0), h - 1) * w + x]) far = true
                badfar[x] = flTr.data[yev * w + x] && far
                if (badfar[x]) ok = false
                lim[x] = max(p.sep.marginLightMin.toDouble(), p.sep.marginLightFrac * dd)
                hit0[x] = ok
            }
            val reach = if (p.sep.marginClose) closeRows(hit0, d, maxd, closeGap, p.sep.flHitR) { x ->
                var c = 0
                for (k in 0 until d[x]) if (light2[(if (rev) h - 1 - k else k) * w + x]) c++
                !badfar[x] && c <= lim[x]
            } else null
            val hit = open1d(hit0, runMin)
            val sd = Mask(w, h)
            for (x in 0 until w) {
                if (!hit[x]) continue
                for (k in 0 until d[x]) sd.data[(if (rev) h - 1 - k else k) * w + x] = true
            }
            if (reach != null && (0 until w).any { hit[it] && reach[it] >= 0 }) {
                val band = Mask(w, h)
                for (x in 0 until w) {
                    if (!hit[x]) continue
                    val dr = if (reach[x] >= 0) reach[x] else d[x].toDouble()
                    var k = 0
                    while (k < h && k < dr) { band.data[(if (rev) h - 1 - k else k) * w + x] = true; k++ }
                }
                sd.orInPlace(behind(whiteOk, sd, band))
            }
            seeds.orInPlace(sd)
        }
        for (i in seeds.data.indices) seeds.data[i] = seeds.data[i] && white.data[i] && !bub.data[i]
        return seeds
    }

    // ── 總遮罩 ──────────────────────────────────────────────────────────────

    /**
     * 回傳溝帶、頁邊、框線群組、逐對結果（`separators`）。[frameHv]＝[Regions.frameLineMask] 的水平／垂直格線，
     * 只併進頁邊線稿否決的格線遮罩；[veto]=false 時頁邊不過 [Texture.veto]（只給測試用）。
     */
    fun separators(g: Gray, seg: Mask?, bubble: Mask?, frameHv: Mask?, p: NightReadParams,
                   veto: Boolean = true, debug: MutableMap<String, Any>? = null): Result {
        val h = g.h
        val w = g.w
        val sh = min(h, w)
        val hc = HoughCache()
        val segs = detectSegments(g, p, debug = debug, cache = hc)
        var ogroups = if (p.sep.marginOcc) timed(debug, "occ") { occludedFrames(g, p, hc) } else emptyList()
        val tGroup = System.nanoTime()
        var groups = groupLines(segs, p)
        debug?.put("segs", segs)
        debug?.put("groupsAll", groups)
        if (bubble != null && bubble.any()) {
            // 貼著對白框／說明框外框的直線不是格框（線上每 2px 取樣，一半以上落在泡 ⊕7 內就丟）
            val bd = dilateSquare(bubble, 2 * p.sep.bubLineR + 1)
            val offBubble = { gp: Group ->
                var n = 0
                var inb = 0
                for (v in gp.iv) {
                    // np.arange(t0, t1+1, 2.0)：長度 ceil((stop−start)/2)，值 start + i·(fl(start+2)−start)
                    val start = v.lo
                    val stop = v.hi + 1
                    val len = ceil((stop - start) / 2.0).toInt()
                    val nxt = start + 2.0
                    val step = nxt - start
                    for (i in 0 until max(len, 0)) {
                        val t = when (i) { 0 -> start; 1 -> nxt; else -> start + i * step }
                        val x = min(max(rint(gp.mx + t * gp.dx), 0), w - 1)
                        val y = min(max(rint(gp.my + t * gp.dy), 0), h - 1)
                        n++
                        if (bd.data[y * w + x]) inb++
                    }
                }
                inb.toDouble() / n < p.sep.bubLineMax
            }
            groups = groups.filter(offBubble)
            ogroups = ogroups.filter(offBubble)
        }
        debug?.put("ogroups", ogroups)
        debug?.put("t_group", (System.nanoTime() - tGroup) / 1e6)
        val pairs = timed(debug, "pairs") {
            separatorPairs(g, groups, p.sep.wmaxFrac * sh, p.sep.ovlFrac * p.sep.lenFrac * sh, p.sep.bridgeFrac * sh, seg, p)
        }
        val trusted = HashSet<Int>()
        val stripPre = Mask(w, h)
        for (q in pairs) if (q.acc) { trusted.add(q.i); trusted.add(q.j); q.mask!!.orInto(stripPre) }
        debug?.put("stripPre", stripPre)
        var mar = timed(debug, "margin") { marginMask(g, groups, seg, bubble, trusted, stripPre, p, ogroups) }
        debug?.put("marginRaw", mar)
        val strip = timed(debug, "network") { networkFilter(pairs, mar, g, p) }
        debug?.put("stripNet", strip)
        val frameArb = timed(debug, "raster") { frameRaster(g, groups, p) }
        if (veto && mar.any()) {
            // 斷框線也當格線隔板（frameArb 本身不變：出血過濾不吃斷框線）
            val fr = if (ogroups.isEmpty()) frameArb.copy() else frameRaster(g, groups + ogroups, p)
            if (frameHv != null) fr.orInPlace(frameHv)
            debug?.put("fr", fr)
            mar = timed(debug, "veto") { Texture.veto(mar, g, fr, seg ?: Mask(w, h), bubble ?: Mask(w, h), p) }
        }
        return Result(strip, mar, groups, pairs, frameArb)
    }

    /**
     * compose 用的 SEP 圖層（`build_sep`）：泡 ⊕bubDil 扣掉；單條溝被泡吃掉超過 pairSubMax 整條丟；
     * 扣泡後 < minCc 的碎塊丟。**人物遮罩不扣**——SEP 畫在人物之上（格溝是畫面的外面），compose 最後的人物
     * 還原也要跳過 SEP。
     */
    fun build(g: Gray, seg: Mask, bubble: Mask, frameHv: Mask?, p: NightReadParams,
              debug: MutableMap<String, Any>? = null): Layer {
        val w = g.w
        val h = g.h
        val r = separators(g, seg, bubble, frameHv, p, veto = true, debug = debug)
        val tLayer = System.nanoTime()
        val bub = if (bubble.any()) dilateSquare(bubble, p.sep.bubDil) else Mask(w, h)
        val keepStrip = Mask(w, h)
        val dropped = ArrayList<Triple<Int, Int, Double>>()
        for (q in r.pairs) {
            if (!q.acc) continue
            val m = q.mask!!
            val tot = m.count()
            val sub = if (tot > 0) m.countAnd(bub) else 0
            if (tot > 0 && sub > p.sep.pairSubMax * tot) {
                dropped.add(Triple(q.i, q.j, java.math.BigDecimal(sub.toDouble() / tot).setScale(3, java.math.RoundingMode.HALF_EVEN).toDouble()))
                continue
            }
            m.orInto(keepStrip)
        }
        val strip = r.strip and keepStrip
        val sepPre = strip or r.margin
        val sep = sepPre.andNot(bub)
        removeSmall(sep, p.sep.minCc)
        debug?.put("t_layer", (System.nanoTime() - tLayer) / 1e6)
        return Layer(sep, sepPre, strip, r.margin, r.frameArb, r.groups, r.pairs, dropped)
    }
}
