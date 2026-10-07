package li.joye.yakuyomi.nightread

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 夜讀移植用的純 Kotlin 影像原語——**語意逐一對齊 OpenCV**（`research/nightread.py` 的 cv2 呼叫），
 * 不依賴 android.graphics ⇒ JVM 單元測試可直接跑、拿 Python 產的 fixture 比對。
 *
 * 設計約束：
 * - 影像一律 row-major、索引 `y * w + x`。
 * - [Mask]＝二值（true＝前景／非零）；[Gray]＝0..255 存 IntArray（避開 Byte 符號坑）；[FImg]＝float32。
 * - 邊界語意照 cv2 預設：`dilate` 邊界外視為 0（前景不外溢）、`erode` 邊界外視為前景
 *   （BORDER_CONSTANT + morphologyDefaultBorderValue ⇒ 影像邊緣不會被「外面的 0」吃掉）；
 *   高斯/均值模糊用 BORDER_REFLECT_101。
 * - 大核膨脹/腐蝕**不可**逐像素掃整個核（r=35 時 2.8M px × 5k ≈ 10¹⁰）：核的每一列都是一段
 *   連續 run（ellipse/rect 都成立），所以逐列做「一維區間查詢」＝ O(W·H·kh)，且與 cv2 的
 *   核形狀**位元一致**（不是用距離場近似圓）。
 * - 結構元素形狀重現 `cv2.getStructuringElement(MORPH_ELLIPSE, (s, s))` 的**光柵化演算法**
 *   （cv2 不是理想圓：以 r=s/2 為半徑、逐列算 `dx = round(c * sqrt(1 - (dy/r)^2))` 的橫向跨距），
 *   否則 dilate 結果會跟 Python 差幾個像素、連鎖影響所有門檻量測。
 * - 影像與核的欄位一律 `@JvmField`：debug APK（debuggable ART）只編譯不內聯，`data`／`w` 的 getter 在逐像素迴圈裡
 *   每頁是二十多億次真呼叫（2026-10-06 剖析）；欄位存取沒有呼叫。Kotlin 呼叫端寫法不變，Java 呼叫端改讀欄位。
 */
class Mask(@JvmField val w: Int, @JvmField val h: Int, @JvmField val data: BooleanArray = BooleanArray(w * h)) {
    operator fun get(x: Int, y: Int): Boolean = data[y * w + x]
    operator fun set(x: Int, y: Int, v: Boolean) { data[y * w + x] = v }
    fun copy(): Mask = Mask(w, h, data.copyOf())
    /**
     * ⚠️ 用手寫迴圈而不是 `data.count { it }`：後者走 Kotlin 的集合擴充，對 BooleanArray 會
     * 建 iterator 並逐個裝箱。2.6 MPx 的遮罩上實測差好幾倍，而管線裡 `count()` / `any()`
     * 被呼叫上百次（每個門檻判斷都要）。
     */
    fun count(): Int {
        var n = 0
        for (v in data) if (v) n++
        return n
    }

    fun any(): Boolean {
        for (v in data) if (v) return true
        return false
    }
    infix fun and(o: Mask): Mask = Mask(w, h, BooleanArray(w * h) { data[it] && o.data[it] })
    infix fun or(o: Mask): Mask = Mask(w, h, BooleanArray(w * h) { data[it] || o.data[it] })
    fun not(): Mask = Mask(w, h, BooleanArray(w * h) { !data[it] })
    fun andNot(o: Mask): Mask = Mask(w, h, BooleanArray(w * h) { data[it] && !o.data[it] })
    /** 就地 OR，省掉大圖的一次配置。 */
    fun orInPlace(o: Mask): Mask { for (i in data.indices) if (o.data[i]) data[i] = true; return this }
}

class Gray(@JvmField val w: Int, @JvmField val h: Int, @JvmField val data: IntArray = IntArray(w * h)) {
    operator fun get(x: Int, y: Int): Int = data[y * w + x]
    operator fun set(x: Int, y: Int, v: Int) { data[y * w + x] = v }
    fun copy(): Gray = Gray(w, h, data.copyOf())
    /** `g >= th` */
    fun ge(th: Int): Mask = Mask(w, h, BooleanArray(w * h) { data[it] >= th })
    /** `g < th` */
    fun lt(th: Int): Mask = Mask(w, h, BooleanArray(w * h) { data[it] < th })
    fun toF(): FImg = FImg(w, h, FloatArray(w * h) { data[it].toFloat() })
}

class FImg(@JvmField val w: Int, @JvmField val h: Int, @JvmField val data: FloatArray = FloatArray(w * h)) {
    operator fun get(x: Int, y: Int): Float = data[y * w + x]
    operator fun set(x: Int, y: Int, v: Float) { data[y * w + x] = v }
    fun copy(): FImg = FImg(w, h, data.copyOf())
}

/**
 * 結構元素（`cv2.getStructuringElement`）：奇數尺寸、錨點置中。
 *
 * [runStart] / [runEnd] 是每一列的連續 run 半開區間（相對核座標，`x` 從 0 起算）；run 為空時
 * `runStart[y] >= runEnd[y]`。形態學靠它做 O(W·H·kh) 的列分解，不逐像素掃核。
 */
class Kernel(@JvmField val w: Int, @JvmField val h: Int, @JvmField val data: BooleanArray) {
    @JvmField val ax: Int = w / 2
    @JvmField val ay: Int = h / 2

    @JvmField val runStart = IntArray(h)
    @JvmField val runEnd = IntArray(h)

    init {
        for (y in 0 until h) {
            var s = -1
            var e = -1
            for (x in 0 until w) {
                if (data[y * w + x]) { if (s < 0) s = x; e = x + 1 }
            }
            runStart[y] = if (s < 0) 0 else s
            runEnd[y] = if (s < 0) 0 else e
        }
    }
}

/**
 * `cv2.connectedComponentsWithStats` 的結果：`labels` 與影像同尺寸（0＝背景）、其餘陣列長度 `n`
 *（含背景 0 號），欄位對應 CC_STAT_LEFT/TOP/WIDTH/HEIGHT/AREA。
 */
class CC(
    @JvmField val n: Int,
    @JvmField val labels: IntArray,
    @JvmField val left: IntArray,
    @JvmField val top: IntArray,
    @JvmField val width: IntArray,
    @JvmField val height: IntArray,
    @JvmField val area: IntArray,
)

object Cv {

    // ── 結構元素 ─────────────────────────────────────────────────────

    /**
     * `cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (size, size))`，size 奇數。
     *
     * 照抄 OpenCV 的光柵化：`r = c = size / 2`，每列 `dx = round(c * sqrt((r² - dy²) / r²))`，
     * 該列填 `[c - dx, c + dx]`。**別用理想圓判斷 `dx² + dy² <= r²`**，那會差幾個像素。
     */
    fun ellipse(size: Int): Kernel {
        val r = size / 2
        val c = size / 2
        val data = BooleanArray(size * size)
        val invR2 = if (r != 0) 1.0 / (r.toDouble() * r) else 0.0
        for (i in 0 until size) {
            val dy = i - r
            if (abs(dy) > r) continue
            val dx = (c * sqrt((r.toDouble() * r - dy.toDouble() * dy) * invR2)).roundToInt()
            val j1 = max(c - dx, 0)
            val j2 = min(c + dx + 1, size)
            for (j in j1 until j2) data[i * size + j] = true
        }
        return Kernel(size, size, data)
    }

    /** `cv2.getStructuringElement(cv2.MORPH_RECT, (kw, kh))`（等同 `np.ones((kh, kw))`）。 */
    fun rect(kw: Int, kh: Int): Kernel = Kernel(kw, kh, BooleanArray(kw * kh) { true })

    // ── 二值形態學（cv2.dilate / erode / morphologyEx）──────────────────

    /**
     * 二值膨脹／侵蝕：1×n、n×1 實心核走 [lineMorph]，其他核走位元打包（[dilatePacked]／[erodePacked]）。
     * 打包版與原本的逐列前綴計數版逐像素相同（取樣：輸出 (x, y) 看輸入 (x + [runStart−ax .. runEnd−1−ax], y − (ky−ay))；
     * 膨脹影像外當 0、侵蝕影像外當前景），前綴計數版留在測試（MorphPackedTest）當參考實作。
     */
    fun dilate(m: Mask, k: Kernel, iterations: Int = 1): Mask {
        val line = lineKernel(k)
        var cur = m
        repeat(iterations) {
            cur = when {
                line > 0 -> lineMorph(cur, line, horizontal = true, anchor = k.ax, dilate = true)
                line < 0 -> lineMorph(cur, -line, horizontal = false, anchor = k.ay, dilate = true)
                else -> dilatePacked(cur, k)
            }
        }
        return cur
    }

    fun erode(m: Mask, k: Kernel, iterations: Int = 1): Mask {
        val line = lineKernel(k)
        var cur = m
        repeat(iterations) {
            cur = when {
                line > 0 -> lineMorph(cur, line, horizontal = true, anchor = k.ax, dilate = false)
                line < 0 -> lineMorph(cur, -line, horizontal = false, anchor = k.ay, dilate = false)
                else -> erodePacked(cur, k)
            }
        }
        return cur
    }

    fun open(m: Mask, k: Kernel): Mask {
        val line = lineKernel(k)
        if (line == 0) return openPacked(m, k)
        // 線核：侵蝕＝¬膨脹(¬m)，中間結果留在打包格式
        val hor = line > 0
        val len = if (hor) line else -line
        val an = if (hor) k.ax else k.ay
        val e = lineOrBits(packBitsNot(m), m.w, m.h, len, hor, an)
        notBitsInPlace(e, m.w, m.h)
        return unpackBits(lineOrBits(e, m.w, m.h, len, hor, an), m.w, m.h)
    }

    fun close(m: Mask, k: Kernel): Mask {
        val line = lineKernel(k)
        if (line == 0) return closePacked(m, k)
        val hor = line > 0
        val len = if (hor) line else -line
        val an = if (hor) k.ax else k.ay
        val d = lineOrBits(packBits(m), m.w, m.h, len, hor, an)
        notBitsInPlace(d, m.w, m.h)
        return unpackBitsNot(lineOrBits(d, m.w, m.h, len, hor, an), m.w, m.h)
    }

    /**
     * 二值膨脹的位元打包快路：與 [dilate]（單次）**逐像素相同**（同一套取樣：輸出 (x, y) 看輸入
     * (x + [runStart−ax .. runEnd−1−ax], y − (ky−ay))，影像外視為 0），但每列打包成 Long（64 像素一字）。
     *
     * 核的每一列是一段水平 run：先對每種 run 算一次整張的「水平區間 OR」（倍增移位，log₂(run 長) 趟），
     * 再逐輸出列把核各列對應的來源列 OR 起來。成本 ≈ 核高 × 像素數 / 64，比 [dilate] 的逐像素列分解
     * （核高 × 像素數）快一個數量級：13×13 橢圓在 2.6 MPx 上約 40 ms → 5 ms。出血格過濾一頁要五趟整頁橢圓／方核外擴，
     * 用這條。
     */
    fun dilatePacked(m: Mask, k: Kernel): Mask = unpackBits(dilateBits(packBits(m), m.w, m.h, k), m.w, m.h)

    /** 列打包：每列 (w+63)/64 個 Long，像素 x 在第 x ushr 6 字的第 x and 63 位；末字超出寬度的位元一律 0。 */
    fun packBits(m: Mask): LongArray {
        val w = m.w
        val h = m.h
        val nw = (w + 63) ushr 6
        val out = LongArray(nw * h)
        val d = m.data
        for (y in 0 until h) {
            val base = y * w
            val wb = y * nw
            for (i in 0 until nw) {
                val x0 = i shl 6
                val n = min(64, w - x0)
                var v = 0L
                val o = base + x0
                for (b in 0 until n) if (d[o + b]) v = v or (1L shl b)
                out[wb + i] = v
            }
        }
        return out
    }

    /** ¬[m] 的 [packBits]（只取反影像內的位元：末字超出寬度的位元照樣是 0）。 */
    fun packBitsNot(m: Mask): LongArray {
        val w = m.w
        val h = m.h
        val nw = (w + 63) ushr 6
        val out = LongArray(nw * h)
        val d = m.data
        for (y in 0 until h) {
            val base = y * w
            val wb = y * nw
            for (i in 0 until nw) {
                val x0 = i shl 6
                val n = min(64, w - x0)
                var v = 0L
                for (b in 0 until n) if (!d[base + x0 + b]) v = v or (1L shl b)
                out[wb + i] = v
            }
        }
        return out
    }

    /** [unpackBits] 再取反（＝¬遮罩）。 */
    fun unpackBitsNot(a: LongArray, w: Int, h: Int): Mask {
        val nw = (w + 63) ushr 6
        val out = Mask(w, h)
        val od = out.data
        for (y in 0 until h) {
            val base = y * w
            val wb = y * nw
            for (i in 0 until nw) {
                val x0 = i shl 6
                val n = min(64, w - x0)
                val v = a[wb + i]
                for (b in 0 until n) od[base + x0 + b] = (v ushr b) and 1L == 0L
            }
        }
        return out
    }

    /** [packBits] 的反向。全 0 的字跳過、全 1 的字整段填，其餘逐位元寫（不逐個找最低位）。 */
    fun unpackBits(a: LongArray, w: Int, h: Int): Mask {
        val nw = (w + 63) ushr 6
        val out = Mask(w, h)
        val od = out.data
        for (y in 0 until h) {
            val base = y * w
            val wb = y * nw
            for (i in 0 until nw) {
                val v = a[wb + i]
                if (v == 0L) continue
                val x0 = i shl 6
                val n = min(64, w - x0)
                val o = base + x0
                if (v == -1L && n == 64) {
                    java.util.Arrays.fill(od, o, o + 64, true)
                    continue
                }
                for (b in 0 until n) od[o + b] = (v ushr b) and 1L != 0L
            }
        }
        return out
    }

    /** 就地取反打包位元（只取反影像內的位元：末字超出寬度的位元維持 0）。 */
    fun notBitsInPlace(a: LongArray, w: Int, h: Int) {
        val nw = (w + 63) ushr 6
        val tail = if (w and 63 == 0) -1L else (1L shl (w and 63)) - 1
        for (y in 0 until h) {
            val b = y * nw
            for (i in 0 until nw - 1) a[b + i] = a[b + i].inv()
            a[b + nw - 1] = a[b + nw - 1].inv() and tail
        }
    }

    /** 打包位元的侵蝕＝¬[dilateBits](¬src)（同 [erodePacked]；不改 [src]）。 */
    fun erodeBits(src: LongArray, w: Int, h: Int, k: Kernel): LongArray {
        val inv = src.copyOf()
        notBitsInPlace(inv, w, h)
        val d = dilateBits(inv, w, h, k)
        notBitsInPlace(d, w, h)
        return d
    }

    /**
     * [dilatePacked] 的本體：輸入輸出都是 [packBits] 的打包格式（呼叫端可以直接查位元、省掉解包）。
     *
     * 核的每一列是一段 run：輸出列 y 的貢獻＝來源列 y − (ky − ay) 的水平區間 OR（OR_{o=lo..hi} in[x + o]，影像外當 0）。
     * 相同 (lo, hi) 的核列只算一次區間 OR（[PackedRuns]），依序 OR 進 [acc]（OR 與順序無關，結果逐位元相同）。
     * 整頁只配 [acc] 與幾張工作緩衝（原本每一步移位都配一張整頁 LongArray：大橢圓一次幾百張）。
     */
    fun dilateBits(src: LongArray, w: Int, h: Int, k: Kernel): LongArray {
        val nw = (w + 63) ushr 6
        val acc = LongArray(nw * h)
        // 不同的 (lo, hi) 依序（核列順序）；每個 key 用到它的核列位移 dy
        val keyLo = IntArray(k.h)
        val keyHi = IntArray(k.h)
        val rowKey = IntArray(k.h) { -1 }
        var nk = 0
        for (ky in 0 until k.h) {
            val runS = k.runStart[ky]
            val runE = k.runEnd[ky]
            if (runS >= runE) continue
            val lo = runS - k.ax
            val hi = runE - 1 - k.ax
            var j = 0
            while (j < nk && (keyLo[j] != lo || keyHi[j] != hi)) j++
            if (j == nk) { keyLo[nk] = lo; keyHi[nk] = hi; nk++ }
            rowKey[ky] = j
        }
        if (nk == 0) return acc
        // 依兩側延伸量由小到大處理（橢圓、方核兩側一起單調變大 ⇒ 單側 OR 只要往上長）
        val order = (0 until nk).sortedWith(compareBy({ max(0, -keyLo[it]) }, { max(0, keyHi[it]) }))
        val runs = PackedRuns(src, nw, h, w)
        for (j in order) {
            val hor = runs.rangeOr(keyLo[j], keyHi[j])
            for (ky in 0 until k.h) {
                if (rowKey[ky] != j) continue
                val dy = ky - k.ay
                val y0 = max(0, dy)
                val y1 = min(h, h + dy)
                for (y in y0 until y1) {
                    val o = y * nw
                    val si = (y - dy) * nw
                    for (i in 0 until nw) acc[o + i] = acc[o + i] or hor[si + i]
                }
            }
        }
        return acc
    }

