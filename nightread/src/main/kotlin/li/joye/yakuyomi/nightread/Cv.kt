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
 */
class Mask(val w: Int, val h: Int, val data: BooleanArray = BooleanArray(w * h)) {
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

class Gray(val w: Int, val h: Int, val data: IntArray = IntArray(w * h)) {
    operator fun get(x: Int, y: Int): Int = data[y * w + x]
    operator fun set(x: Int, y: Int, v: Int) { data[y * w + x] = v }
    fun copy(): Gray = Gray(w, h, data.copyOf())
    /** `g >= th` */
    fun ge(th: Int): Mask = Mask(w, h, BooleanArray(w * h) { data[it] >= th })
    /** `g < th` */
    fun lt(th: Int): Mask = Mask(w, h, BooleanArray(w * h) { data[it] < th })
    fun toF(): FImg = FImg(w, h, FloatArray(w * h) { data[it].toFloat() })
}

class FImg(val w: Int, val h: Int, val data: FloatArray = FloatArray(w * h)) {
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
class Kernel(val w: Int, val h: Int, val data: BooleanArray) {
    val ax: Int get() = w / 2
    val ay: Int get() = h / 2

    val runStart = IntArray(h)
    val runEnd = IntArray(h)

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
    val n: Int,
    val labels: IntArray,
    val left: IntArray,
    val top: IntArray,
    val width: IntArray,
    val height: IntArray,
    val area: IntArray,
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

    fun dilate(m: Mask, k: Kernel, iterations: Int = 1): Mask {
        val line = lineKernel(k)
        var cur = m
        repeat(iterations) {
            cur = when {
                line > 0 -> lineMorph(cur, line, horizontal = true, anchor = k.ax, dilate = true)
                line < 0 -> lineMorph(cur, -line, horizontal = false, anchor = k.ay, dilate = true)
                else -> dilateOnce(cur, k)
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
                else -> erodeOnce(cur, k)
            }
        }
        return cur
    }

    fun open(m: Mask, k: Kernel): Mask = dilate(erode(m, k), k)

