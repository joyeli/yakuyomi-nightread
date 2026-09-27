package li.joye.yakuyomi.nightread

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * 貼紙式背景：純白背景填黑、前景加白描邊抬出立體感。
 *
 * 這是最容易吃掉前景白的一層，所以安全網最厚：元件級的門（前景佔比、細碎佔比、彩度、
 * 疑似被吃的前景白）決定「這顆元件要不要動」，區域級的保護（窄頸附屬白）決定「元件內
 * 哪一塊不准動」。逐段對應 `research/nightread.py` 的 sticker_* 與 paint_sticker。
 */
internal object Sticker {

    /** 元件的工作窗：bbox 外擴 margin。 */
    class Window(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val sub: Gray, val comp: Mask) {
        val w: Int get() = x1 - x0
        val h: Int get() = y1 - y0
    }

    fun window(g: Gray, cc: CC, id: Int, margin: Int): Window {
        val x0 = max(0, cc.left[id] - margin)
        val y0 = max(0, cc.top[id] - margin)
        val x1 = min(g.w, cc.left[id] + cc.width[id] + margin)
        val y1 = min(g.h, cc.top[id] + cc.height[id] + margin)
        val w = x1 - x0
        val h = y1 - y0
        val sub = Gray(w, h)
        val comp = Mask(w, h)
        for (y in 0 until h) {
            val src = (y0 + y) * g.w + x0
            val dst = y * w
            for (x in 0 until w) {
                sub.data[dst + x] = g.data[src + x]
                comp.data[dst + x] = cc.labels[src + x] == id
            }
        }
        return Window(x0, y0, x1, y1, sub, comp)
    }

    /**
     * 「疑似被吃的前景白」：細白（距離變換小）且局部墨密度高。
     *
     * 白鬍子、髮絲這類前景白透過筆畫縫隙連進背景時就長這樣。背景的密細節（教堂花窗速寫）
     * 也會命中——兩者統計上分不開，所以後續一律走區域級保護而不是直接拒收。
     */
    fun eaten(sub: Gray, comp: Mask, p: NightReadParams): Mask {
        val dist = Cv.distanceL2(comp)
        val inkF = FImg(sub.w, sub.h, FloatArray(sub.data.size) { if (sub.data[it] < p.whiteTh) 1f else 0f })
        val dens = Cv.boxBlur(inkF, 15)
        val out = Mask(sub.w, sub.h)
        for (i in out.data.indices) {
            out.data[i] = comp.data[i] && dist.data[i] <= p.stickerEatenR && dens.data[i] >= p.stickerEatenDens
        }
        return out
    }

    /**
     * 區域級保護＝「窄頸附屬白 ∧ 含 eaten」。
     *
     * 開放背景核＝侵蝕後的大殘核（白臉額頭的殘核太小、不算背景）；由核做 3×3 測地重建，
     * 重建到不了的白就是「只能經窄縫抵達的物件附屬白」——白鬍子的整片臉就是這型。
     * 附屬白含足夠 eaten 才保護；乾淨格的窄框縫沒有 eaten，照填。
     *
     * 半解析度重建（省四倍），與 Python 一致。
     */
    fun protect(eatenMask: Mask, comp: Mask, p: NightReadParams): Mask {
        val area = max(comp.count(), 1)
        val w = comp.w
        val h = comp.h
        val hw = max(1, w / 2)
        val hh = max(1, h / 2)
        val compH = Cv.resizeNearest(comp, hw, hh)
        val eatenH = Cv.resizeNearest(eatenMask, hw, hh)
        val r = max(2, p.stickerNeckR / 2)
        val core = Cv.erode(compH, Cv.ellipse(2 * r + 1))
        val cc = Cv.ccStats(core, 8)
        val areaH = max(compH.count(), 1)
        val seed = Mask(hw, hh)
        var hasSeed = false
        for (i in seed.data.indices) {
            val l = cc.labels[i]
            if (l > 0 && cc.area[l] >= p.stickerCoreMin * areaH) { seed.data[i] = true; hasSeed = true }
        }
        // 沒有開放背景核＝整顆都是窄碎 ⇒ 全保護（極端保守）
        if (!hasSeed) return Mask(w, h, BooleanArray(w * h) { true })

        var recon = seed
        var prev = -1
        val k3 = Cv.rect(3, 3)
        for (step in 0 until 4000) {
            recon = Cv.dilate(recon, k3) and compH
            val cnt = recon.count()
            if (cnt == prev) break
            prev = cnt
        }
        val appendage = Mask(hw, hh)
        for (i in appendage.data.indices) appendage.data[i] = compH.data[i] && !recon.data[i]
        val acc = Cv.ccStats(appendage, 8)
        val need = max(100.0, p.stickerProtectEatenMin * area) / 4.0
        val eatenPerBlob = IntArray(acc.n)
        for (i in appendage.data.indices) {
            val l = acc.labels[i]
            if (l > 0 && eatenH.data[i]) eatenPerBlob[l]++
        }
        val protectH = Mask(hw, hh)
        var anyProtect = false
        for (i in protectH.data.indices) {
            val l = acc.labels[i]
            if (l > 0 && eatenPerBlob[l] >= need) { protectH.data[i] = true; anyProtect = true }
        }
        if (!anyProtect) return Mask(w, h)
        val up = Cv.resizeNearest(protectH, w, h)
        return Cv.dilate(up, Cv.ellipse(2 * p.stickerProtectDilate + 1))
    }

