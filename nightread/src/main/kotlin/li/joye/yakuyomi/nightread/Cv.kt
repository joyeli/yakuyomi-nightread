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
        // 就地開平方，省一次 2.6 MPx 的配置；用 Float 版 sqrt 不繞 Double
        val out = FloatArray(w * h)
        for (i in out.indices) out[i] = kotlin.math.sqrt(f[i])
        return FImg(w, h, out)
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
     * 前景所有輪廓（含內外）的**總周長**。arcLength 是折線長（對角步 √2、直步 1）。
     *
     * 這裡用「邊界像素的 8-連通環繞長度」近似：對每個輪廓用 Moore 鄰域追蹤一圈、累加步長。
     * `sticker_metrics` 的 rough ＝ perim² / area，只當粗糙度排序用，容差比對即可。
     */
    fun totalContourLength(m: Mask): Double {
        val w = m.w
        val h = m.h
        val visited = BooleanArray(w * h)
        var total = 0.0
        val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

        fun isFg(x: Int, y: Int): Boolean =
            x in 0 until w && y in 0 until h && m.data[y * w + x]

        fun isBoundary(x: Int, y: Int): Boolean {
            if (!isFg(x, y)) return false
            for (d in 0 until 8 step 2) {
                if (!isFg(x + dx[d], y + dy[d])) return true
            }
            return false
        }

        for (sy in 0 until h) {
            for (sx in 0 until w) {
                if (!isBoundary(sx, sy) || visited[sy * w + sx]) continue
                // Moore 鄰域追蹤
                var cx = sx
                var cy = sy
                var dir = 6      // 從上方開始找
                var len = 0.0
                var steps = 0
                val startX = sx
                val startY = sy
                do {
                    visited[cy * w + cx] = true
                    var found = false
                    for (t in 0 until 8) {
                        val d = (dir + 6 + t) % 8       // 從「上一步方向的左後方」開始繞
                        val nx = cx + dx[d]
                        val ny = cy + dy[d]
                        if (isFg(nx, ny)) {
                            len += if (d % 2 == 0) 1.0 else 1.4142135623730951
                            cx = nx; cy = ny; dir = d; found = true
                            break
                        }
                    }
                    if (!found) break                    // 孤立像素
                    steps++
                } while ((cx != startX || cy != startY) && steps < w * h * 4)
                total += len
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
    fun geodesicGrow(seed: Mask, within: Mask, iters: Int, step: Int = 5): Mask {
        if (iters <= 0) return seed and within
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
}
