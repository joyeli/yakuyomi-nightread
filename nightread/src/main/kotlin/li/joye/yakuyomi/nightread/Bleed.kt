package li.joye.yakuyomi.nightread

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 出血格過濾——逐項移植 `research/nightread_bleed.py`（python 行為就是規格）：留白帶裡「其實是出血格畫面」的塊拿掉。
 *
 * 出血格＝畫面直接延伸到頁邊、沒畫格框的格子。它的天空、地面、桌布和頁邊的白同一個白元件，留白路徑（頁邊帶、
 * 沿格框線切開、[Texture.veto] 否決有線稿的塊）只剩線稿密度一道防線；雲、地面線、桌緣這類稀疏線稿過得了密度門，
 * 於是在距格線 ~160px 內沿線稿被切成鋸齒黑塊（「撕口」）。47 頁實測 215 塊撕口 → 83 塊。
 *
 * 做法：留白帶（veto 之後）的每個 8 連通塊，看它的**外圈**（橢圓 r5 膨脹、扣掉頁緣 4px）碰到什麼。外圈逐像素歸一類，
 * 優先序由高到低：FR 框線（水平垂直 ∪ 任意角度，⊕ 9×9）、SP 溝／頁邊（SEP 扣泡之前的 sepPre，⊕ 5×5）、
 * BB 泡／TX 字／CH 人物（中性物：不算證據）、VT 被 veto 挖掉的白、DC 同留白元件但超出深度帶的白、WO 其他白、
 * AR 非白。`inf`＝外圈扣掉中性物的比例、`frameInf`＝(FR+SP)/inf、`artInf`＝(VT+DC+AR)/inf。決策表見 [decide]。
 * 只拿掉、不新增；SEP 在過濾之後照塗，過濾器拿不掉溝與頁邊。
 *
 * 頁邊條框線（2026-09-28，c362_002）：頁邊條檢驗通過的那一邊，停點排成的直暗線就是格框線。被泡／人物截斷、只在頁緣
 * 露出一小段的框線 [Regions.frameLineMask] 認不出來，框線另一側那塊白外圈碰到的只剩 AR，被當成出血格畫面拿掉。
 * 所以先對所有碰頁緣的塊做頁邊條檢驗、收框線本體（[marginStrip] 的 lineH／lineV）；外圈落在它（⊕ frDil 方核）上、
 * 原本不是 FR 的像素，只在塊碰頁緣、且沿這段框線的接觸長度 ≥ [BleedParams.mlineMinRun] 時改記 FR。
 *
 * 逐位元的地方（與 python 同輸入 ⇒ 逐塊決策與輸出逐像素相同，47 頁驗過）：
 *  - 決策用的比例先 `round(·, 3)` **再**比門檻（FR+SP == 0 其實是「兩者都 < 0.0005」）。python `round` 對**精確的
 *    二進位值**半數取偶 ⇒ `BigDecimal(double).setScale(3, HALF_EVEN)`（不是 `BigDecimal.valueOf`：那走最短十進位字串，
 *    在恰好 .xxx5 的值上會不同）。
 *  - 頁邊條的 Theil–Sen 取樣點照 `np.linspace(0, n−1, k).astype(int)`：i·step（最後一點強制＝n−1）再截斷。
 *  - 中位數照 `np.median`：偶數個取中間兩個的 (a+b)/2。
 *  - 橢圓核是 cv2 `MORPH_ELLIPSE` 的形狀（[Cv.ellipse]）；外擴走位元打包的 [Cv.dilateBits]（與 [Cv.dilate] 逐像素相同、
 *    影像外視為 0，同 cv2），外圈類別只在外圈像素上查打包位元——一頁五趟整頁外擴＋逐塊外圈，逐像素版加整頁類別圖
 *    在 2.6 MPx 上要 ~150 ms。
 *  - 塊的標號用 8 連通；決策只看塊本身，與標號順序無關。
 *  - 頁邊條框線要**先**收齊（所有碰頁緣的塊、四邊都檢驗，不因某邊通過就提早回傳），再算逐塊外圈；框線本體要走回白
 *    （< whiteTh 的連續像素、長度 ≤ marginLineMax，下一格 ≥ whiteTh）才畫；接觸長度算「不同的 x／y 個數」。
 */
internal object Bleed {

    // 外圈類別（優先序由高到低；AR＝其餘＝非白）
    private const val FR = 0
    private const val SP = 1
    private const val BB = 2
    private const val TX = 3
    private const val CH = 4
    private const val VT = 5
    private const val DC = 6
    private const val WO = 7
    private const val AR = 8