    /** 單顆白背景元件的 figure/ground 診斷。欄位語意見 `docs/PARAMETERS.md` 的貼紙段。 */
    class Metrics(
        val id: Int,
        val areaFrac: Double,
        val figFrac: Double,
        val thinFrac: Double,
        val chroma: Double,
        val eatenFrac: Double,
        val textCov: Double,
        val textOn: Double,
        val faintOfF: Double,
        /** 周長²/(4π·面積)（圓＝1；輪廓破碎度）。周長＝[Cv.totalContourLength]（cv2 所有輪廓的閉合折線長）。 */
        val rough: Double,
    )

    fun metrics(
        g: Gray,
        chromaImg: Gray?,
        cc: CC,
        id: Int,
        textRects: Mask,
        textRectsOn: Mask,
        p: NightReadParams,
    ): Metrics {
        val win = window(g, cc, id, 8)
        val area = max(win.comp.count(), 1)

        // 前景＝暗像素 ∪ 被白封閉的白（臉/衣服）
        val enclosed = Cv.holes(win.comp)
        var figCount = 0
        for (i in win.comp.data.indices) {
            if (win.sub.data[i] < p.whiteTh || enclosed.data[i]) figCount++
        }
        val figFrac = figCount.toDouble() / win.comp.data.size

        val dist = Cv.distanceL2(win.comp)
        var thin = 0
        for (i in win.comp.data.indices) if (win.comp.data[i] && dist.data[i] <= p.stickerThinR) thin++
        val thinFrac = thin.toDouble() / area

        var chromaSum = 0L
        var chromaCnt = 0
        if (chromaImg != null) {
            for (y in 0 until win.h) {
                val src = (win.y0 + y) * g.w + win.x0
                for (x in 0 until win.w) {
                    if (win.comp.data[y * win.w + x]) { chromaSum += chromaImg.data[src + x]; chromaCnt++ }
                }
            }
        }
        val chroma = if (chromaCnt > 0) chromaSum.toDouble() / chromaCnt else 0.0

        val e = eaten(win.sub, win.comp, p)
        val eatenFrac = e.count().toDouble() / area

        var covN = 0
        var onN = 0
        for (y in 0 until win.h) {
            val src = (win.y0 + y) * g.w + win.x0
            for (x in 0 until win.w) {
                if (!win.comp.data[y * win.w + x]) continue
                if (textRects.data[src + x]) covN++
                if (textRectsOn.data[src + x]) onN++
            }
        }
        val textCov = covN.toDouble() / area
        val textOn = onN.toDouble() / area

        // 淡色門：前景像素裡「淡」的比例（群眾/建築速寫背景高，填黑會變漂浮碎片）
        var fp = 0
        var faint = 0
        for (y in 0 until cc.height[id]) {
            val src = (cc.top[id] + y) * g.w + cc.left[id]
            for (x in 0 until cc.width[id]) {
                val v = g.data[src + x]
                if (v < p.whiteTh) { fp++; if (v > p.faintG) faint++ }
            }
        }
        val faintOfF = if (fp > 0) faint.toDouble() / fp else 0.0

        val perim = Cv.totalContourLength(win.comp)
        val rough = perim * perim / (4.0 * PI * area)

        return Metrics(id, area.toDouble() / g.data.size, figFrac, thinFrac, chroma,
            eatenFrac, textCov, textOn, faintOfF, rough)
    }