    /**
     * 打包列的水平區間 OR（out[x] = OR_{o=lo..hi} in[x + o]，範圍外視為 0），就地增長的單側版：
     * [right]＝OR in[x .. x+b]、[left]＝OR in[x−a .. x]，往外長 s ≤ 目前長度＋1 的一步＝一趟就地「移位再 OR」
     * （往右讀的由左往右寫、往左讀的由右往左寫，讀到的字都還沒被改過）。單側版讀到影像外的格本來就是 0（那一側全在影像外），
     * 所以跨 0 的區間＝left(−lo) ∪ right(hi)，不會有「先倍增再平移、左緣少一圈」的問題；整段在 0 同一側的再平移一次。
     * 回傳的陣列在下一次 [rangeOr] 前有效（可能是內部緩衝本身）。
     */
    private class PackedRuns(val src: LongArray, val nw: Int, val h: Int, val w: Int) {
        private val tail = if (w and 63 == 0) -1L else (1L shl (w and 63)) - 1
        private var right: LongArray? = null
        private var rightB = 0
        private var left: LongArray? = null
        private var leftA = 0
        private var tmp: LongArray? = null

        fun rangeOr(lo: Int, hi: Int): LongArray {
            if (lo <= 0 && hi >= 0) {
                if (lo == 0) return growRight(hi)
                if (hi == 0) return growLeft(-lo)
                val l = growLeft(-lo)
                val r = growRight(hi)
                val t = tmp ?: LongArray(src.size).also { tmp = it }
                for (i in t.indices) t[i] = l[i] or r[i]
                return t
            }
            val t = tmp ?: LongArray(src.size).also { tmp = it }
            if (lo > 0) shiftInto(growRight(hi - lo), t, lo) else shiftInto(growLeft(hi - lo), t, hi)
            return t
        }

        /** OR in[x .. x+b]。 */
        private fun growRight(b: Int): LongArray {
            var r = right
            if (r == null || b < rightB) {
                r = r ?: LongArray(src.size)
                System.arraycopy(src, 0, r, 0, src.size)
                right = r
                rightB = 0
            }
            while (rightB < b) {
                val s = min(b - rightB, rightB + 1)
                orShiftRightInPlace(r, s)
                rightB += s
            }
            return r
        }

        /** OR in[x−a .. x]。 */
        private fun growLeft(a: Int): LongArray {
            var l = left
            if (l == null || a < leftA) {
                l = l ?: LongArray(src.size)
                System.arraycopy(src, 0, l, 0, src.size)
                left = l
                leftA = 0
            }
            while (leftA < a) {
                val s = min(a - leftA, leftA + 1)
                orShiftLeftInPlace(l, s)
                leftA += s
            }
            return l
        }

        /** a[x] |= a[x + s]（s > 0；由左往右，讀的字都在寫的字右邊或就是它、還沒改）。 */
        private fun orShiftRightInPlace(a: LongArray, s: Int) {
            val q = s ushr 6
            val r = s and 63
            for (y in 0 until h) {
                val b = y * nw
                for (i in 0 until nw - q) {
                    val j = b + i + q
                    var v = a[j] ushr r
                    if (r != 0 && i + q + 1 < nw) v = v or (a[j + 1] shl (64 - r))
                    a[b + i] = a[b + i] or v
                }
            }
        }

        /** a[x] |= a[x − s]（s > 0；由右往左；末字超出寬度的位元清掉）。 */
        private fun orShiftLeftInPlace(a: LongArray, s: Int) {
            val q = s ushr 6
            val r = s and 63
            for (y in 0 until h) {
                val b = y * nw
                for (i in nw - 1 downTo q) {
                    val j = b + i - q
                    var v = a[j] shl r
                    if (r != 0 && i - q - 1 >= 0) v = v or (a[j - 1] ushr (64 - r))
                    if (i == nw - 1) v = v and tail
                    a[b + i] = a[b + i] or v
                }
            }
        }

        /** out[x] = a[x + s]（逐列、範圍外 0；s < 0 時末字超出寬度的位元清掉）。 */
        private fun shiftInto(a: LongArray, out: LongArray, s: Int) {
            java.util.Arrays.fill(out, 0L)
            if (s >= 0) {
                val q = s ushr 6
                val r = s and 63
                for (y in 0 until h) {
                    val b = y * nw
                    for (i in 0 until nw - q) {
                        val j = i + q
                        var v = a[b + j] ushr r
                        if (r != 0 && j + 1 < nw) v = v or (a[b + j + 1] shl (64 - r))
                        out[b + i] = v
                    }
                }
            } else {
                val u = -s
                val q = u ushr 6
                val r = u and 63
                for (y in 0 until h) {
                    val b = y * nw
                    for (i in q until nw) {
                        val j = i - q
                        var v = a[b + j] shl r
                        if (r != 0 && j - 1 >= 0) v = v or (a[b + j - 1] ushr (64 - r))
                        out[b + i] = v
                    }
                    out[b + nw - 1] = out[b + nw - 1] and tail
                }
            }
        }
    }

    /**
     * 一維長條核（1×n 或 n×1）：窗＝水平 [x − a, x + b]／垂直 [y − a, y + b]（a＝[anchor]、b＝len − 1 − a），只看影像內——
     * 膨脹＝窗內有沒有 true（影像外當 0）、侵蝕＝窗內有沒有 false（影像外當前景，同 cv2）。
     *
     * 走位元打包（2026-10-06；原本是「最近目標像素距離」兩趟逐像素掃描，留在測試 MorphPackedTest 當參考實作，逐像素相同）：
     * 侵蝕＝¬膨脹(¬m)；水平的窗 OR 用 [PackedRuns]（單側就地倍增），垂直的用 [rowsOrBits]（整列一次 OR 64 px）。
     * 格框線偵測／格框線切割／線稿密度否決加起來十幾趟線形態學，全走這裡。
     */
    private fun lineMorph(m: Mask, len: Int, horizontal: Boolean, anchor: Int, dilate: Boolean): Mask {
        val w = m.w
        val h = m.h
        val src = if (dilate) packBits(m) else packBitsNot(m)
        val r = lineOrBits(src, w, h, len, horizontal, anchor)
        return if (dilate) unpackBits(r, w, h) else unpackBitsNot(r, w, h)
    }

    /** 打包位元的線窗 OR（[lineMorph] 的窗；影像外 0）。回傳新陣列（不是 [src]）。 */
    private fun lineOrBits(src: LongArray, w: Int, h: Int, len: Int, horizontal: Boolean, anchor: Int): LongArray {
        val nw = (w + 63) ushr 6
        val a = anchor
        val b = len - 1 - anchor
        return if (horizontal) PackedRuns(src, nw, h, w).rangeOr(-a, b) else rowsOrBits(src, nw, h, a, b)
    }

    /** 打包位元的垂直窗 OR：out 列 y＝OR 來源列 [y − a, y + b]（影像外 0）。兩個單側就地倍增再合併。 */
    private fun rowsOrBits(src: LongArray, nw: Int, h: Int, a: Int, b: Int): LongArray {
        val down = src.copyOf()                 // OR 列 [y, y + b]
        var ext = 0
        while (ext < b) {
            val s = min(b - ext, ext + 1)
            val sh = s * nw
            for (i in 0 until (h - s) * nw) down[i] = down[i] or down[i + sh]   // 由上往下：讀的列在寫的列下面、還沒改
            ext += s
        }
        if (a == 0) return down
        val up = src.copyOf()                   // OR 列 [y − a, y]
        ext = 0
        while (ext < a) {
            val s = min(a - ext, ext + 1)
            val sh = s * nw
            for (i in h * nw - 1 downTo sh) up[i] = up[i] or up[i - sh]        // 由下往上
            ext += s
        }
        for (i in down.indices) down[i] = down[i] or up[i]
        return down
    }

    /**
     * 方核（[kw]×[kh] 全 1）膨脹＝橫線 [kw]×1 再直線 1×[kh]（Minkowski 分解，與 `dilate(dilate(m, rect(kw, 1)), rect(1, kh))`
     * 逐像素相同），兩趟都留在打包格式、只解包一次。
     */
    fun dilateRectSep(m: Mask, kw: Int, kh: Int): Mask {
        val w = m.w
        val h = m.h
        var bits = packBits(m)
        if (kw > 1) bits = lineOrBits(bits, w, h, kw, true, kw / 2)
        if (kh > 1) bits = lineOrBits(bits, w, h, kh, false, kh / 2)
        return unpackBits(bits, w, h)
    }

    /**
     * 方核閉運算，與 `erode(erode(dilate(dilate(m, rect(n,1)), rect(1,n)), rect(n,1)), rect(1,n))` 逐像素相同
     * （侵蝕＝¬膨脹(¬·)，全程打包）。
     */
    fun closeRectSep(m: Mask, n: Int): Mask {
        val w = m.w
        val h = m.h
        if (n <= 1) return m.copy()
        var bits = lineOrBits(lineOrBits(packBits(m), w, h, n, true, n / 2), w, h, n, false, n / 2)
        notBitsInPlace(bits, w, h)
        bits = lineOrBits(lineOrBits(bits, w, h, n, true, n / 2), w, h, n, false, n / 2)
        return unpackBitsNot(bits, w, h)
    }

    /** 核是不是單一實心的 1×n（回 n）或 n×1（回 -n）；都不是回 0。 */
    private fun lineKernel(k: Kernel): Int {
        if (k.h == 1 && k.runStart[0] == 0 && k.runEnd[0] == k.w) return k.w
        if (k.w == 1) {
            for (y in 0 until k.h) if (k.runStart[y] != 0 || k.runEnd[y] != 1) return 0
            return -k.h
        }
        return 0
    }

    // ── 灰階形態學（ink_line_mask 的 7×7 ellipse blackhat）───────────────

    fun dilateGray(g: Gray, k: Kernel): Gray = morphGray(g, k, wantMax = true)

    fun erodeGray(g: Gray, k: Kernel): Gray = morphGray(g, k, wantMax = false)

    /**
     * 不限 8 位元的灰階膨脹／侵蝕（背景物件規則的 Q16 影像、字塊標號）：外側不影響極值（同 cv2 的形態學預設邊界）。
     * 與 [dilateGray]／[erodeGray] 只差單位元（Int 的最小／最大值）；核都含錨點，8 位元影像兩者逐位元相同。
     */
    fun dilateGrayI(g: Gray, k: Kernel): Gray = morphGray(g, k, wantMax = true, lo = Int.MIN_VALUE, hi = Int.MAX_VALUE)

    fun erodeGrayI(g: Gray, k: Kernel): Gray = morphGray(g, k, wantMax = false, lo = Int.MIN_VALUE, hi = Int.MAX_VALUE)

    /** `cv2.morphologyEx(g, MORPH_BLACKHAT, k)` ＝ close(g) − g（灰階，結果 ≥ 0）。 */
    fun blackhat(g: Gray, k: Kernel): Gray {
        // 核的每一列都含錨點那一欄（橢圓、方核）時視窗不會整個落在影像外，8 位元影像上邊界的單位元（0／255 或 Int 的極值）
        // 不影響結果：走較快的 [morphGrayGather]（逐位元相同）；其他核或值超出 0..255 照舊
        var anchored = true
        for (ky in 0 until k.h) if (k.runStart[ky] > k.ax || k.runEnd[ky] <= k.ax) { anchored = false; break }
        if (anchored) for (v in g.data) if (v < 0 || v > 255) { anchored = false; break }
        val closed = if (anchored) morphGrayGather(morphGrayGather(g, k, wantMax = true), k, wantMax = false)
        else erodeGray(dilateGray(g, k), k)
        // 就地減（closed 是新配的）：不多配一張整頁
        val d = closed.data
        val gd = g.data
        for (i in d.indices) {
            val v = d[i] - gd[i]
            d[i] = if (v > 0) v else 0
        }
        return closed
    }

    /**
     * 灰階形態學。只用在 `ink_line_mask` 的 7×7 blackhat，核很小，直接掃核最單純也夠快
     * （2M px × 49 ≈ 1 億次整數比較，約一秒）。
     *
     * 邊界照 cv2 的形態學預設：dilate 外側視為 0（不影響 max）、erode 外側視為 255（不影響 min）。
     */
    /**
     * 灰階形態學。核的每一列是一段 run，所以逐列做一維滑動極值即可，成本與核寬無關。
     *
     * 只用在 `ink_line_mask` 的 7×7 blackhat，但那是整頁尺度的兩趟（close = dilate + erode），
     * 逐像素掃核要 204 ms；換成分段掃描後降到 40 ms 上下。
     *
     * 邊界照 cv2 的形態學預設：dilate 外側視為 0、erode 外側視為 255，兩者都不影響極值。
     */
    /**
     * 灰階形態學。
     *
     * 核的每一列是一段 run，所以逐列做一維滑動極值即可。**同寬度的列共用一次掃描**：7×7 橢圓有
     * 七列但只有三種寬度（7、5、1），快取後每行的滑動極值從七次降到三次，blackhat 182→104 ms。
     *
     * 邊界照 cv2 的形態學預設：dilate 外側視為 0、erode 外側視為 255，兩者都不影響極值。
     */
    private fun morphGray(g: Gray, k: Kernel, wantMax: Boolean, lo: Int = 0, hi: Int = 255): Gray {
        val w = g.w
        val h = g.h
        val od = IntArray(w * h)
        java.util.Arrays.fill(od, if (wantMax) lo else hi)
        val row = IntArray(w)
        // 不同的 run 寬度（核列順序首見）與每個核列用哪一個
        val widthOf = IntArray(k.h) { -1 }
        val widths = IntArray(k.h)
        var nW = 0
        for (ky in 0 until k.h) {
            val win = k.runEnd[ky] - k.runStart[ky]
            if (win <= 0) continue
            var j = 0
            while (j < nW && widths[j] != win) j++
            if (j == nW) { widths[nW] = win; nW++ }
            widthOf[ky] = j
        }
        val slides = Array(nW) { IntArray(w) }
        val pre = IntArray(w)
        val suf = IntArray(w)

        // 以「來源列」為外圈：每條來源行只讀一次、每種寬度只掃一次，再散到所有用得到它的輸出列
        for (sy in 0 until h) {
            System.arraycopy(g.data, sy * w, row, 0, w)
            for (j in 0 until nW) slidingExtreme(row, w, widths[j], wantMax, slides[j], pre, suf)
            for (ky in 0 until k.h) {
                val wi = widthOf[ky]
                if (wi < 0) continue
                val y = sy - (ky - k.ay)
                if (y < 0 || y >= h) continue
                val win = widths[wi]
                val slide = slides[wi]
                val offL = k.runStart[ky] - k.ax
                val obase = y * w
                // 視窗完全在界內（x + offL ≥ 0 且 x + offL + win − 1 < w）才用滑動極值；部分越界逐項算——cv2 的邊界是
                // 「外側不影響極值」，把視窗夾進有效範圍會取到不該取的值（blackhat 會立刻對不上）
                val xa = max(0, -offL)
                val xb = min(w - 1, w - win - offL)
                if (xa > xb) {
                    for (x in 0 until w) od[obase + x] = morphMerge(od[obase + x], partialExtreme(row, w, x + offL, win, wantMax), wantMax)
                    continue
                }
                for (x in 0 until xa) od[obase + x] = morphMerge(od[obase + x], partialExtreme(row, w, x + offL, win, wantMax), wantMax)
                if (wantMax) {
                    for (x in xa..xb) { val v = slide[x + offL]; if (v > od[obase + x]) od[obase + x] = v }
                } else {
                    for (x in xa..xb) { val v = slide[x + offL]; if (v < od[obase + x]) od[obase + x] = v }
                }
                for (x in xb + 1 until w) od[obase + x] = morphMerge(od[obase + x], partialExtreme(row, w, x + offL, win, wantMax), wantMax)
            }
        }
        return Gray(w, h, od)
    }

    private fun morphMerge(cur: Int, v: Int, wantMax: Boolean): Int = if (wantMax) max(cur, v) else min(cur, v)

    /**
     * 部分越界的視窗 [a0, a0 + win − 1]：只取界內那段的極值。整段在界外時回傳界內段的端點座標（原本的寫法：核含錨點的核
     * 不會走到，照舊保留）。
     */
    private fun partialExtreme(row: IntArray, w: Int, a0: Int, win: Int, wantMax: Boolean): Int {
        val b0 = a0 + win - 1
        val lo = max(a0, 0)
        val hi = min(b0, w - 1)
        if (lo > hi) return if (wantMax) lo else hi
        var acc = row[lo]
        for (j in lo + 1..hi) acc = if (wantMax) max(acc, row[j]) else min(acc, row[j])
        return acc
    }

    /**
     * 一維滑動極值（van Herk / Gil-Werman 的分段前綴後綴法）：結果寫進 [dst] 的前 `n - win + 1` 項。[pre]／[suf] 是呼叫端的
     * 工作緩衝（長度 ≥ n）。視窗起點剛好是分段起點時整窗就在同一段裡＝pre[hi]，否則＝極值(suf[lo], pre[hi])。
     */
    private fun slidingExtreme(a: IntArray, n: Int, win: Int, wantMax: Boolean, dst: IntArray, pre: IntArray, suf: IntArray) {
        if (win >= n) {
            var acc = a[0]
            for (i in 1 until n) acc = if (wantMax) max(acc, a[i]) else min(acc, a[i])
            dst[0] = acc
            return
        }
        var i = 0
        while (i < n) {
            val end = min(i + win, n)
            var acc = a[i]
            pre[i] = acc
            if (wantMax) {
                for (j in i + 1 until end) { if (a[j] > acc) acc = a[j]; pre[j] = acc }
                acc = a[end - 1]
                suf[end - 1] = acc
                for (j in end - 2 downTo i) { if (a[j] > acc) acc = a[j]; suf[j] = acc }
            } else {
                for (j in i + 1 until end) { if (a[j] < acc) acc = a[j]; pre[j] = acc }
                acc = a[end - 1]
                suf[end - 1] = acc
                for (j in end - 2 downTo i) { if (a[j] < acc) acc = a[j]; suf[j] = acc }
            }
            i = end
        }
        var phase = 0                     // x mod win
        for (x in 0..n - win) {
            val hiI = x + win - 1
            dst[x] = if (phase == 0) pre[hiI] else if (wantMax) max(suf[x], pre[hiI]) else min(suf[x], pre[hiI])
            phase++
            if (phase == win) phase = 0
        }
    }

