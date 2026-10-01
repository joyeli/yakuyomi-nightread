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
    ) {
        /** 貼框分數（研究端 audit 的 frameHug；只有有框頁、未列管、夠大的元件才有，未四捨五入）。[plan] 填。 */
        var hug: Double? = null

        /** 安全網實際沒過的門（空＝收下；研究端 audit 的 gates）。[plan]／[moreGutterCandidates] 填。 */
        var gates: Set<Gate> = emptySet()
    }

    /**
     * 安全網的門（[Metrics.gates]）；[key]＝研究端 audit `gates` 欄的名稱。強貼框那條路：硬門＋[TEXT_ON_P]＋[FAINT_F]＋[HUG_CUT]；
     * 弱貼框／不貼框那條路：硬門＋[TEXT_COV]＋[SMALL]。硬門＝[FIG_LO]／[FIG_HI]／[THIN]／[CHROMA]／[EATEN_HARD]。
     */
    enum class Gate(val key: String) {
        FIG_LO("figlo"), FIG_HI("fighi"), THIN("thin"), CHROMA("chroma"), EATEN_HARD("eatenH"),
        TEXT_ON_P("textOnP"), FAINT_F("faintF"), HUG_CUT("hugCut"),
        TEXT_COV("textCov"), SMALL("small"),
    }

    /** 兩條路共用的硬門裡沒過的。 */
    private fun figGates(met: Metrics, p: NightReadParams, out: MutableSet<Gate>) {
        if (met.figFrac < p.stickerFigMin) out.add(Gate.FIG_LO)
        if (met.figFrac > p.stickerFigMax) out.add(Gate.FIG_HI)
        if (met.thinFrac > p.stickerThinMax) out.add(Gate.THIN)
        if (met.chroma > p.stickerChromaMax) out.add(Gate.CHROMA)
        if (met.eatenFrac > p.stickerEatenHard) out.add(Gate.EATEN_HARD)
    }

    /** 弱貼框／不貼框那條路（含無框頁與「更多」C3 的留白候選）沒過的門。 */
    private fun weakGates(met: Metrics, p: NightReadParams): Set<Gate> {
        val gates = java.util.EnumSet.noneOf(Gate::class.java)
        figGates(met, p, gates)
        if (!(met.textCov <= p.stickerTextMax || met.areaFrac >= 0.02)) gates.add(Gate.TEXT_COV)
        if (!(met.areaFrac >= p.stickerSmallArea || met.textOn >= p.stickerTextBgMin)) gates.add(Gate.SMALL)
        return gates
    }

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
    class Plan(
        val accept: Set<Int>,
        val promoted: Set<Int>,
        val metrics: Map<Int, Metrics> = emptyMap(),
        /** 加「更多」新規則（[moreSelect]）之前的 keep：無框頁的留白層用它（新收的元件整顆當貼紙塗、留白帶照舊）。沒開＝[accept]。 */
        val baseAccept: Set<Int> = accept,
        /** 「更多」新規則對每個候選的判定（除錯／parity 用；沒開＝空）。 */
        val more: List<MoreDecision> = emptyList(),
    )

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
            met.hug = hug[i]
            val hugV = hug[i] ?: 0.0
            // 安全網＝一組門，全過才收；實際沒過的門記在 met.gates（「更多」新規則的候選看它）
            val ok: Boolean
            if (hugV >= p.frameHugStrong) {
                // 強貼框：四道硬門＋字壓＋淡色門＋截斷型小元件（被格框切斷的前景物件，不是格背景）
                val gates = java.util.EnumSet.noneOf(Gate::class.java)
                figGates(met, p, gates)
                if (met.textOn > p.promotedTextOnMax) gates.add(Gate.TEXT_ON_P)
                if (met.faintOfF > p.faintOfFMax) gates.add(Gate.FAINT_F)
                if (hugCut[i] == true && met.areaFrac < p.stickerSmallArea && met.textOn < p.stickerTextBgMin) {
                    gates.add(Gate.HUG_CUT)
                }
                met.gates = gates
                ok = gates.isEmpty()
                if (ok) promoted.add(i)
            } else {
                val eatenMidOk = met.eatenFrac <= p.stickerEatenMax || met.textOn >= p.stickerTextBgMin
                met.gates = weakGates(met, p)
                ok = met.gates.isEmpty()
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
        return selectKeep(plan, plainSet(g, cc, plan, charRaw, frame, p), p)
    }

    /**
     * [filterPlan] 的前半：[plan] 的 accept 裡哪些是 plain（「無畫面背景」，判準見 [filterPlan]）。
     * 與檔位無關（只讀 stickerPlain*、inkDarkTh、whiteTh，不讀 stickerMode／rough／面積門檻），三檔共用分析時算一次。
     */
    internal fun plainSet(g: Gray, cc: CC, plan: Plan, charRaw: Mask, frame: Mask, p: NightReadParams): Set<Int> {
        val keep = HashSet<Int>()
        if (plan.accept.isEmpty()) return keep     // 下面兩道整頁膨脹只給逐元件迴圈用
        val w = g.w
        val r = p.stickerPlainRingR
        val kr = Cv.ellipse(2 * r + 1)
        // 非格線的墨＝線稿：格線遮罩方核外擴後排除（框線本身不算線稿）
        val fd = Cv.dilate(frame, Cv.rect(p.stickerPlainFrameDil, p.stickerPlainFrameDil))
        // 暗墨的抗鋸齒暈：整頁算一次（不能在逐元件的窗裡算——窗只外擴 r，外圈邊上像素的暈來源可能落在窗外）
        val halo = Cv.dilate(g.lt(p.inkDarkTh), Cv.rect(p.stickerPlainFaintHalo, p.stickerPlainFaintHalo))
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
            if (touches) continue
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
                keep.add(i)                             // plain：外圈只碰格線／頁邊（暗墨、淡線稿都沒碰）
            }
        }
        return keep
    }

    /**
     * [filterPlan] 的後半：由 [plainSet] 的結果依檔位挑 keep（PLAIN＝plain；SIMPLE＝plain ∪ rough／面積門檻放行的；
     * ALL＝[plan] 原樣）。這一步才看檔位參數，成本可忽略——三檔共用分析時每檔各叫一次。
     */
    internal fun selectKeep(plan: Plan, plain: Set<Int>, p: NightReadParams): Plan {
        if (p.stickerMode == StickerMode.ALL) return plan
        val keep = HashSet<Int>()
        for (i in plan.accept.sorted()) {
            if (i in plain) { keep.add(i); continue }
            if (p.stickerMode == StickerMode.SIMPLE) {
                val met = plan.metrics[i] ?: continue
                if (pyRound(met.rough, 1) <= p.stickerRoughMax && pyRound(met.areaFrac, 4) >= p.stickerSimpleMinFrac) keep.add(i)
            }
        }
        return Plan(keep, plan.promoted.filterTo(HashSet()) { it in keep }, plan.metrics)
    }

    // ── 「更多」新規則 A2（[MoreRuleParams]；研究端 more_rule／more_features／more_gutter_candidates）──────────────

    /**
     * 一個候選元件的 A2 特徵（[moreFeatures]）。[rfs]／[rfi] 已四捨五入到 2 位、[fnc] 到 3 位（研究端拿四捨五入後的值比門檻）；
     * [lOut]／[lIn]＝外輪廓／內部記號的自由邊界長度（px），[area]＝元件面積，[charf]＝人物原輸出佔比（未四捨五入）。
     */
    class MoreFeatures(
        val rfs: Double, val rfi: Double, val fnc: Double, val charf: Double,
        val lOut: Int, val lIn: Int, val area: Int,
    )

    /**
     * 「更多」新規則對一個候選的判定（除錯／parity；研究端 audit 的 `more` 欄）。[src]：C1＝`acc`、C2＝`rej`、C3＝`gut`。
     * [skip]：`hug`（只靠貼框、貼框不夠）／`gates`（卡在軟門以外的門）／null（量了特徵）。[why]＝沒過的 S 門（out／in／faint／
     * rough／char，`+` 串接）。
     */
    class MoreDecision(
        val id: Int, val src: String, val skip: String?, val soft: Boolean,
        val features: MoreFeatures?, val why: String, val ok: Boolean,
    )

    /** 「更多」新規則與檔位無關的快取（[NightRead] 的分析持有、各檔共用）。換了 [MoreRuleParams] 就整個重來。 */
    internal class MoreCache {
        var params: MoreRuleParams? = null
        val features = HashMap<Int, MoreFeatures>()
        var gutter: Map<Int, Metrics>? = null
    }

    private val MORE_SOFT_GATES = java.util.EnumSet.of(Gate.FIG_LO, Gate.TEXT_ON_P, Gate.TEXT_COV)

    /**
     * 「更多」新規則 A2：在 [base]（檔位篩選後的 keep）上只加不減。[plan]＝安全網的原始計畫（accept／promoted 未經檔位篩選、
     * metrics 含每個安全網候選）。回傳的 accept＝keep2、promoted＝（安全網擢升 ∪ 新加且走核心填色的）∩ keep2、
     * baseAccept＝[base] 的 keep。對應研究端 `more_rule`；判準見 [MoreRuleParams]。
     *
     * 與研究端逐位元對齊的細節：貼框、textOn、textCov、整頁佔比、rough 先照研究端審計表的精度四捨五入再比（同 [selectKeep]）；
     * 特徵全是整數像素計數。[bubble]＝人物修剪前的泡（含封縫救回的）、[charMask]＝收邊＋平滑後、[charRaw]＝模型原輸出。
     */
    internal fun moreSelect(
        g: Gray, chromaImg: Gray?, wc: Regions.WhiteComponents, frameless: Boolean, regions: List<TextRegion>,
        frame: Mask, charMask: Mask, charRaw: Mask, bubble: Mask, seg: Mask,
        plan: Plan, base: Plan, p: NightReadParams, cache: MoreCache,
    ): Plan {
        val m = p.more
        if (cache.params != m) {
            cache.params = m
            cache.features.clear()
            cache.gutter = null
        }
        val cc = wc.cc
        val keep = base.accept
        val cand = java.util.TreeMap<Int, String>()
        for (i in plan.accept) if (i !in keep) cand[i] = "acc"                     // C1：安全網收下、檔位沒收
        for (i in plan.metrics.keys) {
            if (i in plan.accept || i in keep || i in cand) continue
            cand[i] = "rej"                                                        // C2：安全網拒收（只卡在軟門的才繼續）
        }
        val gutMet = HashMap<Int, Metrics>()
        if (!frameless) {
            val gut = cache.gutter ?: moreGutterCandidates(g, chromaImg, wc, regions, p).also { cache.gutter = it }
            for ((i, met) in gut) {
                if (i in keep || i in cand) continue
                cand[i] = "gut"                                                    // C3：門檻邊上的頁邊留白
                gutMet[i] = met
            }
        }
        if (cand.isEmpty()) return Plan(keep, plan.promoted.filterTo(HashSet()) { it in keep }, plan.metrics, keep)

        var expl: Array<Mask>? = null      // fd／bs／bs0：只在有元件要量特徵時才算（整頁三張遮罩，量完就放掉）
        val hugLo = p.frameHugMin * (1 + m.hyst)
        val strongLo = p.frameHugStrong * (1 - m.hyst)
        val strongHi = p.frameHugStrong * (1 + m.hyst)
        val add = HashSet<Int>()
        val prom = HashSet(plan.promoted)
        val decisions = ArrayList<MoreDecision>()
        for ((i, src) in cand) {
            val met = gutMet[i] ?: plan.metrics.getValue(i)
            val hug = met.hug?.let { pyRound(it, 3) }
            val listed = i in wc.panelIds || i in wc.gutterIds || frameless
            if (hug != null && !listed && hug < hugLo) {
                // 只靠貼框才成為候選的：貼框要穩穩過門檻（保守式遲滯）
                decisions.add(MoreDecision(i, src, "hug", false, null, "", false))
                continue
            }
            val gs: Set<Gate> = if (src == "acc") emptySet() else met.gates
            if (src == "rej" && gs.isEmpty()) continue
            if (gs.isNotEmpty() && !MORE_SOFT_GATES.containsAll(gs)) {
                decisions.add(MoreDecision(i, src, "gates", false, null, "", false))
                continue
            }
            // 強／弱貼框兩條路的軟門不同（強：字壓 > 0.3 拒；弱：文字窗 > 0.55 且小 拒），貼框在 0.4 上下會換路。
            // 遲滯帶內兩條路的軟門都要看（保守式）：碰到任一道就當「軟門元件」，要過 rough 上限。
            val soft = gs.isNotEmpty() || run {
                val h = hug ?: 0.0
                (h >= strongLo && pyRound(met.textOn, 3) > p.promotedTextOnMax) ||
                    (h < strongHi && pyRound(met.textCov, 3) > p.stickerTextMax && pyRound(met.areaFrac, 4) < 0.02)
            }
            val f = cache.features.getOrPut(i) {
                val e = expl ?: moreExplained(g, bubble, seg, frame, p).also { expl = it }
                moreFeatures(g, cc, i, charMask, charRaw, e[0], e[1], e[2], p)
            }
            val why = ArrayList<String>(5)
            if (f.rfs > m.tOut) why.add("out")
            if (f.rfi > m.tIn) why.add("in")
            if (f.fnc > m.faintMax) why.add("faint")
            if (soft && pyRound(met.rough, 1) > m.roughCap) why.add("rough")
            if (f.charf >= m.charMax) why.add("char")
            val ok = why.isEmpty()
            decisions.add(MoreDecision(i, src, null, soft, f, why.joinToString("+"), ok))
            if (ok) {
                add.add(i)
                if (!frameless || met.hug != null || src == "gut") prom.add(i)
            }
        }
        val keep2 = HashSet(keep).apply { addAll(add) }
        return Plan(keep2, prom.filterTo(HashSet()) { it in keep2 }, plan.metrics, keep, decisions)
    }

    /**
     * A2 的「交代過」遮罩（整頁）：[0] fd＝格線方核外擴 explainDil；[1] bs＝泡 ∪ 泡框線（泡外 [NightReadParams.bubbleOutlineDist]
     * 橢圓內的非白）∪ 字（seg 方核外擴 explainDil）；[2] bs0＝同 bs 但不含泡框線（外圈淡色用）。研究端 `more_explained`。
     */
    private fun moreExplained(g: Gray, bubble: Mask, seg: Mask, frame: Mask, p: NightReadParams): Array<Mask> {
        val k = Cv.rect(p.more.explainDil, p.more.explainDil)
        val fd = Cv.dilate(frame, k)
        val segd = Cv.dilate(seg, k)
        val bubD = Cv.dilate(bubble, Cv.ellipse(2 * p.bubbleOutlineDist + 1))
        val n = g.data.size
        val bs = Mask(g.w, g.h)
        val bs0 = Mask(g.w, g.h)
        for (i in 0 until n) {
            val b0 = bubble.data[i] || segd.data[i]
            bs0.data[i] = b0
            bs.data[i] = b0 || (bubD.data[i] && g.data[i] < p.whiteTh)
        }
        return arrayOf(fd, bs, bs0)
    }

    /**
     * 一個候選元件的 A2 特徵（研究端 `more_features`），全在元件 bbox 外擴 [MoreRuleParams.winPad] 的窗內量：
     * 外輪廓自由邊界（補洞、中值平滑、1 px 內邊界扣交代過的）、內部記號（真的有畫東西的洞的 1 px 內邊界扣交代過的）、
     * 外圈淡色、人物原輸出佔比。邊界與 cv2 一致：侵蝕視影像外為前景、膨脹不從影像外長、中值 BORDER_REPLICATE。
     */
    private fun moreFeatures(
        g: Gray, cc: CC, id: Int, charMask: Mask, charRaw: Mask, fd: Mask, bs: Mask, bs0: Mask, p: NightReadParams,
    ): MoreFeatures {
        val m = p.more
        val win = window(g, cc, id, m.winPad)
        val w = win.w
        val h = win.h
        val n = w * h
        val comp = win.comp
        val sub = win.sub
        // 交代過的（人物收邊後 ∪ 泡＋框線＋字 ∪ 格線）再方核外擴 nearDil
        val nearSrc = Mask(w, h)
        val chW = Mask(w, h)
        val bs0W = Mask(w, h)
        val fdW = Mask(w, h)
        var charRawN = 0
        for (y in 0 until h) {
            val src = (win.y0 + y) * g.w + win.x0
            for (x in 0 until w) {
                val j = src + x
                val k = y * w + x
                chW.data[k] = charMask.data[j]
                bs0W.data[k] = bs0.data[j]
                fdW.data[k] = fd.data[j]
                nearSrc.data[k] = charMask.data[j] || bs.data[j] || fd.data[j]
                if (comp.data[k] && charRaw.data[j]) charRawN++
            }
        }
        val near = Cv.dilate(nearSrc, Cv.rect(m.nearDil, m.nearDil))
        val k3 = Cv.rect(3, 3)
        val holes = Cv.holes(comp)
        val filled = comp or holes
        val sm = Cv.medianBlurMask(filled, m.median)
        val smE = Cv.erode(sm, k3)
        var lOut = 0
        for (k in 0 until n) if (sm.data[k] && !smE.data[k] && !near.data[k]) lOut++
        var lIn = 0
        if (holes.any()) {
            val hcc = Cv.ccStats(holes, 8)
            if (hcc.n > 1) {
                val mn = IntArray(hcc.n) { 255 }
                for (k in 0 until n) {
                    val l = hcc.labels[k]
                    if (l > 0 && sub.data[k] < mn[l]) mn[l] = sub.data[k]
                }
                val real = BooleanArray(hcc.n)
                for (l in 1 until hcc.n) real[l] = hcc.area[l] >= m.holeMinArea || mn[l] < m.holeDarkMax
                val hm = Mask(w, h, BooleanArray(n) { real[hcc.labels[it]] })
                val hmE = Cv.erode(hm, k3)
                for (k in 0 until n) if (hm.data[k] && !hmE.data[k] && !near.data[k]) lIn++
            }
        }
        val compN = comp.count()
        val area = max(1, compN)
        val rfs = pyRound((lOut.toLong() * lOut).toDouble() / (4.0 * PI * area), 2)
        val rfi = pyRound((lIn.toLong() * lIn).toDouble() / (4.0 * PI * area), 2)
        // 外圈淡色：外圈扣人物（收邊後）為分母；不在泡（不含框線）／字／格線、不在暗墨暈、淡（inkDarkTh ≤ g < whiteTh）為分子
        val ring = Cv.dilate(comp, Cv.ellipse(2 * m.ringR + 1))
        val halo = Cv.dilate(sub.lt(p.inkDarkTh), Cv.rect(m.halo, m.halo))
        var nc = 0
        var faint = 0
        for (k in 0 until n) {
            if (!ring.data[k] || comp.data[k] || chW.data[k]) continue
            nc++
            if (bs0W.data[k] || fdW.data[k] || halo.data[k]) continue
            val v = sub.data[k]
            if (v >= p.inkDarkTh && v < p.whiteTh) faint++
        }
        val fnc = pyRound(faint.toDouble() / max(1, nc), 3)
        val charf = charRawN.toDouble() / compN
        return MoreFeatures(rfs, rfi, fnc, charf, lOut, lIn, area)
    }

    /**
     * C3（研究端 `more_gutter_candidates`）：有框頁的頁邊留白元件裡，格內白判準全部放寬 [MoreRuleParams.hyst] 後算格內白的，
     * 回傳 {元件: 安全網量測（[metrics]＋弱貼框那條路的 [Metrics.gates]）}。與檔位無關（[MoreCache] 存一份）。
     *
     * 厚芯用 5×5 chamfer（[Cv.distanceChamfer]，同研究端 `cv2.distanceTransform(DIST_L2, 5)`）：門檻 coreR×(1−hyst)＝19.5，
     * 附近最近的 chamfer 值差 ≥ 0.08，與研究端（pip 版 cv2 走 IPP）的門檻遮罩相同；[Regions.classifyWhiteComponents] 用的是
     * 精確歐氏，這裡為了與研究端逐元件對齊不共用。深入帶寬照研究端 `int(round(…))`（四捨六入五成雙）。
     */
    internal fun moreGutterCandidates(
        g: Gray, chromaImg: Gray?, wc: Regions.WhiteComponents, regions: List<TextRegion>, p: NightReadParams,
    ): Map<Int, Metrics> {
        val cc = wc.cc
        val w = g.w
        val h = g.h
        val minArea = p.gutterMinAreaFrac * g.data.size
        val todo = wc.gutterIds.filter { cc.area[it] >= minArea }.sorted()
        if (todo.isEmpty()) return emptyMap()
        val lo = 1.0 - p.more.hyst
        val rCore = p.coreR * lo
        val deepPx = max(64, Math.rint(p.deepEdgeFrac * min(w, h)).toInt())
        val dist = Cv.distanceChamfer(g.ge(p.whiteTh), 5)
        val wanted = BooleanArray(cc.n)
        for (i in todo) wanted[i] = true
        val coreCnt = IntArray(cc.n)
        val deepCnt = IntArray(cc.n)
        for (y in 0 until h) {
            val base = y * w
            val edY = min(y, h - 1 - y)
            for (x in 0 until w) {
                val idx = base + x
                val l = cc.labels[idx]
                if (l == 0 || !wanted[l]) continue
                if (dist.data[idx].toDouble() <= rCore) continue
                coreCnt[l]++
                if (min(min(x, w - 1 - x), edY) > deepPx) deepCnt[l]++
            }
        }
        val textRects = rectMask(w, h, regions, p.bubblePad)
        val textRectsOn = rectMask(w, h, regions, p.stickerTextOnPad)
        val out = LinkedHashMap<Int, Metrics>()
        for (i in todo) {
            if (coreCnt[i] == 0) continue
            val cf = coreCnt[i].toDouble() / max(1, cc.area[i])
            val cdeep = deepCnt[i].toDouble() / coreCnt[i]
            var inPanel = cf >= p.inPanelCoreFrac * lo && cdeep >= p.inPanelCoreDeep * lo
            if (!inPanel && cdeep >= p.deepInkDeep * lo) {
                inPanel = Regions.holeInkRatio(cc, i, g, p) >= p.deepInkRatio * lo
            }
            if (!inPanel) continue
            val met = metrics(g, chromaImg, cc, i, textRects, textRectsOn, p)
            met.gates = weakGates(met, p)
            out[i] = met
        }
        return out
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