    /**
     * 貼紙計畫的結果：哪些元件要動（accept），其中哪些走核心填色（promoted），以及每個候選元件的診斷
     * （[metrics]，研究端的 audit；[filterPlan] 的 SIMPLE 檔要看 rough／areaFrac）。
     */
    class Plan(val accept: Set<Int>, val promoted: Set<Int>, val metrics: Map<Int, Metrics> = emptyMap())

    /**
     * 挑目標元件並過安全網。
     *
     * 有框頁的目標是格內白，再加上「貼框擢升」撿回被格框封閉、連分類階段都進不了的格內背景；
     * 無框頁則是夠大的貼邊白元件。
     *
     * 強貼框（背景證據極強）走放寬門：跳過文字覆蓋率與 eaten 中段門，只保留四道硬底線，
     * 因為窄頸類的危險交給核心填色的幾何保護。
     */
    fun plan(
        g: Gray,
        chromaImg: Gray?,
        wc: Regions.WhiteComponents,
        frameless: Boolean,
        regions: List<TextRegion>,
        frame: Mask,
        p: NightReadParams,
    ): Plan {
        val cc = wc.cc
        val textRects = rectMask(g.w, g.h, regions, p.bubblePad)
        val textRectsOn = rectMask(g.w, g.h, regions, p.stickerTextOnPad)

        val cand = HashSet<Int>()
        val hug = HashMap<Int, Double>()
        // 貼框只發生在一對相對邊（另一對兩邊都不貼）＝元件被格框**截斷**，不是沿格框跑。
        // 扁格（高 147 px 的橫幅格）裡的前景衣料白正是這型：上下必然整條貼框、左右不貼。
        val hugCut = HashMap<Int, Boolean>()
        if (frameless) {
            for (i in wc.gutterIds + wc.panelIds) {
                if (cc.area[i] >= p.stickerMinFrac * g.data.size) cand.add(i)
            }
        } else {
            cand.addAll(wc.panelIds)
            // 貼框擢升：未列管的大白元件，若「貼格線長度 / bbox 周長」夠高＝背景沿著格框跑。
            // 前景白（臉、白衣）是獨立元件、只點狀碰框，hug 低、天然不擢升。
            val kd = Cv.ellipse(p.frameHugDilate * 2 + 1)
            val minArea = p.gutterMinAreaFrac * g.data.size
            val listed = wc.gutterIds + wc.panelIds
            for (i in 1 until cc.n) {
                if (i in listed || cc.area[i] < minArea) continue
                val pad = p.frameHugDilate + 1
                val win = window(g, cc, i, pad)
                val grown = Cv.dilate(win.comp, kd)
                var contact = 0
                var topC = 0
                var botC = 0
                var lefC = 0
                var rigC = 0
                val bw = max(1, cc.width[i]).toDouble()
                val bh = max(1, cc.height[i]).toDouble()
                for (y in 0 until win.h) {
                    val src = (win.y0 + y) * g.w + win.x0
                    val ry = (win.y0 + y - cc.top[i]) / bh
                    for (x in 0 until win.w) {
                        if (grown.data[y * win.w + x] && frame.data[src + x]) {
                            contact++
                            if (ry < 0.15) topC++ else if (ry > 0.85) botC++
                            val rx = (win.x0 + x - cc.left[i]) / bw
                            if (rx < 0.15) lefC++ else if (rx > 0.85) rigC++
                        }
                    }
                }
                // 每邊的覆蓋＝該邊的接觸像素 ÷ 名目線厚 ÷ 邊長（會 >1：膨脹後的接觸帶較厚）
                val tc = topC / p.frameHugThick / bw
                val bc = botC / p.frameHugThick / bw
                val lc = lefC / p.frameHugThick / bh
                val rc = rigC / p.frameHugThick / bh
                hugCut[i] = max(tc, bc) < p.hugSideMin || max(lc, rc) < p.hugSideMin
                val hugLen = contact / p.frameHugThick
                val frac = hugLen / max(1.0, 2.0 * (cc.width[i] + cc.height[i]))
                hug[i] = frac
                if (frac >= p.frameHugMin) cand.add(i)
            }
        }

        val accept = HashSet<Int>()
        val promoted = HashSet<Int>()
        val mets = HashMap<Int, Metrics>()
        for (i in cand.sorted()) {
            val met = metrics(g, chromaImg, cc, i, textRects, textRectsOn, p)
            mets[i] = met
            val hugV = hug[i] ?: 0.0
            val ok: Boolean
            if (hugV >= p.frameHugStrong) {
                ok = met.figFrac in p.stickerFigMin..p.stickerFigMax &&
                    met.thinFrac <= p.stickerThinMax &&
                    met.chroma <= p.stickerChromaMax &&
                    met.eatenFrac <= p.stickerEatenHard &&
                    met.textOn <= p.promotedTextOnMax &&
                    met.faintOfF <= p.faintOfFMax &&
                    // 截斷型的小元件＝被格框切斷的前景物件，不是格背景
                    !(hugCut[i] == true &&
                        met.areaFrac < p.stickerSmallArea &&
                        met.textOn < p.stickerTextBgMin)
                if (ok) promoted.add(i)
            } else {
                val textCovOk = met.textCov <= p.stickerTextMax || met.areaFrac >= 0.02
                val eatenMidOk = met.eatenFrac <= p.stickerEatenMax || met.textOn >= p.stickerTextBgMin
                ok = met.figFrac in p.stickerFigMin..p.stickerFigMax &&
                    met.thinFrac <= p.stickerThinMax &&
                    met.chroma <= p.stickerChromaMax &&
                    textCovOk &&
                    met.eatenFrac <= p.stickerEatenHard &&
                    (met.areaFrac >= p.stickerSmallArea || met.textOn >= p.stickerTextBgMin)
                // 中段 eaten 的格內白改走核心填色，不整顆拒；弱貼框擢升元件同理
                if (ok && !eatenMidOk && !frameless && i in wc.panelIds) promoted.add(i)
                if (ok && hug.containsKey(i)) promoted.add(i)
            }
            if (ok) {
                accept.add(i)
                // 有框頁的格內白一律核心填色（整顆填會把連進背景的髮絲白吃掉）
                if (!frameless) promoted.add(i)
            }
        }
        return Plan(accept, promoted, mets)
    }