    // ── 連通元件 ─────────────────────────────────────────────────────

    /**
     * `cv2.connectedComponentsWithStats(m, connectivity)`。
     *
     * 標號＝**首見掃描序**（cv2 的標號就是這個順序，下游有「元件 id 當 key」的邏輯：gutter_ids / panel_ids / sticker，
     * 順序不同會對不上）；統計含背景 0 號（沒有背景像素時 bbox 為 0×0、位置 (0, 0)）。
     *
     * 游程版（2026-10-06，與原本逐像素 union-find 逐值相同）：每列切成連續 true 的游程，只在上下兩列的游程重疊（8 連通含
     * 斜角：區間各外擴 1）時 union；元件的首見像素＝它在掃描序上的第一個游程的起點，所以按游程順序配號就是首見掃描序。
     * 面積、bbox 逐游程累計；背景的 bbox 由每列的游程直接推（列首／列尾第一個非游程像素）。parent 只配游程數那麼長
     * （原本是 2 B/px）、find／union 只在游程重疊時呼叫。
     */
    fun ccStats(m: Mask, connectivity: Int = 8): CC {
        val r = ccRuns(m, connectivity)
        val w = m.w
        val h = m.h
        val n = r.n
        val labels = IntArray(w * h)
        val left = IntArray(n) { Int.MAX_VALUE }
        val top = IntArray(n) { Int.MAX_VALUE }
        val right = IntArray(n) { Int.MIN_VALUE }
        val bottom = IntArray(n) { Int.MIN_VALUE }
        val area = IntArray(n)
        val rs = r.rs
        val re = r.re
        val lab = r.lab
        for (y in 0 until h) {
            val base = y * w
            for (k in r.rowFirst[y] until r.rowFirst[y + 1]) {
                val l = lab[k]
                val s = rs[k]
                val e = re[k]
                java.util.Arrays.fill(labels, base + s, base + e + 1, l)
                area[l] += e - s + 1
                if (s < left[l]) left[l] = s
                if (y < top[l]) top[l] = y
                if (e > right[l]) right[l] = e
                bottom[l] = y
            }
        }
        area[0] = (w.toLong() * h - r.fg).toInt()
        left[0] = r.bgLeft
        top[0] = r.bgTop
        right[0] = r.bgRight
        bottom[0] = r.bgBottom
        val width = IntArray(n)
        val height = IntArray(n)
        for (i in 0 until n) {
            if (left[i] == Int.MAX_VALUE) { left[i] = 0; top[i] = 0 }
            width[i] = right[i] - left[i] + 1
            height[i] = bottom[i] - top[i] + 1
            if (width[i] < 0) width[i] = 0
            if (height[i] < 0) height[i] = 0
        }
        return CC(n, labels, left, top, width, height, area)
    }

    /**
     * [ccStats] 的游程本體（不攤整頁標號）：[rs]／[re]＝每個游程的起點／終點（含）、[rowFirst]＝每列第一個游程的索引
     * （[rowFirst] 長 h＋1）、[lab]＝游程的最終標號（首見掃描序，1..n−1）、[n]＝元件數（含背景 0 號）；[fg]＝前景像素數，
     * bg*＝背景（0 號）的 bbox（沒有背景像素＝Int.MAX／MIN）。只要逐元件累計、不需要逐像素查標號的呼叫端用它省 4 B/px。
     */
    internal class CcRuns(
        @JvmField val n: Int,
        @JvmField val rs: IntArray,
        @JvmField val re: IntArray,
        @JvmField val rowFirst: IntArray,
        @JvmField val lab: IntArray,
        @JvmField val fg: Long,
        @JvmField val bgLeft: Int,
        @JvmField val bgTop: Int,
        @JvmField val bgRight: Int,
        @JvmField val bgBottom: Int,
    )

    internal fun ccRuns(m: Mask, connectivity: Int = 8): CcRuns {
        val w = m.w
        val h = m.h
        val d = m.data
        val adj = if (connectivity == 8) 1 else 0
        var rs = IntArray(max(64, h * 2))
        var re = IntArray(rs.size)
        var parent = IntArray(rs.size)
        val rowFirst = IntArray(h + 1)
        var nr = 0
        var bgLeft = Int.MAX_VALUE
        var bgTop = Int.MAX_VALUE
        var bgRight = Int.MIN_VALUE
        var bgBottom = Int.MIN_VALUE
        var fg = 0L
        for (y in 0 until h) {
            rowFirst[y] = nr
            val base = y * w
            val p0 = if (y > 0) rowFirst[y - 1] else 0
            val p1 = nr
            var pj = p0
            var x = 0
            while (x < w) {
                if (!d[base + x]) { x++; continue }
                val s = x
                while (x < w && d[base + x]) x++
                val e = x - 1
                if (nr == rs.size) {
                    val cap = rs.size * 2
                    rs = rs.copyOf(cap)
                    re = re.copyOf(cap)
                    parent = parent.copyOf(cap)
                }
                rs[nr] = s
                re[nr] = e
                parent[nr] = nr
                // 上一列與 [s − adj, e + adj] 重疊的游程（上一列的游程依 x 排序）
                while (pj < p1 && re[pj] < s - adj) pj++
                var k = pj
                while (k < p1 && rs[k] <= e + adj) {
                    // union（根＝較小的游程索引；path halving）
                    var ra = nr
                    while (parent[ra] != ra) { parent[ra] = parent[parent[ra]]; ra = parent[ra] }
                    var rb = k
                    while (parent[rb] != rb) { parent[rb] = parent[parent[rb]]; rb = parent[rb] }
                    if (ra != rb) { if (ra < rb) parent[rb] = ra else parent[ra] = rb }
                    k++
                }
                fg += e - s + 1
                nr++
            }
            // 背景：這一列有沒有非前景像素、最左／最右的是哪個
            val r0 = rowFirst[y]
            if (nr == r0) {
                if (w > 0) {
                    if (0 < bgLeft) bgLeft = 0
                    if (w - 1 > bgRight) bgRight = w - 1
                    if (y < bgTop) bgTop = y
                    bgBottom = y
                }
            } else if (!(nr - r0 == 1 && rs[r0] == 0 && re[r0] == w - 1)) {
                val lx = if (rs[r0] > 0) 0 else re[r0] + 1
                val rx = if (re[nr - 1] < w - 1) w - 1 else rs[nr - 1] - 1
                if (lx < bgLeft) bgLeft = lx
                if (rx > bgRight) bgRight = rx
                if (y < bgTop) bgTop = y
                bgBottom = y
            }
        }
        rowFirst[h] = nr
        // 按游程（＝掃描）順序配最終標號
        val remap = IntArray(nr)
        val lab = IntArray(nr)
        var n = 1
        for (r in 0 until nr) {
            var root = r
            while (parent[root] != root) root = parent[root]
            if (remap[root] == 0) { remap[root] = n; n++ }
            parent[r] = root
            lab[r] = remap[root]
        }
        return CcRuns(n, rs, re, rowFirst, lab, fg, bgLeft, bgTop, bgRight, bgBottom)
    }

    /**
     * [ccRuns] 的列打包輸入版（[packBits] 格式；2026-10-07）：游程直接從位元取（[bitRuns]），不配整頁的布林遮罩。標號、游程與 [ccRuns]
     * 相同（同一套 union-find、同一個掃描序）；背景外接框不算（欄位填 0）。
     */
    internal fun ccRunsBits(bits: LongArray, w: Int, h: Int, connectivity: Int = 8): CcRuns {
        val nw = (w + 63) ushr 6
        val adj = if (connectivity == 8) 1 else 0
        var rs = IntArray(max(64, h * 2))
        var re = IntArray(rs.size)
        var parent = IntArray(rs.size)
        val rowFirst = IntArray(h + 1)
        val cap0 = (64 * nw + 1) / 2 + 1
        val ts = IntArray(cap0)
        val te = IntArray(cap0)
        var nr = 0
        var fg = 0L
        for (y in 0 until h) {
            rowFirst[y] = nr
            val p0 = if (y > 0) rowFirst[y - 1] else 0
            val p1 = nr
            var pj = p0
            val cnt = bitRuns(bits, y * nw, nw, ts, te)
            for (q in 0 until cnt) {
                val s = ts[q]
                val e = te[q] - 1
                if (nr == rs.size) {
                    val cap = rs.size * 2
                    rs = rs.copyOf(cap)
                    re = re.copyOf(cap)
                    parent = parent.copyOf(cap)
                }
                rs[nr] = s
                re[nr] = e
                parent[nr] = nr
                while (pj < p1 && re[pj] < s - adj) pj++
                var k = pj
                while (k < p1 && rs[k] <= e + adj) {
                    var ra = nr
                    while (parent[ra] != ra) { parent[ra] = parent[parent[ra]]; ra = parent[ra] }
                    var rb = k
                    while (parent[rb] != rb) { parent[rb] = parent[parent[rb]]; rb = parent[rb] }
                    if (ra != rb) { if (ra < rb) parent[rb] = ra else parent[ra] = rb }
                    k++
                }
                fg += e - s + 1
                nr++
            }
        }
        rowFirst[h] = nr
        val remap = IntArray(nr)
        val lab = IntArray(nr)
        var n = 1
        for (r in 0 until nr) {
            var root = r
            while (parent[root] != root) root = parent[root]
            if (remap[root] == 0) { remap[root] = n; n++ }
            parent[r] = root
            lab[r] = remap[root]
        }
        return CcRuns(n, rs, re, rowFirst, lab, fg, 0, 0, 0, 0)
    }

    /** 列打包位元第 [off] 字起那一列，在 [s, e) 裡有沒有任何一位是 1。 */
    internal fun anyBits(bits: LongArray, off: Int, s: Int, e: Int): Boolean {
        if (s >= e) return false
        val a = s ushr 6
        val b = (e - 1) ushr 6
        val lo = -1L shl (s and 63)
        val hi = -1L ushr (63 - ((e - 1) and 63))
        if (a == b) return bits[off + a] and lo and hi != 0L
        if (bits[off + a] and lo != 0L) return true
        for (i in a + 1 until b) if (bits[off + i] != 0L) return true
        return bits[off + b] and hi != 0L
    }

    // ── 距離變換 ─────────────────────────────────────────────────────

    /**
     * `cv2.distanceTransform(m, DIST_L2, maskSize)`：每個**前景**像素到最近**背景**像素的距離、背景＝0。
     *
     * 精確歐氏（Felzenszwalb 兩趟一維下包絡）。cv2 的 3×3／5×5 chamfer 是近似、差 ≤ 數 %，
     * nightread 的距離門檻都有餘裕；parity 測試用容差比對。
     */
    fun distanceL2(m: Mask): FImg {
        val w = m.w
        val h = m.h
        val inf = 1e20f
        val f = FloatArray(w * h) { if (m.data[it]) inf else 0f }

        val n = max(w, h)
        val tmp = FloatArray(n)
        val d = FloatArray(n)
        val v = IntArray(n)
        val z = FloatArray(n + 1)

        // 逐行：資料本來就連續，用 arraycopy 進出，不逐格搬
        for (y in 0 until h) {
            val base = y * w
            System.arraycopy(f, base, tmp, 0, w)
            edt1d(tmp, w, d, v, z)
            System.arraycopy(d, 0, f, base, w)
        }
        // 逐列：一次搬 16 欄進欄優先的暫存（每列讀連續的 16 格，整條快取線用完），逐欄做完再一起搬回——
        // 每一欄的運算與原本逐欄搬的寫法完全相同，只是存取順序換了（這趟原本是快取不友善的那一半）
        val bw = 16
        val cols = FloatArray(bw * h)
        var x0 = 0
        while (x0 < w) {
            val nb = min(bw, w - x0)
            for (y in 0 until h) {
                val base = y * w + x0
                for (b in 0 until nb) cols[b * h + y] = f[base + b]
            }
            for (b in 0 until nb) {
                System.arraycopy(cols, b * h, tmp, 0, h)
                edt1d(tmp, h, d, v, z)
                System.arraycopy(d, 0, cols, b * h, h)
            }
            for (y in 0 until h) {
                val base = y * w + x0
                for (b in 0 until nb) f[base + b] = cols[b * h + y]
            }
            x0 += bw
        }
        // 就地開平方，省一次 2.6 MPx 的配置（這個函式是合成階段的記憶體峰值所在：留白帶的格線距離、核心填色的直線距離）；
        // 用 Float 版 sqrt 不繞 Double
        for (i in f.indices) f[i] = kotlin.math.sqrt(f[i])
        return FImg(w, h, f)
    }

    /** [distanceSq] 裡「沒有背景像素」的值。 */
    const val DIST_SQ_INF: Int = Int.MAX_VALUE

    /**
     * [distanceSq] 的飽和值：真實距離平方 ≥ 2³¹−1（距離超過 46,340 px，只有寬或高超過這個數的頁才可能）一律存這個，
     * 不溢位成負數、也不跟 [DIST_SQ_INF] 撞在一起。灰圈收細的背景側只在可認領像素（都在人物遮罩旁）上用到，那裡的距離遠小於此。
     */
    const val DIST_SQ_SAT: Long = Int.MAX_VALUE - 1L