    /** 頁邊條：塊在頁緣這麼多 px（列）內有像素＝該位置碰頁緣（python `m[0:3]`）。 */
    private const val EDGE_ROWS = 3

    /** 頁邊條：碰頁緣的位置、候選列至少這麼多才檢驗（python 的字面值 12）。 */
    private const val MIN_TOUCH = 12

    /** 頁邊條：Theil–Sen 最多取這麼多個等距取樣點（python 的字面值 60）。 */
    private const val TS_SAMPLES = 60

    /** 頁邊條：Theil–Sen 兩點沿頁緣相距至少這麼多列才算斜率（python 的字面值 8）。 */
    private const val TS_MIN_SEP = 8.0

    /** 頁邊條：停點落在擬合直線 ± 此 px 內＝在線上（python 的字面值 2.0）。 */
    private const val INLIER_PX = 2.0

    private val DEG = 180.0 / Math.PI                 // np.degrees：x·(180/π)

    /** 留白帶的一塊：特徵（比例已捨入到 3 位，同 python 審計表）＋決策。 */
    class Piece(
        val i: Int, val area: Int, val x: Int, val y: Int, val w: Int, val h: Int,
        val touch: Boolean, val margin: Boolean, val ns: Int,
        /** FR、SP、BB、TX、CH、VT、DC、WO、AR 各佔外圈的比例（捨入到 3 位）。 */
        val frac: DoubleArray,
        val inf: Double, val frameInf: Double, val artInf: Double, val txt: Double, val net: Boolean,
        /** 沿頁邊條框線的接觸長度：上下邊框線（不同的 x 個數）、左右邊框線（不同的 y 個數）；不碰頁緣的塊一律 0。 */
        val mrunH: Int = 0, val mrunV: Int = 0,
    ) {
        /** 決策理由（拿掉的加 `DROP-` 前綴，同 python `why`）。 */
        var why: String = ""
        var drop: Boolean = false
    }

    /** [filter] 的輸出：過濾後的留白帶＋逐塊特徵與決策（parity／除錯用）。 */
    class Result(val band: Mask, val pieces: List<Piece>)

    /** `round(v, 3)`：對精確的二進位值半數取偶（python `float.__round__`）。 */
    private fun r3(v: Double): Double = BigDecimal(v).setScale(3, RoundingMode.HALF_EVEN).toDouble()

    /** 外擴後的打包位元（[Cv.packBits] 格式；影像外視為 0，同 cv2）。 */
    private fun dilated(m: Mask, k: Kernel): LongArray = Cv.dilateBits(Cv.packBits(m), m.w, m.h, k)

    /** 打包位元查 (x, y)。 */
    private fun has(a: LongArray, nw: Int, x: Int, y: Int): Boolean = (a[y * nw + (x ushr 6)] ushr (x and 63)) and 1L != 0L