    fun close(m: Mask, k: Kernel): Mask = erode(dilate(m, k), k)

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
                for (b in 0 until n) if (d[base + x0 + b]) v = v or (1L shl b)
                out[wb + i] = v
            }
        }
        return out
    }

    /** [packBits] 的反向。 */
    fun unpackBits(a: LongArray, w: Int, h: Int): Mask {
        val nw = (w + 63) ushr 6
        val out = Mask(w, h)
        for (y in 0 until h) {
            val base = y * w
            val wb = y * nw
            for (i in 0 until nw) {
                var v = a[wb + i]
                while (v != 0L) {
                    val x = (i shl 6) + java.lang.Long.numberOfTrailingZeros(v)
                    if (x < w) out.data[base + x] = true
                    v = v and (v - 1)
                }
            }
        }
        return out
    }

    /** [dilatePacked] 的本體：輸入輸出都是 [packBits] 的打包格式（呼叫端可以直接查位元、省掉解包）。 */
    fun dilateBits(src: LongArray, w: Int, h: Int, k: Kernel): LongArray {
        val nw = (w + 63) ushr 6
        val acc = LongArray(nw * h)
        val cache = HashMap<Long, LongArray>()
        for (ky in 0 until k.h) {
            val runS = k.runStart[ky]
            val runE = k.runEnd[ky]
            if (runS >= runE) continue
            val dy = ky - k.ay
            val offL = runS - k.ax
            val offR = runE - 1 - k.ax
            val hor = cache.getOrPut((offL.toLong() shl 32) or (offR.toLong() and 0xffffffffL)) {
                packedRangeOr(src, nw, h, w, offL, offR)
            }
            for (y in 0 until h) {
                val sy = y - dy
                if (sy < 0 || sy >= h) continue
                val o = y * nw
                val si = sy * nw
                for (i in 0 until nw) acc[o + i] = acc[o + i] or hor[si + i]
            }
        }
        return acc
    }

    /**
     * 打包列的水平區間 OR：out[x] = OR_{o=lo..hi} in[x + o]（範圍外視為 0）。
     *
     * ⚠️ 不能「先倍增出 [0, len) 再整體平移 lo」：lo < 0 時 out[0] 要讀倍增結果的第 −1 格，那一格（含 in[0..]）
     * 沒存在陣列裡 ⇒ 左緣少一圈。所以跨 0 的區間拆成往左（負移位倍增）與往右（正移位倍增）兩半；整段在 0 的
     * 同一側時才倍增後平移，平移方向讀到的界外格本來就是 0。
     */
    private fun packedRangeOr(src: LongArray, nw: Int, h: Int, w: Int, lo: Int, hi: Int): LongArray {
        if (lo >= 0) {                                   // 全在右側：a[x] = OR in[x .. x+len−1]，再往右讀 lo
            val a = packedDouble(src, nw, h, w, hi - lo + 1, 1)
            return if (lo == 0) a else shiftPacked(a, nw, h, w, lo)
        }
        if (hi <= 0) {                                   // 全在左側：a[x] = OR in[x−len+1 .. x]，再往左讀 −hi
            val a = packedDouble(src, nw, h, w, hi - lo + 1, -1)
            return if (hi == 0) a else shiftPacked(a, nw, h, w, hi)
        }
        val left = packedDouble(src, nw, h, w, 1 - lo, -1)
        val right = packedDouble(src, nw, h, w, hi + 1, 1)
        for (i in left.indices) left[i] = left[i] or right[i]
        return left
    }

    /**
     * 倍增：dir = 1 ⇒ out[x] = OR in[x .. x+n−1]；dir = −1 ⇒ out[x] = OR in[x−n+1 .. x]。
     * n = 1 時回傳複本（呼叫端會就地改寫）。
     */
    private fun packedDouble(src: LongArray, nw: Int, h: Int, w: Int, n: Int, dir: Int): LongArray {
        var a = src
        var span = 1
        while (span * 2 <= n) {
            a = orShifted(a, a, nw, h, w, dir * span)
            span *= 2
        }
        if (span < n) a = orShifted(a, a, nw, h, w, dir * (n - span))
        return if (a === src) src.copyOf() else a
    }

    /** x | shift(y, s)：shift 見 [shiftPacked]。 */
    private fun orShifted(x: LongArray, y: LongArray, nw: Int, h: Int, w: Int, s: Int): LongArray {
        val t = shiftPacked(y, nw, h, w, s)
        for (i in t.indices) t[i] = t[i] or x[i]
        return t
    }

    /**
     * 打包列平移：out[x] = in[x + s]（逐列、範圍外視為 0）。s < 0 會把位元推過寬度 w，末字的尾巴要清掉，
     * 否則下一趟往回移時會漏回影像內。
     */
    private fun shiftPacked(src: LongArray, nw: Int, h: Int, w: Int, s: Int): LongArray {
        val out = LongArray(src.size)
        val tail = if (w and 63 == 0) -1L else (1L shl (w and 63)) - 1
        if (s >= 0) {
            val q = s ushr 6
            val r = s and 63
            for (y in 0 until h) {
                val b = y * nw
                for (i in 0 until nw) {
                    val j = i + q
                    if (j >= nw) break
                    var v = src[b + j] ushr r
                    if (r != 0 && j + 1 < nw) v = v or (src[b + j + 1] shl (64 - r))
                    out[b + i] = v
                }
            }
        } else {
            val u = -s
            val q = u ushr 6
            val r = u and 63
            for (y in 0 until h) {
                val b = y * nw
                for (i in 0 until nw) {
                    val j = i - q
                    if (j < 0) continue
                    var v = src[b + j] shl r
                    if (r != 0 && j - 1 >= 0) v = v or (src[b + j - 1] ushr (64 - r))
                    out[b + i] = v
                }
                out[b + nw - 1] = out[b + nw - 1] and tail
            }
        }
        return out
    }

    /**
     * 列分解的膨脹。核的每一列是一段 run，所以該列的貢獻＝「原圖某一行的某個水平區間內有沒有
     * true」——用每行的 prefix count O(1) 查詢，總複雜度 O(W·H·kh)。
     *
     * 邊界：外側視為 0，所以區間裁切到影像內即可（前景不外溢）。
     */
    /**
     * 一維長條核（1×n 或 n×1）的快路：**最近目標像素距離**兩趟掃描，成本與核長無關。
     *
     * 二值遮罩的線膨脹＝「窗內有沒有 true」、線侵蝕＝「窗內有沒有 false」（影像外：膨脹視為 0 不貢獻、
     * 侵蝕視為前景不否決——都等於「只看影像內」）。所以只要知道每個位置往前、往後最近一個目標像素
     * 有多遠：正向一趟記「最近的目標在左／上多遠」、反向一趟記「在右／下多遠」，任一在窗內就中。
     *
     * 取代 van Herk 分段極值的原因：那版每個像素要兩次整數除法（分段索引）、每行還要搬一次緩衝；
     * 垂直方向更是逐欄跨行取值、快取全失。這版兩趟都是 row-major、垂直方向只帶一個寬度大小的
     * 狀態陣列。格框線偵測／格框線切割／線稿密度否決加起來十幾趟線掃描，全走這裡。
     */
    private fun lineMorph(m: Mask, len: Int, horizontal: Boolean, anchor: Int, dilate: Boolean): Mask {
        val w = m.w
        val h = m.h
        val out = Mask(w, h)
        val src = m.data
        val dst = out.data
        val a = anchor                 // 窗往左／上伸 a
        val b = len - 1 - anchor       // 窗往右／下伸 b
        val target = dilate            // 膨脹找 true，侵蝕找 false
        val far = Int.MIN_VALUE / 2
        if (horizontal) {
            for (y in 0 until h) {
                val base = y * w
                var prev = far
                for (x in 0 until w) {
                    if (src[base + x] == target) prev = x
                    dst[base + x] = x - prev <= a
                }
                var next = -far
                for (x in w - 1 downTo 0) {
                    if (src[base + x] == target) next = x
                    if (next - x <= b) dst[base + x] = true
                }
            }
        } else {
            val prev = IntArray(w) { far }
            for (y in 0 until h) {
                val base = y * w
                for (x in 0 until w) {
                    if (src[base + x] == target) prev[x] = y
                    dst[base + x] = y - prev[x] <= a
                }
            }
            val next = IntArray(w) { -far }
            for (y in h - 1 downTo 0) {
                val base = y * w
                for (x in 0 until w) {
                    if (src[base + x] == target) next[x] = y
                    if (next[x] - y <= b) dst[base + x] = true
                }
            }
        }
        // dst 現在＝「窗內有目標」。膨脹就是答案；侵蝕是「窗內有 false ⇒ 輸出 false」，取反。
        if (!dilate) for (i in dst.indices) dst[i] = !dst[i]
        return out
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

    private fun dilateOnce(m: Mask, k: Kernel): Mask {
        val w = m.w
        val h = m.h
        val out = Mask(w, h)
        val prefix = IntArray(w + 1)
        for (ky in 0 until k.h) {
            val runS = k.runStart[ky]
            val runE = k.runEnd[ky]
            if (runS >= runE) continue
            val dy = ky - k.ay
            // 輸出 (x,y) 取樣輸入 (x + [runS-ax .. runE-1-ax], y - dy)
            val offL = runS - k.ax
            val offR = runE - 1 - k.ax
            for (y in 0 until h) {
                val sy = y - dy
                if (sy < 0 || sy >= h) continue
                val base = sy * w
                prefix[0] = 0
                for (x in 0 until w) prefix[x + 1] = prefix[x] + if (m.data[base + x]) 1 else 0
                if (prefix[w] == 0) continue
                val obase = y * w
                for (x in 0 until w) {
                    if (out.data[obase + x]) continue
                    val a = max(0, x + offL)
                    val b = min(w - 1, x + offR)
                    if (a > b) continue
                    if (prefix[b + 1] - prefix[a] > 0) out.data[obase + x] = true
                }
            }
        }
        return out
    }

    /**
     * 列分解的腐蝕。初值全 true，逐核列否決；外側視為前景（cv2 的
     * BORDER_CONSTANT + morphologyDefaultBorderValue），所以落在影像外的區間不否決任何像素。
     */
    private fun erodeOnce(m: Mask, k: Kernel): Mask {
        val w = m.w
        val h = m.h
        val out = Mask(w, h, BooleanArray(w * h) { true })
        val prefix = IntArray(w + 1)
        for (ky in 0 until k.h) {
            val runS = k.runStart[ky]
            val runE = k.runEnd[ky]
            if (runS >= runE) continue
            val dy = ky - k.ay
            val offL = runS - k.ax
            val offR = runE - 1 - k.ax
            for (y in 0 until h) {
                val sy = y - dy
                val obase = y * w
                if (sy < 0 || sy >= h) continue     // 外側視為前景 ⇒ 不否決
                val base = sy * w
                prefix[0] = 0
                for (x in 0 until w) prefix[x + 1] = prefix[x] + if (m.data[base + x]) 1 else 0
                for (x in 0 until w) {
                    if (!out.data[obase + x]) continue
                    val a = max(0, x + offL)
                    val b = min(w - 1, x + offR)
                    if (a > b) continue              // 整段在影像外 ⇒ 視為前景
                    val need = b - a + 1
                    if (prefix[b + 1] - prefix[a] < need) out.data[obase + x] = false
                }
            }
        }
        return out
    }

    // ── 灰階形態學（ink_line_mask 的 7×7 ellipse blackhat）───────────────

    fun dilateGray(g: Gray, k: Kernel): Gray = morphGray(g, k, wantMax = true)

    fun erodeGray(g: Gray, k: Kernel): Gray = morphGray(g, k, wantMax = false)

    /** `cv2.morphologyEx(g, MORPH_BLACKHAT, k)` ＝ close(g) − g（灰階，結果 ≥ 0）。 */
    fun blackhat(g: Gray, k: Kernel): Gray {
        val closed = erodeGray(dilateGray(g, k), k)
        return Gray(g.w, g.h, IntArray(g.w * g.h) { max(0, closed.data[it] - g.data[it]) })
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
    private fun morphGray(g: Gray, k: Kernel, wantMax: Boolean): Gray {
        val w = g.w
        val h = g.h
        val out = Gray(w, h, IntArray(w * h) { if (wantMax) 0 else 255 })
        val row = IntArray(w)
        val widths = k.runStart.indices
            .filter { k.runEnd[it] > k.runStart[it] }
            .map { k.runEnd[it] - k.runStart[it] }
            .distinct()
        val cache = HashMap<Int, IntArray>(widths.size)
        for (win in widths) cache[win] = IntArray(w)

        // 以「來源列」為外圈：每條來源行只讀一次、每種寬度只掃一次，再散到所有用得到它的輸出列
        for (sy in 0 until h) {
            System.arraycopy(g.data, sy * w, row, 0, w)
            for (win in widths) slidingExtreme(row, w, win, wantMax, cache[win]!!)
            for (ky in 0 until k.h) {
                val runS = k.runStart[ky]
                val runE = k.runEnd[ky]
                if (runS >= runE) continue
                val y = sy - (ky - k.ay)
                if (y < 0 || y >= h) continue
                val win = runE - runS
                val slide = cache[win]!!
                val offL = runS - k.ax
                val obase = y * w
                for (x in 0 until w) {
                    // 視窗完全在界內才用滑動極值；部分越界逐項算——cv2 的邊界是「外側不影響極值」，
                    // 把視窗夾進有效範圍會取到不該取的值（blackhat 會立刻對不上）
                    val a0 = x + offL
                    val b0 = a0 + win - 1
                    val v = if (a0 >= 0 && b0 < w) {
                        slide[a0]
                    } else {
                        val lo = max(a0, 0)
                        val hi = min(b0, w - 1)
                        if (lo > hi) {
                            if (wantMax) 0 else 255
                        } else {
                            var acc = row[lo]
                            for (j in lo + 1..hi) acc = if (wantMax) max(acc, row[j]) else min(acc, row[j])
                            acc
                        }
                    }
                    val cur = out.data[obase + x]
                    out.data[obase + x] = if (wantMax) max(cur, v) else min(cur, v)
                }
            }
        }
        return out
    }

    /**
     * 一維滑動極值（van Herk / Gil-Werman 的分段前綴後綴法）：結果寫進 [dst] 的前 `n - win + 1` 項。
     */
    private fun slidingExtreme(a: IntArray, n: Int, win: Int, wantMax: Boolean, dst: IntArray) {
        if (win >= n) {
            var acc = a[0]
            for (i in 1 until n) acc = if (wantMax) max(acc, a[i]) else min(acc, a[i])
            dst[0] = acc
            return
        }
        val pre = IntArray(n)
        val suf = IntArray(n)
        var i = 0
        while (i < n) {
            val end = min(i + win, n)
            var acc = a[i]
            pre[i] = acc
            for (j in i + 1 until end) {
                acc = if (wantMax) max(acc, a[j]) else min(acc, a[j])
                pre[j] = acc
            }
            acc = a[end - 1]
            suf[end - 1] = acc
            for (j in end - 2 downTo i) {
                acc = if (wantMax) max(acc, a[j]) else min(acc, a[j])
                suf[j] = acc
            }
            i = end
        }
        for (x in 0..n - win) {
            val lo = x
            val hi = x + win - 1
            dst[x] = if (lo / win == hi / win) {
                var acc = a[lo]
                for (j in lo + 1..hi) acc = if (wantMax) max(acc, a[j]) else min(acc, a[j])
                acc
            } else {
                if (wantMax) max(suf[lo], pre[hi]) else min(suf[lo], pre[hi])
            }
        }
    }

    // ── 連通元件 ─────────────────────────────────────────────────────

    /**
     * `cv2.connectedComponentsWithStats(m, connectivity)`。
     *
     * 兩趟：先掃描序 union-find 配臨時標號，再按**首見掃描序**重新編號——cv2 的標號就是這個順序，
     * 下游有「元件 id 當 key」的邏輯（gutter_ids / panel_ids / sticker），順序不同會對不上。
     */
    fun ccStats(m: Mask, connectivity: Int = 8): CC {
        val w = m.w
        val h = m.h
        val labels = IntArray(w * h)
        val parent = IntArray(w * h / 2 + 2)
        var next = 1

        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[max(ra, rb)] = min(ra, rb)
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!m.data[i]) continue
                var best = 0
                // 已掃過的鄰居：左、上（4-連通）＋ 左上、右上（8-連通）
                if (x > 0 && labels[i - 1] != 0) best = labels[i - 1]
                if (y > 0 && labels[i - w] != 0) {
                    best = if (best == 0) labels[i - w] else { union(best, labels[i - w]); min(best, labels[i - w]) }
                }
                if (connectivity == 8) {
                    if (x > 0 && y > 0 && labels[i - w - 1] != 0) {
                        best = if (best == 0) labels[i - w - 1] else { union(best, labels[i - w - 1]); min(best, labels[i - w - 1]) }
                    }
                    if (x < w - 1 && y > 0 && labels[i - w + 1] != 0) {
                        best = if (best == 0) labels[i - w + 1] else { union(best, labels[i - w + 1]); min(best, labels[i - w + 1]) }
                    }
                }
                if (best == 0) {
                    if (next >= parent.size) throw IllegalStateException("標號溢位")
                    parent[next] = next
                    best = next
                    next++
                }
                labels[i] = best
            }
        }

        // 第二趟：壓平 + 按首見順序重編
        val remap = IntArray(next)
        var n = 1
        for (i in labels.indices) {
            if (labels[i] == 0) continue
            val root = find(labels[i])
            if (remap[root] == 0) { remap[root] = n; n++ }
            labels[i] = remap[root]
        }

        val left = IntArray(n) { Int.MAX_VALUE }
        val top = IntArray(n) { Int.MAX_VALUE }
        val right = IntArray(n) { Int.MIN_VALUE }
        val bottom = IntArray(n) { Int.MIN_VALUE }
        val area = IntArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val l = labels[y * w + x]
                area[l]++
                if (x < left[l]) left[l] = x
                if (y < top[l]) top[l] = y
                if (x > right[l]) right[l] = x
                if (y > bottom[l]) bottom[l] = y
            }
        }
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
        // 逐列：跨列存取無法連續，只能逐格（這趟是快取不友善的那一半）
        for (x in 0 until w) {
            var i = x
            for (y in 0 until h) { tmp[y] = f[i]; i += w }
            edt1d(tmp, h, d, v, z)
            i = x
            for (y in 0 until h) { f[i] = d[y]; i += w }
        }
        // 就地開平方，省一次 2.6 MPx 的配置（這個函式是合成階段的記憶體峰值所在：留白帶的格線距離、核心填色的直線距離）；
        // 用 Float 版 sqrt 不繞 Double
        for (i in f.indices) f[i] = kotlin.math.sqrt(f[i])
        return FImg(w, h, f)
    }

    /** [distanceSq] 裡「沒有背景像素」的值。 */
    const val DIST_SQ_INF: Int = Int.MAX_VALUE

    /**
     * 精確的**平方**歐氏距離：每個前景（true）像素到最近背景（false）像素的距離平方（整數）、背景＝0；整張沒有背景＝[DIST_SQ_INF]。
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
                out[base + x] = (dx * dx + fx[p] - p.toLong() * p).toInt()
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

    /**
     * 整數方窗和：`cv2.boxFilter(a, -1, (k, k), normalize=False, borderType=BORDER_REPLICATE)`（k 奇數、錨點置中；窗伸出影像的
     * 部分取最近的邊緣像素）。整數加總，與 cv2 的 float32 結果逐值相同（窗和遠小於 2²⁴）。先橫後縱、各一趟滑動窗。
     */
    fun boxSum(a: IntArray, w: Int, h: Int, k: Int): IntArray {
        val r = k / 2
        val mid = IntArray(w * h)
        for (y in 0 until h) {
            val base = y * w
            var s = 0
            for (d in -r..r) s += a[base + min(max(d, 0), w - 1)]
            for (x in 0 until w) {
                mid[base + x] = s
                s += a[base + min(x + r + 1, w - 1)] - a[base + max(x - r, 0)]
            }
        }
        val out = IntArray(w * h)
        val s = IntArray(w)
        for (d in -r..r) {
            val rb = min(max(d, 0), h - 1) * w
            for (x in 0 until w) s[x] += mid[rb + x]
        }
        for (y in 0 until h) {
            System.arraycopy(s, 0, out, y * w, w)
            val add = min(y + r + 1, h - 1) * w
            val sub = max(y - r, 0) * w
            for (x in 0 until w) s[x] += mid[add + x] - mid[sub + x]
        }
        return out
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
    private fun reflect101(i: Int, n: Int): Int {
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
        val w = f.w
        val h = f.h
        if (nw == w && nh == h) return f.copy()
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
                        acc += ww * f.data[y * w + x]
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
        val w = seed.w
        val h = seed.h
        var cur = (seed and within).data
        var next = BooleanArray(w * h)
        // 3×3 膨脹 + 與 within 取交集，就地做：原本每次迭代都走通用 dilate（走核的 run 分解、
        // 配置新陣列），十次迭代就是十次全頁配置。3×3 直接看八鄰居更短也不配置。
        //
        // ⚠️ within 的交集要照 Python 的節奏：它是 `dilate(cur, k, iterations=n) & within`，
        // 也就是**連做 n 次膨脹後才交集一次**。每次都交集會讓生長被 within 的細縫擋住，
        // 結果完全不同（實測 MAE 0.57→0.93、紅線 0.09→0.36%）。
        var done = 0
        while (done < iters) {
            val n = min(step, iters - done)
            var changed = false
            repeat(n) { sub ->
                val last = sub == n - 1
                for (y in 0 until h) {
                    val base = y * w
                    val up = base - w
                    val dn = base + w
                    for (x in 0 until w) {
                        val i = base + x
                        if (last && !within.data[i]) { next[i] = false; continue }
                        if (cur[i]) { next[i] = true; continue }
                        val l = x > 0
                        val r = x < w - 1
                        val hit = (l && cur[i - 1]) || (r && cur[i + 1]) ||
                            (y > 0 && (cur[up + x] || (l && cur[up + x - 1]) || (r && cur[up + x + 1]))) ||
                            (y < h - 1 && (cur[dn + x] || (l && cur[dn + x - 1]) || (r && cur[dn + x + 1])))
                        next[i] = hit
                        if (hit) changed = true
                    }
                }
                val t = cur; cur = next; next = t
            }
            done += n
            if (!changed) return Mask(w, h, cur)   // 長不動了就停
        }
        return Mask(w, h, cur)
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
}
