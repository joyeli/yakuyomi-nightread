package li.joye.yakuyomi.nightread

import kotlin.math.max
import kotlin.math.min

/**
 * 人物外那圈灰收細（折衷版；2026-10-03 使用者拍板，標準與更多都套）——研究端 `research/nightread_ring.py` 逐像素照搬。
 *
 * 病：背景塗黑停在「人物安全邊」外面，人物與黑之間留一圈 15–20 px 的灰（47 頁圈寬中位 16.8 px）。只在**真的有畫出來的
 * 輪廓線**把背景跟人物隔開的地方，讓已經塗黑的背景長到輪廓線；沒有輪廓線的地方維持現在的寬度（認領的像素保持人物還原）。
 *
 * - [claimable]：可認領像素 D 與種子（每檔一份，吃合成當下的狀態：背景已塗、人物還原遮罩、四種「只因人物安全邊而沒黑」）。
 * - [evidence]：頁面級的「可以收細」遮罩＝背景側 ∧ 空白紙（只跟灰階與人物原輸出有關，一頁算一次、各檔共用）。
 * - [grow]：從種子在 D ∧ 證據裡 4 連通長 ≤ [RingParams.steps] 步，再對黑（種子 ∪ 認領）開運算削掉細指頭、只留與種子相連的。
 *
 * 逐像素與研究端相同的前提（全部守在 RingParityTest）：深墨與淡筆觸是整數窗和；精確歐氏是 [Cv.distanceSq]（整數平方距離，
 * 研究端關掉 IPP 的 cv2 精確版＝它的 float32 √，逐值相同）；gv 的 5×5 chamfer 是 [Cv.distanceChamfer]（研究端關掉 IPP 的定點版）；
 * 外法向是 [normal] 的 float64 逐點計算，加總順序與研究端 `outward_normals` 一字不差（高斯核用 StrictMath.exp）。
 */
internal object Ring {

    /**
     * 合成途中收集、只因人物安全邊而沒黑的兩樣（貼紙層填；一檔一份），位元集合 1 bit/px（[set]／[has]）：貼紙層的核心填色是合成
     * 的記憶體峰值之一，那時多抱兩張整頁遮罩會墊高它。
     */
    class Collect(w: Int, h: Int) {
        /** 貼紙核心填色選中、只因離人物 coreReleasePad 內而沒填的（扣掉保護區）。 */
        val withheld = LongArray((w * h + 63) ushr 6)
        /** 貼紙實際畫上的前景描亮邊。 */
        val stkBand = LongArray((w * h + 63) ushr 6)
    }

