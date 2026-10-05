package li.joye.yakuyomi.nightread

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 「更多」背景物件規則（2026-10-03 使用者拍板，規則版本 3）＝研究端 `research/nightread_obj.py`，逐像素照它（Kotlin 與研究端
 * 同一套整數／固定順序寫法）。使用者原則：「塗黑不用看白不白，以有沒有物件判斷」。只在開了「更多」新規則的檔生效
 * （[ObjectRuleParams.enabled] ∧ [MoreRuleParams.enabled]），「標準」逐像素不變。兩個機制，都在貼紙層之後、灰圈收細與泡重繪之前：
 *
 * - **V（[vetoBlocks]）**：「更多」比只塗標準（L2）那幾顆多塗黑的連通塊，把白跨細線閉合成「超區」，超區證據 ‰ 高＝夾在物件
 *   之間的白（牆板、桌面、地磚）⇒ 還原成只塗標準的樣子；塊（補洞後）裡有字區中心＝字幕框，不否決。
 * - **L（[lightFill]）**：亮背景區（σ5 亮度 ≥ 門檻，白與淺色調一樣）整區判有沒有物件；過門的從核心（離證據夠遠）塗起，長到
 *   線邊為止；拿掉夾在物件之間的白塊、灰海裡的孤島、貼著人物的小塊。塗法同貼紙（BG、描亮邊、字畫亮）。
 *
 * 效果線與閃光（2026-10-03 使用者裁定 2；[ObjectRuleParams.fxLines]／[ObjectRuleParams.fxSparks]）：
 * - **A 效果線**（整頁的效果線族＝[EffectLines.field]）：否決的例外（超區證據扣掉效果墨後 ≤ 門檻、碰到夠多成員線 ⇒ 不否決）；
 *   整區門沒過、但區內的墨絕大多數是效果墨、扣掉效果墨後調子邊與殘差照樣過的區＝效果線區，同一片場（地盤被格框線與已塗黑切開）
 *   裡有一區沒過就整片不塗；效果線區另走一條塗法（[fxPaint]：紙白 → BG、墨 → 最亮 [EffectLineParams.lineV]）。
 * - **C 閃光**：亮記號裡四周沒有暗墨、大小合適的＝閃光：不算證據、不算調子邊、「孤立亮記號多就整區留灰」關掉；塗黑區裡的閃光
 *   畫成淺灰。尺畫的直線不給長線帶。
 *
 * 複核收尾（2026-10-04）：人物旁的淡線（人物模型漏掉、用點狀淡線畫的手、筆）外圈不塗（[ObjectRuleParams.personFaint]，
 * [Context.phalo]）；字畫亮只限碰到字框的字塊（[ObjectRuleParams.textNeedsRegion]）。
 *
 * 規則版本 4（2026-10-05 使用者決定 q1–q4；研究端同名開關，四條都關＝版本 3 逐像素相同）：
 * - q1 灰虛線補黑（[ObjectRuleParams.seam]，[seam]）：孤島之後，塗黑區閉合補得起來、沒有細暗線／亮記號／谷、原圖都亮的細縫補黑。
 * - q2 沒有字框的手寫字畫亮（[ObjectRuleParams.textStroke]，[strokeLike]）：碰不到字框、像粗墨筆畫的字塊照樣畫亮；這些字旁、只由
 *   它們的墨引起的描亮邊塗回 BG（有字框的字旁照版本 3）。
 * - q3 淡線外圈貼線形（[ObjectRuleParams.pfShape]，[pfShape]）：一條線只留 pfMargin，手／網點照舊 pfHalo，圍住的小塊與淡小記號
 *   （pfMark）也不塗；⊆ 版本 3 的外圈。
 * - q4 譯後頁去字區旁的小塊不塗（[ObjectRuleParams.inpaintIslands]，[NightReadInput.inpaintMask]，[Context.inpaint]）。
 * 計算順序照研究端：context 的淡線外圈（含記號）→ light_fill 的孤島（含去字區）→ 補縫 → 塗 BG → 描亮邊 → 閃光 → 字（含手寫字）→
 * 手寫字旁的描亮邊清理。版本 4 交出之後「更多」凍結，只修紅線。
 *
 * **確定性寫法**：高斯＝整數核（[Cv.gaussW]）兩趟卷積到 Q16；blackhat／tophat、線核平均、亮度門檻都在 Q16 整數上比；Canny 吃
 * Q16 整數部分；調子邊緣＝Q16 的 3×3 Sobel 平方和比門檻平方；距離＝5×5 chamfer（非 IPP）；‰／平均／中位都用整數比；
 * 二次曲面殘差＝正規方程（numpy 的分段成對加總，[npSumChunked]）＋固定順序高斯消去；字的亮度＝256 格查表。與原型（浮點）的差
 * 見 docs/DECISIONS.md。
 */
internal object BgObjects {

    private const val Q = 65536

    /** 整頁量測（與檔位無關；[NightRead.Analysis] 快取）。遮罩 1 bit/px（[Ring.packBits] 格式），σ2.5 亮度 Q16。 */
    class Context(
        val w: Int,
        val h: Int,
        /** 交代過：人物（收邊後）∪ 原輸出 ∪ 泡⊕6 ∪ 字⊕3 ∪ 格框線⊕2。 */
        val x: LongArray,
        /** X⊕3 ∪ 頁緣 6 px。 */
        val x3: LongArray,
        /** 物件證據（細暗線｜σ4 Canny｜亮記號，扣 X3、去 < 15 px）。 */
        val e: LongArray,
        /** 否決證據（細暗線不經線核｜σ2 Canny，扣 X3、去 < 15 px）。 */
        val ev2: LongArray,
        val ink: LongArray,
        val bright: LongArray,
        /** 調子邊緣（中值 7 → σ3 → Sobel）。 */
        val tone: LongArray,
        /** 調子邊界（σ4 Canny）。 */
        val toneE: LongArray,
        /** σ5 亮度 ≥ 門檻。 */
        val light: LongArray,
        /** 長線帶。 */
        val longz: LongArray,
        /** 閃光（C；沒開或沒有＝null）。 */
        val spark: LongArray?,
        /** 效果線場（A；沒開或沒有收下的族＝null）。 */
        val fx: EffectLines.Field?,
        /** 人物旁的淡線外圈（複核 1：亮背景區塗黑扣掉；沒開或沒有＝null）。規則版本 4 起貼線形（[ObjectRuleParams.pfShape]）。 */
        val phalo: LongArray? = null,
        /** σ2 blackhat > [ObjectRuleParams.bhTh]（不經線核、不去網點；規則版本 4 縫補黑的「谷」；縫補黑沒開＝null）。 */
        val bhOn: LongArray? = null,
        /** 譯後頁的去字遮罩（規則版本 4 孤島判斷；沒開、沒有或全空＝null）。 */
        val inpaint: LongArray? = null,
    )

    private fun has(b: LongArray, i: Int): Boolean = (b[i ushr 6] ushr (i and 63)) and 1L != 0L
    private fun unpack(b: LongArray, w: Int, h: Int): Mask = Mask(w, h, BooleanArray(w * h) { has(b, it) })
    private fun dil(m: Mask, r: Int): Mask = if (r <= 0) m.copy() else Cv.dilatePacked(m, Cv.ellipse(2 * r + 1))

    /** 連通塊外接框長邊 ≥ [mind] 的留下（網點：長邊 < [mind] 的小點／小環）。 */
    private fun noDots(m: Mask, mind: Int): Mask {
        val cc = Cv.ccStats(m, 8)
        val keep = BooleanArray(cc.n) { it > 0 && max(cc.width[it], cc.height[it]) >= mind }
        return Mask(m.w, m.h, BooleanArray(m.data.size) { keep[cc.labels[it]] })
    }

    private fun minArea(m: Mask, a: Int): Mask {
        val cc = Cv.ccStats(m, 8)
        val keep = BooleanArray(cc.n) { it > 0 && cc.area[it] >= a }
        return Mask(m.w, m.h, BooleanArray(m.data.size) { keep[cc.labels[it]] })
    }

    /** 8 方向（0、π/8 … 7π/8）[len] px 線核的位置（dy, dx）＝研究端 `line_offsets`（每方向去重、列優先序）。 */
    internal fun lineOffsets(len: Int): List<IntArray> {
        val c = len / 2
        val n = 4 * len
        val out = ArrayList<IntArray>(8)
        for (d in 0 until 8) {
            val t = d * (Math.PI / 8)
            val k = BooleanArray(len * len)
            val step = (2.0 * c) / (n - 1)
            for (i in 0 until n) {
                val u = if (i == n - 1) c.toDouble() else i * step + (-c).toDouble()
                val ry = Math.rint(c + u * Math.sin(t)).toInt()
                val rx = Math.rint(c + u * Math.cos(t)).toInt()
                k[ry * len + rx] = true
            }
            val pts = ArrayList<Int>()
            for (i in 0 until len * len) if (k[i]) { pts.add(i / len - c); pts.add(i % len - c) }
            out.add(pts.toIntArray())
        }
        return out
    }

    private val off17 by lazy { lineOffsets(17) }
    private val off11 by lazy { lineOffsets(11) }