    /** `np.median`：排序後奇數個取中間、偶數個取中間兩個的 (a+b)/2。 */
    private fun median(v: DoubleArray, n: Int = v.size): Double {
        val s = v.copyOf(n)
        s.sort()
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /**
     * 留白帶 [band]（[Texture.veto] 之後）拿掉出血格畫面塊。
     *
     * @param band0 veto 之前的留白帶（VT＝band0 − band）
     * @param frameHv 水平／垂直格框線（[Regions.frameLineMask] 的 lh ∪ lv）
     * @param charMask 人物遮罩（收邊平滑後，compose 用的那份）
     * @param gutter compose 收到的留白元件（DC＝gutter − band0、WO＝白 − gutter）
     * @param layer SEP 圖層（null＝SEP 關：溝／頁邊／任意角度框線證據一律空）
     */
    fun filter(
        band: Mask, band0: Mask, g: Gray, frameHv: Mask, seg: Mask, bubble: Mask, charMask: Mask,
        regions: List<TextRegion>, gutter: Mask, layer: Separators.Layer?, p: NightReadParams,
    ): Result {
        val w = g.w
        val h = g.h
        if (!band.any()) return Result(band, emptyList())    // 沒有塊：python 也原樣回傳
        val b = p.bleed

        // ── 外圈歸類的素材（整頁外擴一次；存打包位元，只在外圈像素上查）────────────────
        val nw = (w + 63) ushr 6
        val frSrc = if (layer != null) frameHv or layer.frameArb else frameHv
        val fr = dilated(frSrc, Cv.rect(b.frDil, b.frDil))
        val sp = if (layer != null) dilated(layer.sepPre, Cv.rect(b.spDil, b.spDil)) else null
        val bb = dilated(bubble, Cv.ellipse(2 * b.bbR + 1))
        val tx = dilated(seg, Cv.ellipse(2 * b.txR + 1))
        val ch = dilated(charMask, Cv.ellipse(2 * b.chR + 1))
        val neu = LongArray(bb.size) { bb[it] or tx[it] or ch[it] }   // 中性物（頁邊條檢驗用）
        val bd = band.data
        val b0 = band0.data
        val gu = gutter.data
        val gv = g.data

        // ── 標號＋頁邊條檢驗（先做：通過那邊停點排成的直暗線＝框線本體 lineH（上下邊）／lineV（左右邊））──
        val cc = Cv.ccStats(band, 8)
        val lab = cc.labels
        val t = b.touchPx
        val touchOf = BooleanArray(cc.n)
        val marginOf = BooleanArray(cc.n)
        val lineH = LongArray(nw * h)                 // 打包位元（同 [Cv.packBits]），框線像素少、省掉整頁 Mask 與打包
        val lineV = LongArray(nw * h)
        for (i in 1 until cc.n) {
            val x = cc.left[i]
            val y = cc.top[i]
            touchOf[i] = x <= t || y <= t || x + cc.width[i] >= w - t || y + cc.height[i] >= h - t
            if (touchOf[i]) marginOf[i] = marginStrip(lab, i, g, neu, nw, p, lineH, lineV)   // 碰頁緣的塊才做
        }
        val fmh = if (lineH.any { it != 0L }) Cv.dilateBits(lineH, w, h, Cv.rect(b.frDil, b.frDil)) else null   // 同 FR 的外擴
        val fmv = if (lineV.any { it != 0L }) Cv.dilateBits(lineV, w, h, Cv.rect(b.frDil, b.frDil)) else null

        /** 外圈像素 (x, y) 的類別（優先序由高到低）。 */
        fun category(x: Int, y: Int): Int {
            val i = y * w + x
            return when {
                has(fr, nw, x, y) -> FR
                sp != null && has(sp, nw, x, y) -> SP
                has(bb, nw, x, y) -> BB
                has(tx, nw, x, y) -> TX
                has(ch, nw, x, y) -> CH
                b0[i] && !bd[i] -> VT          // 被 veto 挖掉的白＝有線稿的白
                gu[i] && !b0[i] -> DC          // 同留白元件、超出深度帶的白＝深入格內的白
                gv[i] >= p.whiteTh && !gu[i] -> WO
                else -> AR
            }
        }
        val sep = layer?.sep?.data

        // 文字區 bbox ⊕ textBoxPad（txt 重疊比例用；python 切片含 x1、y1）
        val tb = BooleanArray(w * h)
        val q = b.textBoxPad
        for (r in regions) {
            val x0 = max(0, r.x0 - q)
            val y0 = max(0, r.y0 - q)
            val x1 = min(w, r.x1 + q + 1)
            val y1 = min(h, r.y1 + q + 1)
            for (yy in y0 until y1) {
                val base = yy * w
                for (xx in x0 until x1) tb[base + xx] = true
            }
        }

        // ── 逐塊特徵 ─────────────────────────────────────────────────────
        val e = b.ringEdge
        val ringK = Cv.ellipse(2 * b.ringR + 1)
        val pieces = ArrayList<Piece>(cc.n - 1)
        val drop = BooleanArray(cc.n)
        for (i in 1 until cc.n) {
            val a = cc.area[i]
            val x = cc.left[i]
            val y = cc.top[i]
            val pw = cc.width[i]
            val ph = cc.height[i]
            val touch = touchOf[i]
            val y0 = max(0, y - b.piecePad)
            val y1 = min(h, y + ph + b.piecePad)
            val x0 = max(0, x - b.piecePad)
            val x1 = min(w, x + pw + b.piecePad)
            val sw = x1 - x0
            val sh = y1 - y0
            val mm = Mask(sw, sh)
            var ns = 0
            var inTb = 0
            for (yy in 0 until sh) {
                val src = (y0 + yy) * w + x0
                for (xx in 0 until sw) {
                    if (lab[src + xx] != i) continue
                    mm.data[yy * sw + xx] = true
                    if (sep == null || !sep[src + xx]) ns++
                    if (tb[src + xx]) inTb++
                }
            }
            val margin = marginOf[i]                                   // 碰頁緣的塊才做頁邊條檢驗（上面先做了）
            // 外圈＝橢圓 r5 膨脹 − 塊本身，扣掉頁緣 e px（頁緣外沒有證據，不能算成「沒碰到框線」）
            val snw = (sw + 63) ushr 6
            val mmBits = Cv.packBits(mm)
            val dil = Cv.dilateBits(mmBits, sw, sh, ringK)
            val cnt = IntArray(9)
            var rn = 0
            // FM：外圈落在頁邊條框線（外擴後）上、原本不是 FR 的像素（碰頁緣的塊才看）。按「只在上下邊框線／只在左右邊
            // 框線／兩者都在」分三組記原類別；沿線接觸長度＝上下邊框線上不同的 x、左右邊框線上不同的 y
            val useFm = touch && (fmh != null || fmv != null)
            val cntH = if (useFm) IntArray(9) else null
            val cntV = if (useFm) IntArray(9) else null
            val cntB = if (useFm) IntArray(9) else null
            val colH = if (useFm) BooleanArray(sw) else null
            val rowV = if (useFm) BooleanArray(sh) else null
            for (yy in 0 until sh) {
                val gy = y0 + yy
                if (gy < e || gy >= h - e) continue
                val wb = yy * snw
                for (wi in 0 until snw) {
                    var v = dil[wb + wi] and mmBits[wb + wi].inv()
                    while (v != 0L) {
                        val xx = (wi shl 6) + java.lang.Long.numberOfTrailingZeros(v)
                        v = v and (v - 1)
                        if (xx >= sw) continue
                        val gx = x0 + xx
                        if (gx < e || gx >= w - e) continue
                        rn++
                        val c = category(gx, gy)
                        cnt[c]++
                        if (useFm && c != FR) {
                            val inH = fmh != null && has(fmh, nw, gx, gy)
                            val inV = fmv != null && has(fmv, nw, gx, gy)
                            if (inH) colH!![xx] = true
                            if (inV) rowV!![yy] = true
                            if (inH && inV) cntB!![c]++ else if (inH) cntH!![c]++ else if (inV) cntV!![c]++
                        }
                    }
                }
            }
            rn = max(rn, 1)
            var runH = 0
            var runV = 0
            if (useFm) {
                for (v in colH!!) if (v) runH++
                for (v in rowV!!) if (v) runV++
                val okH = runH >= b.mlineMinRun
                val okV = runV >= b.mlineMinRun
                for (k in 0 until 9) {                 // 接觸夠長的方向：FM 改記 FR
                    val mv = (if (okH) cntH!![k] else 0) + (if (okV) cntV!![k] else 0) + (if (okH || okV) cntB!![k] else 0)
                    cnt[k] -= mv
                    cnt[FR] += mv
                }
            }
            val frac = DoubleArray(9) { r3(cnt[it].toDouble() / rn) }
            val inf = rn - cnt[BB] - cnt[TX] - cnt[CH]
            val pc = Piece(
                i, a, x, y, pw, ph, touch, margin, ns, frac,
                inf = r3(inf.toDouble() / rn),
                frameInf = r3((cnt[FR] + cnt[SP]).toDouble() / max(inf, 1)),
                artInf = r3((cnt[VT] + cnt[DC] + cnt[AR]).toDouble() / max(inf, 1)),
                txt = r3(inTb.toDouble() / a),
                net = a >= b.netFrac * h * w,
                mrunH = runH, mrunV = runV,
            )
            val (d, why) = decide(pc, b)
            pc.drop = d
            pc.why = (if (d) "DROP-" else "") + why
            drop[i] = d
            pieces.add(pc)
        }
        val out = Mask(w, h)
        for (i in bd.indices) out.data[i] = bd[i] && !drop[lab[i]]
        return Result(out, pieces)
    }

    /**
     * 單塊決策 →（拿掉？, 理由），依序第一條成立就定：
     *
     *     ns = 0（整塊都在 SEP 裡）           留  allsep   SEP 之後照塗，拿掉也沒用
     *     面積 ≥ netFrac×頁                   留  net      整片溝網
     *     與文字區重疊 ≥ txtKeep              留  text     字旁的留白
     *     頁邊條檢驗通過                      留  margin   從頁緣走到一條近乎筆直的暗線、途中乾淨
     *     frameInf ≥ frameKeep                留  framed   邊界多半是格框／溝
     *     不碰頁緣 ∧ FR+SP = 0 ∧ BB < islandBbMax  拿掉 island  畫中孤島
     *     inf < enclosedMax                   留  enclosed 被人物／泡／字包住（證據不足）
     *     artInf ≥ artDrop                    拿掉 art     邊界近半是畫
     *     其餘                                留  weak
     */
    fun decide(pc: Piece, b: BleedParams): Pair<Boolean, String> = when {
        pc.ns < 1 -> false to "allsep"
        pc.net -> false to "net"
        pc.txt >= b.txtKeep -> false to "text"
        pc.margin -> false to "margin"
        pc.frameInf >= b.frameKeep -> false to "framed"
        !pc.touch && pc.frac[FR] + pc.frac[SP] == 0.0 && pc.frac[BB] < b.islandBbMax -> true to "island"
        pc.inf < b.enclosedMax -> false to "enclosed"
        pc.artInf >= b.artDrop -> true to "art"
        else -> false to "weak"
    }

    /**
     * 頁邊條檢驗（`margin_strip`）：塊碰頁緣的每一邊，沿頁緣逐列從頁緣往內走。停點＝第一個「暗（g < marginOk）
     * 或中性物」像素；停點往內 marginNeuLook px 內碰到中性物也算中性。停在中性物上的列不計（泡／人物蓋住框線是常態），
     * 停在暗點上的列＝資訊列，要求：深度 ≤ 短邊×marginFrac、途中亮而非白夠少、停點沿頁緣近乎一直線（夾角 ≤ marginAng、
     * 殘差 ≤ 2px 的列 ≥ marginCover）、停點夠暗、資訊列與直線跨度 ≥ marginMinRows、深度 ≥ marginMinDepth。
     * 任一邊通過就回 true。
     *
     * [lineH]／[lineV]（整頁打包位元，[Cv.packBits] 格式）給了就四邊都檢驗（回傳值不變），並把**通過那邊**的框線本體
     * 畫進去（上下邊進 lineH、左右邊進 lineV）：
     * 每個落在擬合直線上的停點，從停點起往內走過連續非白（< whiteTh），marginLineMax px 內走回白才畫（線的另一側是紙；
     * 走不回白＝框線在這裡跟畫黏在一起）。
     *
     * 座標：r＝離頁緣的深度、t＝沿頁緣的位置（上／下邊 t＝x、左／右邊 t＝y），同 python 轉置／翻轉後的 `G[r, t]`。
     */
    private fun marginStrip(lab: IntArray, id: Int, g: Gray, neutral: LongArray, nw: Int, p: NightReadParams,
                            lineH: LongArray? = null, lineV: LongArray? = null): Boolean {
        val b = p.bleed
        val w = g.w
        val h = g.h
        val maxd = (b.marginFrac * min(h, w)).toInt()
        val rows = maxd + 1
        val collect = lineH != null && lineV != null
        var ok = false
        for (side in 0 until 4) {
            val len = if (side < 2) w else h
            val depthN = if (side < 2) h else w         // 深度方向的長度（python G.shape[0]）
            // (r, t) → 像素 (x, y)：0 上、1 下（g[::-1]）、2 左（g.T）、3 右（g[:, ::-1].T）
            fun px(r: Int, tt: Int): Int = when (side) {
                0, 1 -> tt
                2 -> r
                else -> w - 1 - r
            }
            fun py(r: Int, tt: Int): Int = when (side) {
                0 -> r
                1 -> h - 1 - r
                else -> tt
            }
            fun at(r: Int, tt: Int): Int = py(r, tt) * w + px(r, tt)
            fun neu(r: Int, tt: Int): Boolean = has(neutral, nw, px(r, tt), py(r, tt))
            var nts = 0
            val ts = IntArray(len)
            // python 的 m[0:3] 切片在深度不到 3 px 的頁會自己截短；這裡照截（1–2 px 寬或高的小頁原本越界）
            val edgeRows = min(EDGE_ROWS, depthN)
            for (tt in 0 until len) {
                for (r in 0 until edgeRows) {
                    if (lab[at(r, tt)] == id) { ts[nts++] = tt; break }
                }
            }
            if (nts < MIN_TOUCH) continue
            val d = IntArray(nts)
            val info = BooleanArray(nts)
            val cand = BooleanArray(nts)
            var nInf = 0
            var nCand = 0
            for (k in 0 until nts) {
                val tt = ts[k]
                var dk = rows                         // 沒有停點＝maxd+1
                var nl = 0                            // 停點之前（深度 0..d−1）亮而非白的像素數
                for (r in 0 until rows) {
                    val v = g.data[at(r, tt)]
                    if (v < b.marginOk || neu(r, tt)) { dk = r; break }
                    if (v < p.whiteTh) nl++           // v ≥ marginOk 且非中性（上面已排除）
                }
                d[k] = dk
                val anyb = dk <= maxd
                val dcl = min(dk, maxd)
                var look = false
                for (qq in 0..b.marginNeuLook) {
                    if (neu(min(dcl + qq, maxd), tt)) { look = true; break }
                }
                val neut = anyb && look
                val clean = nl.toDouble() <= max(b.marginLightMin.toDouble(), b.marginLightMaxFrac * dk)
                info[k] = anyb && !neut
                if (info[k]) nInf++
                cand[k] = info[k] && clean
                if (cand[k]) nCand++
            }
            if (nInf < b.marginMinRows || nCand < MIN_TOUCH) continue
            val n = nCand
            val tt = DoubleArray(n)
            val dd = DoubleArray(n)
            val colK = IntArray(n)
            var c = 0
            for (k in 0 until nts) {
                if (!cand[k]) continue
                tt[c] = ts[k].toDouble(); dd[c] = d[k].toDouble(); colK[c] = k; c++
            }
            // Theil–Sen 斜率：最多 60 個等距取樣點兩兩斜率（沿頁緣相距 ≥ 8 列）的中位數
            val ks = min(n, TS_SAMPLES)
            val step = (n - 1).toDouble() / (ks - 1)
            val idx = IntArray(ks) { (it.toDouble() * step + 0.0).toInt() }
            idx[ks - 1] = n - 1
            val sl = DoubleArray(ks * (ks - 1) / 2)
            var ns = 0
            for (i in 0 until ks) {
                val a = idx[i]
                for (j in i + 1 until ks) {
                    val bb = idx[j]
                    if (tt[bb] - tt[a] >= TS_MIN_SEP) sl[ns++] = (dd[bb] - dd[a]) / (tt[bb] - tt[a])
                }
            }
            if (ns == 0) continue
            val s = median(sl, ns)
            val cs = DoubleArray(n) { dd[it] - s * tt[it] }
            val c0 = median(cs)
            var nIn = 0
            var tMin = Double.MAX_VALUE
            var tMax = -Double.MAX_VALUE
            val stopv = DoubleArray(n)
            val depths = DoubleArray(n)
            val inK = IntArray(n)                     // 在線上的停點（候選列序號）
            for (k in 0 until n) {
                val res = abs(dd[k] - (s * tt[k] + c0))
                if (res > INLIER_PX) continue
                inK[nIn] = colK[k]
                val tk = ts[colK[k]]
                stopv[nIn] = g.data[at(min(d[colK[k]], maxd), tk)].toDouble()
                depths[nIn] = dd[k]
                tMin = min(tMin, tt[k])
                tMax = max(tMax, tt[k])
                nIn++
            }
            val cover = nIn.toDouble() / nInf
            val dark = if (nIn > 0) median(stopv, nIn) else 255.0
            val ang = StrictMath.atan(abs(s)) * DEG
            val span = if (nIn > 0) tMax - tMin + 1 else 0.0
            // python 先 round(·, 1)：整數的中位數只會是 .0／.5，捨入不動值
            val depth = if (nIn > 0) BigDecimal(median(depths, nIn)).setScale(1, RoundingMode.HALF_EVEN).toDouble() else -1.0
            if (cover >= b.marginCover && ang <= b.marginAng && dark <= b.marginDark && span >= b.marginMinRows &&
                depth >= b.marginMinDepth) {
                if (!collect) return true
                ok = true
                val line = if (side < 2) lineH!! else lineV!!
                for (j in 0 until nIn) {              // 停點起往內的連續非白＝框線本體（走回白才畫）
                    val tk = ts[inK[j]]
                    val d0 = d[inK[j]]
                    val lim = min(d0 + b.marginLineMax, depthN)
                    var e = d0
                    while (e < lim && g.data[at(e, tk)] < p.whiteTh) e++
                    if (e >= depthN || g.data[at(e, tk)] < p.whiteTh) continue
                    for (r in d0 until e) {
                        val x = px(r, tk)
                        val y = py(r, tk)
                        line[y * nw + (x ushr 6)] = line[y * nw + (x ushr 6)] or (1L shl (x and 63))
                    }
                }
            }
        }
        return ok
    }
}