    /** 位元集合（[packBits] 同格式：第 i 像素在第 i ushr 6 字的第 i and 63 位）。 */
    fun set(bits: LongArray, i: Int) { bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63)) }
    fun has(bits: LongArray?, i: Int): Boolean = bits != null && (bits[i ushr 6] ushr (i and 63)) and 1L != 0L

    /** 遮罩 → 位元集合。 */
    fun packBits(m: Mask): LongArray {
        val d = m.data
        val bits = LongArray((d.size + 63) ushr 6)
        for (i in d.indices) if (d[i]) bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63))
        return bits
    }

    /** [claimable] 的結果。 */
    class Claimable(val d: Mask, val seed: Mask, val any: Boolean)

    /**
     * [claimable] 裡只跟頁面有關的兩張遮罩（各檔共用；呼叫端決定要不要快取）：[rawClosed]＝人物原輸出閉運算（[rawClosedOf]），
     * [near]＝人物遮罩（收邊後）外擴 rOut（[nearOf]，只有出現「只因人物的墨被否決的留白」時才要）。
     */
    class PageParts(val rawClosed: () -> Mask, val near: () -> Mask)

    /** 人物原輸出的閉運算（橢圓半徑 rawClose）。 */
    fun rawClosedOf(charRaw: Mask, rp: RingParams): Mask {
        val k = Cv.ellipse(2 * rp.rawClose + 1)
        return erodeSym(Cv.dilatePacked(charRaw, k), k)
    }

    /** 人物遮罩（收邊後）外擴 rOut（橢圓）。 */
    fun nearOf(charMask: Mask, rp: RingParams): Mask = Cv.dilatePacked(charMask, Cv.ellipse(2 * rp.rOut + 1))

    /**
     * 可認領像素 D（研究端 `claimable`；[withheld]／[restPre]／[gvWh]／[stkBand] 是位元集合，見 [has]）：紙白、不在人物原輸出
     * [charRaw]、不是泡 [bub]（泡、偽泡、被人物扣掉的泡），而且屬於
     * 背景塗了又被還原的（[bgPaint] ∧ [restore]）、貼紙核心填色只因安全邊沒填的與泡外圈讓開人物之前的（[withheld] ∪ [restPre]，
     * 限「還原區或沒被任何一層塗過」）、貼紙描亮邊落在還原區的（[stkBand] ∧ [restore]），或只因人物自己的墨被線稿否決的留白
     * （[gvWh]，同上限制、人物遮罩外擴 rOut 內、到種子 ＋ 到人物原輸出的 5×5 chamfer ≤ gvGap）；再扣人物原輸出閉運算多出來的窄凹口。
     * 種子＝[bgPaint] ∧ ¬[restore] 的 8 連通塊、面積 ≥ seedMin（8 連通：見 [fillSmallHoles]）。「沒被塗過」＝ [out] 與場景調
     * [scene] 同值（float 逐位元比，兩者從同一份 scene 複製出來）。
     */
    fun claimable(
        g: Gray, out: FImg, scene: FImg, restore: Mask, charRaw: Mask, charMask: Mask, bub: Mask,
        bgPaint: Mask, withheld: LongArray?, restPre: LongArray?, gvWh: LongArray?, stkBand: LongArray?, p: NightReadParams,
        parts: PageParts = PageParts({ rawClosedOf(charRaw, p.ring) }, { nearOf(charMask, p.ring) }),
    ): Claimable {
        val rp = p.ring
        val w = g.w
        val h = g.h
        val n = w * h
        val black = Mask(w, h)
        for (i in 0 until n) black.data[i] = bgPaint.data[i] && !restore.data[i]
        val seed = seeds(black, rp.seedMin)
        val d = Mask(w, h)
        var gv: Mask? = null
        var anyD = false
        for (i in 0 until n) {
            if (g.data[i] < p.whiteTh || charRaw.data[i] || bub.data[i]) continue       // ok＝紙白、不在原輸出、不是泡
            val r = restore.data[i]
            val ru = r || out.data[i] == scene.data[i]                                 // 還原區或沒被任何一層塗過
            val wh = has(withheld, i) || has(restPre, i)
            if ((bgPaint.data[i] && r) || (wh && ru) || (has(stkBand, i) && r)) { d.data[i] = true; anyD = true }
            if (ru && has(gvWh, i)) { (gv ?: Mask(w, h).also { gv = it }).data[i] = true }
        }
        val gvm = gv
        if (gvm != null) {
            val near = parts.near()
            var any = false
            for (i in 0 until n) { if (gvm.data[i] && !near.data[i]) gvm.data[i] = false; if (gvm.data[i]) any = true }
            if (any && gvGapPass(gvm, seed, charRaw, rp, d)) anyD = true
        }
        if (rp.rawClose > 0 && anyD) {
            val closed = parts.rawClosed()
            anyD = false
            for (i in 0 until n) { if (closed.data[i]) d.data[i] = false; if (d.data[i]) anyD = true }
        }
        return Claimable(d, seed, anyD)
    }

    /**
     * gv 的間隙條件：[gv] 像素裡「到種子 ＋ 到人物原輸出（5×5 chamfer）≤ gvGap」的併進 [d]（回傳有沒有併進任何像素）。
     *
     * 只在 gv 的外框外擴 gvGap＋2 的窗裡算兩張 chamfer：5×5 chamfer 的每一步至少是歐氏長度的 0.98 倍，距離 ≤ gvGap 的來源一定在
     * 歐氏 gvGap/0.98 內（窗內）；兩趟掃描算的是「單調路徑」的最短加權長，最短路徑留在來源與 p 的外框裡（窗內），所以窗內算出的值
     * 與整頁逐位元相同；超過 gvGap 的值窗內只會更大，門檻判定不變。整頁算要 2 張 12 B/px 的暫存，這裡只有窗大小。
     */
    private fun gvGapPass(gv: Mask, seed: Mask, charRaw: Mask, rp: RingParams, d: Mask): Boolean {
        val w = gv.w
        val h = gv.h
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                if (!gv.data[base + x]) continue
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (x1 < 0) return false
        val pad = rp.gvGap + 2
        val cx0 = max(0, x0 - pad)
        val cy0 = max(0, y0 - pad)
        val cw = min(w, x1 + pad + 1) - cx0
        val ch = min(h, y1 + pad + 1) - cy0
        fun notCrop(m: Mask): Mask {
            val o = Mask(cw, ch)
            for (y in 0 until ch) {
                val src = (cy0 + y) * w + cx0
                val dst = y * cw
                for (x in 0 until cw) o.data[dst + x] = !m.data[src + x]
            }
            return o
        }
        val db = Cv.distanceChamfer(notCrop(seed), 5)
        val dr = Cv.distanceChamfer(notCrop(charRaw), 5)
        val gap = rp.gvGap.toFloat()
        var any = false
        for (y in 0 until ch) {
            val src = (cy0 + y) * w + cx0
            val dst = y * cw
            for (x in 0 until cw) {
                if (gv.data[src + x] && db.data[dst + x] + dr.data[dst + x] <= gap) { d.data[src + x] = true; any = true }
            }
        }
        return any
    }

    /** [black] 的 8 連通塊裡面積 ≥ [minArea] 的。 */
    private fun seeds(black: Mask, minArea: Int): Mask {
        val cc = Cv.ccStats(black, 8)
        val big = BooleanArray(cc.n)
        for (l in 1 until cc.n) big[l] = cc.area[l] >= minArea
        val out = Mask(black.w, black.h)
        for (i in out.data.indices) out.data[i] = big[cc.labels[i]]
        return out
    }

    /**
     * 對稱核（橢圓、十字）的腐蝕，影像外當前景（同 cv2）：erode(m, k) ＝ ¬dilate(¬m, k)，¬m 在影像外是 0 ⇒ m 在影像外是 1。
     * 走 [Cv.dilatePacked]（位元打包），與 [Cv.erode] 逐像素相同、快一個數量級。
     */
    private fun erodeSym(m: Mask, k: Kernel): Mask {
        val d = Cv.dilatePacked(m.not(), k)
        for (i in d.data.indices) d.data[i] = !d.data[i]
        return d
    }

    /**
     * 頁面級的「可以收細」遮罩（研究端 `evidence`）＝背景側 ∧ 空白紙。只跟 [g]（紙白正規化後）與人物原輸出 [charRaw] 有關。
     * [dbg] 給 map 就存 `ring_conf`（有輪廓的邊界點）、`ring_side`（背景側）、`ring_paper`（空白紙），與各段耗時 `t_paper`／`t_holes`／
     * `t_band`／`t_conf`／`t_ray`／`t_cont`／`t_side`（ms）。
     */
    fun evidence(g: Gray, charRaw: Mask, p: NightReadParams, dbg: MutableMap<String, Any>?): Mask {
        val rp = p.ring
        val w = g.w
        val h = g.h
        val n = w * h
        var tm = System.nanoTime()
        fun lap(k: String) { if (dbg != null) { val t = System.nanoTime(); dbg["t_$k"] = (t - tm) / 1e6; tm = t } }
        // 深墨：窗內 (255−g) 總和（邊界複製）≥ inkSum 且 g < inkMaxGray。deep＝窗和 ≥ inkSum（淡筆觸要「不是深墨」）
        val deep = Mask(w, h)
        boxRows(w, h, rp.inkBox, { y, row -> val b = y * w; for (x in 0 until w) row[x] = 255 - g.data[b + x] }) { y, sums ->
            val b = y * w
            for (x in 0 until w) deep.data[b + x] = sums[x] >= rp.inkSum
        }
        val ink = Mask(w, h)
        for (i in 0 until n) ink.data[i] = deep.data[i] && g.data[i] < rp.inkMaxGray
        val paper = paperOf(g, deep, ink, rp)
        lap("paper")
        val raw = fillSmallHoles(charRaw, rp.holeMax)
        lap("holes")
        if (!raw.any()) {
            dbg?.let { it["ring_conf"] = Mask(w, h); it["ring_side"] = Mask(w, h); it["ring_paper"] = paper }
            return Mask(w, h)
        }
        val conf = outlinePoints(raw, ink, rp, ::lap)
        // 背景側：p 到有輪廓邊界點的精確距離 ≤ p 到 raw 的精確距離
        val side = if (conf.any()) sideOf(raw, conf) else Mask(w, h)
        lap("side")
        dbg?.let { it["ring_conf"] = conf; it["ring_side"] = side; it["ring_paper"] = paper }
        val ok = Mask(w, h)
        for (i in 0 until n) ok.data[i] = side.data[i] && paper.data[i]
        return ok
    }

    /**
     * 方窗和（k 奇數、錨點置中、邊界複製＝`cv2.boxFilter(normalize=False, BORDER_REPLICATE)`；整數加總，與 cv2 的 float32
     * 結果逐值相同：窗和遠小於 2²⁴），逐列串流：[fill] 把第 y 列的值填進 row，[emit] 收第 y 列的窗和。只配 O(w) 的暫存
     * （整頁兩張 IntArray 改成三條列）。
     */
    private inline fun boxRows(w: Int, h: Int, k: Int, fill: (Int, IntArray) -> Unit, emit: (Int, IntArray) -> Unit) {
        val r = k / 2
        val row = IntArray(w)
        val col = IntArray(w)
        val sums = IntArray(w)
        for (d in -r..r) {
            fill(min(max(d, 0), h - 1), row)
            for (x in 0 until w) col[x] += row[x]
        }
        for (y in 0 until h) {
            var s = 0
            for (d in -r..r) s += col[min(max(d, 0), w - 1)]
            for (x in 0 until w) {
                sums[x] = s
                s += col[min(x + r + 1, w - 1)] - col[max(x - r, 0)]
            }
            emit(y, sums)
            if (y == h - 1) break
            fill(min(y + r + 1, h - 1), row)
            for (x in 0 until w) col[x] += row[x]
            fill(max(y - r, 0), row)
            for (x in 0 until w) col[x] -= row[x]
        }
    }

    /** 空白紙：淡筆觸＝g < faintGray、不是深墨（[deep] 否）、離深墨 > faintInkPad（橢圓）；faintWin² 窗（邊界複製）內 < faintMin 個。 */
    private fun paperOf(g: Gray, deep: Mask, ink: Mask, rp: RingParams): Mask {
        val w = g.w
        val h = g.h
        val inkNear = Cv.dilatePacked(ink, Cv.ellipse(2 * rp.faintInkPad + 1))
        val paper = Mask(w, h)
        boxRows(w, h, rp.faintWin, { y, row ->
            val b = y * w
            for (x in 0 until w) {
                val i = b + x
                row[x] = if (g.data[i] < rp.faintGray && !deep.data[i] && !inkNear.data[i]) 1 else 0
            }
        }) { y, sums ->
            val b = y * w
            for (x in 0 until w) paper.data[b + x] = sums[x] < rp.faintMin
        }
        return paper
    }

    /** 半徑 r 的歐氏圓盤核（dx²＋dy² ≤ r²；不是 cv2 的橢圓光柵化）：膨脹它＝「r 內（精確歐氏）有沒有目標像素」。 */
    private fun disk(r: Int): Kernel {
        val s = 2 * r + 1
        return Kernel(s, s, BooleanArray(s * s) { val dy = it / s - r; val dx = it % s - r; dx * dx + dy * dy <= r * r })
    }

    /**
     * 有輪廓的邊界點（規則二的 2–4）：補洞後 [raw] 的 4 鄰邊界點，離「raw 內 evIn px 或 raw 外 evOut px 帶裡的深墨」在橢圓半徑
     * max(evIn, evOut) 內；外法向 rayFrom..rayTo px 又碰到 raw 的不算；閉運算補缺口、沒輪廓的外擴 breakPad 斷開。
     * 「raw 內 evIn px」＝精確歐氏距離 ≤ evIn ⟺ 圓盤膨脹（研究端比的是精確距離變換的值；√ 對整數單調、r 是整數，兩者等價）。
     */
    private fun outlinePoints(raw: Mask, ink: Mask, rp: RingParams, lap: (String) -> Unit): Mask {
        val w = raw.w
        val h = raw.h
        val n = w * h
        val inkBand = Mask(w, h)
        run {
            val nearBg = Cv.dilatePacked(raw.not(), disk(rp.evIn))      // raw 像素：evIn 內有非 raw
            val nearRaw = Cv.dilatePacked(raw, disk(rp.evOut))          // 非 raw 像素：evOut 內有 raw
            for (i in 0 until n) {
                if (!ink.data[i]) continue
                inkBand.data[i] = if (raw.data[i]) nearBg.data[i] else nearRaw.data[i]
            }
        }
        lap("band")
        val bnd = boundary4(raw)
        val conf = Cv.dilatePacked(inkBand, Cv.ellipse(2 * max(rp.evIn, rp.evOut) + 1))
        for (i in 0 until n) conf.data[i] = bnd.data[i] && conf.data[i]
        lap("conf")
        // 前面要是開闊的背景：外法向 rayFrom..rayTo px 碰到 raw 的不算
        val k = gaussKernel(rp.raySigma)
        for (i in 0 until n) {
            if (!conf.data[i]) continue
            val y = i / w
            val x = i - y * w
            if (!rayOk(raw, x, y, k, rp)) conf.data[i] = false
        }
        lap("ray")
        // 連續：閉運算補回缺口上的邊界點；剩下沒輪廓的邊界點外擴 breakPad，範圍內的也算沒輪廓
        if (rp.gapClose > 0) {
            val kc = Cv.ellipse(2 * rp.gapClose + 1)
            val closed = erodeSym(Cv.dilatePacked(conf, kc), kc)
            for (i in 0 until n) if (bnd.data[i] && closed.data[i]) conf.data[i] = true
        }
        val noc = Mask(w, h)
        var anyNoc = false
        for (i in 0 until n) if (bnd.data[i] && !conf.data[i]) { noc.data[i] = true; anyNoc = true }
        if (anyNoc) {
            val brk = Cv.dilatePacked(noc, Cv.ellipse(2 * rp.breakPad + 1))
            for (i in 0 until n) if (brk.data[i]) conf.data[i] = false
        }
        lap("cont")
        return conf
    }

    /**
     * 背景側：p 到有輪廓邊界點 [conf] 的精確距離 ≤ p 到 [raw] 的精確距離。照研究端比 float32 的 √（cv2 存的值）：兩個不同的整數平方
     * 距離要大到 2²² 以上才可能開根號後相等，整數比較在那裡會跟研究端不同。
     */
    private fun sideOf(raw: Mask, conf: Mask): Mask {
        val sqOut = Cv.distanceSq(raw.not())
        val sqC = Cv.distanceSq(conf.not())
        val side = Mask(raw.w, raw.h)
        for (i in side.data.indices) {
            val a = sqC[i]
            val b = sqOut[i]
            side.data[i] = a <= b || Math.sqrt(a.toDouble()).toFloat() <= Math.sqrt(b.toDouble()).toFloat()
        }
        return side
    }

    /**
     * raw 的洞（¬raw 的 8 連通塊）面積 < [maxArea]、外框不碰頁緣的併進 raw（研究端 `fill_small_holes`）。
     * ⚠️ 8 連通（這裡與 [seeds]）：研究版寫的是 `cv2.connectedComponentsWithStats(m, 4)`，但 cv2 的第二個位置參數是 labels 輸出、
     * 不是 connectivity，實際走預設的 8 連通（研究報告的文字寫 4 連通）。產品照研究版的實際輸出用 8；改 4 會變（demo06 背景側
     * 差 16,154 px、127 個研究狀態裡大多數的種子差幾到兩千多 px）。
     */
    fun fillSmallHoles(raw: Mask, maxArea: Int): Mask {
        val w = raw.w
        val h = raw.h
        val cc = Cv.ccStats(raw.not(), 8)
        val small = BooleanArray(cc.n)
        for (l in 1 until cc.n) {
            small[l] = cc.area[l] < maxArea && cc.left[l] > 0 && cc.top[l] > 0 &&
                cc.left[l] + cc.width[l] < w && cc.top[l] + cc.height[l] < h
        }
        val out = raw.copy()
        for (i in out.data.indices) if (small[cc.labels[i]]) out.data[i] = true
        return out
    }

    /** raw ∧ ¬erode(raw, 十字 3×3)，影像外當前景：四鄰（影像內）有一個不是 raw。 */
    private fun boundary4(raw: Mask): Mask {
        val w = raw.w
        val h = raw.h
        val d = raw.data
        val out = Mask(w, h)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val i = base + x
                if (!d[i]) continue
                out.data[i] = (x > 0 && !d[i - 1]) || (x < w - 1 && !d[i + 1]) ||
                    (y > 0 && !d[i - w]) || (y < h - 1 && !d[i + w])
            }
        }
        return out
    }

    /**
     * 研究端 `_gauss_kernel`：核長 rint(σ·8+1)|1，k[i] = exp(c·x·x)（c＝−0.5/σ²、x＝i−(核長−1)/2，StrictMath.exp）依序加總後各除以總和。
     */
    fun gaussKernel(sigma: Double): DoubleArray {
        val ks = Math.rint(sigma * 8 + 1).toInt() or 1
        val c = -0.5 / (sigma * sigma)
        val t = DoubleArray(ks)
        var s = 0.0
        for (i in 0 until ks) {
            val x = i - (ks - 1) * 0.5
            t[i] = StrictMath.exp(c * x * x)
            s += t[i]
        }
        for (i in 0 until ks) t[i] = t[i] / s
        return t
    }

    /** 高斯模糊後的 raw 在 (x, y)（研究端 `outward_normals` 的 B：先橫後縱、各從 0.0 依序加）。 */
    private fun blurAt(raw: Mask, x: Int, y: Int, k: DoubleArray): Double {
        val w = raw.w
        val h = raw.h
        val r = k.size / 2
        var b = 0.0
        for (i in k.indices) {
            val row = Cv.reflect101(y + i - r, h) * w
            var hs = 0.0
            for (j in k.indices) hs += k[j] * (if (raw.data[row + Cv.reflect101(x + j - r, w)]) 1.0 else 0.0)
            b += k[i] * hs
        }
        return b
    }

    /**
     * 外法向（單位向量）＝ −Sobel(高斯(raw))，逐點（研究端 `outward_normals`，加總順序一字不差）：
     * gx = (hx(y−1) + 2·hx(y)) + hx(y+1)，hx(y') = B(y', x+1) − B(y', x−1)；gy = s(y+1) − s(y−1)，s(y') = (B(y', x−1) + 2·B(y', x)) + B(y', x+1)；
     * 鄰點與高斯窗都反射 101（[Cv.reflect101]：反覆反射，寬或高不到 9 px 的頁也不越界）；n = (−gx, −gy) / (√(gx²+gy²) + 1e-9)。
     * 回傳 [nx, ny]。
     */
    fun normal(raw: Mask, x: Int, y: Int, k: DoubleArray): DoubleArray {
        val w = raw.w
        val h = raw.h
        val ys = intArrayOf(Cv.reflect101(y - 1, h), y, Cv.reflect101(y + 1, h))
        val xs = intArrayOf(Cv.reflect101(x - 1, w), x, Cv.reflect101(x + 1, w))
        val v = DoubleArray(9)
        for (a in 0 until 3) for (c in 0 until 3) {
            if (a == 1 && c == 1) continue                     // 中心點兩個 Sobel 都用不到
            v[3 * a + c] = blurAt(raw, xs[c], ys[a], k)
        }
        val hx0 = v[2] - v[0]
        val hx1 = v[5] - v[3]
        val hx2 = v[8] - v[6]
        val gx = (hx0 + 2.0 * hx1) + hx2
        val s0 = (v[0] + 2.0 * v[1]) + v[2]
        val s2 = (v[6] + 2.0 * v[7]) + v[8]
        val gy = s2 - s0
        val nx = -gx
        val ny = -gy
        val nn = Math.sqrt(nx * nx + ny * ny) + 1e-9
        return doubleArrayOf(nx / nn, ny / nn)
    }

    /** 邊界點 (x, y) 沿外法向走 rayFrom..rayTo px 都沒碰到 raw（取樣點 Math.rint、半數取偶；出界不算碰到）。 */
    private fun rayOk(raw: Mask, x: Int, y: Int, k: DoubleArray, rp: RingParams): Boolean {
        val nrm = normal(raw, x, y, k)
        val w = raw.w
        val h = raw.h
        for (t in rp.rayFrom..rp.rayTo) {
            val px = Math.rint(x.toDouble() + nrm[0] * t)
            val py = Math.rint(y.toDouble() + nrm[1] * t)
            if (px < 0 || px >= w || py < 0 || py >= h) continue
            if (raw.data[py.toInt() * w + px.toInt()]) return false
        }
        return true
    }

    /**
     * 生長＋收尾（研究端 `grow`）：從 [seed] 在 [allowed] 裡 4 連通長 ≤ [RingParams.steps] 步（種子本身是第 0 步），認領＝到得了的
     * allowed；再對黑（種子 ∪ 認領）做半徑 [RingParams.open] 的開運算，認領只留開運算後還在的。開運算只在認領外框外擴
     * 2·open＋1 的窗內算（窗外不影響窗內的開運算結果；窗被頁緣截掉時頁緣語意相同）。[RingParams.seedConnected]：最後只留與種子相連的。
     */
    fun grow(allowed: Mask, seed: Mask, rp: RingParams): Mask {
        val w = allowed.w
        val h = allowed.h
        val n = w * h
        val cl = Mask(w, h)
        if (rp.steps <= 0) {
            for (i in 0 until n) cl.data[i] = allowed.data[i] && seed.data[i]
            if (rp.open > 0) openClaim(cl, seed, rp.open)
            if (rp.seedConnected) keepSeedConnected(cl, seed)
            return cl
        }
        val reached = BooleanArray(n)
        var front = IntArray(1024)
        var fn = 0
        for (i in 0 until n) {
            if (!allowed.data[i]) continue
            if (seed.data[i]) { cl.data[i] = true; reached[i] = true; continue }
            val y = i / w
            val x = i - y * w
            if ((x > 0 && seed.data[i - 1]) || (x < w - 1 && seed.data[i + 1]) ||
                (y > 0 && seed.data[i - w]) || (y < h - 1 && seed.data[i + w])
            ) {
                reached[i] = true
                cl.data[i] = true
                if (fn == front.size) front = front.copyOf(fn * 2)
                front[fn++] = i
            }
        }
        var next = IntArray(1024)
        var step = 1
        while (fn > 0 && step < rp.steps) {
            step++
            var nn = 0
            for (q in 0 until fn) {
                val i = front[q]
                val y = i / w
                val x = i - y * w
                for (dir in 0 until 4) {
                    val j = when (dir) {
                        0 -> if (x > 0) i - 1 else -1
                        1 -> if (x < w - 1) i + 1 else -1
                        2 -> if (y > 0) i - w else -1
                        else -> if (y < h - 1) i + w else -1
                    }
                    if (j < 0 || reached[j] || !allowed.data[j] || seed.data[j]) continue
                    reached[j] = true
                    cl.data[j] = true
                    if (nn == next.size) next = next.copyOf(nn * 2)
                    next[nn++] = j
                }
            }
            val t = front; front = next; next = t
            fn = nn
        }
        if (rp.open > 0) openClaim(cl, seed, rp.open)
        if (rp.seedConnected) keepSeedConnected(cl, seed)
        return cl
    }

    /**
     * 只留經認領像素與種子 4 連通相連的認領（研究端：`(seed ∪ cl)` 的 4 連通塊含種子像素的才留）。等價寫法：從「本身是種子或四鄰
     * 有種子」的認領像素出發，只在認領裡 4 連通走（路徑一碰到種子，前一格就是起點之一）。就地改 cl。
     */
    private fun keepSeedConnected(cl: Mask, seed: Mask) {
        val w = cl.w
        val h = cl.h
        val n = w * h
        val keep = BooleanArray(n)
        var q = IntArray(1024)
        var qe = 0
        for (i in 0 until n) {
            if (!cl.data[i]) continue
            val y = i / w
            val x = i - y * w
            if (seed.data[i] || (x > 0 && seed.data[i - 1]) || (x < w - 1 && seed.data[i + 1]) ||
                (y > 0 && seed.data[i - w]) || (y < h - 1 && seed.data[i + w])
            ) {
                keep[i] = true
                if (qe == q.size) q = q.copyOf(qe * 2)
                q[qe++] = i
            }
        }
        var qs = 0
        while (qs < qe) {
            val i = q[qs++]
            val y = i / w
            val x = i - y * w
            for (dir in 0 until 4) {
                val j = when (dir) {
                    0 -> if (x > 0) i - 1 else -1
                    1 -> if (x < w - 1) i + 1 else -1
                    2 -> if (y > 0) i - w else -1
                    else -> if (y < h - 1) i + w else -1
                }
                if (j < 0 || keep[j] || !cl.data[j]) continue
                keep[j] = true
                if (qe == q.size) q = q.copyOf(qe * 2)
                q[qe++] = j
            }
        }
        for (i in 0 until n) if (cl.data[i] && !keep[i]) cl.data[i] = false
    }

    /** cl ∧= open(seed ∪ cl, 橢圓半徑 r)，在認領外框外擴 2r＋1 的窗內算（就地改 cl）。 */
    private fun openClaim(cl: Mask, seed: Mask, r: Int) {
        val w = cl.w
        val h = cl.h
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                if (!cl.data[base + x]) continue
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (x1 < 0) return
        val pad = 2 * r + 1
        val wx0 = max(0, x0 - pad)
        val wy0 = max(0, y0 - pad)
        val wx1 = min(w, x1 + pad + 1)
        val wy1 = min(h, y1 + pad + 1)
        val sw = wx1 - wx0
        val sh = wy1 - wy0
        val blk = Mask(sw, sh)
        for (y in 0 until sh) {
            val src = (wy0 + y) * w + wx0
            for (x in 0 until sw) blk.data[y * sw + x] = cl.data[src + x] || seed.data[src + x]
        }
        val k = Cv.ellipse(2 * r + 1)
        val op = Cv.dilatePacked(erodeSym(blk, k), k)
        for (y in 0 until sh) {
            val src = (wy0 + y) * w + wx0
            for (x in 0 until sw) if (cl.data[src + x] && !op.data[y * sw + x]) cl.data[src + x] = false
        }
    }
}