    /**
     * [cand] 上的像素：任一方向的線核平均 > num/den（Σ_線上 img ×den > num ×點數；BORDER_REFLECT_101；整數）＝研究端 `line_mean_gt`
     * 在 [cand] 上的值（研究端整頁算再 ∧ cand）。
     */
    private fun lineMeanGt(img: Gray, offs: List<IntArray>, num: Long, den: Long, cand: Mask): Mask {
        val w = img.w
        val h = img.h
        val d = img.data
        val out = Mask(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!cand.data[i]) continue
                for (o in offs) {
                    var s = 0L
                    var j = 0
                    while (j < o.size) {
                        val yy = y + o[j]
                        val xx = x + o[j + 1]
                        val ry = if (yy < 0 || yy >= h) Cv.reflect101(yy, h) else yy
                        val rx = if (xx < 0 || xx >= w) Cv.reflect101(xx, w) else xx
                        s += d[ry * w + rx]
                        j += 2
                    }
                    if (s * den > num * (o.size / 2)) { out.data[i] = true; break }
                }
            }
        }
        return out
    }

    /**
     * 整頁量測（研究端 `context`）：[bubble]＝修剪前的泡（含封縫救回的，[NightRead.Analysis.bubbleUntrim]）、[frame]＝格框線。
     */
    fun context(
        g: Gray, charMask: Mask, charRaw: Mask, bubble: Mask, seg: Mask, frame: Mask, p: ObjectRuleParams,
        diag: MutableMap<String, Any>?, inpaint: Mask? = null,
    ): Context {
        val w = g.w
        val h = g.h
        val n = w * h
        val qv = Q.toLong()
        val ell11 = Cv.ellipse(11)
        val keepDiag = diag != null && diag["obj"] == true
        val sH = min(2.0, max(0.5, h / 1920.0))
        // 交代過（只看遮罩，先算：效果線場要在 blackhat 還活著時算）；存 1 bit/px、用的時候攤開
        val xB: LongArray
        val x3B: LongArray
        run {
            val x = charMask or charRaw
            x.orInPlace(dil(bubble, 6)).orInPlace(dil(seg, 3)).orInPlace(dil(frame, 2))
            xB = Ring.packBits(x)
            val x3 = dil(x, 3)
            for (yy in 0 until h) for (xx in 0 until w) {
                if (yy < 6 || yy >= h - 6 || xx < 6 || xx >= w - 6) x3.data[yy * w + xx] = true
            }
            x3B = Ring.packBits(x3)
        }
        // 記憶體：整頁的 Q16 影像一次最多三張活著，算完的遮罩立刻壓成 1 bit/px（研究端的計算順序無關結果）
        // σ2：Canny（否決證據）→ blackhat（細暗線）
        var bh: Gray?
        val can: Mask
        // σ2 Canny 去網點之前（規則版本 4：淡線外圈的淡小記號要它；1 bit/px，外圈算完就放掉）
        var canRawB: LongArray?
        run {
            val gb2 = Cv.gaussQ16(g, 2.0)
            val raw = Cv.canny(gb2.data, w, h, 16, p.cannyLo, p.cannyHi)
            canRawB = if (p.personFaint && p.pfShape && p.pfMark > 0) Ring.packBits(raw) else null
            can = noDots(raw, p.dot)
            val cl = Cv.morphGrayGather(Cv.morphGrayGather(gb2, ell11, wantMax = true), ell11, wantMax = false)
            for (i in 0 until n) cl.data[i] -= gb2.data[i]
            bh = cl
        }
        val bhOn = Mask(w, h, BooleanArray(n) { bh!!.data[it] > p.bhTh * Q })
        val bhOnB = if (p.seam) Ring.packBits(bhOn) else null
        val ink = noDots(lineMeanGt(bh!!, off17, p.lineR * qv, 1L, bhOn), p.dot)
        val inkB = Ring.packBits(ink)
        // 效果線場（A）：細暗線＋blackhat（結構張量）；算完 blackhat 就不要了
        val fxInfo = if (keepDiag) ArrayList<String>() else null
        val fx = if (p.fxLines) EffectLines.field(g, ink, unpack(x3B, w, h), bh!!, sH, p.fx, fxInfo) else null
        bh = null
        // 否決證據的細暗線（不經線核）
        val ev2Raw = noDots(bhOn, p.dot).orInPlace(can)
        // 亮記號：σ1.5 tophat ＞ brightTh 且線核平均 > 7.2
        val bright = run {
            val gb15 = Cv.gaussQ16(g, 1.5)
            val op = Cv.morphGrayGather(Cv.morphGrayGather(gb15, ell11, wantMax = false), ell11, wantMax = true)
            for (i in 0 until n) op.data[i] = gb15.data[i] - op.data[i]
            val cand = Mask(w, h, BooleanArray(n) { op.data[it] > p.brightTh * Q })
            noDots(lineMeanGt(op, off11, p.brightLrNum * qv, p.brightLrDen.toLong(), cand), p.dotBright)
        }
        // 閃光（C）：亮記號裡四周 sparkIso px 沒有暗（< 128）、沒有細暗線，外接框長邊在 sparkMin–sparkMax（×sH）的連通塊
        val spark = if (p.fxSparks) sparks(g, bright, ink, sH, p.fx) else null
        val toneE = Cv.canny(Cv.gaussQ16(g, 4.0).data, w, h, 16, p.canny4Lo, p.canny4Hi)
        if (spark != null) {
            val sd = dil(spark, p.fx.sparkDil)
            for (i in 0 until n) if (sd.data[i]) toneE.data[i] = false
        }
        val light = run {
            val gb5 = Cv.gaussQ16(g, 5.0).data
            val t = p.lightTh * Q
            Ring.packBits(Mask(w, h, BooleanArray(n) { gb5[it] >= t }))
        }
        // 調子邊緣：中值 7 → σ3（Q16）→ 3×3 Sobel（REFLECT_101）平方和 > 門檻²
        val tone = run {
            val gm = Cv.gaussQ16(Cv.medianBlur8(g, 7), 3.0).data
            val tg = p.toneGrad / sH * 8.0 * Q
            val tg2 = tg * tg
            val out = Mask(w, h)
            for (y in 0 until h) {
                val ym = (if (y == 0) Cv.reflect101(-1, h) else y - 1) * w
                val y0 = y * w
                val yp = (if (y == h - 1) Cv.reflect101(h, h) else y + 1) * w
                for (x in 0 until w) {
                    val xm = if (x == 0) Cv.reflect101(-1, w) else x - 1
                    val xp = if (x == w - 1) Cv.reflect101(w, w) else x + 1
                    val dx = (gm[ym + xp].toLong() + 2L * gm[y0 + xp] + gm[yp + xp]) - (gm[ym + xm].toLong() + 2L * gm[y0 + xm] + gm[yp + xm])
                    val dy = (gm[yp + xm].toLong() + 2L * gm[yp + x] + gm[yp + xp]) - (gm[ym + xm].toLong() + 2L * gm[ym + x] + gm[ym + xp])
                    out.data[y0 + x] = (dx * dx + dy * dy).toDouble() > tg2
                }
            }
            out
        }
        val x3 = unpack(x3B, w, h)
        val e = minArea(Mask(w, h, BooleanArray(n) {
            (ink.data[it] || toneE.data[it] || (bright.data[it] && (spark == null || !spark.data[it]))) && !x3.data[it]
        }), p.evMinArea)
        for (i in 0 until n) if (x3.data[i]) ev2Raw.data[i] = false
        // 人物旁的淡線（複核 1）：人物模型漏掉的手、筆多半是淡的點狀線，不算物件證據 E ⇒ 亮背景區塗黑會蓋過去。從離人物 pfTouch px
        // 內的淡線（[ev2Raw]＝細暗線不經線核｜σ2 Canny，扣 X3）起，沿外擴 pfBridge 的淡線測地長 pfReach px，長到的再外擴 pfHalo
        val phalo: LongArray? = if (!p.personFaint) null else run {
            val fb = dil(ev2Raw, p.pfBridge)
            val cmd = dil(charMask or charRaw, p.pfTouch)
            var any = false
            for (i in 0 until n) { cmd.data[i] = cmd.data[i] && fb.data[i]; if (cmd.data[i]) any = true }
            if (!any) null else {
                val gf = growCropped(cmd, fb, p.pfReach, bfs = null)
                if (p.pfShape) {
                    // 淡小記號（細暗線不經線核｜σ2 Canny 在去網點之前、扣 X3）：碰到版本 3 外圈、外接框長邊 < dot 的也留 pfMark
                    val cr = canRawB
                    pfShape(gf, xB, if (cr != null) { i: Int -> (bhOn.data[i] || has(cr, i)) && !x3.data[i] } else null, p,
                        if (keepDiag) diag else null)
                } else {
                    Ring.packBits(dil(gf, p.pfHalo))
                }
            }
        }
        canRawB = null
        val ev2 = minArea(ev2Raw, p.evMinArea)
        // 長線帶
        val longz = run {
            for (i in 0 until n) if (x3.data[i]) ink.data[i] = false     // ink 已存成位元，這裡就地改成 ink ∧ ¬X3
            val cc = Cv.ccStats(ink, 8)
            val keep = BooleanArray(cc.n) { it > 0 && max(cc.width[it], cc.height[it]) >= p.longLen }
            if (p.fxSparks) straightLines(cc, w, keep, p.fx.longStraight)
            dil(Mask(w, h, BooleanArray(n) { keep[cc.labels[it]] }), p.longR)
        }
        if (keepDiag) {
            diag!!["obj_ink"] = unpack(inkB, w, h); diag["obj_bright"] = bright; diag["obj_can"] = can; diag["obj_can4"] = toneE
            diag["obj_tone"] = tone; diag["obj_light"] = unpack(light, w, h); diag["obj_E"] = e; diag["obj_Ev2"] = ev2
            diag["obj_longz"] = longz; diag["obj_X3"] = x3; diag["obj_spark"] = spark ?: Mask(w, h)
            diag["obj_fx_info"] = fxInfo!!
            diag["obj_phalo"] = if (phalo != null) unpack(phalo, w, h) else Mask(w, h)
            if (fx != null) {
                diag["obj_fxe"] = unpack(fx.fxe, w, h); diag["obj_fxe_t"] = unpack(fx.fxeT, w, h)
                diag["obj_terr"] = unpack(fx.terr, w, h)
                diag["obj_memline"] = Mask(w, h).also { m -> for (i in fx.memIdx) m.data[i] = true }
            }
        }
        return Context(
            w, h, xB, x3B, Ring.packBits(e), Ring.packBits(ev2), inkB,
            Ring.packBits(bright), Ring.packBits(tone), Ring.packBits(toneE), light, Ring.packBits(longz),
            spark?.let { Ring.packBits(it) }, fx, phalo, bhOnB,
            if (p.inpaintIslands && inpaint != null && inpaint.any()) Ring.packBits(inpaint) else null,
        )
    }

    /**
     * 閃光＝研究端 context 的 `spark`：[bright] 的 8 連通塊（逐塊 BFS）裡，沒有一點落在「(灰階 < 128 ∨ 細暗線) 橢圓外擴
     * [EffectLineParams.sparkIso]」、外接框長邊在 [EffectLineParams.sparkMin]·sH 到 [EffectLineParams.sparkMax]·sH 的。沒有＝null。
     */
    private fun sparks(g: Gray, bright: Mask, ink: Mask, sH: Double, p: EffectLineParams): Mask? {
        val w = g.w
        val h = g.h
        val near = dil(Mask(w, h, BooleanArray(w * h) { g.data[it] < 128 || ink.data[it] }), p.sparkIso)
        val out = Mask(w, h)
        val seen = BooleanArray(w * h)
        var q = IntArray(64)
        var any = false
        for (s0 in 0 until w * h) {
            if (!bright.data[s0] || seen[s0]) continue
            var qe = 0
            q[qe++] = s0
            seen[s0] = true
            var qs = 0
            var bad = false
            var x0 = s0 % w; var x1 = x0; var y0 = s0 / w; var y1 = y0
            while (qs < qe) {
                val i = q[qs++]
                if (near.data[i]) bad = true
                val x = i % w
                val y = i / w
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val j = yy * w + xx
                    if (bright.data[j] && !seen[j]) {
                        seen[j] = true
                        if (qe == q.size) q = q.copyOf(q.size * 2)
                        q[qe++] = j
                    }
                }
            }
            val ext = max(x1 - x0 + 1, y1 - y0 + 1)
            if (!bad && ext >= p.sparkMin * sH && ext <= p.sparkMax * sH) {
                for (k in 0 until qe) out.data[q[k]] = true
                any = true
            }
        }
        return if (any) out else null
    }

    /**
     * 尺畫的直線不給長線帶＝研究端 `_straight`：[keep] 裡的連通塊，像素座標（減外接框左上）的整數動差 → 共變異（double）→
     * 次特徵值 λ₂；√max(λ₂, 0) ≤ [lim] 的從 [keep] 拿掉。
     */
    private fun straightLines(cc: CC, pw: Int, keep: BooleanArray, lim: Double) {
        if (keep.none { it }) return
        val n = cc.n
        val sx = LongArray(n); val sy = LongArray(n); val sxx = LongArray(n); val syy = LongArray(n); val sxy = LongArray(n)
        for (i in cc.labels.indices) {
            val l = cc.labels[i]
            if (l == 0 || !keep[l]) continue
            val lx = (i % pw - cc.left[l]).toLong()
            val ly = (i / pw - cc.top[l]).toLong()
            sx[l] += lx; sy[l] += ly; sxx[l] += lx * lx; syy[l] += ly * ly; sxy[l] += lx * ly
        }
        for (l in 1 until n) {
            if (!keep[l]) continue
            val c = cc.area[l].toDouble()
            val mx = sx[l].toDouble() / c
            val my = sy[l].toDouble() / c
            val cxx = sxx[l].toDouble() / c - mx * mx
            val cyy = syy[l].toDouble() / c - my * my
            val cxy = sxy[l].toDouble() / c - mx * my
            val tr = cxx + cyy
            val l2 = tr / 2.0 - sqrt(max(tr * tr / 4.0 - (cxx * cyy - cxy * cxy), 0.0))
            if (sqrt(max(l2, 0.0)) <= lim) keep[l] = false
        }
    }

    /**
     * 規則版本 4 的淡線外圈（研究端 `_pf_shape`；[ObjectRuleParams.pfShape]）：[gf]＝長到的淡線（已外擴 pfBridge）。回傳外圈
     * （1 bit/px），⊆ 版本 3 的外圈 dil(gf, pfHalo)。
     * - 逐個 8 連通塊（BFS）看是不是一條線：塊在外接框外擴 max(pfClose, pfHalo)＋1 的窗裡橢圓閉合 pfClose（窗外照 cv2 的
     *   侵蝕邊界當前景），閉合後面積 ×100 ≤ 原面積 ×pfLinePct ＝線，外擴 pfMargin；不是線外擴 pfHalo。
     * - 洞：gf 整體在外接框外擴 2·pfClose＋1 的窗裡閉合（＝整頁閉合）成 Gc；「Gc ∪ 交代過」的補集（8 連通，研究端的
     *   connectedComponentsWithStats(free, 4) 其實是 8 連通：第二個位置參數是 labels）裡碰到 Gc⊕3×3 的塊，
     *   面積 ×1920² ≤ pfHole ×clamp(頁高, 960, 3840)² 或不碰「交代過⊕3×3」的，∩ 版本 3 外圈也不塗。從碰到 Gc⊕3×3 的像素逐塊
     *   BFS（整頁走、只記落在版本 3 外圈的像素），不配整頁標號。
     * - 淡小記號：[mark]（像素 → 是不是淡記號候選）的 8 連通塊（BFS，整頁走）碰到版本 3 外圈、外接框長邊 < dot 的，橢圓外擴
     *   pfMark ∩ 版本 3 外圈也不塗；null＝不做。
     * [diag]（parity 除錯，context 的 diag["obj"]＝true 才傳）：`obj_gf`（長到的淡線）、`obj_gc`（閉合後）、`obj_pf_lines`（逐塊外擴之後）、
     * `obj_pf_holes`（加洞之後）、`obj_X`（交代過）。
     */
    private fun pfShape(
        gf: Mask, xB: LongArray, mark: ((Int) -> Boolean)?, p: ObjectRuleParams, diag: MutableMap<String, Any>? = null,
    ): LongArray {
        val w = gf.w
        val h = gf.h
        val n = w * h
        val old = dil(gf, p.pfHalo)
        val out = Mask(w, h)
        val pc = p.pfClose
        val kc = Cv.ellipse(2 * pc + 1)
        val seen = LongArray((n + 63) ushr 6)
        var q = IntArray(256)
        var gx0 = w; var gy0 = h; var gx1 = -1; var gy1 = -1
        // 逐塊：一條線只留 pfMargin
        for (s0 in 0 until n) {
            if (!gf.data[s0] || Ring.has(seen, s0)) continue
            var qe = 0
            q[qe++] = s0
            Ring.set(seen, s0)
            var qs = 0
            var bx0 = s0 % w; var bx1 = bx0; var by0 = s0 / w; var by1 = by0
            while (qs < qe) {
                val i = q[qs++]
                val x = i % w
                val y = i / w
                if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; if (y < by0) by0 = y; if (y > by1) by1 = y
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val j = yy * w + xx
                    if (gf.data[j] && !Ring.has(seen, j)) {
                        Ring.set(seen, j)
                        if (qe == q.size) q = q.copyOf(q.size * 2)
                        q[qe++] = j
                    }
                }
            }
            gx0 = min(gx0, bx0); gy0 = min(gy0, by0); gx1 = max(gx1, bx1); gy1 = max(gy1, by1)
            val pd = max(pc, p.pfHalo) + 1
            val x0 = max(0, bx0 - pd); val y0 = max(0, by0 - pd); val x1 = min(w, bx1 + 1 + pd); val y1 = min(h, by1 + 1 + pd)
            val sw = x1 - x0
            val c = Mask(sw, y1 - y0)
            for (k in 0 until qe) { val i = q[k]; c.data[(i / w - y0) * sw + i % w - x0] = true }
            val ca = if (pc > 0) Cv.closePacked(c, kc).count() else qe
            val r = if (100L * ca <= p.pfLinePct.toLong() * qe) p.pfMargin else p.pfHalo
            val d = dil(c, r)
            for (yy in y0 until y1) {
                val b = (yy - y0) * sw - x0
                for (xx in x0 until x1) if (d.data[b + xx]) out.data[yy * w + xx] = true
            }
        }
        if (gx1 < 0) return Ring.packBits(out)
        if (diag != null) { diag["obj_gf"] = gf; diag["obj_pf_lines"] = out.copy(); diag["obj_X"] = unpack(xB, w, h) }
        if (p.pfHole > 0) {
            // Gc＝gf 在窗裡閉合（窗內離 gf ≤ pfClose 的像素侵蝕看的鄰居都在窗內 ⇒ 與整頁閉合逐像素相同）
            val qd = 2 * pc + 1
            val wx0 = max(0, gx0 - qd); val wy0 = max(0, gy0 - qd); val wx1 = min(w, gx1 + 1 + qd); val wy1 = min(h, gy1 + 1 + qd)
            val ww = wx1 - wx0; val wh = wy1 - wy0
            val gw = Mask(ww, wh)
            for (yy in 0 until wh) for (xx in 0 until ww) gw.data[yy * ww + xx] = gf.data[(yy + wy0) * w + xx + wx0]
            val gc = if (pc > 0) Cv.closePacked(gw, kc) else gw
            if (diag != null) {
                val gcf = Mask(w, h)
                for (yy in 0 until wh) for (xx in 0 until ww) if (gc.data[yy * ww + xx]) gcf.data[(yy + wy0) * w + xx + wx0] = true
                diag["obj_gc"] = gcf
            }
            // Gc⊕3×3：窗外擴 1（Gc 在窗外是 0）
            val pw = ww + 2
            val gcd = Mask(pw, wh + 2)
            for (yy in 0 until wh) for (xx in 0 until ww) {
                if (!gc.data[yy * ww + xx]) continue
                for (dy in 0..2) for (dx in 0..2) gcd.data[(yy + dy) * pw + xx + dx] = true
            }
            fun gcAt(x: Int, y: Int): Boolean = x in wx0 until wx1 && y in wy0 until wy1 && gc.data[(y - wy0) * ww + x - wx0]
            fun free(i: Int): Boolean = !has(xB, i) && !gcAt(i % w, i / w)
            fun nearX(x: Int, y: Int): Boolean {
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) if (has(xB, yy * w + xx)) return true
                return false
            }
            val hc = min(3840, max(960, h)).toLong()
            val holeLim = p.pfHole.toLong() * hc * hc
            val seenF = LongArray((n + 63) ushr 6)
            var keepPx = IntArray(256)
            for (py in 0 until wh + 2) for (px in 0 until pw) {
                if (!gcd.data[py * pw + px]) continue
                val sx = px + wx0 - 1
                val sy = py + wy0 - 1
                if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue
                val s0 = sy * w + sx
                if (Ring.has(seenF, s0) || !free(s0)) continue
                // 補集的一塊（8 連通，整頁走）：面積、碰不碰交代過⊕3×3、落在版本 3 外圈的像素
                var qe = 0
                q[qe++] = s0
                Ring.set(seenF, s0)
                var qs = 0
                var area = 0L
                var touchX = false
                var nk = 0
                // 佇列當環形用：只留還沒處理的（大塊可以接近整頁）
                var cap = q.size
                while (qs != qe) {
                    val i = q[qs]
                    qs = if (qs + 1 == cap) 0 else qs + 1
                    area++
                    val x = i % w
                    val y = i / w
                    if (!touchX && nearX(x, y)) touchX = true
                    if (old.data[i]) {
                        if (nk == keepPx.size) keepPx = keepPx.copyOf(nk * 2)
                        keepPx[nk++] = i
                    }
                    for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                        val j = yy * w + xx
                        if (Ring.has(seenF, j) || !free(j)) continue
                        Ring.set(seenF, j)
                        val next = if (qe + 1 == cap) 0 else qe + 1
                        if (next == qs) {
                            // 環滿：攤平成 [qs..) 再加倍
                            val nq = IntArray(cap * 2)
                            var m = 0
                            var t = qs
                            while (t != qe) { nq[m++] = q[t]; t = if (t + 1 == cap) 0 else t + 1 }
                            q = nq; cap = nq.size; qs = 0; qe = m
                        }
                        q[qe] = j
                        qe = if (qe + 1 == cap) 0 else qe + 1
                    }
                }
                val small = area * (1920L * 1920L) <= holeLim
                if (small || !touchX) for (k in 0 until nk) out.data[keepPx[k]] = true
            }
        }
        if (diag != null) diag["obj_pf_holes"] = out.copy()
        if (mark != null) {
            // 淡小記號：碰到版本 3 外圈的候選塊（8 連通、整頁走），外接框長邊 < dot 的外擴 pfMark ∩ 版本 3 外圈
            val pd = p.pfHalo + p.dot + p.pfMark
            val ox0 = max(0, gx0 - pd); val oy0 = max(0, gy0 - pd); val ox1 = min(w, gx1 + 1 + pd); val oy1 = min(h, gy1 + 1 + pd)
            val ow = ox1 - ox0
            val mk = Mask(ow, oy1 - oy0)
            var anyMk = false
            val seenM = LongArray((n + 63) ushr 6)
            for (sy in max(0, gy0 - p.pfHalo)..min(h - 1, gy1 + p.pfHalo)) for (sx in max(0, gx0 - p.pfHalo)..min(w - 1, gx1 + p.pfHalo)) {
                val s0 = sy * w + sx
                if (!old.data[s0] || Ring.has(seenM, s0) || !mark(s0)) continue
                var qe = 0
                q[qe++] = s0
                Ring.set(seenM, s0)
                var qs = 0
                var bx0 = sx; var bx1 = sx; var by0 = sy; var by1 = sy
                while (qs < qe) {
                    val i = q[qs++]
                    val x = i % w
                    val y = i / w
                    if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; if (y < by0) by0 = y; if (y > by1) by1 = y
                    for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                        val j = yy * w + xx
                        if (!Ring.has(seenM, j) && mark(j)) {
                            Ring.set(seenM, j)
                            if (qe == q.size) q = q.copyOf(q.size * 2)
                            q[qe++] = j
                        }
                    }
                }
                if (max(bx1 - bx0 + 1, by1 - by0 + 1) >= p.dot) continue
                for (k in 0 until qe) { val i = q[k]; mk.data[(i / w - oy0) * ow + i % w - ox0] = true }
                anyMk = true
            }
            if (anyMk) {
                val md = dil(mk, p.pfMark)
                for (yy in oy0 until oy1) {
                    val b = (yy - oy0) * ow - ox0
                    for (xx in ox0 until ox1) { val i = yy * w + xx; if (md.data[b + xx] && old.data[i]) out.data[i] = true }
                }
            }
        }
        return Ring.packBits(out)
    }

    // ── 整數／確定性的小工具 ─────────────────────────────────────────────

    /**
     * M ∪ M 的洞（外框 4 連通到不了的背景）＝研究端 `fill_holes`（＝[Cv.holes] 的結果，堆疊按需長大：窗可能接近整頁）。
     */
    private fun fillHoles(m: Mask): Mask {
        val w = m.w
        val h = m.h
        val reached = BooleanArray(w * h)
        var st = IntArray(256)
        var sp = 0
        fun push(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= w || y >= h) return
            val i = y * w + x
            if (reached[i] || m.data[i]) return
            reached[i] = true
            if (sp == st.size) st = st.copyOf(st.size * 2)
            st[sp++] = i
        }
        for (x in 0 until w) { push(x, 0); push(x, h - 1) }
        for (y in 0 until h) { push(0, y); push(w - 1, y) }
        while (sp > 0) {
            val i = st[--sp]
            val x = i % w
            val y = i / w
            push(x - 1, y); push(x + 1, y); push(x, y - 1); push(x, y + 1)
        }
        return Mask(w, h, BooleanArray(w * h) { !reached[it] })
    }

    /** 窗 [x0,x1)×[y0,y1) 裡 [b] 的複本。 */
    private fun subBits(b: LongArray, pageW: Int, x0: Int, y0: Int, x1: Int, y1: Int): Mask {
        val sw = x1 - x0
        val out = Mask(sw, y1 - y0)
        for (y in y0 until y1) {
            val base = y * pageW
            for (x in x0 until x1) if (has(b, base + x)) out.data[(y - y0) * sw + x - x0] = true
        }
        return out
    }

    /** 5×5 chamfer（m 前景到最近背景）在外圍補 1 px 0 之後的最大值＝研究端 `chamfer5(np.pad(M, 1)).max()`。 */
    private fun thickness(m: Mask): Float {
        val pw = m.w + 2
        val pm = Mask(pw, m.h + 2)
        for (y in 0 until m.h) for (x in 0 until m.w) pm.data[(y + 1) * pw + x + 1] = m.data[y * m.w + x]
        val t = Cv.chamfer5Padded(pm)
        val tw = pw + 4
        var mx = 0
        for (y in 0 until m.h + 2) for (x in 0 until pw) { val v = t[(y + 2) * tw + x + 2]; if (v > mx) mx = v }
        return mx.toFloat() * (1f / 65536f)
    }

    /**
     * `np.sum`（float64、一維連續）：numpy 的歸約按緩衝區大小（預設 8192 個元素）分段，每段成對加總（[Cv.npSum]），段與段依序相加。
     * [Cv.npSum] 只等於 8192 個以內的整段；區的取樣點最多 4 萬個，要分段。
     */
    internal fun npSumChunked(a: DoubleArray): Double {
        val n = a.size
        if (n == 0) return 0.0
        var res = Cv.npSum(a, 0, min(n, 8192))
        var off = 8192
        while (off < n) { res += Cv.npSum(a, off, min(8192, n - off)); off += 8192 }
        return res
    }

    /** 6×6 線性方程：部分選主元高斯消去（固定順序；主元為 0 的那一維係數取 0）＝研究端 `solve6`。 */
    internal fun solve6(m: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val a = Array(6) { i -> DoubleArray(7) { j -> if (j < 6) m[i][j] else b[i] } }
        for (col in 0 until 6) {
            var piv = col
            for (r in col + 1 until 6) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (piv != col) { val t = a[col]; a[col] = a[piv]; a[piv] = t }
            val pv = a[col][col]
            if (pv == 0.0) continue
            for (r in col + 1 until 6) {
                val f = a[r][col] / pv
                if (f != 0.0) for (k in col until 7) a[r][k] = a[r][k] - f * a[col][k]
            }
        }
        val c = DoubleArray(6)
        for (col in 5 downTo 0) {
            val pv = a[col][col]
            if (pv == 0.0) { c[col] = 0.0; continue }
            var s = a[col][6]
            for (k in col + 1 until 6) s = s - a[col][k] * c[k]
            c[col] = s / pv
        }
        return c
    }

    /**
     * 亮度（Q16）對二次曲面的殘差 RMS（灰階）＝研究端 `quadfit`：點依掃描序、步長 n/20000 取樣；座標減平均（numpy 成對加總）除 500；
     * 正規方程 → [solve6]；殘差平方和同樣（numpy 的分段成對加總，[npSumChunked]）。
     */
    internal fun quadfit(gq: IntArray, ys: IntArray, xs: IntArray, n0: Int, s: Int = max(1, n0 / 20000)): Double {
        if (n0 < 50) return 0.0
        val n = (n0 + s - 1) / s
        // [gq]／[ys]／[xs] 已是取樣後的 n 點（第 k 點＝掃描序第 k·s 點）
        val gv = DoubleArray(n) { gq[it] / 65536.0 }
        val y = DoubleArray(n) { ys[it].toDouble() }
        val x = DoubleArray(n) { xs[it].toDouble() }
        val ym = npSumChunked(y) / n
        val xm = npSumChunked(x) / n
        for (i in 0 until n) { y[i] = (y[i] - ym) / 500.0; x[i] = (x[i] - xm) / 500.0 }
        val cols = arrayOf(DoubleArray(n) { 1.0 }, x, y, DoubleArray(n) { x[it] * x[it] }, DoubleArray(n) { x[it] * y[it] },
            DoubleArray(n) { y[it] * y[it] })
        val m = Array(6) { DoubleArray(6) }
        val tmp = DoubleArray(n)
        for (i in 0 until 6) for (j in i until 6) {
            for (k in 0 until n) tmp[k] = cols[i][k] * cols[j][k]
            val v = npSumChunked(tmp)
            m[i][j] = v; m[j][i] = v
        }
        val b = DoubleArray(6) { i -> for (k in 0 until n) tmp[k] = cols[i][k] * gv[k]; npSumChunked(tmp) }
        val c = solve6(m, b)
        for (k in 0 until n) {
            var pr = c[0] + c[1] * x[k]
            pr = pr + c[2] * y[k]
            pr = pr + c[3] * cols[3][k]
            pr = pr + c[4] * cols[4][k]
            pr = pr + c[5] * cols[5][k]
            val r = gv[k] - pr
            tmp[k] = r * r
        }
        return sqrt(npSumChunked(tmp) / n)
    }

    /** 字筆畫亮度查表＝研究端 `text_lut`：BG ＋ clip((((255−g)/255)^1.4 − 0.35)/0.65, 0, 1) ×(INK − BG)，向下取整。 */
    internal fun textLut(bg: Int, ink: Int): FloatArray = FloatArray(256) {
        val av = ((255.0 - it) / 255.0).pow(1.4)
        val a3 = min(1.0, max(0.0, (av - 0.35) / 0.65))
        floor(bg + a3 * (ink - bg)).toFloat()
    }

    /**
     * 效果線區（inv＝true：BG ＋ ((255−g)/255)^1.4 ×(top − BG)）與閃光（inv＝false：BG ＋ (g/255)^1.4 ×(top − BG)）的查表＝研究端
     * `ink_lut`，向下取整。
     */
    internal fun inkLut(bg: Int, top: Int, inv: Boolean): FloatArray = FloatArray(256) {
        val a = if (inv) ((255.0 - it) / 255.0).pow(1.4) else (it / 255.0).pow(1.4)
        floor(bg + a * (top - bg)).toFloat()
    }

    /** 「已經黑」＝研究端 `blackish`：已經塗成 bg 的像素 ∪ 原圖灰階 ≤ [ObjectRuleParams.darkG]。 */
    fun blackish(out: FImg, g: Gray, p: ObjectRuleParams, np: NightReadParams): Mask {
        val bgf = np.bg.toFloat()
        return Mask(g.w, g.h, BooleanArray(g.data.size) { out.data[it] == bgf || g.data[it] <= p.darkG })
    }

    // ── V：否決 A2 多塗的白 ─────────────────────────────────────────────

    /**
     * 研究端 `veto_blocks`：[extra]＝「更多」比只塗標準多塗黑的像素、[stdDark]＝只塗標準時「已經黑」的像素。回傳否決遮罩。
     * [bubble]＝修剪前的泡。多塗的連通塊（8 連通）逐塊 BFS；超區＝白的閉運算裡碰到這塊的 8 連通塊的聯集，從塊的像素沿閉運算後的
     * 白 BFS 出來（研究端用整頁標號；集合相同，這裡不配整頁標號）。
     */
    fun vetoBlocks(
        g: Gray, extra: Mask, stdDark: Mask, ctx: Context, charMask: Mask, charRaw: Mask, bubble: Mask, frame: Mask,
        regions: List<TextRegion>, p: ObjectRuleParams, diag: MutableMap<String, Any>?,
    ): Mask {
        val w = g.w
        val h = g.h
        val n = w * h
        val out = Mask(w, h)
        if (!extra.any()) return out
        val sd = stdDark.andNot(extra)
        val wc = Cv.closePacked(Mask(w, h, BooleanArray(n) { g.data[it] >= WHITE_TH && !sd.data[it] }), Cv.ellipse(2 * p.vetoRc + 1))
        run {
            val xs = dil(charMask or charRaw, 3).orInPlace(dil(bubble, 7)).orInPlace(dil(frame, 3))
            for (i in 0 until n) if (xs.data[i] || sd.data[i]) wc.data[i] = false
        }
        val sdd = dil(sd, 2)
        val info = if (diag != null) ArrayList<String>() else null
        val seen = BooleanArray(n)
        val seenS = BooleanArray(n)
        var px = IntArray(1024)
        var sq = IntArray(1024)
        for (start in 0 until n) {
            if (!extra.data[start] || seen[start]) continue
            // 多塗塊（8 連通）：BFS 收像素與外接框
            var np = 0
            px[np++] = start
            seen[start] = true
            var head = 0
            var mx0 = Int.MAX_VALUE; var my0 = Int.MAX_VALUE; var mx1 = Int.MIN_VALUE; var my1 = Int.MIN_VALUE
            while (head < np) {
                val i = px[head++]
                val x = i % w
                val y = i / w
                mx0 = min(mx0, x); my0 = min(my0, y); mx1 = max(mx1, x); my1 = max(my1, y)
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val j = yy * w + xx
                    if (extra.data[j] && !seen[j]) {
                        seen[j] = true
                        if (np == px.size) px = px.copyOf(px.size * 2)
                        px[np++] = j
                    }
                }
            }
            if (np < p.vetoMinArea) continue
            // 超區：從塊 ∩ 白閉運算的像素沿白閉運算（8 連通）長出去
            var ns = 0
            for (k in 0 until np) {
                val i = px[k]
                if (wc.data[i] && !seenS[i]) {
                    seenS[i] = true
                    if (ns == sq.size) sq = sq.copyOf(sq.size * 2)
                    sq[ns++] = i
                }
            }
            if (ns == 0) continue
            var qs = 0
            var bx0 = Int.MAX_VALUE; var by0 = Int.MAX_VALUE; var bx1 = Int.MIN_VALUE; var by1 = Int.MIN_VALUE
            while (qs < ns) {
                val i = sq[qs++]
                val x = i % w
                val y = i / w
                bx0 = min(bx0, x); by0 = min(by0, y); bx1 = max(bx1, x); by1 = max(by1, y)
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val j = yy * w + xx
                    if (wc.data[j] && !seenS[j]) {
                        seenS[j] = true
                        if (ns == sq.size) sq = sq.copyOf(sq.size * 2)
                        sq[ns++] = j
                    }
                }
            }
            val sa = ns
            val pd = p.vetoEvDil + 1
            val x0 = max(0, bx0 - pd); val y0 = max(0, by0 - pd); val x1 = min(w, bx1 + 1 + pd); val y1 = min(h, by1 + 1 + pd)
            val sw = x1 - x0
            val sm = Mask(sw, y1 - y0)
            for (k in 0 until ns) { val i = sq[k]; sm.data[(i / w - y0) * sw + i % w - x0] = true; seenS[i] = false }
            val sdl = dil(sm, p.vetoEvDil)
            var e = 0
            for (yy in y0 until y1) for (xx in x0 until x1) {
                val i = yy * w + xx
                if (sdl.data[(yy - y0) * sw + xx - x0] && has(ctx.ev2, i) && !sdd.data[i]) e++
            }
            var v = 1000.0 * e > p.vetoEpm * max(1, sa)
            val mw = mx1 - mx0 + 1
            val mh = my1 - my0 + 1
            if (v) {
                // 字幕框／旁白框：塊（補洞後）裡有字區中心＝這片白是容器不是背景 ⇒ 不否決
                val mm = Mask(mw, mh)
                for (k in 0 until np) { val i = px[k]; mm.data[(i / w - my0) * mw + i % w - mx0] = true }
                val mf = fillHoles(mm)
                for (r in regions) {
                    val cx = Math.floorDiv(r.x0 + r.x1, 2) - mx0
                    val cy = Math.floorDiv(r.y0 + r.y1, 2) - my0
                    if (cx in 0 until mw && cy in 0 until mh && mf.data[cy * mw + cx]) { v = false; break }
                }
            }
            var e2 = -1
            var nmem = -1
            val fx = ctx.fx
            if (v && fx != null) {
                // 效果線不算物件（A）：超區證據扣掉效果墨（外擴 excl）後 ≤ 門檻、且碰到 ≥ nMem 條成員線 ⇒ 不否決
                e2 = 0
                val labs = HashSet<Int>()
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    if (!sdl.data[(yy - y0) * sw + xx - x0]) continue
                    val i = yy * w + xx
                    if (has(ctx.ev2, i) && !sdd.data[i] && !has(fx.fxeD, i)) e2++
                    if (has(fx.memline, i)) labs.add(fx.memLabel(i))
                }
                nmem = labs.size
                if (1000.0 * e2 <= p.vetoEpm * max(1, sa) && nmem >= p.fx.nMem) v = false
            }
            info?.add("[$mx0,$my0,$mw,$mh] area=$np sarea=$sa ev=$e evfx=$e2 nmem=$nmem veto=$v")
            if (v) for (k in 0 until np) out.data[px[k]] = true
        }
        if (diag != null) diag["obj_veto"] = info!!
        return out
    }

    // ── L：亮背景區塗黑 ─────────────────────────────────────────────────

    /**
     * 亮記號裡「孤立」的（四周 4 px 沒有暗（< 128）、沒有細暗線）個數＝研究端 `_isolated_marks`：[bm] 的 8 連通塊裡沒有一點落在
     * 「(暗 ∨ 細暗線) 外擴 4」的。亮記號稀疏，逐塊 BFS（不配窗大小的標號）。
     */
    private fun isolatedMarks(bm: Mask, darkish: Mask): Int {
        val w = bm.w
        val h = bm.h
        val near = dil(darkish, 4)
        val seen = BooleanArray(w * h)
        var q = IntArray(64)
        var k = 0
        for (s0 in 0 until w * h) {
            if (!bm.data[s0] || seen[s0]) continue
            var qe = 0
            q[qe++] = s0
            seen[s0] = true
            var qs = 0
            var bad = false
            while (qs < qe) {
                val i = q[qs++]
                if (near.data[i]) bad = true
                val x = i % w
                val y = i / w
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val j = yy * w + xx
                    if (bm.data[j] && !seen[j]) {
                        seen[j] = true
                        if (qe == q.size) q = q.copyOf(q.size * 2)
                        q[qe++] = j
                    }
                }
            }
            if (!bad) k++
        }
        return k
    }

    /** 整數高斯（[Cv.gaussQ16]）在單一像素 (x, y) 的值（同一套整數運算：先橫後直、REFLECT_101、Q16 四捨五入）。 */
    internal fun gaussAt(g: Gray, k: IntArray, x: Int, y: Int): Int {
        val w = g.w
        val h = g.h
        val c = k.size / 2
        var acc = 0L
        for (j in 0 until k.size) {
            val kj = k[j]
            if (kj == 0) continue
            val r = Cv.reflect101(y + j - c, h) * w
            var hs = k[c] * g.data[r + x]
            for (t in 1..c) hs += k[c + t] * (g.data[r + Cv.reflect101(x - t, w)] + g.data[r + Cv.reflect101(x + t, w)])
            acc += kj.toLong() * hs
        }
        return ((acc + 32768L) shr 16).toInt()
    }

    /**
     * 研究端 `light_fill`：在貼紙層（含否決）之後的 [out] 上把無物件的亮背景塗黑（就地改）。[chroma] 可 null（當 0）；[bubble]＝修剪前的泡。
     * [darkOverride]＝「已經黑」的覆寫（函式層 parity 用：餵研究端那一邊的；null＝[blackish]）。[regions]＝字區外接框（字畫亮只限
     * 碰到字框的字塊，[ObjectRuleParams.textNeedsRegion]）。fill 生長完先扣人物旁的淡線外圈（[Context.phalo]）。
     */
    fun lightFill(
        out: FImg, g: Gray, ctx: Context, charMask: Mask, charRaw: Mask, bubble: Mask, frame: Mask, seg: Mask, chroma: Gray?,
        regions: List<TextRegion>, p: ObjectRuleParams, np: NightReadParams, diag: MutableMap<String, Any>?,
        darkOverride: Mask? = null,
    ) {
        val w = g.w
        val h = g.h
        val n = w * h
        // 記憶體：跨階段要留的遮罩存 1 bit/px；σ2.5 亮度用的時候才算、用完就丟（研究端整頁算一次，值相同）
        val darkB: LongArray
        val l0B: LongArray
        run {
            val dark = darkOverride ?: blackish(out, g, p, np)
            if (diag != null && diag["obj"] == true) diag["obj_dark"] = dark
            darkB = Ring.packBits(dark)
            val l0 = Mask(w, h, BooleanArray(n) { has(ctx.light, it) && !has(ctx.x, it) && !dark.data[it] })
            l0B = Ring.packBits(l0)
        }
        val fx = ctx.fx
        // 效果線場的標號：地盤被格框線與已經黑（格溝）切開的連通塊（一致性以「格內的一片場」為單位）
        val tcomp = if (fx == null) null else fieldLabels(fx, unpack(darkB, w, h), frame, w, h)
        val rp = regionsPassed(g, ctx, Cv.openPacked(unpack(l0B, w, h), Cv.ellipse(5)), chroma, p, tcomp, diag)
        val passed = rp.passed
        // 效果線區（A）：在核心塗法之前另走一條；之後「已經黑」加上它塗到 ≤ 40 的像素（原型重算成品 ≤ 40；查表後確定）
        val fxpf = rp.fxpf
        val fxp = if (fx != null && fxpf != null && fxpf.any()) fxPaint(out, g, ctx, fx, fxpf, unpack(darkB, w, h), p, np) else null
        if (diag != null) diag["obj_fxpaint"] = fxp ?: Mask(w, h)
        if (fxp != null) {
            val lut = inkLut(np.bg, p.fx.lineV, true)
            for (i in 0 until n) if (fxp.data[i] && lut[g.data[i]] <= 40f) Ring.set(darkB, i)
        }
        if (!passed.any()) return
        val passedB = Ring.packBits(passed)
        val b: Mask
        val core: Mask
        val dEvB: LongArray
        run {
            val ev = Mask(w, h, BooleanArray(n) { has(ctx.e, it) || (has(ctx.toneE, it) && !has(ctx.x3, it)) })
            core = run {
                val t = Cv.chamfer5Padded(ev.not())
                val r = p.rLoc * Q
                val sw = w + 4
                val c = passed
                for (y in 0 until h) for (x in 0 until w) {
                    val i = y * w + x
                    c.data[i] = c.data[i] && t[(y + 2) * sw + x + 2] > r && !has(ctx.longz, i)
                }
                c
            }
            val dEv = dil(ev, 1)
            dEvB = Ring.packBits(dEv)
            val sealed = Cv.closePacked(dEv, Cv.ellipse(2 * p.seal + 1))
            b = Mask(w, h, BooleanArray(n) { has(passedB, it) && !sealed.data[it] })
        }
        var fill = run {
            val ccB = Cv.ccStats(b, 8)
            val cnt = IntArray(ccB.n)
            for (i in 0 until n) if (core.data[i]) cnt[ccB.labels[i]]++
            val lim = p.coreMin * h * w
            val keep = BooleanArray(ccB.n) { it > 0 && cnt[it] >= lim }
            for (i in 0 until n) core.data[i] = core.data[i] && keep[ccB.labels[i]]
            core
        }
        fill = growCropped(fill, b, p.rLoc + 6)
        fill = growCropped(fill, Mask(w, h, BooleanArray(n) { has(passedB, it) && !has(dEvB, it) }), p.seal + 3)
        val ph = ctx.phalo
        if (ph != null) for (i in 0 until n) if (has(ph, i)) fill.data[i] = false     // 人物旁的淡線外圈不塗（複核 1）
        val dark = unpack(darkB, w, h)
        if (fill.any()) fill = dropContext(g, fill, l0B, dark, ctx, charMask, charRaw, bubble, frame, p, diag)
        if (fill.any()) fill = dropIslands(g, fill, dark, charMask, charRaw, bubble, ctx.inpaint, p, diag)
        if (p.seam && fill.any()) {
            // 規則版本 4 縫補黑（q1）：兩階光影的交界被 σ4 Canny 當成調子邊，塗黑區在那裡留一條灰虛線 ⇒ 閉合補得起來、整條沒有
            // 細暗線／亮記號／谷、原圖都亮的細縫補黑
            val sm = seam(fill, g, ctx, dark, p)
            if (sm != null) fill.orInPlace(sm)
            if (diag != null) diag["obj_seam"] = sm ?: Mask(w, h)
        }
        if (diag != null) diag["obj_fill"] = fill
        if (!fill.any()) return
        val bgf = np.bg.toFloat()
        for (i in 0 until n) if (fill.data[i]) out.data[i] = bgf
        // 描亮邊：fill 內、緊鄰「細暗線／暗（< 100）／人物／格框線」（還沒黑的）的帶
        val r = Math.rint(0.0035 * min(h, w)).toInt().coerceIn(4, 7)
        // 描亮邊的來源 F（1 bit/px）：規則版本 4 沒有字框才畫亮的字旁要再用
        val fB = Ring.packBits(Mask(w, h, BooleanArray(n) {
            !fill.data[it] && (has(ctx.ink, it) || g.data[it] < 100 || charMask.data[it] || frame.data[it]) && !dark.data[it]
        }))
        val band = run {
            val bd = dil(unpack(fB, w, h), r)
            val segd = dil(seg, 2)
            for (i in 0 until n) bd.data[i] = bd.data[i] && fill.data[i] && !segd.data[i]
            bd
        }
        val sv = np.strokeObjV.toFloat()
        for (i in 0 until n) if (band.data[i]) out.data[i] = sv
        // 閃光（C）：塗黑區裡的閃光（外擴 1）畫成淺灰（亮度依原圖）
        if (ctx.spark != null) {
            val sp = dil(unpack(ctx.spark, w, h), 1)
            val lut = inkLut(np.bg, p.fx.sparkV, false)
            for (i in 0 until n) if (sp.data[i] && fill.data[i]) out.data[i] = max(out.data[i], lut[g.data[i]])
        }
        // 字（DBNet 筆畫）四周大多被塗黑的：筆畫畫亮、字縫填黑（同泡裡的字）
        val sd = dil(seg, 3)
        for (i in 0 until n) sd.data[i] = sd.data[i] && !bubble.data[i] && !charMask.data[i]
        val ccT = Cv.ccStats(sd, 8)
        var txt: Mask? = null
        if (ccT.n > 1) {
            val ringm = dil(sd, 6).andNot(sd)
            // 外圈像素歸給 6 px（橢圓 13）內標號最大的字塊（研究端整頁對標號做灰階膨脹）；只有外圈像素要，逐點掃核的 run
            val k13 = Cv.ellipse(13)
            val lb = ccT.labels
            val tot = IntArray(ccT.n)
            val hit = IntArray(ccT.n)
            for (i in 0 until n) {
                if (!ringm.data[i]) continue
                val x = i % w
                val y = i / w
                var mx = 0
                for (ky in 0 until k13.h) {
                    val yy = y + ky - k13.ay
                    if (yy < 0 || yy >= h || k13.runEnd[ky] <= k13.runStart[ky]) continue
                    val x0 = max(0, x + k13.runStart[ky] - k13.ax)
                    val x1 = min(w - 1, x + k13.runEnd[ky] - 1 - k13.ax)
                    val b = yy * w
                    for (xx in x0..x1) { val v = lb[b + xx]; if (v > mx) mx = v }
                }
                tot[mx]++
                if (fill.data[i]) hit[mx]++
            }
            val okt = BooleanArray(ccT.n) { it > 0 && 2 * hit[it] >= max(tot[it], 1) }
            // 規則版本 4（q2）：沒有字框、因為像粗墨筆畫才畫亮的字塊
            val sNew = BooleanArray(ccT.n)
            var anyNew = false
            if (p.textNeedsRegion) {
                // 複核 2：只畫亮碰到字框的字塊；只有字遮罩、沒有字框的（樹叢、星形記號被 DBNet 誤當字）不當字（留原樣）
                val hasR = BooleanArray(ccT.n)
                for (rg in regions) {
                    for (y in max(0, rg.y0) until min(h, max(0, rg.y1))) {
                        val b = y * w
                        for (x in max(0, rg.x0) until min(w, max(0, rg.x1))) { val v = lb[b + x]; if (v > 0) hasR[v] = true }
                    }
                }
                if (p.textStroke) {
                    // 四周大多被塗黑、沒有字框的字塊，像粗墨筆畫（多半很黑、輪廓平滑不細）的照樣畫亮（手寫字）；樹叢、星形、汗滴留原樣
                    val info = if (diag != null) ArrayList<String>() else null
                    for (k in 1 until ccT.n) {
                        if (!okt[k] || hasR[k]) continue
                        val ok = strokeLike(g, ccT, k, p, info)
                        if (ok) { hasR[k] = true; sNew[k] = true; anyNew = true }
                    }
                    if (diag != null) diag["obj_txtc"] = info!!
                }
                for (k in 1 until ccT.n) if (!hasR[k]) okt[k] = false
            }
            val t = Mask(w, h, BooleanArray(n) { okt[ccT.labels[it]] })
            if (t.any()) {
                val lut = textLut(np.bg, np.ink)
                for (i in 0 until n) if (t.data[i]) out.data[i] = lut[g.data[i]]
                if (p.textStroke && p.tsBandClean && anyNew) {
                    // 沒有字框才畫亮的字旁、只由這些字的墨引起（離別的線／人物／有字框的字都遠）的描亮邊塗回 BG（有字框的字旁照版本 3）
                    val tn = Mask(w, h, BooleanArray(n) { sNew[ccT.labels[it]] })
                    val fNew = Mask(w, h, BooleanArray(n) { tn.data[it] && has(fB, it) })
                    val fOther = Mask(w, h, BooleanArray(n) { !tn.data[it] && has(fB, it) })
                    val dn = dil(fNew, r)
                    val dOther = dil(fOther, r)
                    for (i in 0 until n) if (band.data[i] && dn.data[i] && !dOther.data[i]) out.data[i] = bgf
                }
            }
            txt = t
        }
        if (diag != null) { diag["obj_band"] = band; diag["obj_txt"] = txt ?: Mask(w, h) }
    }

    /** 效果線場的 8 連通標號（研究端 light_fill 的 `tcomp`）：地盤 ∧ ¬[dark] ∧ ¬格框線⊕2，只在地盤外接框裡（標號掃描首見序）。 */
    private fun fieldLabels(fx: EffectLines.Field, dark: Mask, frame: Mask, w: Int, h: Int): FieldLabels {
        val x0 = fx.terrX0; val y0 = fx.terrY0
        val bw = fx.terrX1 - x0; val bh = fx.terrY1 - y0
        val fr = dil(frame, 2)
        val m = BooleanArray(bw * bh)
        for (yy in 0 until bh) for (xx in 0 until bw) {
            val i = (yy + y0) * w + xx + x0
            m[yy * bw + xx] = has(fx.terr, i) && !dark.data[i] && !fr.data[i]
        }
        val lab = IntArray(bw * bh)
        var nl = 0
        var q = IntArray(1024)
        for (s0 in 0 until bw * bh) {
            if (!m[s0] || lab[s0] != 0) continue
            nl++
            var qe = 0
            q[qe++] = s0
            lab[s0] = nl
            var qs = 0
            while (qs < qe) {
                val j = q[qs++]
                val x = j % bw
                val y = j / bw
                for (yy in max(0, y - 1)..min(bh - 1, y + 1)) for (xx in max(0, x - 1)..min(bw - 1, x + 1)) {
                    val k = yy * bw + xx
                    if (m[k] && lab[k] == 0) {
                        lab[k] = nl
                        if (qe == q.size) q = q.copyOf(q.size * 2)
                        q[qe++] = k
                    }
                }
            }
        }
        return FieldLabels(x0, y0, bw, bh, lab)
    }

    /**
     * 效果線區的塗法＝研究端 `_fx_paint`：區（補洞，[fxpf]）沿「效果墨外擴 exclT 或亮區」往外長最多 grow px，扣掉交代過、已經黑、
     * 殘量證據（非效果的細線與調子邊，連通塊 ≥ erMin px）橢圓外擴 halo；拿掉 < minPart px 的塊；剩下的依原圖墨度反著畫
     * （[inkLut]：紙白 → BG、墨 → 最亮 lineV）。就地改 [out]；回傳塗的範圍（沒有＝null）。
     * 只在 [fxpf] 外接框外擴 grow＋1 的窗裡做（長不出這個窗）；殘量證據的連通塊面積照整頁算（從窗外擴 halo 的像素起 BFS）。
     */
    private fun fxPaint(
        out: FImg, g: Gray, ctx: Context, fx: EffectLines.Field, fxpf: Mask, dark: Mask, p: ObjectRuleParams, np: NightReadParams,
    ): Mask? {
        val w = g.w
        val h = g.h
        val q = p.fx
        var fx0 = w; var fy0 = h; var fx1 = -1; var fy1 = -1
        for (y in 0 until h) for (x in 0 until w) if (fxpf.data[y * w + x]) {
            if (x < fx0) fx0 = x; if (x > fx1) fx1 = x; if (y < fy0) fy0 = y; if (y > fy1) fy1 = y
        }
        val pad = q.grow + 1
        val wx0 = max(0, fx0 - pad); val wy0 = max(0, fy0 - pad); val wx1 = min(w, fx1 + 1 + pad); val wy1 = min(h, fy1 + 1 + pad)
        // 殘量證據：窗外擴 halo 的範圍裡、所在連通塊（整頁 8 連通）面積 ≥ erMin 的
        val ex0 = max(0, wx0 - q.halo); val ey0 = max(0, wy0 - q.halo); val ex1 = min(w, wx1 + q.halo); val ey1 = min(h, wy1 + q.halo)
        val ew = ex1 - ex0; val eh = ey1 - ey0
        fun er(i: Int) = (has(ctx.e, i) || has(ctx.toneE, i)) && !has(fx.fxeT, i) && !has(ctx.x3, i)
        val erW = Mask(ew, eh)
        val seen = LongArray((w * h + 63) ushr 6)
        var qq = IntArray(256)
        for (yy in ey0 until ey1) for (xx in ex0 until ex1) {
            val s0 = yy * w + xx
            if (Ring.has(seen, s0) || !er(s0)) continue
            var qe = 0
            qq[qe++] = s0
            Ring.set(seen, s0)
            var qs = 0
            while (qs < qe) {
                val i = qq[qs++]
                val x = i % w
                val y = i / w
                for (y2 in max(0, y - 1)..min(h - 1, y + 1)) for (x2 in max(0, x - 1)..min(w - 1, x + 1)) {
                    val j = y2 * w + x2
                    if (!Ring.has(seen, j) && er(j)) {
                        Ring.set(seen, j)
                        if (qe == qq.size) qq = qq.copyOf(qq.size * 2)
                        qq[qe++] = j
                    }
                }
            }
            if (qe < q.erMin) continue
            for (k in 0 until qe) {
                val x = qq[k] % w
                val y = qq[k] / w
                if (x in ex0 until ex1 && y in ey0 until ey1) erW.data[(y - ey0) * ew + x - ex0] = true
            }
        }
        val halo = dil(erW, q.halo)
        val sw = wx1 - wx0; val sh = wy1 - wy0
        val allow = Mask(sw, sh)
        val seed = Mask(sw, sh)
        for (yy in 0 until sh) for (xx in 0 until sw) {
            val i = (yy + wy0) * w + xx + wx0
            val a = !has(ctx.x, i) && !dark.data[i] && !halo.data[(yy + wy0 - ey0) * ew + xx + wx0 - ex0] &&
                (has(fx.fxeT, i) || has(ctx.light, i))
            allow.data[yy * sw + xx] = a
            seed.data[yy * sw + xx] = a && fxpf.data[i]
        }
        val pw = minArea(growCropped(seed, allow, q.grow), q.minPart)
        if (!pw.any()) return null
        val lut = inkLut(np.bg, q.lineV, true)
        val pm = Mask(w, h)
        for (yy in 0 until sh) for (xx in 0 until sw) {
            if (!pw.data[yy * sw + xx]) continue
            val i = (yy + wy0) * w + xx + wx0
            pm.data[i] = true
            out.data[i] = lut[g.data[i]]
        }
        return pm
    }

    /**
     * 研究端 `_grow`（＝[Cv.geodesicGrow] step 4）裁到 [within] 的外接框做：長不出 within，框外的像素既不是種子也不會被
     * 留下，結果與整頁逐位元相同，暫存只有框大小。[bfs]＝false 用迭代版（預設）；null＝依成本選（長距離的淡線外圈用，兩種逐位元相同）。
     */
    private fun growCropped(seed: Mask, within: Mask, iters: Int, bfs: Boolean? = false): Mask {
        val w = seed.w
        val h = seed.h
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (within.data[y * w + x]) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        val out = Mask(w, h)
        if (x1 < 0) return out
        val sw = x1 - x0 + 1
        val sh = y1 - y0 + 1
        val cs = Mask(sw, sh)
        val cw = Mask(sw, sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val i = (y + y0) * w + x + x0
            cw.data[y * sw + x] = within.data[i]
            cs.data[y * sw + x] = seed.data[i] && within.data[i]
        }
        val gr = Cv.geodesicGrow(cs, cw, iters, step = 4, bfs = bfs)
        for (y in 0 until sh) for (x in 0 until sw) if (gr.data[y * sw + x]) out.data[(y + y0) * w + x + x0] = true
        return out
    }

    /** 效果線場的標號（[lightFill] 算：地盤 ∧ ¬已經黑 ∧ ¬格框線⊕2 的 8 連通塊；只在地盤外接框裡存）。 */
    private class FieldLabels(val x0: Int, val y0: Int, val bw: Int, val bh: Int, val lab: IntArray) {
        fun at(x: Int, y: Int): Int = if (x < x0 || y < y0 || x >= x0 + bw || y >= y0 + bh) 0 else lab[(y - y0) * bw + x - x0]
    }

    /** [regionsPassed] 的結果：過門的區、效果線區（補洞後，過了場一致性；沒有＝null）。 */
    private class Passed(val passed: Mask, val fxpf: Mask?)

    /**
     * 研究端 `region_rows`：L 的每個連通塊（≥ 整頁 [ObjectRuleParams.areaMin]）量特徵、判過門；回傳過門的區與效果線區。整頁標號
     * 只用來挑區與記種子，σ2.5 亮度在丟掉標號之後才算；每區的像素從種子沿 L（8 連通）在外接框裡灌回來（與標號相同）。
     * 整區門沒過、效果線場在（[Context.fx]）的區另判效果線區（研究端 `_fx_region`）；同一片場裡有一區沒過，整片的區都不算。
     */
    private fun regionsPassed(
        g: Gray, ctx: Context, l: Mask, chroma: Gray?, p: ObjectRuleParams, tcomp: FieldLabels?, diag: MutableMap<String, Any>?,
    ): Passed {
        val w = g.w
        val h = g.h
        val amin = p.areaMin * h * w
        // 挑區：(seed, left, top, width, height, area)
        val regs = ArrayList<IntArray>()
        run {
            val cc = Cv.ccStats(l, 8)
            val seed = IntArray(cc.n) { -1 }
            for (i in 0 until w * h) { val lb = cc.labels[i]; if (lb > 0 && seed[lb] < 0) seed[lb] = i }
            for (i in 1 until cc.n) if (cc.area[i] >= amin) regs.add(intArrayOf(seed[i], cc.left[i], cc.top[i], cc.width[i], cc.height[i], cc.area[i]))
        }
        val passed = Mask(w, h)
        val rows = if (diag != null) ArrayList<String>() else null
        val k25 = Cv.gaussW(2.5)
        val e5 = Cv.ellipse(5)
        val e7 = Cv.ellipse(7)
        val fx = ctx.fx
        val q = p.fx
        // 效果線：場標號 → 這片場裡的區都過了（false＝有一區沒過）；過了的效果線區（窗、補洞後的遮罩、場標號）
        val fieldOk = HashMap<Int, Boolean>()
        val fxRegs = ArrayList<Triple<IntArray, Mask, Set<Int>>>()
        for (rg in regs) {
            val x = rg[1]; val y = rg[2]; val bw = rg[3]; val bh = rg[4]
            val x0 = max(0, x - 8); val y0 = max(0, y - 8)
            val x1 = min(w, x + bw + 8); val y1 = min(h, y + bh + 8)
            val sw = x1 - x0; val sh = y1 - y0
            val m = floodWindow(l, rg[0], x0, y0, x1, y1)
            val x3 = subBits(ctx.x3, w, x0, y0, x1, y1)
            val mf = fillHoles(m)
            val mm = mf.andNot(x3)
            val me = Cv.erodePacked(mm, e5)
            val ae = max(1, me.count())
            var mi = Cv.erodePacked(m, e7)
            if (mi.count() < 200) mi = m
            val inkW = subBits(ctx.ink, w, x0, y0, x1, y1)
            val brW = subBits(ctx.bright, w, x0, y0, x1, y1)
            var nl = 0; var nc = 0; var nt = 0; var area = 0; var csum = 0L
            // 二次曲面只用掃描序每 s 點取一點（研究端 gv[::s]）：只對取到的點算 σ2.5 亮度
            val nMi = mi.count()
            val step = max(1, nMi / 20000)
            val ns = (nMi + step - 1) / step
            val gq = IntArray(ns); val ys = IntArray(ns); val xs = IntArray(ns)
            var k = 0
            var kk = 0
            for (yy in 0 until sh) for (xx in 0 until sw) {
                val j = yy * sw + xx
                val i = (yy + y0) * w + xx + x0
                if (me.data[j]) {
                    // 閃光（C）不算細線
                    if (inkW.data[j] || (brW.data[j] && !Ring.has(ctx.spark, i))) nl++
                    if (has(ctx.toneE, i)) nc++
                    if (has(ctx.tone, i)) nt++
                }
                if (m.data[j]) { area++; if (chroma != null) csum += chroma.data[i] }
                if (mi.data[j]) {
                    if (kk % step == 0) { gq[k] = gaussAt(g, k25, xx + x0, yy + y0); ys[k] = yy; xs[k] = xx; k++ }
                    kk++
                }
            }
            val fit = quadfit(gq, ys, xs, nMi, step)
            val marks = run {
                val bm = brW and mm
                val dk = Mask(sw, sh, BooleanArray(sw * sh) { g.data[(it / sw + y0) * w + it % sw + x0] < 128 || inkW.data[it] })
                isolatedMarks(bm, dk)
            }
            // 閃光（C）開著：孤立亮記號多不再整區留灰
            val okMarks = p.fxSparks || !(marks >= p.marks && 1e5 * marks >= p.marksDen * area)
            val chromaOk = csum <= p.chromaMax * area
            val okRest = 1000.0 * nl <= p.lineMax * ae && 1000.0 * nc <= p.can4Max * ae && fit <= p.fitMax &&
                1000.0 * nt <= p.toneMax * ae && chromaOk && okMarks
            val fxCand = fx != null && chromaOk && okMarks
            // 最大內切半徑只影響過不過門（與效果線區）：用不到就不算（窗大小的距離表）；除錯紀錄要的時候照算
            val thick = if (okRest || fxCand || rows != null) thickness(m) else 0f
            val ok = okRest && thick >= p.thickMin
            var fxNote = ""
            if (ok) {
                for (yy in 0 until sh) for (xx in 0 until sw) if (m.data[yy * sw + xx]) passed.data[(yy + y0) * w + xx + x0] = true
            } else if (fxCand && thick >= p.thickMin) {
                // 效果線區（研究端 `_fx_region`）
                var inkR = 0; var feR = 0
                val labs = HashSet<Int>()
                val pairs = HashSet<Long>()
                for (yy in 0 until sh) for (xx in 0 until sw) {
                    val j = yy * sw + xx
                    val i = (yy + y0) * w + xx + x0
                    if (me.data[j]) {
                        if (inkW.data[j]) inkR++
                        if (has(fx!!.fxe, i)) feR++
                    }
                    if (mf.data[j] && has(fx!!.memline, i)) {
                        val lb = fx.memLabel(i)
                        labs.add(lb)
                        pairs.add((lb.toLong() shl 32) or tcomp!!.at(xx + x0, yy + y0).toLong())
                    }
                }
                val cnt = HashMap<Int, Int>()
                for (pr in pairs) { val t = (pr and 0xffffffffL).toInt(); if (t > 0) cnt[t] = (cnt[t] ?: 0) + 1 }
                val fams = cnt.filterValues { it >= q.nMem }.keys
                val nmem = labs.size
                var fxa = false
                if (feR.toDouble() / max(1, inkR) >= q.agree && 1000.0 * (inkR - feR) <= q.resid * ae && nmem >= q.nMem) {
                    val fzt = subBits(fx!!.fxeT, w, x0, y0, x1, y1)
                    val me3 = me.andNot(fzt)
                    val ae3 = max(1, me3.count())
                    var nc3 = 0
                    for (yy in 0 until sh) for (xx in 0 until sw) {
                        if (me3.data[yy * sw + xx] && has(ctx.toneE, (yy + y0) * w + xx + x0)) nc3++
                    }
                    var mi2 = mi.andNot(fzt)
                    if (mi2.count() < 200) mi2 = mi
                    val nMi2 = mi2.count()
                    val step2 = max(1, nMi2 / 20000)
                    val ns2 = (nMi2 + step2 - 1) / step2
                    val gq2 = IntArray(ns2); val ys2 = IntArray(ns2); val xs2 = IntArray(ns2)
                    var k2 = 0
                    var kk2 = 0
                    for (yy in 0 until sh) for (xx in 0 until sw) {
                        if (!mi2.data[yy * sw + xx]) continue
                        if (kk2 % step2 == 0) { gq2[k2] = gaussAt(g, k25, xx + x0, yy + y0); ys2[k2] = yy; xs2[k2] = xx; k2++ }
                        kk2++
                    }
                    val fit2 = quadfit(gq2, ys2, xs2, nMi2, step2)
                    fxa = 1000.0 * nc3 <= p.can4Max * ae3 && fit2 <= p.fitMax
                    fxNote = " fxnc=$nc3 fxae=$ae3 fxfit=$fit2"
                }
                for (t in fams) fieldOk[t] = (fieldOk[t] ?: true) && fxa
                if (fxa) fxRegs.add(Triple(intArrayOf(x0, y0, sw, sh), mf, fams))
                fxNote = " fxink=$inkR fxfe=$feR fxnmem=$nmem fams=${fams.sorted()} fxa=$fxa$fxNote"
            }
            rows?.add("[$x,$y,$bw,$bh] area=${rg[5]} nl=$nl nc=$nc nt=$nt ae=$ae fit=$fit thick=$thick csum=$csum marks=$marks ok=$ok$fxNote")
        }
        if (diag != null) diag["obj_rows"] = rows!!
        if (fxRegs.isEmpty()) return Passed(passed, null)
        // 場一致性：同一片效果線場裡只要有一區沒過，整片場的區都不塗
        val fxpf = Mask(w, h)
        for ((win, mf, fams) in fxRegs) {
            if (fams.any { fieldOk[it] == false }) continue
            val x0 = win[0]; val y0 = win[1]; val sw = win[2]; val sh = win[3]
            for (yy in 0 until sh) for (xx in 0 until sw) if (mf.data[yy * sw + xx]) fxpf.data[(yy + y0) * w + xx + x0] = true
        }
        return Passed(passed, fxpf)
    }

    /** [l] 裡含 [seed] 的 8 連通塊，在窗 [x0,x1)×[y0,y1)（含整塊）裡的遮罩。 */
    private fun floodWindow(l: Mask, seed: Int, x0: Int, y0: Int, x1: Int, y1: Int): Mask {
        val w = l.w
        val sw = x1 - x0
        val sh = y1 - y0
        val m = Mask(sw, sh)
        var q = IntArray(256)
        var qe = 0
        val s0 = (seed / w - y0) * sw + seed % w - x0
        m.data[s0] = true
        q[qe++] = s0
        var qs = 0
        while (qs < qe) {
            val j = q[qs++]
            val x = j % sw
            val y = j / sw
            for (yy in max(0, y - 1)..min(sh - 1, y + 1)) for (xx in max(0, x - 1)..min(sw - 1, x + 1)) {
                val k = yy * sw + xx
                if (!m.data[k] && l.data[(yy + y0) * w + xx + x0]) {
                    m.data[k] = true
                    if (qe == q.size) {
                        // 佇列只留還沒處理的：前段已出列的搬掉
                        val rest = qe - qs
                        if (qs > 0 && rest < q.size / 2) { System.arraycopy(q, qs, q, 0, rest); qe = rest; qs = 0 } else q = q.copyOf(q.size * 2)
                    }
                    q[qe++] = k
                }
            }
        }
        return m
    }

    /**
     * 脈絡：白塊（σ2.5 中位亮度 ≥ [ObjectRuleParams.ctxWhite]）連同跨細線閉合的亮區算超區；超區證據 ‰ 高的塊拿掉。
     * 記憶體：先用整頁 σ2.5 亮度判完每塊白不白、丟掉，再做閉運算；超區沿閉運算後的亮區（8 連通）從塊的像素 BFS 出來（＝研究端
     * 「碰到的連通塊的聯集」），不配整頁標號。
     */
    private fun dropContext(
        g: Gray, fill: Mask, l0B: LongArray, dark: Mask, ctx: Context, charMask: Mask, charRaw: Mask, bubble: Mask, frame: Mask,
        p: ObjectRuleParams, diag: MutableMap<String, Any>?,
    ): Mask {
        val w = g.w
        val h = g.h
        val n = w * h
        val ccF = Cv.ccStats(fill, 8)
        val white = BooleanArray(ccF.n)
        run {
            val gb25 = Cv.gaussQ16(g, 2.5).data
            for (k in 1 until ccF.n) {
                val x = ccF.left[k]; val y = ccF.top[k]
                val vals = IntArray(ccF.area[k])
                var c = 0
                for (yy in y until y + ccF.height[k]) for (xx in x until x + ccF.width[k]) {
                    val i = yy * w + xx
                    if (ccF.labels[i] == k) vals[c++] = gb25[i]
                }
                vals.sort()
                val med2 = if (c % 2 == 1) 2L * vals[c / 2] else vals[c / 2 - 1].toLong() + vals[c / 2]
                white[k] = med2 >= 2L * p.ctxWhite * Q
            }
        }
        val drop = BooleanArray(ccF.n)
        val info = if (diag != null) ArrayList<String>() else null
        if (white.any { it }) {
            val sc = Cv.closePacked(Mask(w, h, BooleanArray(n) { has(l0B, it) || fill.data[it] }), Cv.ellipse(2 * p.vetoRc + 1))
            run {
                val xs = dil(charMask or charRaw, 3).orInPlace(dil(bubble, 7)).orInPlace(dil(frame, 3)).orInPlace(dark)
                for (i in 0 until n) if (xs.data[i]) sc.data[i] = false
            }
            val blk = dil(Mask(w, h, BooleanArray(n) { dark.data[it] && g.data[it] >= 128 }), 8)
            val seen = BooleanArray(n)
            var q = IntArray(1024)
            for (k in 1 until ccF.n) {
                if (!white[k]) continue
                val x = ccF.left[k]; val y = ccF.top[k]; val fw = ccF.width[k]; val fh = ccF.height[k]
                var qe = 0
                for (yy in y until y + fh) for (xx in x until x + fw) {
                    val i = yy * w + xx
                    if (ccF.labels[i] == k && sc.data[i] && !seen[i]) {
                        seen[i] = true
                        if (qe == q.size) q = q.copyOf(q.size * 2)
                        q[qe++] = i
                    }
                }
                if (qe == 0) continue
                var qs = 0
                var bx0 = Int.MAX_VALUE; var by0 = Int.MAX_VALUE; var bx1 = Int.MIN_VALUE; var by1 = Int.MIN_VALUE
                while (qs < qe) {
                    val i = q[qs++]
                    val cx = i % w
                    val cy = i / w
                    bx0 = min(bx0, cx); by0 = min(by0, cy); bx1 = max(bx1, cx); by1 = max(by1, cy)
                    for (yy in max(0, cy - 1)..min(h - 1, cy + 1)) for (xx in max(0, cx - 1)..min(w - 1, cx + 1)) {
                        val j = yy * w + xx
                        if (sc.data[j] && !seen[j]) {
                            seen[j] = true
                            if (qe == q.size) q = q.copyOf(q.size * 2)
                            q[qe++] = j
                        }
                    }
                }
                val sa = qe
                val x0 = max(0, bx0 - 3); val y0 = max(0, by0 - 3); val x1 = min(w, bx1 + 4); val y1 = min(h, by1 + 4)
                val sw = x1 - x0
                val ss = Mask(sw, y1 - y0)
                for (t in 0 until qe) { val i = q[t]; ss.data[(i / w - y0) * sw + i % w - x0] = true; seen[i] = false }
                val sdl = dil(ss, 2)
                var e = 0
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    val i = yy * w + xx
                    if (sdl.data[(yy - y0) * sw + xx - x0] && has(ctx.e, i) && !blk.data[i]) e++
                }
                info?.add("[$x,$y,$fw,$fh] area=${ccF.area[k]} sarea=$sa ev=$e")
                if (1000.0 * e > p.ctxEpm * max(1, sa)) drop[k] = true
            }
        }
        if (diag != null) diag["obj_ctx"] = info!!
        return Mask(w, h, BooleanArray(n) { fill.data[it] && !drop[ccF.labels[it]] })
    }

    /**
     * 孤島：小塊又 [ObjectRuleParams.islandTouch] px 內碰不到塗黑的（泡不算）；或外緣多半貼著人物遮罩；或（規則版本 4 q4，
     * [inpaint]＝譯後頁的去字遮罩）有 ≥ [ObjectRuleParams.islandInpPct] % 在去字區橢圓外擴 [ObjectRuleParams.islandInpD] 內
     * （窗外擴 islandTouch＋2 ≥ islandInpD：在窗裡外擴與整頁外擴在塊的像素上相同）。
     */
    private fun dropIslands(
        g: Gray, fill: Mask, dark: Mask, charMask: Mask, charRaw: Mask, bubble: Mask, inpaint: LongArray?, p: ObjectRuleParams,
        diag: MutableMap<String, Any>?,
    ): Mask {
        val w = g.w
        val h = g.h
        val n = w * h
        val ccF = Cv.ccStats(fill, 8)
        val drop = BooleanArray(ccF.n)
        val bd = dil(bubble, 8)
        val info = if (diag != null) ArrayList<String>() else null
        val lim = p.islandMax * h * w
        val r3 = Cv.rect(3, 3)
        for (k in 1 until ccF.n) {
            if (ccF.area[k] >= lim) continue
            val pd = p.islandTouch + 2
            val x0 = max(0, ccF.left[k] - pd); val y0 = max(0, ccF.top[k] - pd)
            val x1 = min(w, ccF.left[k] + ccF.width[k] + pd); val y1 = min(h, ccF.top[k] + ccF.height[k] + pd)
            val sw = x1 - x0; val sh = y1 - y0
            val ck = Mask(sw, sh)
            val blk = Mask(sw, sh)
            val cmk = Mask(sw, sh)
            for (yy in y0 until y1) for (xx in x0 until x1) {
                val i = yy * w + xx
                val j = (yy - y0) * sw + xx - x0
                ck.data[j] = ccF.labels[i] == k
                blk.data[j] = dark.data[i] && g.data[i] >= 128 && !bd.data[i]
                cmk.data[j] = charMask.data[i] || charRaw.data[i]
            }
            val ckd = dil(ck, p.islandTouch)
            var t = 0
            for (j in 0 until sw * sh) if (ckd.data[j] && blk.data[j]) t++
            val per = ck.andNot(Cv.erode(ck, r3))
            val cmd = dil(cmk, 4)
            var ph = 0
            var ps = 0
            for (j in 0 until sw * sh) if (per.data[j]) { ps++; if (cmd.data[j]) ph++ }
            ps = max(1, ps)
            var tin = -1
            if (inpaint != null) {
                // 規則版本 4（q4）：譯後頁的小塊有 ≥ islandInpPct % 離去字區 islandInpD px 內＝原文字旁、去字後才變乾淨的白
                val ind = dil(subBits(inpaint, w, x0, y0, x1, y1), p.islandInpD)
                tin = 0
                for (j in 0 until sw * sh) if (ck.data[j] && ind.data[j]) tin++
            }
            info?.add("[${ccF.left[k]},${ccF.top[k]},${ccF.width[k]},${ccF.height[k]}] area=${ccF.area[k]} touch=$t txt=$tin hug=$ph/$ps")
            if (t < p.islandTouchMin || ph > p.hugMax * ps || (tin >= 0 && tin.toLong() * 100 >= p.islandInpPct.toLong() * ccF.area[k])) {
                drop[k] = true
            }
        }
        if (diag != null) diag["obj_island"] = info!!
        return Mask(w, h, BooleanArray(n) { fill.data[it] && !drop[ccF.labels[it]] })
    }

    /**
     * 規則版本 4 縫補黑（q1；研究端 `_seam`）：塗黑區橢圓閉合 [ObjectRuleParams.seamR] 補得起來、不是塗黑區／交代過／已經黑／人物旁
     * 淡線外圈的像素，8 連通塊（BFS）裡只要有一點細暗線、亮記號、谷（σ2 blackhat > bhTh，[Context.bhOn]）、原圖 < seamG 或閃光
     * 外擴 seamSpark 就整塊不補。回傳要補的像素（沒有＝null）。
     */
    private fun seam(fill: Mask, g: Gray, ctx: Context, dark: Mask, p: ObjectRuleParams): Mask? {
        val w = g.w
        val h = g.h
        val n = w * h
        val c = Cv.closePacked(fill, Cv.ellipse(2 * p.seamR + 1))
        val ph = ctx.phalo
        var any = false
        for (i in 0 until n) {
            val v = c.data[i] && !fill.data[i] && !has(ctx.x, i) && !dark.data[i] && (ph == null || !has(ph, i))
            c.data[i] = v
            if (v) any = true
        }
        if (!any) return null
        val bhOn = ctx.bhOn ?: error("縫補黑要 Context.bhOn（context 時 seam 要開）")
        val sp = if (ctx.spark != null) Ring.packBits(dil(unpack(ctx.spark, w, h), p.seamSpark)) else null
        fun bad(i: Int): Boolean =
            has(ctx.ink, i) || has(ctx.bright, i) || has(bhOn, i) || g.data[i] < p.seamG || (sp != null && has(sp, i))
        val out = Mask(w, h)
        val seen = LongArray((n + 63) ushr 6)
        var q = IntArray(256)
        var found = false
        for (s0 in 0 until n) {
            if (!c.data[s0] || Ring.has(seen, s0)) continue
            var qe = 0
            q[qe++] = s0
            Ring.set(seen, s0)
            var qs = 0
            var isBad = false
            while (qs < qe) {
                val i = q[qs++]
                if (!isBad && bad(i)) isBad = true
                val x = i % w
                val y = i / w
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val j = yy * w + xx
                    if (c.data[j] && !Ring.has(seen, j)) {
                        Ring.set(seen, j)
                        if (qe == q.size) q = q.copyOf(q.size * 2)
                        q[qe++] = j
                    }
                }
            }
            if (isBad) continue
            for (k in 0 until qe) out.data[q[k]] = true
            found = true
        }
        return if (found) out else null
    }

    /**
     * 規則版本 4（q2；研究端 `_stroke_like`）：字塊 [k]（[cc] 的標號，外接框的窗）是不是「粗墨筆畫」：墨 D＝塊 ∩ 灰階 < tsInk、
     * ≥ tsMin px；(1) 多半很黑：#(D ∩ 灰階 ≤ tsG) ×2 > #D；(2) 輪廓平滑又不細：D（窗外補 2 px 0）做 3×3 方核開、再 3×3 方核閉
     * （影像外當 0），與 D 不同的 px ×100 ≤ D 的邊界 px（D 扣掉 3×3 侵蝕）×tsRough。
     */
    private fun strokeLike(g: Gray, cc: CC, k: Int, p: ObjectRuleParams, info: MutableList<String>?): Boolean {
        val w = g.w
        val x0 = cc.left[k]; val y0 = cc.top[k]; val bw = cc.width[k]; val bh = cc.height[k]
        val pw = bw + 4
        val ph = bh + 4
        val d = BooleanArray(pw * ph)
        var nD = 0
        var nk = 0
        for (yy in 0 until bh) for (xx in 0 until bw) {
            val i = (yy + y0) * w + xx + x0
            if (cc.labels[i] != k) continue
            val gv = g.data[i]
            if (gv >= p.tsInk) continue
            d[(yy + 2) * pw + xx + 2] = true
            nD++
            if (gv <= p.tsG) nk++
        }
        if (nD < p.tsMin) {
            info?.add("[$x0,$y0,$bw,$bh] nD=$nD stroke=false")
            return false
        }
        // 3×3 方核：影像（補過 2 px 的窗）外當 0
        fun morph(src: BooleanArray, dilate: Boolean): BooleanArray {
            val o = BooleanArray(pw * ph)
            for (y in 0 until ph) for (x in 0 until pw) {
                var v = !dilate
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    val yy = y + dy
                    val xx = x + dx
                    val s = yy in 0 until ph && xx in 0 until pw && src[yy * pw + xx]
                    if (dilate && s) { v = true; break@loop }
                    if (!dilate && !s) { v = false; break@loop }
                }
                o[y * pw + x] = v
            }
            return o
        }
        val ero = morph(d, dilate = false)
        var per = 0
        for (i in d.indices) if (d[i] && !ero[i]) per++
        val op = morph(ero, dilate = true)
        val cl = morph(morph(op, dilate = true), dilate = false)
        var chg = 0
        for (i in d.indices) if (cl[i] != d[i]) chg++
        val ok = 2 * nk > nD && 100L * chg <= p.tsRough.toLong() * per
        info?.add("[$x0,$y0,$bw,$bh] nD=$nD nk=$nk per=$per chg=$chg stroke=$ok")
        return ok
    }

    private const val WHITE_TH = 235
}