    /**
     * 精確的**平方**歐氏距離：每個前景（true）像素到最近背景（false）像素的距離平方（整數）、背景＝0；整張沒有背景＝[DIST_SQ_INF]；
     * 超過 [DIST_SQ_SAT] 的存 [DIST_SQ_SAT]。
     * 與 `cv2.distanceTransform(m, DIST_L2, DIST_MASK_PRECISE)`（關掉 IPP）的值是同一個數的平方根（cv2 存 float32 的 √）。
     *
     * 第一趟沿欄算「直向到最近背景」（上下各掃一次，逐列存取、快取友善），第二趟逐列做 Felzenszwalb 下包絡，拋物線交點用
     * 有理數（分子分母）以 Long 交叉相乘比較——全程整數，沒有浮點捨入選錯拋物線的可能，跟掃描順序無關。比 [distanceL2]
     * 快一倍多（2.6 MPx 約 20 ms vs 45 ms）。
     */
    fun distanceSq(m: Mask): IntArray {
        val w = m.w
        val h = m.h
        val n = w * h
        val inf = DIST_SQ_INF
        val g = IntArray(n)                      // 直向距離（px），沒有＝-1
        val col = IntArray(w) { -1 }
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                if (!m.data[base + x]) col[x] = 0 else if (col[x] >= 0) col[x]++
                g[base + x] = col[x]
            }
        }
        java.util.Arrays.fill(col, -1)
        for (y in h - 1 downTo 0) {
            val base = y * w
            for (x in 0 until w) {
                if (!m.data[base + x]) col[x] = 0 else if (col[x] >= 0) col[x]++
                val c = col[x]
                if (c >= 0 && (g[base + x] < 0 || c < g[base + x])) g[base + x] = c
            }
        }
        val out = IntArray(n)
        val v = IntArray(w)
        val zn = LongArray(w + 1)                // 交點 z[k] = zn[k] / zd[k]（zd > 0）；zd = 0 表示 ±∞（看 zn 的號）
        val zd = LongArray(w + 1)
        val fx = LongArray(w)
        for (y in 0 until h) {
            val base = y * w
            var k = -1
            for (q in 0 until w) {
                val gq = g[base + q]
                if (gq < 0) continue
                val fq = gq.toLong() * gq + q.toLong() * q
                fx[q] = fq
                if (k < 0) {
                    k = 0; v[0] = q; zn[0] = -1; zd[0] = 0; zn[1] = 1; zd[1] = 0
                    continue
                }
                var sn: Long
                var sd: Long
                while (true) {
                    val p = v[k]
                    sn = fq - fx[p]
                    sd = 2L * (q - p)
                    // s ≤ z[k]？z[k] = -∞ 時永遠否
                    val le = if (zd[k] == 0L) zn[k] > 0 else sn * zd[k] <= zn[k] * sd
                    if (le && k > 0) k-- else if (le) { k--; break } else break
                }
                k++
                v[k] = q
                zn[k] = sn; zd[k] = sd
                if (k == 0) { zn[0] = -1; zd[0] = 0 }
                zn[k + 1] = 1; zd[k + 1] = 0
            }
            if (k < 0) {
                for (x in 0 until w) out[base + x] = if (m.data[base + x]) inf else 0
                continue
            }
            var j = 0
            for (x in 0 until w) {
                // z[j+1] < x：z = +∞ 時永遠否
                while (zd[j + 1] != 0L && zn[j + 1] < x.toLong() * zd[j + 1]) j++
                val p = v[j]
                val dx = (x - p).toLong()
                val d2 = dx * dx + fx[p] - p.toLong() * p
                out[base + x] = if (d2 < DIST_SQ_SAT) d2.toInt() else DIST_SQ_SAT.toInt()
            }
        }
        return out
    }

    /** Felzenszwalb & Huttenlocher 的一維平方距離變換（拋物線下包絡）。 */
    private fun edt1d(src: FloatArray, n: Int, dst: FloatArray, v: IntArray, z: FloatArray) {
        var k = 0
        v[0] = 0
        z[0] = -1e20f
        z[1] = 1e20f
        for (q in 1 until n) {
            var s: Float
            while (true) {
                val p = v[k]
                s = ((src[q] + q.toFloat() * q) - (src[p] + p.toFloat() * p)) / (2f * q - 2f * p)
                if (s <= z[k]) k-- else break
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = 1e20f
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val p = v[k]
            val dq = (q - p).toFloat()
            dst[q] = dq * dq + src[p]
        }
    }

    // ── 濾波 ─────────────────────────────────────────────────────────

    /**
     * `cv2.GaussianBlur(f, (0,0), sigma)`。
     *
     * ksize 照 OpenCV：float 影像是 `round(sigma * 4 * 2 + 1) | 1`。核值用
     * `getGaussianKernel` 的公式（sigma > 0 一律走公式，不查小核表）。可分離、BORDER_REFLECT_101。
     */
    fun gaussianBlur(f: FImg, sigma: Double): FImg {
        if (sigma <= 0.0) return f.copy()
        var ks = (sigma * 4.0 * 2.0 + 1.0).roundToInt() or 1
        if (ks < 3) ks = 3
        val k = gaussianKernel(ks, sigma)
        return sepFilter(f, k)
    }

    private fun gaussianKernel(ks: Int, sigma: Double): DoubleArray {
        val k = DoubleArray(ks)
        val scale2X = -0.5 / (sigma * sigma)
        var sum = 0.0
        for (i in 0 until ks) {
            val x = i - (ks - 1) * 0.5
            val t = exp(scale2X * x * x)
            k[i] = t
            sum += t
        }
        for (i in 0 until ks) k[i] /= sum
        return k
    }

    /** `cv2.blur(f, (k, k))`：均值濾波、BORDER_REFLECT_101。 */
    fun boxBlur(f: FImg, k: Int): FImg {
        val kern = DoubleArray(k) { 1.0 / k }
        return sepFilter(f, kern)
    }

    /** 可分離濾波（先橫後縱），BORDER_REFLECT_101。 */
    /**
     * 可分離濾波（先橫後縱），BORDER_REFLECT_101。
     *
     * 邊界只影響最外圈的 r 個像素，但每個像素都呼叫 [reflect101] 的話，2.6 MPx × 兩趟 × 核長
     * 全都要走一次分支與迴圈。拆成「邊緣照走反射、中段直接索引」後，sigma=8 的模糊從 261 ms
     * 降到 70 ms 上下——中段是整張圖的絕大部分，而它完全不需要邊界檢查。
     *
     * 係數也預先轉成 FloatArray：內迴圈裡 Double 乘 Float 會反覆裝箱轉型。
     */
    private fun sepFilter(f: FImg, k: DoubleArray): FImg {
        val w = f.w
        val h = f.h
        val r = k.size / 2
        val kf = FloatArray(k.size) { k[it].toFloat() }
        val mid = FloatArray(w * h)

        // 橫向
        for (y in 0 until h) {
            val base = y * w
            // 左緣
            for (x in 0 until min(r, w)) {
                var acc = 0f
                for (t in kf.indices) acc += kf[t] * f.data[base + reflect101(x + t - r, w)]
                mid[base + x] = acc
            }
            // 中段：索引一定在界內
            val hiX = w - r
            for (x in r until hiX) {
                var acc = 0f
                var idx = base + x - r
                for (t in kf.indices) { acc += kf[t] * f.data[idx]; idx++ }
                mid[base + x] = acc
            }
            // 右緣
            for (x in max(r, hiX) until w) {
                var acc = 0f
                for (t in kf.indices) acc += kf[t] * f.data[base + reflect101(x + t - r, w)]
                mid[base + x] = acc
            }
        }

        // 縱向
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val obase = y * w
            if (y < r || y >= h - r) {
                for (x in 0 until w) {
                    var acc = 0f
                    for (t in kf.indices) acc += kf[t] * mid[reflect101(y + t - r, h) * w + x]
                    out[obase + x] = acc
                }
            } else {
                for (t in kf.indices) {
                    val c = kf[t]
                    val sbase = (y + t - r) * w
                    if (t == 0) {
                        for (x in 0 until w) out[obase + x] = c * mid[sbase + x]
                    } else {
                        for (x in 0 until w) out[obase + x] += c * mid[sbase + x]
                    }
                }
            }
        }
        return FImg(w, h, out)
    }

    /** BORDER_REFLECT_101：`abcd | cba` — 邊界像素不重複。 */
    internal fun reflect101(i: Int, n: Int): Int {
        if (n == 1) return 0
        var x = i
        while (x < 0 || x >= n) {
            if (x < 0) x = -x
            if (x >= n) x = 2 * (n - 1) - x
        }
        return x
    }

    // ── 縮放 ─────────────────────────────────────────────────────────

    /** `cv2.resize(f, (nw, nh), INTER_AREA)`（縮小用；對每個輸出格取其來源矩形的面積加權平均）。 */
    fun resizeArea(f: FImg, nw: Int, nh: Int): FImg {
        if (nw == f.w && nh == f.h) return f.copy()
        val d = f.data
        return resizeAreaOf(f.w, f.h, nw, nh) { d[it] }
    }

    /** [resizeArea] 的本體，來源像素由 [src]（索引 → 值）給：呼叫端可以不先攤出一張整頁的來源圖（同一套浮點運算）。 */
    internal inline fun resizeAreaOf(w: Int, h: Int, nw: Int, nh: Int, src: (Int) -> Float): FImg {
        val sx = w.toDouble() / nw
        val sy = h.toDouble() / nh
        val out = FloatArray(nw * nh)
        for (oy in 0 until nh) {
            val y0 = oy * sy
            val y1 = min(h.toDouble(), (oy + 1) * sy)
            val iy0 = floor(y0).toInt()
            val iy1 = min(h - 1, ceil(y1).toInt() - 1)
            for (ox in 0 until nw) {
                val x0 = ox * sx
                val x1 = min(w.toDouble(), (ox + 1) * sx)
                val ix0 = floor(x0).toInt()
                val ix1 = min(w - 1, ceil(x1).toInt() - 1)
                var acc = 0.0
                var wsum = 0.0
                for (y in iy0..iy1) {
                    val wy = min((y + 1).toDouble(), y1) - max(y.toDouble(), y0)
                    if (wy <= 0) continue
                    for (x in ix0..ix1) {
                        val wx = min((x + 1).toDouble(), x1) - max(x.toDouble(), x0)
                        if (wx <= 0) continue
                        val ww = wx * wy
                        acc += ww * src(y * w + x)
                        wsum += ww
                    }
                }
                out[oy * nw + ox] = if (wsum > 0) (acc / wsum).toFloat() else 0f
            }
        }
        return FImg(nw, nh, out)
    }

    /**
     * 雙線性放大（`cv2.resize(..., INTER_LINEAR)` 的幾何：來源座標 `(dst + 0.5) * scale - 0.5`）。
     *
     * 給「在半解析度算、放大回來用」的平滑場用：大 sigma 的模糊本來就沒有高頻，降採樣再放大
     * 的誤差遠小於它的平滑尺度。
     */
    fun resizeBilinear(f: FImg, nw: Int, nh: Int): FImg {
        if (nw == f.w && nh == f.h) return f.copy()
        val out = FloatArray(nw * nh)
        val sx = f.w.toDouble() / nw
        val sy = f.h.toDouble() / nh
        for (y in 0 until nh) {
            val fy = ((y + 0.5) * sy - 0.5).coerceIn(0.0, (f.h - 1).toDouble())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, f.h - 1)
            val wy = (fy - y0).toFloat()
            for (x in 0 until nw) {
                val fx = ((x + 0.5) * sx - 0.5).coerceIn(0.0, (f.w - 1).toDouble())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, f.w - 1)
                val wx = (fx - x0).toFloat()
                val a = f.data[y0 * f.w + x0] * (1 - wx) + f.data[y0 * f.w + x1] * wx
                val b = f.data[y1 * f.w + x0] * (1 - wx) + f.data[y1 * f.w + x1] * wx
                out[y * nw + x] = a * (1 - wy) + b * wy
            }
        }
        return FImg(nw, nh, out)
    }

    /** `cv2.resize(m, (nw, nh), INTER_NEAREST)`（cv2 取 `floor(dst * scale)`）。 */
    fun resizeNearest(m: Mask, nw: Int, nh: Int): Mask {
        val w = m.w
        val h = m.h
        if (nw == w && nh == h) return m.copy()
        val sx = w.toDouble() / nw
        val sy = h.toDouble() / nh
        val out = Mask(nw, nh)
        for (oy in 0 until nh) {
            val iy = min(h - 1, floor(oy * sy).toInt())
            for (ox in 0 until nw) {
                val ix = min(w - 1, floor(ox * sx).toInt())
                out.data[oy * nw + ox] = m.data[iy * w + ix]
            }
        }
        return out
    }

    /** 灰階版最近鄰，供遮罩以外的圖層用。 */
    fun resizeNearestGray(g: Gray, nw: Int, nh: Int): Gray {
        val w = g.w
        val h = g.h
        if (nw == w && nh == h) return g.copy()
        val sx = w.toDouble() / nw
        val sy = h.toDouble() / nh
        val out = Gray(nw, nh)
        for (oy in 0 until nh) {
            val iy = min(h - 1, floor(oy * sy).toInt())
            for (ox in 0 until nw) {
                val ix = min(w - 1, floor(ox * sx).toInt())
                out.data[oy * nw + ox] = g.data[iy * w + ix]
            }
        }
        return out
    }

    // ── 洞 / 輪廓 ────────────────────────────────────────────────────

    /**
     * 「1px 零邊框 + 從 (0,0) floodFill」的洞判定（`_hole_ink_ratio` / `sticker_metrics` 用）：
     * 回傳 **洞遮罩**＝非前景、且從影像外側（經 4-連通背景）到不了的像素。
     */
    fun holes(m: Mask): Mask {
        val w = m.w
        val h = m.h
        val reached = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        fun push(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= w || y >= h) return
            val i = y * w + x
            if (reached[i] || m.data[i]) return
            reached[i] = true
            stack[sp++] = i
        }
        for (x in 0 until w) { push(x, 0); push(x, h - 1) }
        for (y in 0 until h) { push(0, y); push(w - 1, y) }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            val y = i / w
            push(x - 1, y); push(x + 1, y); push(x, y - 1); push(x, y + 1)
        }
        return Mask(w, h, BooleanArray(w * h) { !m.data[it] && !reached[it] })
    }

    /**
     * `cv2.findContours(RETR_LIST, CHAIN_APPROX_NONE)` 後 `sum(arcLength(c, closed=True))`：
     * 前景所有輪廓（外輪廓＋洞輪廓）的**總周長**。arcLength 是閉合折線長（直步 1、對角步 √2、首尾相接）。
     *
     * 逐點復刻 OpenCV 的 Suzuki 邊界追蹤（contours.cpp 的 cvFindNextContour／icvFetchContour）：影像外圍補一圈 0、
     * 逐列掃描找「0→1」的外輪廓起點與「≥1→0」的洞輪廓起點（右鄰在追蹤時已被檢查為 0 的邊界像素標成負值＝那個 0 區
     * 的輪廓已追過，掃到它不再起新輪廓）；起點先順時針（外輪廓從左上、洞從右下）找第一個前景鄰居，之後每步從
     * 「回到上一點的方向」起逆時針找下一個前景鄰居，回到起點且下一步是第二點就停。細線會來回走（3 px 橫線的輪廓
     * ＝4 點、閉合長 4），與 cv2 一樣。
     *
     * 這不再是近似：純 python 同一份規則對 cv2 4.11 隨機 6400 張遮罩的輪廓點序列全同（長度差只剩 cv2 float32 累加的
     * 1e-8）。`Sticker.Metrics.rough`（perim²/(4π·面積)）是背景填黑三檔的決策量（≤ 10／20），不能只當排序用。
     */
    fun totalContourLength(m: Mask): Double {
        val w = m.w
        val h = m.h
        val pw = w + 2
        val ph = h + 2
        // 0＝背景、1＝未追、2＝已追、-126＝已追且右鄰是追蹤時檢查過的 0（cv2 的 nbd | -128）
        val img = IntArray(pw * ph)
        for (y in 0 until h) {
            val src = y * w
            val dst = (y + 1) * pw + 1
            for (x in 0 until w) if (m.data[src + x]) img[dst + x] = 1
        }
        // 方向 0..7＝右、右上、上、左上、左、左下、下、右下（cv2 的 icvCodeDeltas；y 向下）
        val delta = intArrayOf(1, 1 - pw, -pw, -pw - 1, -1, pw - 1, pw, pw + 1)
        val sqrt2 = 1.4142135623730951

        // 從起點 i0 追一圈，回傳這條輪廓的閉合折線長。每次迭代寫出點 i3、走到 i4：那一步就是折線的一段，
        // 最後一步（i3 = 第二點、i4 = 起點）正好是閉合段，所以直接累加每步的步長即可。
        fun fetch(i0: Int, isHole: Boolean): Double {
            val sStart = if (isHole) 0 else 4
            var s = sStart
            var i1: Int
            do {
                s = (s - 1) and 7
                i1 = i0 + delta[s]
            } while (img[i1] == 0 && s != sStart)
            if (s == sStart) {              // 單像素：輪廓只有一點、長 0
                img[i0] = -126
                return 0.0
            }
            var i3 = i0
            var len = 0.0
            while (true) {
                val sEnd = s
                var i4: Int
                do {
                    s++
                    i4 = i3 + delta[s and 7]
                } while (img[i4] == 0)
                s = s and 7
                // 找到的方向落在 1..sEnd ＝ 逆時針搜尋時經過了方向 0（右鄰）且它是 0 ⇒ 標「右界」
                if (s >= 1 && s - 1 < sEnd) img[i3] = -126 else if (img[i3] == 1) img[i3] = 2
                len += if (s and 1 == 0) 1.0 else sqrt2
                if (i4 == i0 && i3 == i1) break
                i3 = i4
                s = (s + 4) and 7
            }
            return len
        }

        var total = 0.0
        val width = pw - 1
        for (y in 1 until ph - 1) {
            val row = y * pw
            var x = 1
            var prev = 0
            while (x < width) {
                while (x < width && img[row + x] == prev) x++
                if (x >= width) break
                val p = img[row + x]
                var isHole = false
                var skip = false
                if (!(prev == 0 && p == 1)) {
                    if (p != 0 || prev < 1) skip = true else isHole = true
                }
                if (!skip) total += fetch(row + x - (if (isHole) 1 else 0), isHole)
                prev = img[row + x]         // cv2 每追完一條就 return，下次進來以（已標記的）img[x-1] 重讀 prev
                x++
            }
        }
        return total
    }

    /** `cv2.contourArea(quad)`（多邊形面積，shoelace 絕對值）。 */
    fun polygonArea(xs: FloatArray, ys: FloatArray): Double {
        var a = 0.0
        val n = xs.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += xs[i].toDouble() * ys[j] - xs[j].toDouble() * ys[i]
        }
        return abs(a) / 2.0
    }

    // ── 管線常用的小工具 ──────────────────────────────────────────────

    /**
     * 測地生長（`geodesic_grow`）：seed 在 within 內反覆 **3×3 膨脹 iters 次**，所以 `iters`
     * 就是生長距離（像素）。
     *
     * ⚠️ [step] 只是**批次大小**（一次連做 n 次膨脹再與 within 取交集，省下中間的 AND），
     * 不是膨脹半徑。把它當半徑用會讓生長距離變成 step 倍——人物遮罩因此胖了 17%，
     * 連帶讓該填黑的背景被當成人物還原成灰。
     */
    /**
     * 前沿 BFS 版的測地生長：回傳每個像素**第一次被到達時的累計膨脹次數**（種子 0、未到達 -1）。
     *
     * 節奏與 [geodesicGrow] 相同——每批 n = min(step, maxIters − done) 次 3×3 膨脹後才與 within
     * 交集一次（可跨過窄於 n 的縫）。但每批只把**上一批新到的像素**往外擴一個 (2n+1)² 的方塊：
     * R_t = D_n(R_{t−1}) ∩ within，而 D_n(R_{t−2}) ∩ within ⊆ R_{t−1}，所以只擴新像素就夠，每個像素
     * 一生只擴張一次 ⇒ O(到達面積 × (2·step+1)²)，與 iters 無關。迭代版是 O(iters × 面積)：貼紙的
     * 測地比要走上千步、偽泡生長 cap 上百，demo06／demo02 的 2.4 s／3.7 s 全是這個。
     */
    fun geodesicDistance(seed: Mask, within: Mask, step: Int, maxIters: Int): IntArray {
        val w = seed.w
        val h = seed.h
        val dist = IntArray(w * h) { -1 }
        val queue = IntArray(within.count()) // 每個像素一生只進一次，而且只有 within 內的像素會進
        var qEnd = 0
        for (i in dist.indices) if (seed.data[i] && within.data[i]) { dist[i] = 0; queue[qEnd++] = i }
        var qStart = 0
        var done = 0
        while (done < maxIters && qStart < qEnd) {
            val n = min(step, maxIters - done)
            done += n
            val batchEnd = qEnd
            while (qStart < batchEnd) {
                val idx = queue[qStart++]
                val cx = idx % w
                val cy = idx / w
                val y1 = min(h - 1, cy + n)
                val x0 = max(0, cx - n)
                val x1 = min(w - 1, cx + n)
                for (yy in max(0, cy - n)..y1) {
                    val base = yy * w
                    for (xx in x0..x1) {
                        val j = base + xx
                        if (dist[j] < 0 && within.data[j]) { dist[j] = done; queue[qEnd++] = j }
                    }
                }
            }
        }
        return dist
    }

    /**
     * 兩種實作結果逐位元相同，按成本選（[bfs] 可強制）：迭代 O(iters × 面積)、BFS
     * O(到達面積 × (2·step+1)²)；BFS 的隨機查表比迭代的循序掃描每次貴約 3 倍，門檻取 3·iters > (2·step+1)²。
     */
    fun geodesicGrow(seed: Mask, within: Mask, iters: Int, step: Int = 5, bfs: Boolean? = null): Mask {
        if (iters <= 0) return seed and within
        // 實測 BFS 每次查表比迭代每像素貴 ~3×（隨機存取），門檻放 3 倍：iters > (2·step+1)²/3
        val useBfs = bfs ?: (iters.toLong() * 3 > (2L * step + 1) * (2L * step + 1))
        if (useBfs) {
            val dist = geodesicDistance(seed, within, step, iters)
            return Mask(seed.w, seed.h, BooleanArray(dist.size) { dist[it] >= 0 })
        }
        // 迭代版走位元打包（2026-10-06；原本逐像素看八鄰居，留在測試 MorphPackedTest 當參考實作，逐像素相同）：
        // 一次 3×3 膨脹＝每列左右移一位 OR、再上下三列 OR（64 px 一字）。
        //
        // ⚠️ within 的交集要照 Python 的節奏：它是 `dilate(cur, k, iterations=n) & within`，
        // 也就是**連做 n 次膨脹後才交集一次**。每次都交集會讓生長被 within 的細縫擋住，
        // 結果完全不同（實測 MAE 0.57→0.93、紅線 0.09→0.36%）。長不動（一批之後跟之前相同）就停：之後每批都一樣。
        val w = seed.w
        val h = seed.h
        val withinB = packBits(within)
        var cur = packBits(seed)
        for (i in cur.indices) cur[i] = cur[i] and withinB[i]
        var nxt = LongArray(cur.size)
        val hor = LongArray(cur.size)
        var done = 0
        while (done < iters) {
            val n = min(step, iters - done)
            System.arraycopy(cur, 0, nxt, 0, cur.size)
            repeat(n) { dilate3Bits(nxt, hor, w, h) }
            for (i in nxt.indices) nxt[i] = nxt[i] and withinB[i]
            done += n
            val same = nxt.contentEquals(cur)
            val t = cur; cur = nxt; nxt = t
            if (same) break
        }
        return unpackBits(cur, w, h)
    }

    /** 打包位元就地 3×3 膨脹（影像外當 0）：[hor] 是同尺寸的工作緩衝。 */
    private fun dilate3Bits(a: LongArray, hor: LongArray, w: Int, h: Int) {
        val nw = (w + 63) ushr 6
        val tail = if (w and 63 == 0) -1L else (1L shl (w and 63)) - 1
        for (y in 0 until h) {
            val b = y * nw
            for (i in 0 until nw) {
                val v = a[b + i]
                var l = v shl 1                       // out[x] |= in[x − 1]
                if (i > 0) l = l or (a[b + i - 1] ushr 63)
                var r = v ushr 1                      // out[x] |= in[x + 1]
                if (i + 1 < nw) r = r or (a[b + i + 1] shl 63)
                hor[b + i] = v or l or r
            }
            hor[b + nw - 1] = hor[b + nw - 1] and tail
        }
        for (y in 0 until h) {
            val b = y * nw
            val up = b - nw
            val dn = b + nw
            for (i in 0 until nw) {
                var v = hor[b + i]
                if (y > 0) v = v or hor[up + i]
                if (y < h - 1) v = v or hor[dn + i]
                a[b + i] = v
            }
        }
    }

    /**
     * 中值濾波（`cv2.medianBlur`）。二值影像的中值＝多數決：窗內 true 的數量過半即 true。
     *
     * 邊界是 cv2 的 BORDER_REPLICATE（複製邊緣像素），所以先把影像 replicate-pad 到
     * `(w+2rad, h+2rad)` 再算積分圖，這樣每個窗都是完整的 r×r，不必補償。
     */
    fun medianBlurMask(m: Mask, r: Int): Mask {
        val w = m.w
        val h = m.h
        val rad = r / 2
        val need = (r * r) / 2 + 1
        val pw = w + 2 * rad
        val ph = h + 2 * rad
        // replicate pad 後的積分圖：integral[(y+1)*(pw+1) + (x+1)] ＝ [0,y]×[0,x] 的 true 數
        val integral = IntArray((pw + 1) * (ph + 1))
        for (py in 0 until ph) {
            val sy = (py - rad).coerceIn(0, h - 1)
            var rowSum = 0
            val base = sy * w
            for (px in 0 until pw) {
                val sx = (px - rad).coerceIn(0, w - 1)
                if (m.data[base + sx]) rowSum++
                integral[(py + 1) * (pw + 1) + (px + 1)] = integral[py * (pw + 1) + (px + 1)] + rowSum
            }
        }
        val out = Mask(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // 原圖 (x,y) 的窗在 padded 座標是 [x, x+r) × [y, y+r)
                val a = x
                val b = y
                val c = x + r
                val d = y + r
                val cnt = integral[d * (pw + 1) + c] - integral[b * (pw + 1) + c] -
                    integral[d * (pw + 1) + a] + integral[b * (pw + 1) + a]
                out.data[y * w + x] = cnt >= need
            }
        }
        return out
    }

    // ── 繪圖（cv2.line / cv2.fillPoly，LINE_8、shift 0）──────────────────────
    //
    // 逐行移植 OpenCV 4.11 `modules/imgproc/src/drawing.cpp`：LineIterator（8 連通、左到右）、Line2（16 位定點）、
    // FillConvexPoly、Circle（實心）、ThickLine、CollectPolyEdges + FillEdgeCollection、clipLine。
    // 點陣化規則（端點捨入、邊的定點斜率、掃描線左右端的取整）全照 cv2——格溝偵測拿它畫框線、延長線與走廊，
    // 差一顆像素就會連鎖改變溝網連通與頁邊判定。只支援單通道遮罩、顏色＝true。

    private const val XY_SHIFT = 16
    private const val XY_ONE = 1L shl XY_SHIFT

    /** `cv::clipLine(Size2l, Point2l&, Point2l&)`：把線段裁進 [0,w)×[0,h)；回傳是否有落在影像內的部分。 */
    private fun clipLine(w: Long, h: Long, p: LongArray): Boolean {
        // p = [x1, y1, x2, y2]，就地修改
        if (w <= 0 || h <= 0) return false
        val right = w - 1
        val bottom = h - 1
        var x1 = p[0]; var y1 = p[1]; var x2 = p[2]; var y2 = p[3]
        fun code(x: Long, y: Long) =
            (if (x < 0) 1 else 0) + (if (x > right) 2 else 0) + (if (y < 0) 4 else 0) + (if (y > bottom) 8 else 0)
        var c1 = code(x1, y1)
        var c2 = code(x2, y2)
        if ((c1 and c2) == 0 && (c1 or c2) != 0) {
            var a: Long
            if ((c1 and 12) != 0) {
                a = if (c1 < 8) 0 else bottom
                x1 += ((a - y1).toDouble() * (x2 - x1).toDouble() / (y2 - y1).toDouble()).toLong()
                y1 = a
                c1 = (if (x1 < 0) 1 else 0) + (if (x1 > right) 2 else 0)
            }
            if ((c2 and 12) != 0) {
                a = if (c2 < 8) 0 else bottom
                x2 += ((a - y2).toDouble() * (x2 - x1).toDouble() / (y2 - y1).toDouble()).toLong()
                y2 = a
                c2 = (if (x2 < 0) 1 else 0) + (if (x2 > right) 2 else 0)
            }
            if ((c1 and c2) == 0 && (c1 or c2) != 0) {
                if (c1 != 0) {
                    a = if (c1 == 1) 0 else right
                    y1 += ((a - x1).toDouble() * (y2 - y1).toDouble() / (x2 - x1).toDouble()).toLong()
                    x1 = a
                    c1 = 0
                }
                if (c2 != 0) {
                    a = if (c2 == 1) 0 else right
                    y2 += ((a - x2).toDouble() * (y2 - y1).toDouble() / (x2 - x1).toDouble()).toLong()
                    x2 = a
                    c2 = 0
                }
            }
        }
        p[0] = x1; p[1] = y1; p[2] = x2; p[3] = y2
        return (c1 or c2) == 0
    }

    private fun hline(m: Mask, y: Int, xl: Int, xr: Int) {
        val base = y * m.w
        for (x in xl..xr) m.data[base + x] = true
    }

    /** cv2 `Line()`（thickness 1、LINE_8）：`LineIterator(img, p1, p2, 8, leftToRight=true)`，端點在外先 clipLine。 */
    private fun lineIter(m: Mask, ax: Int, ay: Int, bx: Int, by: Int) {
        val w = m.w
        val h = m.h
        var x1 = ax; var y1 = ay; var x2 = bx; var y2 = by
        if (x1 < 0 || x1 >= w || x2 < 0 || x2 >= w || y1 < 0 || y1 >= h || y2 < 0 || y2 >= h) {
            val p = longArrayOf(x1.toLong(), y1.toLong(), x2.toLong(), y2.toLong())
            if (!clipLine(w.toLong(), h.toLong(), p)) return
            x1 = p[0].toInt(); y1 = p[1].toInt(); x2 = p[2].toInt(); y2 = p[3].toInt()
        }
        var deltaX = 1
        var deltaY = 1
        var dx = x2 - x1
        var dy = y2 - y1
        if (dx < 0) {                      // leftToRight：從左端開始
            dx = -dx; dy = -dy
            x1 = x2; y1 = y2
        }
        if (dy < 0) { dy = -dy; deltaY = -1 }
        val vert = dy > dx
        if (vert) {
            val t = dx; dx = dy; dy = t
            val td = deltaX; deltaX = deltaY; deltaY = td
        }
        var err = dx - (dy + dy)
        val plusDelta = dx + dx
        val minusDelta = -(dy + dy)
        var minusShift = deltaX
        var plusShift = 0
        var minusStep = 0
        var plusStep = deltaY
        val count = dx + 1
        if (vert) {
            var t = plusStep; plusStep = plusShift; plusShift = t
            t = minusStep; minusStep = minusShift; minusShift = t
        }
        var px = x1
        var py = y1
        for (i in 0 until count) {
            m.data[py * w + px] = true
            val mask = if (err < 0) -1 else 0
            err += minusDelta + (plusDelta.toInt() and mask)
            px += minusShift + (plusShift and mask)
            py += minusStep + (plusStep and mask)
        }
    }

    /** cv2 `Line2()`：16 位定點端點的細線（FillConvexPoly 在 shift≠0 時畫邊用）。 */
    private fun line2(m: Mask, p1x0: Long, p1y0: Long, p2x0: Long, p2y0: Long) {
        val w = m.w
        val h = m.h
        val p = longArrayOf(p1x0, p1y0, p2x0, p2y0)
        if (!clipLine(w.toLong() shl XY_SHIFT, h.toLong() shl XY_SHIFT, p)) return
        var pt1x = p[0]; var pt1y = p[1]; var pt2x = p[2]; var pt2y = p[3]
        var dx = pt2x - pt1x
        var dy = pt2y - pt1y
        val j = if (dx < 0) -1L else 0L
        val ax = (dx xor j) - j
        val i = if (dy < 0) -1L else 0L
        val ay = (dy xor i) - i
        val xStep: Long
        val yStep: Long
        var ecount: Int
        if (ax > ay) {
            dy = (dy xor j) - j
            pt1x = pt1x xor (pt2x and j); pt2x = pt2x xor (pt1x and j); pt1x = pt1x xor (pt2x and j)
            pt1y = pt1y xor (pt2y and j); pt2y = pt2y xor (pt1y and j); pt1y = pt1y xor (pt2y and j)
            xStep = XY_ONE
            yStep = dy * (1L shl XY_SHIFT) / (ax or 1L)
            ecount = ((pt2x - pt1x) shr XY_SHIFT).toInt()
        } else {
            dx = (dx xor i) - i
            pt1x = pt1x xor (pt2x and i); pt2x = pt2x xor (pt1x and i); pt1x = pt1x xor (pt2x and i)
            pt1y = pt1y xor (pt2y and i); pt2y = pt2y xor (pt1y and i); pt1y = pt1y xor (pt2y and i)
            xStep = dx * (1L shl XY_SHIFT) / (ay or 1L)
            yStep = XY_ONE
            ecount = ((pt2y - pt1y) shr XY_SHIFT).toInt()
        }
        pt1x += XY_ONE shr 1
        pt1y += XY_ONE shr 1
        fun put(x: Long, y: Long) {
            if (x in 0 until w && y in 0 until h) m.data[y.toInt() * w + x.toInt()] = true
        }
        put((pt2x + (XY_ONE shr 1)) shr XY_SHIFT, (pt2y + (XY_ONE shr 1)) shr XY_SHIFT)
        if (ax > ay) {
            pt1x = pt1x shr XY_SHIFT
            while (ecount >= 0) {
                put(pt1x, pt1y shr XY_SHIFT)
                pt1x++
                pt1y += yStep
                ecount--
            }
        } else {
            pt1y = pt1y shr XY_SHIFT
            while (ecount >= 0) {
                put(pt1x shr XY_SHIFT, pt1y)
                pt1x += xStep
                pt1y++
                ecount--
            }
        }
    }

    /** cv2 `FillConvexPoly()`（LINE_8）。vx/vy 是 `shift` 位定點座標。 */
    private fun fillConvexPoly(m: Mask, vx: LongArray, vy: LongArray, shift: Int) {
        val npts = vx.size
        val w = m.w
        val h = m.h
        val delta = (1 shl shift) shr 1
        val delta1 = XY_ONE shr 1
        val delta2 = XY_ONE shr 1
        var imin = 0
        var edges = npts
        var p0x = vx[npts - 1] shl (XY_SHIFT - shift)
        var p0y = vy[npts - 1] shl (XY_SHIFT - shift)
        var xmin = vx[0]; var xmax = vx[0]
        var ymin = vy[0]; var ymax = vy[0]
        for (i in 0 until npts) {
            var px = vx[i]
            var py = vy[i]
            if (py < ymin) { ymin = py; imin = i }
            ymax = max(ymax, py)
            xmax = max(xmax, px)
            xmin = min(xmin, px)
            px = px shl (XY_SHIFT - shift)
            py = py shl (XY_SHIFT - shift)
            if (shift == 0) {
                lineIter(m, (p0x shr XY_SHIFT).toInt(), (p0y shr XY_SHIFT).toInt(),
                    (px shr XY_SHIFT).toInt(), (py shr XY_SHIFT).toInt())
            } else {
                line2(m, p0x, p0y, px, py)
            }
            p0x = px
            p0y = py
        }
        xmin = (xmin + delta) shr shift
        xmax = (xmax + delta) shr shift
        ymin = (ymin + delta) shr shift
        ymax = (ymax + delta) shr shift
        if (npts < 3 || xmax.toInt() < 0 || ymax.toInt() < 0 || xmin.toInt() >= w || ymin.toInt() >= h) return
        ymax = min(ymax, (h - 1).toLong())
        val eIdx = intArrayOf(imin, imin)
        val eDi = intArrayOf(1, npts - 1)
        val eX = longArrayOf(-XY_ONE, -XY_ONE)
        val eDx = longArrayOf(0, 0)
        var y = ymin.toInt()
        val eYe = intArrayOf(y, y)
        do {
            for (i in 0..1) {
                if (y >= eYe[i]) {
                    var idx0 = eIdx[i]
                    val di = eDi[i]
                    var idx = idx0 + di
                    if (idx >= npts) idx -= npts
                    var ty: Int
                    while (true) {
                        val cont = edges > 0
                        edges--
                        if (!cont) break
                        ty = ((vy[idx] + delta) shr shift).toInt()
                        if (ty > y) {
                            var xs = vx[idx0]
                            var xe = vx[idx]
                            if (shift != XY_SHIFT) {
                                xs = xs shl (XY_SHIFT - shift)
                                xe = xe shl (XY_SHIFT - shift)
                            }
                            eYe[i] = ty
                            eDx[i] = ((xe - xs) * 2 + (ty.toLong() - y)) / (2 * (ty.toLong() - y))
                            eX[i] = xs
                            eIdx[i] = idx
                            break
                        }
                        idx0 = idx
                        idx += di
                        if (idx >= npts) idx -= npts
                    }
                }
            }
            if (edges < 0) break
            if (y >= 0) {
                var left = 0
                var right = 1
                if (eX[0] > eX[1]) { left = 1; right = 0 }
                var xx1 = ((eX[left] + delta1) shr XY_SHIFT).toInt()
                var xx2 = ((eX[right] + delta2) shr XY_SHIFT).toInt()
                if (xx2 >= 0 && xx1 < w) {
                    if (xx1 < 0) xx1 = 0
                    if (xx2 >= w) xx2 = w - 1
                    hline(m, y, xx1, xx2)
                }
            }
            eX[0] += eDx[0]
            eX[1] += eDx[1]
        } while (++y <= ymax.toInt())
    }

    /** cv2 `Circle(img, center, radius, color, fill=1)`：實心圓（Bresenham 式逐列水平線）。 */
    private fun fillCircle(m: Mask, cx: Int, cy: Int, radius: Int) {
        val w = m.w
        val h = m.h
        var err = 0L
        var dx = radius.toLong()
        var dy = 0L
        var plus = 1L
        var minus = (radius.toLong() shl 1) - 1
        val inside = cx >= radius && cx < w - radius && cy >= radius && cy < h - radius
        while (dx >= dy) {
            val y11 = cy - dy; val y12 = cy + dy; val y21 = cy - dx; val y22 = cy + dx
            var x11 = cx - dx; var x12 = cx + dx; var x21 = cx - dy; var x22 = cx + dy
            if (inside) {
                hline(m, y11.toInt(), x11.toInt(), x12.toInt())
                hline(m, y12.toInt(), x11.toInt(), x12.toInt())
                hline(m, y21.toInt(), x21.toInt(), x22.toInt())
                hline(m, y22.toInt(), x21.toInt(), x22.toInt())
            } else if (x11 < w && x12 >= 0 && y21 < h && y22 >= 0) {
                x11 = max(x11, 0L)
                x12 = min(x12, (w - 1).toLong())
                if (y11 >= 0 && y11 < h) hline(m, y11.toInt(), x11.toInt(), x12.toInt())
                if (y12 >= 0 && y12 < h) hline(m, y12.toInt(), x11.toInt(), x12.toInt())
                if (x21 < w && x22 >= 0) {
                    x21 = max(x21, 0L)
                    x22 = min(x22, (w - 1).toLong())
                    if (y21 >= 0 && y21 < h) hline(m, y21.toInt(), x21.toInt(), x22.toInt())
                    if (y22 >= 0 && y22 < h) hline(m, y22.toInt(), x21.toInt(), x22.toInt())
                }
            }
            dy++
            err += plus
            plus += 2
            val mask = if (err <= 0) 0L else -1L
            err -= minus and mask
            dx += mask
            minus -= mask and 2L
        }
    }

    /**
     * `cv2.line(m, (x0,y0), (x1,y1), 1, thickness)`（LINE_8、shift 0）＝ThickLine：thickness ≤ 1 走 LineIterator；
     * 否則定點平行四邊形（FillConvexPoly，半寬 = thickness/2 沿法向、`cvRound` 半數取偶）＋兩端各一個實心圓
     * （半徑 (thickness+1)/2，即 thickness=2r+1 時為 r+1）。端點可在影像外（照 cv2 裁切）。
     */
    fun line(m: Mask, x0: Int, y0: Int, x1: Int, y1: Int, thickness: Int) {
        var p0x = x0.toLong() shl XY_SHIFT
        var p0y = y0.toLong() shl XY_SHIFT
        val p1x = x1.toLong() shl XY_SHIFT
        val p1y = y1.toLong() shl XY_SHIFT
        if (thickness <= 1) {
            lineIter(m, x0, y0, x1, y1)
            return
        }
        val inv = 1.0 / XY_ONE.toDouble()
        val dx = (p0x - p1x) * inv
        val dy = (p1y - p0y) * inv
        var r = dx * dx + dy * dy
        val odd = thickness and 1
        val th = thickness.toLong() shl (XY_SHIFT - 1)
        if (abs(r) > 2.220446049250313e-16) {
            r = (th + odd * XY_ONE * 0.5) / sqrt(r)
            val dpx = Math.rint(dy * r).toLong()      // cvRound：半數取偶
            val dpy = Math.rint(dx * r).toLong()
            fillConvexPoly(
                m,
                longArrayOf(p0x + dpx, p0x - dpx, p1x - dpx, p1x + dpx),
                longArrayOf(p0y + dpy, p0y - dpy, p1y - dpy, p1y + dpy),
                XY_SHIFT,
            )
        }
        val rad = ((th + (XY_ONE shr 1)) shr XY_SHIFT).toInt()
        for (i in 0..1) {
            val cx = ((p0x + (XY_ONE shr 1)) shr XY_SHIFT).toInt()
            val cy = ((p0y + (XY_ONE shr 1)) shr XY_SHIFT).toInt()
            fillCircle(m, cx, cy, rad)
            p0x = p1x
            p0y = p1y
        }
    }

    /**
     * `cv2.fillPoly(m, [pts], 1)`（單一多邊形、LINE_8、shift 0）：CollectPolyEdges（先用 LineIterator 畫外框、
     * 裁切後的端點修正邊的起點）＋ FillEdgeCollection（活動邊表逐列掃描、左端 ceil 右端 floor）。
     * 頂點可在影像外。
     */
    fun fillPoly(m: Mask, xs: IntArray, ys: IntArray) {
        val count = xs.size
        val w = m.w
        val h = m.h
        // ── CollectPolyEdges（offset 0、shift 0 ⇒ delta 0）──
        val ey0 = IntArray(count)
        val ey1 = IntArray(count)
        val ex = LongArray(count + 1)
        val edx = LongArray(count + 1)
        var total = 0
        var pt0x = xs[count - 1].toLong() shl XY_SHIFT
        var pt0y = ys[count - 1].toLong()
        for (i in 0 until count) {
            val pt1x = xs[i].toLong() shl XY_SHIFT
            val pt1y = ys[i].toLong()
            var pt0cy = pt0y
            var pt1cy = pt1y
            val t = longArrayOf((pt0x + (XY_ONE shr 1)) shr XY_SHIFT, pt0y, (pt1x + (XY_ONE shr 1)) shr XY_SHIFT, pt1y)
            lineIter(m, t[0].toInt(), t[1].toInt(), t[2].toInt(), t[3].toInt())
            if (t[0] < 0 || t[0] >= w || t[2] < 0 || t[2] >= w || t[1] < 0 || t[1] >= h || t[3] < 0 || t[3] >= h) {
                clipLine(w.toLong(), h.toLong(), t)
                if (t[1] != t[3]) { pt0cy = t[1]; pt1cy = t[3] }
            }
            val pt0cx = t[0] shl XY_SHIFT
            val pt1cx = t[2] shl XY_SHIFT
            if (pt0y != pt1y) {
                val dxe = (pt1cx - pt0cx) / (pt1cy - pt0cy)
                if (pt0y < pt1y) {
                    ey0[total] = pt0y.toInt(); ey1[total] = pt1y.toInt()
                    ex[total] = pt0cx + (pt0y - pt0cy) * dxe
                } else {
                    ey0[total] = pt1y.toInt(); ey1[total] = pt0y.toInt()
                    ex[total] = pt1cx + (pt1y - pt1cy) * dxe
                }
                edx[total] = dxe
                total++
            }
            pt0x = pt1x
            pt0y = pt1y
        }
        // ── FillEdgeCollection ──
        if (total < 2) return
        val delta = XY_ONE - 1
        var yMax = Int.MIN_VALUE
        var yMin = Int.MAX_VALUE
        var xMax = -1L
        var xMin = Long.MAX_VALUE
        for (i in 0 until total) {
            val x1 = ex[i] + (ey1[i] - ey0[i]).toLong() * edx[i]
            yMin = min(yMin, ey0[i]); yMax = max(yMax, ey1[i])
            xMin = min(xMin, ex[i]); xMax = max(xMax, ex[i])
            xMin = min(xMin, x1); xMax = max(xMax, x1)
        }
        if (yMax < 0 || yMin >= h || xMax < 0 || xMin >= (w.toLong() shl XY_SHIFT)) return
        // CmpEdges：y0、x、dx 遞增。std::sort 在 ≤16 個元素時是插入排序（穩定）⇒ 這裡用穩定排序
        val order = (0 until total).sortedWith(compareBy<Int>({ ey0[it] }, { ex[it] }, { edx[it] }))
        val sy0 = IntArray(total + 1); val sy1 = IntArray(total + 1)
        val sx = LongArray(total + 1); val sdx = LongArray(total + 1)
        for ((k, o) in order.withIndex()) { sy0[k] = ey0[o]; sy1[k] = ey1[o]; sx[k] = ex[o]; sdx[k] = edx[o] }
        sy0[total] = Int.MAX_VALUE                     // 哨兵（edges.push_back(tmp)）
        val next = IntArray(total + 2) { -1 }
        val head = total + 1                           // tmp（活動邊表的頭）
        var i = 0
        var e = 0
        yMax = min(yMax, h)
        var y = sy0[e]
        while (y < yMax) {
            var draw = 0
            val clipline = y < 0
            var prelast = head
            var last = next[head]
            while (last >= 0 || sy0[e] == y) {
                if (last >= 0 && sy1[last] == y) {
                    next[prelast] = next[last]
                    last = next[last]
                    continue
                }
                val keepPrelast = prelast
                if (last >= 0 && (sy0[e] > y || sx[last] < sx[e])) {
                    prelast = last
                    last = next[last]
                } else if (i < total) {
                    next[prelast] = e
                    next[e] = last
                    prelast = e
                    e = ++i
                } else {
                    break
                }
                if (draw != 0) {
                    if (!clipline) {
                        var x1: Int
                        var x2: Int
                        if (sx[keepPrelast] > sx[prelast]) {
                            x1 = ((sx[prelast] + delta) shr XY_SHIFT).toInt()
                            x2 = (sx[keepPrelast] shr XY_SHIFT).toInt()
                        } else {
                            x1 = ((sx[keepPrelast] + delta) shr XY_SHIFT).toInt()
                            x2 = (sx[prelast] shr XY_SHIFT).toInt()
                        }
                        if (x1 < w && x2 >= 0) {
                            if (x1 < 0) x1 = 0
                            if (x2 >= w) x2 = w - 1
                            hline(m, y, x1, x2)
                        }
                    }
                    sx[keepPrelast] += sdx[keepPrelast]
                    sx[prelast] += sdx[prelast]
                }
                draw = draw xor 1
            }
            // 活動邊表依 x 泡沫排序
            var keep = -1
            do {
                prelast = head
                last = next[head]
                var lastExchange = -1
                while (last != keep && next[last] != -1) {
                    val te = next[last]
                    if (sx[last] > sx[te]) {
                        next[prelast] = te
                        next[last] = next[te]
                        next[te] = last
                        prelast = te
                        lastExchange = prelast
                    } else {
                        prelast = last
                        last = te
                    }
                }
                if (lastExchange == -1) break
                keep = lastExchange
            } while (keep != next[head] && keep != head)
            y++
        }
    }

    // ── chamfer 距離變換（cv2.distanceTransform(DIST_L2, 3／5) 的非 IPP 路徑）──────────
    //
    // [distanceL2] 是精確歐氏；研究端拿 chamfer 值比門檻的地方（格溝的 NET_THICK）要用這個。
    // 逐行移植 `distransform.cpp` 的 distanceTransform_3x3／_5x5：16 位定點（HV=round(0.955·2¹⁶)…）、
    // 兩趟掃描、DIST_MAX 飽和。⚠️ pip 版 opencv 內建 IPP，實際走 `ippiDistanceTransform_*_8u32f`（浮點累加、
    // 權重不同）：這裡的定點權重 62587/65536、89738/65536 對 0.955、1.3693 有 ≤ 5e-6 的相對誤差，隨距離累積；
    // 但決策門檻（≥ 5）附近最近的 chamfer 值是 4.775／5.0629，差距遠大於此 ⇒ 門檻遮罩逐像素相同（47 頁實測）。
    // `cv2.ipp.setUseIPP(False)` 時與這裡逐位元相同。

    /** `cv2.distanceTransform(m, DIST_L2, maskSize)`，maskSize ∈ {3, 5}；前景＝true，輸出到最近背景的 chamfer 距離。 */
    fun distanceChamfer(m: Mask, maskSize: Int): FImg {
        require(maskSize == 3 || maskSize == 5) { "maskSize 只能 3 或 5" }
        val w = m.w
        val h = m.h
        val border = if (maskSize == 3) 1 else 2
        fun fix(v: Float): Long = Math.rint(v.toDouble() * (1 shl 16)).toLong()
        val hv: Long
        val diag: Long
        val lng: Long
        if (maskSize == 3) { hv = fix(0.955f); diag = fix(1.3693f); lng = 0 } else { hv = fix(1.0f); diag = fix(1.4f); lng = fix(2.1969f) }
        val distMax = 0xFFFFFFFFL - (if (maskSize == 3) diag else lng)
        val scale = 1f / (1 shl 16)
        val sw = w + 2 * border
        val sh = h + 2 * border
        val t = LongArray(sw * sh)
        for (bi in 0 until border) {
            for (x in 0 until sw) { t[bi * sw + x] = distMax; t[(sh - 1 - bi) * sw + x] = distMax }
        }
        // 前向
        for (y in 0 until h) {
            val row = (y + border) * sw + border
            for (bj in 0 until border) { t[row - bj - 1] = distMax; t[row + w + bj] = distMax }
            val src = y * w
            for (x in 0 until w) {
                val j = row + x
                if (!m.data[src + x]) { t[j] = 0; continue }
                var t0: Long
                if (maskSize == 3) {
                    t0 = t[j - sw - 1] + diag
                    var c = t[j - sw] + hv; if (t0 > c) t0 = c
                    c = t[j - sw + 1] + diag; if (t0 > c) t0 = c
                    c = t[j - 1] + hv; if (t0 > c) t0 = c
                } else {
                    t0 = t[j - sw * 2 - 1] + lng
                    var c = t[j - sw * 2 + 1] + lng; if (t0 > c) t0 = c
                    c = t[j - sw - 2] + lng; if (t0 > c) t0 = c
                    c = t[j - sw - 1] + diag; if (t0 > c) t0 = c
                    c = t[j - sw] + hv; if (t0 > c) t0 = c
                    c = t[j - sw + 1] + diag; if (t0 > c) t0 = c
                    c = t[j - sw + 2] + lng; if (t0 > c) t0 = c
                    c = t[j - 1] + hv; if (t0 > c) t0 = c
                }
                t[j] = if (t0 > distMax) distMax else t0
            }
        }
        // 後向
        val out = FImg(w, h)
        for (y in h - 1 downTo 0) {
            val row = (y + border) * sw + border
            for (x in w - 1 downTo 0) {
                val j = row + x
                var t0 = t[j]
                if (t0 > hv) {
                    if (maskSize == 3) {
                        var c = t[j + sw + 1] + diag; if (t0 > c) t0 = c
                        c = t[j + sw] + hv; if (t0 > c) t0 = c
                        c = t[j + sw - 1] + diag; if (t0 > c) t0 = c
                        c = t[j + 1] + hv; if (t0 > c) t0 = c
                    } else {
                        var c = t[j + sw * 2 + 1] + lng; if (t0 > c) t0 = c
                        c = t[j + sw * 2 - 1] + lng; if (t0 > c) t0 = c
                        c = t[j + sw + 2] + lng; if (t0 > c) t0 = c
                        c = t[j + sw + 1] + diag; if (t0 > c) t0 = c
                        c = t[j + sw] + hv; if (t0 > c) t0 = c
                        c = t[j + sw - 1] + diag; if (t0 > c) t0 = c
                        c = t[j + sw - 2] + lng; if (t0 > c) t0 = c
                        c = t[j + 1] + hv; if (t0 > c) t0 = c
                    }
                    t[j] = t0
                }
                out.data[y * w + x] = t0.toFloat() * scale
            }
        }
        return out
    }

    // ── numpy／LAPACK 相容的數值原語（格溝偵測的逐位元 parity 要用）──────────────────

    /**
     * `np.argsort(v)`（預設 kind='quicksort'）對 int32 的結果：numpy 1.26 在沒有 AVX-512 的機器上走
     * `aquicksort_`（introsort：三數取中、≤16 插入排序、深度超過 2·⌊log2 n⌋ 改 heapsort）。**不穩定**——
     * 同值的順序由這個演算法決定，格溝取峰的截斷（前 `SeparatorParams.peakMax` 峰，預設 800）與後續「同長度保持輸入順序」都吃它。
     */
    fun npArgsort(v: IntArray): IntArray {
        val num = v.size
        val ts = IntArray(num) { it }
        if (num <= 1) return ts
        val small = 15
        var pl = 0
        var pr = num - 1
        val stack = IntArray(256)
        var sp = 0
        val depth = IntArray(128)
        var dp = 0
        var cdepth = 0
        run { var u = num; while (u > 1) { u = u shr 1; cdepth++ }; cdepth *= 2 }
        fun swap(a: Int, b: Int) { val t = ts[a]; ts[a] = ts[b]; ts[b] = t }
        while (true) {
            if (cdepth < 0) {
                aheapsort(v, ts, pl, pr - pl + 1)
            } else {
                while (pr - pl > small) {
                    val pm = pl + ((pr - pl) shr 1)
                    if (v[ts[pm]] < v[ts[pl]]) swap(pm, pl)
                    if (v[ts[pr]] < v[ts[pm]]) swap(pr, pm)
                    if (v[ts[pm]] < v[ts[pl]]) swap(pm, pl)
                    val vp = v[ts[pm]]
                    var pi = pl
                    var pj = pr - 1
                    swap(pm, pj)
                    while (true) {
                        do { ++pi } while (v[ts[pi]] < vp)
                        do { --pj } while (vp < v[ts[pj]])
                        if (pi >= pj) break
                        swap(pi, pj)
                    }
                    val pk = pr - 1
                    swap(pi, pk)
                    if (pi - pl < pr - pi) {
                        stack[sp++] = pi + 1; stack[sp++] = pr
                        pr = pi - 1
                    } else {
                        stack[sp++] = pl; stack[sp++] = pi - 1
                        pl = pi + 1
                    }
                    depth[dp++] = --cdepth
                }
                // 插入排序
                for (pi in pl + 1..pr) {
                    val vi = ts[pi]
                    val vpv = v[vi]
                    var pj = pi
                    var pk = pi - 1
                    while (pj > pl && vpv < v[ts[pk]]) { ts[pj--] = ts[pk--] }
                    ts[pj] = vi
                }
            }
            if (sp == 0) break
            pr = stack[--sp]
            pl = stack[--sp]
            cdepth = depth[--dp]
        }
        return ts
    }

    /** numpy `aheapsort_`（1-based 索引），給 [npArgsort] 的深度保護。 */
    private fun aheapsort(v: IntArray, ts: IntArray, off: Int, n0: Int) {
        val base = off - 1
        var n = n0
        var l = n shr 1
        while (l > 0) {
            val tmp = ts[base + l]
            var i = l
            var j = l shl 1
            while (j <= n) {
                if (j < n && v[ts[base + j]] < v[ts[base + j + 1]]) j += 1
                if (v[tmp] < v[ts[base + j]]) { ts[base + i] = ts[base + j]; i = j; j += j } else break
            }
            ts[base + i] = tmp
            l--
        }
        while (n > 1) {
            val tmp = ts[base + n]
            ts[base + n] = ts[base + 1]
            n -= 1
            var i = 1
            var j = 2
            while (j <= n) {
                if (j < n && v[ts[base + j]] < v[ts[base + j + 1]]) j++
                if (v[tmp] < v[ts[base + j]]) { ts[base + i] = ts[base + j]; i = j; j += j } else break
            }
            ts[base + i] = tmp
        }
    }

    /**
     * `np.sum`／`np.mean` 對連續 float64 陣列的加總：numpy 的**成對加總**（n < 8 逐項；≤ 128 八路累加器再
     * `((r0+r1)+(r2+r3))+((r4+r5)+(r6+r7))`、餘數逐項；更長則對半遞迴，切點取 8 的倍數）。
     */
    fun npSum(a: DoubleArray, off: Int = 0, n: Int = a.size - off): Double {
        if (n < 8) {
            var s = 0.0
            for (i in 0 until n) s += a[off + i]
            return s
        }
        if (n <= 128) {
            val r = DoubleArray(8) { a[off + it] }
            var i = 8
            val lim = n - (n % 8)
            while (i < lim) {
                for (j in 0 until 8) r[j] += a[off + i + j]
                i += 8
            }
            var res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]))
            while (i < n) { res += a[off + i]; i++ }
            return res
        }
        var n2 = n / 2
        n2 -= n2 % 8
        return npSum(a, off, n2) + npSum(a, off + n2, n - n2)
    }

    /**
     * `np.linalg.eigh([[a, b], [b, c]])` 的特徵向量（LAPACK dsyevd → dstedc → dsteqr 的 2×2 路徑：dlaev2 ＋
     * 旋轉 ＋ 特徵值遞增排序），逐位元重現。回傳 [w0, w1, v00, v10, v01, v11]（w 遞增；第 k 欄＝w_k 的向量）。
     */
    fun eigh2(a: Double, b: Double, c: Double): DoubleArray {
        val eps = Math.ulp(1.0) / 2              // dlamch('E')＝2⁻⁵³
        val safmin = java.lang.Double.MIN_NORMAL
        var e = b
        var w0: Double
        var w1: Double
        var z00 = 1.0; var z01 = 0.0; var z10 = 0.0; var z11 = 1.0
        if (e != 0.0 && abs(e) <= (sqrt(abs(a)) * sqrt(abs(c))) * eps) e = 0.0
        if (e == 0.0 || abs(e) * abs(e) <= (eps * eps * abs(a)) * abs(c) + safmin) {
            w0 = a; w1 = c
        } else {
            // dlaev2
            val sm = a + c
            val df = a - c
            val adf = abs(df)
            val tb = e + e
            val ab = abs(tb)
            val acmx: Double
            val acmn: Double
            if (abs(a) > abs(c)) { acmx = a; acmn = c } else { acmx = c; acmn = a }
            val rt = when {
                adf > ab -> adf * sqrt(1.0 + (ab / adf) * (ab / adf))
                adf < ab -> ab * sqrt(1.0 + (adf / ab) * (adf / ab))
                else -> ab * sqrt(2.0)
            }
            val rt1: Double
            val rt2: Double
            val sgn1: Int
            if (sm < 0.0) { rt1 = 0.5 * (sm - rt); sgn1 = -1; rt2 = (acmx / rt1) * acmn - (e / rt1) * e }
            else if (sm > 0.0) { rt1 = 0.5 * (sm + rt); sgn1 = 1; rt2 = (acmx / rt1) * acmn - (e / rt1) * e }
            else { rt1 = 0.5 * rt; rt2 = -0.5 * rt; sgn1 = 1 }
            val cs: Double
            val sgn2: Int
            if (df >= 0.0) { cs = df + rt; sgn2 = 1 } else { cs = df - rt; sgn2 = -1 }
            var cs1: Double
            var sn1: Double
            if (abs(cs) > ab) {
                val ct = -tb / cs
                sn1 = 1.0 / sqrt(1.0 + ct * ct)
                cs1 = ct * sn1
            } else if (ab == 0.0) {
                cs1 = 1.0; sn1 = 0.0
            } else {
                val tn = -cs / tb
                cs1 = 1.0 / sqrt(1.0 + tn * tn)
                sn1 = tn * cs1
            }
            if (sgn1 == sgn2) { val tn = cs1; cs1 = -sn1; sn1 = tn }
            w0 = rt1; w1 = rt2
            z00 = cs1; z01 = -sn1; z10 = sn1; z11 = cs1
        }
        if (w1 < w0) {
            val tw = w0; w0 = w1; w1 = tw
            var t = z00; z00 = z01; z01 = t
            t = z10; z10 = z11; z11 = t
        }
        return doubleArrayOf(w0, w1, z00, z10, z01, z11)
    }

    /**
     * (2nt+1)×(2nr+1) 最大值濾波，列（θ）方向**環狀**、欄（ρ）方向界外視為 0（值域 ≥ 0 ⇒ 等於只看界內）。
     * ＝ `scipy.ndimage.maximum_filter(s, size=(2nt+1, 2nr+1), mode=("wrap", "constant"))`。可分離：先欄後列。
     */
    fun maxFilterWrapRows(s: IntArray, rows: Int, cols: Int, nt: Int, nr: Int): IntArray {
        // 欄方向：van Herk／Gil–Werman（分塊前綴／後綴極大），兩側各補 nr 個 0 ⇒ 每元素常數次比較
        val k = 2 * nr + 1
        val pl = cols + 2 * nr
        val pad = IntArray(pl)
        val pre = IntArray(pl)
        val suf = IntArray(pl)
        val tmp = IntArray(rows * cols)
        for (r in 0 until rows) {
            val base = r * cols
            System.arraycopy(s, base, pad, nr, cols)
            var i = 0
            while (i < pl) {
                val e = min(i + k, pl)
                pre[i] = pad[i]
                for (j in i + 1 until e) pre[j] = max(pre[j - 1], pad[j])
                suf[e - 1] = pad[e - 1]
                for (j in e - 2 downTo i) suf[j] = max(suf[j + 1], pad[j])
                i = e
            }
            // 窗 [c, c+k−1]（補零後座標）＝ max(suf[c], pre[c+k−1])
            for (c in 0 until cols) tmp[base + c] = max(suf[c], pre[c + k - 1])
        }
        // 列方向（θ）：環狀、窗只有 2nt+1 列，直接取
        val out = IntArray(rows * cols)
        for (r in 0 until rows) {
            val base = r * cols
            System.arraycopy(tmp, base, out, base, cols)
            for (q in 1..nt) {
                val ub = (((r - q) % rows + rows) % rows) * cols
                val db = ((r + q) % rows) * cols
                for (c in 0 until cols) {
                    var v = tmp[ub + c]; if (v > out[base + c]) out[base + c] = v
                    v = tmp[db + c]; if (v > out[base + c]) out[base + c] = v
                }
            }
        }
        return out
    }

    // ── 背景物件規則的確定性原語（研究端 research/nightread_obj.py 照同一套整數寫法；逐位元相同）──────────

    /**
     * 整數高斯核（研究端 `gauss_w`）：核長＝round(8σ+1)|1（同 cv2 浮點版）；權重＝exp(−d²/2σ²) 逐項加總正規化、×65536 四捨五入
     * （半進位），中心補足使總和＝65536。
     */
    fun gaussW(sigma: Double): IntArray {
        val ks = Math.rint(sigma * 8 + 1).toInt() or 1
        val c = ks / 2
        val k = DoubleArray(ks) { val d = (it - c).toDouble(); exp(-(d * d) / (2.0 * sigma * sigma)) }
        var t = 0.0
        for (v in k) t += v
        val w = IntArray(ks) { floor(k[it] / t * 65536.0 + 0.5).toInt() }
        var sum = 0
        for (v in w) sum += v
        w[c] += 65536 - sum
        return w
    }

    /**
     * uint8 影像的整數高斯（研究端 `gauss_q16`）：[gaussW] 兩趟可分離卷積（BORDER_REFLECT_101；橫向 Int、直向 Long 累加），
     * 結果四捨五入到 Q16（灰階 ×65536）。整數運算與加總順序無關，與研究端的 cv2.sepFilter2D(float64) 逐位元相同。
     */
    fun gaussQ16(g: Gray, sigma: Double): Gray {
        val w = g.w
        val h = g.h
        val k = gaussW(sigma)
        val c = k.size / 2
        val src = g.data
        // 橫向那一趟只留直向核涵蓋的 2c+1 列（環形緩衝）：輸出列 y 要的來源列（REFLECT_101 之後）都落在 [y−c, y+c] 裡，
        // 以「列號 mod (2c+1)」放槽不會撞；整頁只配輸出那一張
        val nr = 2 * c + 1
        val rows = Array(nr) { IntArray(w) }
        val rowId = IntArray(nr) { -1 }
        val xi = IntArray(w + 2 * c) { reflect101(it - c, w) }
        val line = IntArray(w + 2 * c)
        fun hrow(r: Int): IntArray {
            val slot = r % nr
            val dst = rows[slot]
            if (rowId[slot] == r) return dst
            val base = r * w
            for (i in line.indices) line[i] = src[base + xi[i]]
            // 核在外、x 在內（整數加總與順序無關；內圈是同一個常數乘加，C2／ART 可以向量化）
            val kc = k[c]
            for (x in 0 until w) dst[x] = kc * line[x + c]
            for (j in 1..c) {
                val kj = k[c + j]
                val lo = c - j
                val hi = c + j
                for (x in 0 until w) dst[x] += kj * (line[x + lo] + line[x + hi])
            }
            rowId[slot] = r
            return dst
        }
        val out = IntArray(w * h)
        val acc = LongArray(w)
        for (y in 0 until h) {
            // 對稱核：上下對稱的兩列先相加（Int：兩個 ≤ 255·65536 的值）再乘一次，整數運算與順序無關
            val hc = hrow(y)
            val kc = k[c].toLong()
            for (x in 0 until w) acc[x] = kc * hc[x]
            for (j in 1..c) {
                val kj = k[c + j].toLong()
                if (kj == 0L) continue
                val ha = hrow(reflect101(y - j, h))
                val hb = hrow(reflect101(y + j, h))
                for (x in 0 until w) acc[x] += kj * (ha[x] + hb[x])
            }
            val ob = y * w
            for (x in 0 until w) out[ob + x] = ((acc[x] + 32768L) shr 16).toInt()
        }
        return Gray(w, h, out)
    }

    /** [gaussQ16Sparse] 的來源列：把頁面第 [r] 列在游程 [rs]／[re]（[s, e)，依序、不重疊，共 [n] 段）上的值寫進頁寬的 [dst]。 */
    internal fun interface RowSrc {
        fun fill(r: Int, rs: IntArray, re: IntArray, n: Int, dst: IntArray)
    }

    /** [gaussQ16Sparse] 的輸出列：頁面第 [y] 列的值在頁寬的 [v]，只在游程 [rs]／[re]（共 [n] 段）上有定義；回呼返回後緩衝會被重用。 */
    internal fun interface RowSink {
        fun row(y: Int, v: IntArray, rs: IntArray, re: IntArray, n: Int)
    }

    /** 灰階頁面當 [RowSrc]（逐段複製）。 */
    internal fun grayRows(g: Gray): RowSrc = RowSrc { r, rs, re, n, dst ->
        val base = r * g.w
        for (k in 0 until n) System.arraycopy(g.data, base + rs[k], dst, rs[k], re[k] - rs[k])
    }

    /**
     * 列打包位元（[packBits] 格式）的一列（從 [off] 起 [nw] 字）→ 游程 [s, e)，依序寫進 [rs]／[re]，回傳段數（跨字的段接起來）。
     * [rs]／[re] 至少要 (64·nw + 1) / 2 格。
     */
    internal fun bitRuns(bits: LongArray, off: Int, nw: Int, rs: IntArray, re: IntArray): Int {
        var cnt = 0
        for (i in 0 until nw) {
            var v = bits[off + i]
            val base = i shl 6
            while (v != 0L) {
                val t = java.lang.Long.numberOfTrailingZeros(v)
                val sh = v ushr t
                val len = if (sh == -1L) 64 - t else java.lang.Long.numberOfTrailingZeros(sh.inv())
                val st = base + t
                val en = st + len
                if (cnt > 0 && re[cnt - 1] == st) re[cnt - 1] = en else { rs[cnt] = st; re[cnt] = en; cnt++ }
                val u = t + len
                v = if (u >= 64) 0L else v and (-1L shl u)
            }
        }
        return cnt
    }

    /**
     * [gaussQ16] 只算 [need] 標的輸出像素（2026-10-07 加速）：同一套整數運算（先橫後直、REFLECT_101、橫向 Int、直向 Long、Q16 四捨
     * 五入），只是用不到的位置不算，有算的值與整頁版逐位元相同。[need]＝頁面第 [ny0] 列起 [nrows] 列的列打包位元（每列 (w+63)/64 字，
     * 頁寬）；[k]＝[gaussW] 的核。來源列由 [src] 只填要的欄；每個有 need 的輸出列（y 遞增）交給 [sink]。
     *
     * 要的範圍：橫向那一趟只在「need 直向外擴核半徑 c」的列、那一列 need 的欄算；來源只要再橫向外擴 c 的欄。反射到頁內的列／欄
     * （頁緣 REFLECT_101）離原位不超過 c，一定落在外擴的範圍裡，所以夾在頁內的外擴就夠。橫向結果留 2c+1 列的環形緩衝（同 [gaussQ16]）。
     */
    internal fun gaussQ16Sparse(w: Int, h: Int, k: IntArray, need: LongArray, ny0: Int, nrows: Int, src: RowSrc, sink: RowSink) {
        if (nrows <= 0 || w <= 0) return
        val c = k.size / 2
        val nw = (w + 63) ushr 6
        // 橫向要算的位置＝need 直向外擴 c（夾在頁內）
        val hy0 = max(0, ny0 - c)
        val hy1 = min(h, ny0 + nrows + c)
        val hn = LongArray(nw * (hy1 - hy0))
        var any = false
        for (yi in 0 until nrows) {
            val o = yi * nw
            var nz = false
            for (i in 0 until nw) if (need[o + i] != 0L) { nz = true; break }
            if (!nz) continue
            any = true
            val y = ny0 + yi
            for (r in max(hy0, y - c)..min(hy1 - 1, y + c)) {
                val ro = (r - hy0) * nw
                for (i in 0 until nw) hn[ro + i] = hn[ro + i] or need[o + i]
            }
        }
        if (!any) return
        val cap = (64 * nw + 1) / 2 + 1
        val rs = IntArray(cap)
        val re = IntArray(cap)
        val hs = IntArray(cap)
        val he = IntArray(cap)
        val ss = IntArray(cap)
        val se = IntArray(cap)
        val nr = 2 * c + 1
        val rows = arrayOfNulls<IntArray>(nr)
        val rowId = IntArray(nr) { -1 }
        val srcRow = IntArray(w)
        val line = IntArray(w + 2 * c)
        val kc = k[c]
        fun hrow(r: Int): IntArray {
            val slot = r % nr
            val dst = rows[slot] ?: IntArray(w).also { rows[slot] = it }
            if (rowId[slot] == r) return dst
            val n = bitRuns(hn, (r - hy0) * nw, nw, hs, he)
            // 來源要的欄：每段外擴 c（夾在頁內），接起來
            var m = 0
            for (q in 0 until n) {
                val a = max(0, hs[q] - c)
                val b = min(w, he[q] + c)
                if (m > 0 && a <= se[m - 1]) { if (b > se[m - 1]) se[m - 1] = b } else { ss[m] = a; se[m] = b; m++ }
            }
            src.fill(r, ss, se, m, srcRow)
            for (q in 0 until n) {
                val s0 = hs[q]
                val len = he[q] - s0
                // line[i]＝來源第 reflect101(s0 − c + i) 欄（同 [gaussQ16] 的 line，只取這一段要的）
                val ll = len + 2 * c
                val a = s0 - c
                var i = 0
                while (i < ll && a + i < 0) { line[i] = srcRow[reflect101(a + i, w)]; i++ }
                val mid = min(ll, w - a)
                if (mid > i) { System.arraycopy(srcRow, a + i, line, i, mid - i); i = mid }
                while (i < ll) { line[i] = srcRow[reflect101(a + i, w)]; i++ }
                // 核在外、x 在內（同 [gaussQ16]）
                for (x in 0 until len) dst[s0 + x] = kc * line[x + c]
                for (j in 1..c) {
                    val kj = k[c + j]
                    val lo = c - j
                    val hi = c + j
                    for (x in 0 until len) dst[s0 + x] += kj * (line[x + lo] + line[x + hi])
                }
            }
            rowId[slot] = r
            return dst
        }
        val acc = LongArray(w)
        val out = IntArray(w)
        for (yi in 0 until nrows) {
            val y = ny0 + yi
            val n = bitRuns(need, yi * nw, nw, rs, re)
            if (n == 0) continue
            val hc = hrow(y)
            val kcl = kc.toLong()
            for (q in 0 until n) for (x in rs[q] until re[q]) acc[x] = kcl * hc[x]
            for (j in 1..c) {
                val kj = k[c + j].toLong()
                if (kj == 0L) continue
                val ha = hrow(reflect101(y - j, h))
                val hb = hrow(reflect101(y + j, h))
                for (q in 0 until n) for (x in rs[q] until re[q]) acc[x] += kj * (ha[x] + hb[x])
            }
            for (q in 0 until n) for (x in rs[q] until re[q]) out[x] = ((acc[x] + 32768L) shr 16).toInt()
            sink.row(y, out, rs, re, n)
        }
    }

    /**
     * [medianBlur8] 當 [gaussQ16Sparse] 的來源：只算要的欄（每段從段首的窗起算、再沿段滑動；同一個順序統計量，逐值相同）。
     */
    internal class MedianRows(private val g: Gray, private val ksize: Int) : RowSrc {
        private val fine = IntArray(256)
        private val cols = IntArray(ksize)

        override fun fill(r: Int, rs: IntArray, re: IntArray, n: Int, dst: IntArray) {
            val w = g.w
            val h = g.h
            val rr = ksize / 2
            val need = ksize * ksize / 2 + 1
            val src = g.data
            for (dy in -rr..rr) cols[dy + rr] = (r + dy).coerceIn(0, h - 1) * w
            for (q in 0 until n) {
                val s0 = rs[q]
                val e0 = re[q]
                java.util.Arrays.fill(fine, 0)
                for (dx in -rr..rr) {
                    val xx = (s0 + dx).coerceIn(0, w - 1)
                    for (cb in cols) fine[src[cb + xx]]++
                }
                var m = 0
                var lt = 0
                while (lt + fine[m] < need) { lt += fine[m]; m++ }
                for (x in s0 until e0) {
                    dst[x] = m
                    if (x + 1 < e0) {
                        val xo = if (x - rr < 0) 0 else x - rr
                        val xn = if (x + 1 + rr > w - 1) w - 1 else x + 1 + rr
                        for (cb in cols) {
                            val a = src[cb + xo]
                            fine[a]--
                            if (a < m) lt--
                            val b = src[cb + xn]
                            fine[b]++
                            if (b < m) lt++
                        }
                        while (lt >= need) { m--; lt -= fine[m] }
                        while (lt + fine[m] < need) { lt += fine[m]; m++ }
                    }
                }
            }
        }
    }

    /**
     * `cv2.medianBlur(u8, ksize)`（ksize 奇數、uint8；BORDER_REPLICATE）：窗內 ksize² 個值排序後第 ksize²/2 個（0 起算）。
     * 逐列滑動直方圖；中位數**跟著窗走**（2026-10-06）：記著目前的中位數 m 與窗內 < m 的個數，窗移一格只調這個計數、再把 m
     * 往上或往下挪到「< m 的不到 need、≤ m 的夠 need」為止——同一個順序統計量，逐值相同，不必每格從 0 掃直方圖。
     */
    fun medianBlur8(g: Gray, ksize: Int): Gray {
        val w = g.w
        val h = g.h
        val r = ksize / 2
        val need = ksize * ksize / 2 + 1          // 第 need 小（1 起算）
        val src = g.data
        val out = IntArray(w * h)
        val fine = IntArray(256)
        val cols = IntArray(ksize)
        for (y in 0 until h) {
            java.util.Arrays.fill(fine, 0)
            for (dy in -r..r) cols[dy + r] = (y + dy).coerceIn(0, h - 1) * w
            // x = 0 的窗：列 −r..r（複製邊）
            for (dx in -r..r) {
                val xx = dx.coerceIn(0, w - 1)
                for (cb in cols) fine[src[cb + xx]]++
            }
            var m = 0
            var lt = 0                              // 窗內 < m 的個數
            while (lt + fine[m] < need) { lt += fine[m]; m++ }
            val ob = y * w
            for (x in 0 until w) {
                out[ob + x] = m
                if (x + 1 < w) {
                    val xo = if (x - r < 0) 0 else x - r
                    val xn = if (x + 1 + r > w - 1) w - 1 else x + 1 + r
                    for (cb in cols) {
                        val a = src[cb + xo]
                        fine[a]--
                        if (a < m) lt--
                        val b = src[cb + xn]
                        fine[b]++
                        if (b < m) lt++
                    }
                    while (lt >= need) { m--; lt -= fine[m] }
                    while (lt + fine[m] < need) { lt += fine[m]; m++ }
                }
            }
        }
        return Gray(w, h, out)
    }

    /**
     * `cv2.Canny(u8, lo, hi)`（aperture 3、L1 梯度；OpenCV 4.11 canny.cpp 的非 IPP 路徑）：BORDER_REPLICATE 的 3×3 Sobel、
     * 梯度＝|dx|＋|dy|；非極大抑制用定點正切（TG22＝13573、CANNY_SHIFT 15；影像外的梯度當 0）：近水平比左右（左 >、右 ≥）、
     * 近垂直比上下（上 >、下 ≥）、斜向比對角（兩邊都 >）；> lo 的極大值是候選、> hi 的是強邊；結果＝與強邊 8 連通的候選。
     * 輸入＝[src] 右移 [shift] 位的整數（背景物件規則餵 Q16 模糊的整數部分，不另配 uint8 影像）。梯度只留三列環形緩衝，
     * 整頁只配 1 B/px 的狀態與按需長大的堆疊。
     */
    fun canny(src: IntArray, w: Int, h: Int, shift: Int, lo: Int, hi: Int): Mask {
        val dx = Array(3) { IntArray(w) }
        val dy = Array(3) { IntArray(w) }
        val mag = Array(3) { IntArray(w + 2) }      // [x + 1]；兩側各一格 0
        fun row(y: Int, slot: Int) {
            val ym = max(0, y - 1) * w
            val y0 = y * w
            val yp = min(h - 1, y + 1) * w
            val ddx = dx[slot]; val ddy = dy[slot]; val mm = mag[slot]
            for (x in 0 until w) {
                val xm = max(0, x - 1)
                val xp = min(w - 1, x + 1)
                val a = src[ym + xm] shr shift; val b = src[ym + x] shr shift; val c = src[ym + xp] shr shift
                val d = src[y0 + xm] shr shift; val f = src[y0 + xp] shr shift
                val e = src[yp + xm] shr shift; val gg = src[yp + x] shr shift; val k = src[yp + xp] shr shift
                val gx = (c + 2 * f + k) - (a + 2 * d + e)
                val gy = (e + 2 * gg + k) - (a + 2 * b + c)
                ddx[x] = gx; ddy[x] = gy
                mm[x + 1] = abs(gx) + abs(gy)
            }
        }
        val zero = IntArray(w + 2)
        // 0＝不是邊、1＝候選、2＝強邊
        val st = ByteArray(w * h)
        var stack = IntArray(1024)
        var sp = 0
        if (h > 0) row(0, 0)
        for (y in 0 until h) {
            val cur = y % 3
            if (y + 1 < h) row(y + 1, (y + 1) % 3)
            val mp = if (y > 0) mag[(y + 2) % 3] else zero
            val mn = if (y + 1 < h) mag[(y + 1) % 3] else zero
            val ma = mag[cur]
            val ddx = dx[cur]; val ddy = dy[cur]
            for (x in 0 until w) {
                val mv = ma[x + 1]
                if (mv <= lo) continue
                val xs = ddx[x]
                val ys = ddy[x]
                val ax = abs(xs)
                val ay = abs(ys) shl 15
                val tg22x = ax * 13573
                val isMax = if (ay < tg22x) {
                    mv > ma[x] && mv >= ma[x + 2]
                } else {
                    val tg67x = tg22x + (ax shl 16)
                    if (ay > tg67x) {
                        mv > mp[x + 1] && mv >= mn[x + 1]
                    } else {
                        val sg = if ((xs xor ys) < 0) -1 else 1
                        mv > mp[x + 1 - sg] && mv > mn[x + 1 + sg]
                    }
                }
                if (!isMax) continue
                val i = y * w + x
                if (mv > hi) {
                    st[i] = 2
                    if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                    stack[sp++] = i
                } else st[i] = 1
            }
        }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            val y = i / w
            for (yy in max(0, y - 1)..min(h - 1, y + 1)) {
                for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val j = yy * w + xx
                    if (st[j].toInt() == 1) {
                        st[j] = 2
                        if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[sp++] = j
                    }
                }
            }
        }
        return Mask(w, h, BooleanArray(w * h) { st[it].toInt() == 2 })
    }

    /**
     * [distanceChamfer]（5×5、非 IPP）的整數版：回傳**外圍補 2 px** 的定點距離表（×65536；寬 w+4、第 (y+2)·(w+4)+(x+2) 格是像素
     * (x, y)；前景到最近背景、背景＝0），與 Long 版逐點相同（上限飽和在 Int 範圍內，頁面上的距離遠不到），省掉 8 B/px 的 Long
     * 暫存與輸出那一張。`(t × 2⁻¹⁶ 的 float) > thr` 對 t < 2²⁴ 等價於 `t > thr × 65536`（背景物件規則的門檻 24 px）。
     */
    fun chamfer5Padded(m: Mask): IntArray {
        val w = m.w
        val h = m.h
        val border = 2
        val hv = Math.rint(1.0 * 65536).toInt()
        val diag = Math.rint(1.4f.toDouble() * 65536).toInt()
        val lng = Math.rint(2.1969f.toDouble() * 65536).toInt()
        val inf = Int.MAX_VALUE / 2
        val sw = w + 2 * border
        val sh = h + 2 * border
        val t = IntArray(sw * sh)
        for (bi in 0 until border) for (x in 0 until sw) { t[bi * sw + x] = inf; t[(sh - 1 - bi) * sw + x] = inf }
        for (y in 0 until h) {
            val row = (y + border) * sw + border
            for (bj in 0 until border) { t[row - bj - 1] = inf; t[row + w + bj] = inf }
            val src = y * w
            for (x in 0 until w) {
                val j = row + x
                if (!m.data[src + x]) { t[j] = 0; continue }
                var t0 = t[j - sw * 2 - 1] + lng
                var c = t[j - sw * 2 + 1] + lng; if (t0 > c) t0 = c
                c = t[j - sw - 2] + lng; if (t0 > c) t0 = c
                c = t[j - sw - 1] + diag; if (t0 > c) t0 = c
                c = t[j - sw] + hv; if (t0 > c) t0 = c
                c = t[j - sw + 1] + diag; if (t0 > c) t0 = c
                c = t[j - sw + 2] + lng; if (t0 > c) t0 = c
                c = t[j - 1] + hv; if (t0 > c) t0 = c
                t[j] = if (t0 > inf) inf else t0
            }
        }
        for (y in h - 1 downTo 0) {
            val row = (y + border) * sw + border
            for (x in w - 1 downTo 0) {
                val j = row + x
                var t0 = t[j]
                if (t0 > hv) {
                    var c = t[j + sw * 2 + 1] + lng; if (t0 > c) t0 = c
                    c = t[j + sw * 2 - 1] + lng; if (t0 > c) t0 = c
                    c = t[j + sw + 2] + lng; if (t0 > c) t0 = c
                    c = t[j + sw + 1] + diag; if (t0 > c) t0 = c
                    c = t[j + sw] + hv; if (t0 > c) t0 = c
                    c = t[j + sw - 1] + diag; if (t0 > c) t0 = c
                    c = t[j + sw - 2] + lng; if (t0 > c) t0 = c
                    c = t[j + 1] + hv; if (t0 > c) t0 = c
                    t[j] = t0
                }
            }
        }
        return t
    }

    /**
     * 灰階形態學（不限 8 位元，外側不影響極值）＝[morphGray]（[dilateGrayI]／[erodeGrayI]）逐位元相同的另一種算法：核每列是一段
     * run；每條來源列（兩端補單位元）對每種 run **寬度**算「從 p 起算、寬度 W 的視窗極值」（窗起點陣列與 run 的偏移無關，偏移在
     * 取值時才加），環形快取核高那麼多列；輸出列逐像素取核高個值的極值。[morphGray] 是把每條來源列散佈到核高條輸出列（讀改寫
     * 11 次），這裡每個輸出像素只寫一次。
     *
     * 寬度由小到大用倍增求（2026-10-06；原本每種寬度各做一次 van Herk）：已有寬度 c 的窗極值 f_c，寬度 c + s（s ≤ c）的就是
     * max(f_c[p], f_c[p + s])——兩個窗的聯集剛好是大窗。橢圓 11 的四種寬度（1、7、9、11）一列只要五趟「兩兩取極值」。
     */
    fun morphGrayGather(g: Gray, k: Kernel, wantMax: Boolean): Gray {
        val w = g.w
        val h = g.h
        val id = if (wantMax) Int.MIN_VALUE else Int.MAX_VALUE
        // 不同的 run 寬度（由小到大）與每個核列的寬度索引、偏移
        val kh = k.h
        val winOf = IntArray(kh)
        val offOf = IntArray(kh)
        val widths = ArrayList<Int>()
        var pad = 0
        for (ky in 0 until kh) {
            val win = k.runEnd[ky] - k.runStart[ky]
            winOf[ky] = win
            if (win <= 0) continue
            val off = k.runStart[ky] - k.ax
            offOf[ky] = off
            if (win !in widths) widths.add(win)
            pad = max(pad, max(abs(off), abs(off + win - 1)))
        }
        widths.sort()
        val nwid = widths.size
        val wv = IntArray(nwid) { widths[it] }
        val widIdx = IntArray(kh) { if (winOf[it] > 0) wv.indexOf(winOf[it]) else -1 }
        val pl = w + 2 * pad
        // 環形快取：每槽每種寬度一條「窗起點極值」陣列（長 pl；起點 p 的窗要整個在 [0, pl) 才有定義，取值只用得到那些）
        val cache = Array(kh) { Array(nwid) { IntArray(pl) } }
        val cacheRow = IntArray(kh) { -1 }
        val tmp = IntArray(pl)
        fun rowExt(sy: Int): Array<IntArray> {
            val slot = sy % kh
            val dst = cache[slot]
            if (cacheRow[slot] == sy) return dst
            // 寬度 1＝補了單位元的來源列本身
            val base = tmp
            java.util.Arrays.fill(base, id)
            System.arraycopy(g.data, sy * w, base, pad, w)
            var cur = base
            var c = 1
            for (q in 0 until nwid) {
                val target = wv[q]
                val out = dst[q]
                if (c == target && cur === base) {
                    System.arraycopy(base, 0, out, 0, pl)
                    cur = out
                    continue
                }
                while (c < target) {
                    val st = min(target - c, c)
                    val lim = pl - st
                    if (wantMax) {
                        for (p in 0 until lim) { val a = cur[p]; val b = cur[p + st]; out[p] = if (a > b) a else b }
                    } else {
                        for (p in 0 until lim) { val a = cur[p]; val b = cur[p + st]; out[p] = if (a < b) a else b }
                    }
                    cur = out
                    c += st
                }
            }
            cacheRow[slot] = sy
            return dst
        }
        // 上下對稱的兩條核列（同寬、同偏移；橢圓、方核都是）合成一趟：兩列先取極值再併進輸出（極值與順序無關，逐值相同；
        // 2026-10-07：不內聯的 debug 版每像素少一次迴圈，C2 不變）
        val partner = IntArray(kh) { -1 }
        val skip = BooleanArray(kh)
        for (ky in 0 until kh) {
            val k2 = kh - 1 - ky
            if (k2 <= ky || widIdx[ky] < 0 || widIdx[k2] != widIdx[ky] || offOf[k2] != offOf[ky]) continue
            partner[ky] = k2
            skip[k2] = true
        }
        val out = IntArray(w * h)
        val cur = IntArray(w)
        for (y in 0 until h) {
            var first = true
            for (ky in 0 until kh) {
                if (skip[ky]) continue
                val r = widIdx[ky]
                if (r < 0) continue
                val o = pad + offOf[ky]
                val sy = y + (ky - k.ay)
                val in1 = sy in 0 until h
                val k2 = partner[ky]
                val sy2 = if (k2 >= 0) y + (k2 - k.ay) else -1
                val in2 = k2 >= 0 && sy2 in 0 until h
                if (in1 && in2) {
                    val a = rowExt(sy)[r]
                    val b = rowExt(sy2)[r]
                    if (wantMax) {
                        if (first) { for (x in 0 until w) { val u = a[x + o]; val v = b[x + o]; cur[x] = if (u > v) u else v } }
                        else { for (x in 0 until w) { val u = a[x + o]; val v = b[x + o]; val m = if (u > v) u else v; if (m > cur[x]) cur[x] = m } }
                    } else {
                        if (first) { for (x in 0 until w) { val u = a[x + o]; val v = b[x + o]; cur[x] = if (u < v) u else v } }
                        else { for (x in 0 until w) { val u = a[x + o]; val v = b[x + o]; val m = if (u < v) u else v; if (m < cur[x]) cur[x] = m } }
                    }
                    first = false
                } else if (in1 || in2) {
                    val src = rowExt(if (in1) sy else sy2)[r]
                    if (first) System.arraycopy(src, o, cur, 0, w)
                    else if (wantMax) { for (x in 0 until w) { val v = src[x + o]; if (v > cur[x]) cur[x] = v } }
                    else { for (x in 0 until w) { val v = src[x + o]; if (v < cur[x]) cur[x] = v } }
                    first = false
                }
            }
            if (first) java.util.Arrays.fill(cur, id)
            System.arraycopy(cur, 0, out, y * w, w)
        }
        return Gray(w, h, out)
    }

    /**
     * 二值侵蝕的位元打包快路：`erode(m, k)` ＝ ¬`dilate`(¬m, k)（同一組位移；影像外：膨脹當 0 ⇔ 侵蝕當 1，同 cv2）。
     * 取反直接在打包／解包時做（[packBitsNot]／[unpackBitsNot]），不配兩張整頁的取反遮罩。
     */
    fun erodePacked(m: Mask, k: Kernel): Mask = unpackBitsNot(dilateBits(packBitsNot(m), m.w, m.h, k), m.w, m.h)

    /** [close]（cv2 MORPH_CLOSE）的位元打包快路（中間結果留在打包格式，不解包再打包）。 */
    fun closePacked(m: Mask, k: Kernel): Mask =
        unpackBits(erodeBits(dilateBits(packBits(m), m.w, m.h, k), m.w, m.h, k), m.w, m.h)

    /** [open]（cv2 MORPH_OPEN）的位元打包快路（中間結果留在打包格式）。 */
    fun openPacked(m: Mask, k: Kernel): Mask =
        unpackBits(dilateBits(erodeBits(packBits(m), m.w, m.h, k), m.w, m.h, k), m.w, m.h)
}