    /**
     * 背景填黑三檔（[NightReadParams.stickerMode]）：在 [plan] 的安全網之後再挑一次，決定哪些白元件真的填黑。
     * 對應研究端 `filter_sticker_plan`。回傳的 accept＝keep、promoted 只留仍在 keep 內的（擢升元件落選＝連核心填色也不做）。
     *
     * plain(i)＝「無畫面背景」：元件不碰 [charRaw]（模型原輸出人物遮罩、未收邊——三檔實驗就是這樣量的）、且外圈
     * （dilate 橢圓 (2·stickerPlainRingR+1)² − 元件）非空、且外圈上「非格線的墨」（g < inkDarkTh 且不在格線外擴
     * stickerPlainFrameDil² 內）的佔比 < stickerPlainArtMax、「淡線稿」（inkDarkTh ≤ g < whiteTh、不在格線外擴內、
     * 不在暗墨 stickerPlainFaintHalo² 外擴內）的佔比 < stickerPlainFaintMax ⇒ 邊界只碰格線／頁邊、沒碰線稿
     * （雲、效果線這類淡線也是線稿）。
     *   ALL   ：keep＝accept（預設完整管線，不動）
     *   PLAIN ：keep＝{plain}（L1）
     *   SIMPLE：keep＝{plain} ∪ {rough ≤ stickerRoughMax 且 areaFrac ≥ stickerSimpleMinFrac}（L2／L3）
     * 落選的元件回到 [plan] 落選時的待遇（有框頁 panel 白＝場景調壓暗；frameless＝背景保留），絕不會比原圖糟。
     *
     * rough／areaFrac 先照研究端審計表的精度四捨五入（`round(rough, 1)`、`round(areaFrac, 4)`；python 是拿表裡的值比）
     * 再比門檻，這樣 Kotlin 與 python 在門檻邊上的判定才會一致。
     */
    fun filterPlan(g: Gray, cc: CC, plan: Plan, charRaw: Mask, frame: Mask, p: NightReadParams): Plan {
        if (p.stickerMode == StickerMode.ALL) return plan
        val w = g.w
        val r = p.stickerPlainRingR
        val kr = Cv.ellipse(2 * r + 1)
        // 非格線的墨＝線稿：格線遮罩方核外擴後排除（框線本身不算線稿）
        val fd = Cv.dilate(frame, Cv.rect(p.stickerPlainFrameDil, p.stickerPlainFrameDil))
        // 暗墨的抗鋸齒暈：整頁算一次（不能在逐元件的窗裡算——窗只外擴 r，外圈邊上像素的暈來源可能落在窗外）
        val halo = Cv.dilate(g.lt(p.inkDarkTh), Cv.rect(p.stickerPlainFaintHalo, p.stickerPlainFaintHalo))
        val keep = HashSet<Int>()
        for (i in plan.accept.sorted()) {
            // 不碰人物：元件像素與原始人物遮罩無交集（只掃元件 bbox）
            var touches = false
            run {
                for (y in cc.top[i] until cc.top[i] + cc.height[i]) {
                    val base = y * w
                    for (x in cc.left[i] until cc.left[i] + cc.width[i]) {
                        if (cc.labels[base + x] == i && charRaw.data[base + x]) { touches = true; return@run }
                    }
                }
            }
            if (!touches) {
                // 外圈：元件膨脹減元件。膨脹最遠只到 r，所以在 bbox 外擴 r 的窗內算與全頁算一樣（窗外的像素本來就不在外圈）
                val win = window(g, cc, i, r)
                val grown = Cv.dilate(win.comp, kr)
                var ring = 0
                var art = 0
                var faint = 0
                for (y in 0 until win.h) {
                    val src = (win.y0 + y) * w + win.x0
                    for (x in 0 until win.w) {
                        val k = y * win.w + x
                        if (!grown.data[k] || win.comp.data[k]) continue
                        ring++
                        val j = src + x
                        if (fd.data[j]) continue
                        val v = g.data[j]
                        if (v < p.inkDarkTh) art++                              // 非格線的墨＝線稿
                        else if (v < p.whiteTh && !halo.data[j]) faint++        // 淡線稿：雲／效果線／淡網點（不是暗線的暈）
                    }
                }
                if (ring > 0 && art.toDouble() / ring < p.stickerPlainArtMax &&
                    faint.toDouble() / ring < p.stickerPlainFaintMax
                ) {
                    keep.add(i)                         // plain：外圈只碰格線／頁邊（暗墨、淡線稿都沒碰）
                    continue
                }
            }
            if (p.stickerMode == StickerMode.SIMPLE) {
                val met = plan.metrics[i] ?: continue
                if (pyRound(met.rough, 1) <= p.stickerRoughMax && pyRound(met.areaFrac, 4) >= p.stickerSimpleMinFrac) keep.add(i)
            }
        }
        return Plan(keep, plan.promoted.filterTo(HashSet()) { it in keep }, plan.metrics)
    }

    /**
     * python 的 `round(x, digits)`：對 double 的**精確**二進位值做十進位四捨六入五成雙。`BigDecimal(double)` 就是那個
     * 精確值，所以 HALF_EVEN 逐位對齊 python（`Math.round(x * 10) / 10.0` 在 0.x5 附近會因為乘法捨入而不同）。
     */
    private fun pyRound(x: Double, digits: Int): Double =
        BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

    private fun rectMask(w: Int, h: Int, regions: List<TextRegion>, pad: Int): Mask {
        val m = Mask(w, h)
        for (r in regions) {
            val x0 = max(0, r.x0 - pad)
            val y0 = max(0, r.y0 - pad)
            val x1 = min(w - 1, r.x1 + pad)
            val y1 = min(h - 1, r.y1 + pad)
            for (y in y0..y1) {
                val base = y * w
                for (x in x0..x1) m.data[base + x] = true
            }
        }
        return m
    }
}
