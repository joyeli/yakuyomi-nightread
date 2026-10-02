package li.joye.yakuyomi.nightread

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 分區判定：頁面在重繪前要先回答四個問題——紙白在哪、這頁有沒有格框、白色連通元件各是什麼、
 * 哪些白是氣泡。逐段對應 `research/nightread.py`。
 */
internal object Regions {

    // ── 人物遮罩加工 ─────────────────────────────────────────────────

    /**
     * 貼墨收邊：在非墨區內從遮罩測地生長，碰到線稿就停。遮罩不足處沿角色內部白長到輪廓線，
     * 輪廓外的背景進不來 ⇒ 邊界貼合角色而不是等寬光暈。再 dilate 把輪廓線本身納入。
     *
     * **加大半徑不會讓邊界變粗**——這是測地生長的性質，也是「細邊界 vs 紅線」不衝突的原因。
     */
    fun snapCharMask(keep: Mask, g: Gray, p: NightReadParams): Mask {
        val allowed = g.ge(p.whiteTh) or keep
        val grown = Cv.geodesicGrow(keep, allowed, p.charSnap, step = 4)
        if (p.charSnapPad <= 0) return grown
        return Cv.dilate(grown, Cv.rect(3, 3), iterations = p.charSnapPad)
    }

    /**
     * 遮罩形狀平滑：中值濾波削掉方塊階梯（遮罩在 640 解析度產生、放大後邊界呈直角梯級），
     * 再用原圖的墨線把邊界拉回輪廓——平滑只准在非墨區改寫，線稿上的歸屬維持原判。
     */
    fun smoothCharMask(keep: Mask, g: Gray, p: NightReadParams): Mask {
        val sm = Cv.medianBlurMask(keep, p.maskSmoothMedian or 1)
        val out = Mask(keep.w, keep.h)
        for (i in keep.data.indices) {
            out.data[i] = if (g.data[i] < p.whiteTh) keep.data[i] else sm.data[i]
        }
        return out
    }

    // ── 紙白正規化 ───────────────────────────────────────────────────

    /**
     * 掃描頁與色紙的紙白不是 255。取亮部眾數當紙白峰，低於門檻就線性拉到 255。
     *
     * **彩度門是關鍵**：真紙白是灰白（彩度≈0），淡彩畫底也可能很亮但有彩，那是畫不是紙。
     * 沒有這道門，水彩頁會被整片拉白、輸出反而更亮。
     *
     * @return 正規化後的灰階（乾淨白紙原樣回傳）
     */
    fun normalizePaper(g: Gray, chroma: Gray?, p: NightReadParams): Gray {
        val hist = IntArray(256)
        for (v in g.data) hist[v]++
        var total = 0
        for (v in p.paperPeakLo until 256) total += hist[v]
        if (total == 0) return g
        var peak = p.paperPeakLo
        for (v in p.paperPeakLo until 256) if (hist[v] > hist[peak]) peak = v
        if (peak >= p.paperNormMin) return g
        if (chroma != null) {
            var sum = 0L
            var cnt = 0
            for (i in g.data.indices) {
                if (abs(g.data[i] - peak) <= 4) { sum += chroma.data[i]; cnt++ }
            }
            if (cnt > 0 && sum.toDouble() / cnt > p.paperNormChromaMax) return g   // 有彩＝畫不是紙
        }
        val scale = 255.0 / peak
        return Gray(g.w, g.h, IntArray(g.data.size) {
            (g.data[it] * scale).roundToInt().coerceIn(0, 255)
        })
    }

    // ── 頁型判別 ─────────────────────────────────────────────────────

    /** 長直格框線遮罩：暗像素對長橫線／長直線開運算，只留貼直的長線。 */
    fun frameLineMask(g: Gray, p: NightReadParams): Pair<Mask, Mask> {
        val dark = g.lt(p.frameDarkTh)
        val l = max(60, min(g.w, g.h) / p.frameLineLDiv)
        val lh = Cv.open(dark, Cv.rect(l, 1))
        val lv = Cv.open(dark, Cv.rect(1, l))
        return lh to lv
    }

    /**
     * 長直格框線的密度（每千像素）。兩向都近零＝無框頁，背景只壓暗不填深（安全降級）。
     */
    fun pageIsFrameless(lh: Mask, lv: Mask, p: NightReadParams): Boolean {
        val n = lh.data.size.toDouble()
        val hk = 1000.0 * lh.count() / n
        val vk = 1000.0 * lv.count() / n
        return !(min(hk, vk) >= p.frameMinEach && hk + vk >= p.frameMinSum)
    }

    /**
     * 格框線切割：把留白遮罩沿格框線斷開，只留真的是留白的塊。
     *
     * 病根：格內的淺色背景（牆面／窗／地板）與頁邊留白在像素層**連通成同一個白元件**
     * ⇒ 整塊被判留白填黑。元件級別救不了——「深入頁內」量的是距頁邊的距離，緊貼頁面
     * 上下緣的橫幅格永遠算不上深入（ch34_011 那顆白元件橫跨 y870..1920、深入只有 0.9%）。
     * 這裡逐像素切：用框線斷開遮罩，只保留「仍碰得到頁邊」或「細得像真格溝」的塊。
     */
    fun gutterFrameCut(gutter: Mask, lhIn: Mask, lvIn: Mask, p: NightReadParams): Mask {
        if (!gutter.any()) return gutter
        val w = gutter.w
        val h = gutter.h
        var lh = lhIn
        var lv = lvIn
        val raw = lh or lv                       // 真的偵測到的框線
        if (p.gfcCloseFrac > 0) {                // 沿線方向閉合＝補上被出血人物打斷的框線
            val c = max(3, (p.gfcCloseFrac * min(w, h)).roundToInt())
            lh = Cv.close(lh, Cv.rect(c, 1))
            lv = Cv.close(lv, Cv.rect(1, c))
        }
        val bridge = (lh or lv).andNot(raw)      // 閉合補出來的橋
        if (!raw.any() && !bridge.any()) return gutter
        // 真框線要膨脹（補缺口），橋不膨脹：橋本來就是實心線、切開就夠；跟著膨脹會吃掉它
        // 順著跑的那條留白（長線開運算在黑髮團裡會誤判出假框線，閉合再把假線接成長橋）。
        val fr = Cv.dilate(raw, Cv.ellipse(p.gfcDilate * 2 + 1)) or bridge
        val cut = gutter.andNot(fr)
        val cc = Cv.ccStats(cut, 8)
        if (cc.n <= 1) return gutter
        // 「碰得到頁邊」的容差要涵蓋框線膨脹：頁緣本身常是一條長暗線（掃描邊／最外格框），
        // 膨脹後會把貼邊那幾 px 白吃掉 ⇒ 2px 判定會把整片頁邊留白誤判成不碰邊。
        val etol = p.gfcDilate + 3
        val keepId = BooleanArray(cc.n)
        val thin = ArrayList<Int>()
        for (i in 1 until cc.n) {
            val touches = cc.left[i] <= etol || cc.top[i] <= etol ||
                cc.left[i] + cc.width[i] >= w - etol || cc.top[i] + cc.height[i] >= h - etol
            if (touches) keepId[i] = true else thin.add(i)   // 碰得到頁邊＝真留白
        }
        // 只有不碰邊的塊要看粗細：細長（半寬遠小於 coreR）＝真格溝，也留著，防閉合把格溝
        // 橫切成孤島 ⇒ 該填的反而留灰。距離變換整頁算要 52 ms，而這裡只問「max 有沒有超過
        // coreR」，所以逐塊在 bbox 外擴 coreR+8 的視窗內算——視窗邊界只會**低估**距離，而
        // pad 大於門檻，所以「是否 ≤ coreR」的答案與整頁算完全相同。
        for (i in thin) {
            val pad = p.coreR + 8
            val x0 = max(0, cc.left[i] - pad)
            val y0 = max(0, cc.top[i] - pad)
            val x1 = min(w, cc.left[i] + cc.width[i] + pad)
            val y1 = min(h, cc.top[i] + cc.height[i] + pad)
            val sw = x1 - x0
            val sub = Mask(sw, y1 - y0)
            for (y in y0 until y1) {
                val src = y * w
                val dst = (y - y0) * sw - x0
                for (x in x0 until x1) sub.data[dst + x] = cut.data[src + x]
            }
            val sd = Cv.distanceL2(sub)
            var mx = 0f
            for (y in cc.top[i] until cc.top[i] + cc.height[i]) {
                val src = y * w
                val dst = (y - y0) * sw - x0
                for (x in cc.left[i] until cc.left[i] + cc.width[i]) {
                    if (cc.labels[src + x] == i && sd.data[dst + x] > mx) mx = sd.data[dst + x]
                }
            }
            keepId[i] = mx <= p.coreR
        }
        val keep = Mask(w, h)
        for (i in keep.data.indices) {
            val l = cc.labels[i]
            if (l > 0 && keepId[l]) keep.data[i] = true
        }
        if (!keep.any()) return gutter           // 全切光＝框線偵測異常，退回不切
        // 框線帶回填：否則格框旁留一圈白
        val near = Cv.dilate(keep, Cv.ellipse((p.gfcDilate + 2) * 2 + 1))
        for (i in keep.data.indices) {
            if (gutter.data[i] && fr.data[i] && near.data[i]) keep.data[i] = true
        }
        return keep
    }

    // ── 白元件分類 ───────────────────────────────────────────────────

    /** 白元件分類的結果：留白（填深）與格內白（當畫面壓暗）。 */
    class WhiteComponents(
        val cc: CC,
        val gutterIds: Set<Int>,
        val panelIds: Set<Int>,
    )

    /**
     * 整頁白連通元件一次算完，留白與氣泡共用同一份。
     *
     * 貼頁邊的元件逐顆分類：厚芯大量深入頁內＝格內白（出血格的天空）；深入且包住線稿＝白包畫，
     * 也改判畫面。其餘才是真留白。
     */
    fun classifyWhiteComponents(g: Gray, p: NightReadParams): WhiteComponents {
        val w = g.w
        val h = g.h
        val white = g.ge(p.whiteTh)
        val cc = Cv.ccStats(white, 8)
        val deepPx = max(64, (p.deepEdgeFrac * min(w, h)).roundToInt())
        val minArea = (g.data.size * p.gutterMinAreaFrac).toInt()

        // 先挑出真正要判斷的元件：夠大、且貼頁邊。距離變換是整頁的重活（60 ms），
        // 沒有候選就完全不必算。
        val cands = (1 until cc.n).filter { i ->
            cc.area[i] >= minArea &&
                (cc.left[i] <= 2 || cc.top[i] <= 2 ||
                    cc.left[i] + cc.width[i] >= w - 2 || cc.top[i] + cc.height[i] >= h - 2)
        }
        val gutter = HashSet<Int>()
        val panel = HashSet<Int>()
        if (cands.isEmpty()) return WhiteComponents(cc, gutter, panel)
        val dist = Cv.distanceL2(white)

        // ⚠️ 逐元件掃各自的 bbox 會重複掃描：留白元件的 bbox 常常涵蓋整頁（格溝從頁頂連到頁底），
        // 候選有 n 顆就掃 n 次全頁。改成**掃一次全頁、按標號累計到各自的計數器**。
        val coreCnt = IntArray(cc.n)
        val deepCnt = IntArray(cc.n)
        val wanted = BooleanArray(cc.n)
        for (i in cands) wanted[i] = true
        for (yy in 0 until h) {
            val base = yy * w
            val edY = min(yy, h - 1 - yy)
            for (xx in 0 until w) {
                val idx = base + xx
                val l = cc.labels[idx]
                if (l == 0 || !wanted[l]) continue
                if (dist.data[idx] <= p.coreR) continue
                coreCnt[l]++
                if (min(min(xx, w - 1 - xx), edY) > deepPx) deepCnt[l]++
            }
        }

        for (i in cands) {
            val a = cc.area[i]
            val coreCount = coreCnt[i]
            val coreDeep = deepCnt[i]
            val coreFrac = coreCount.toDouble() / a
            val deepFrac = if (coreCount > 0) coreDeep.toDouble() / coreCount else 0.0

            var inPanel = coreFrac >= p.inPanelCoreFrac && deepFrac >= p.inPanelCoreDeep
            if (!inPanel && deepFrac >= p.deepInkDeep) {
                inPanel = holeInkRatio(cc, i, g, p) >= p.deepInkRatio
            }
            if (inPanel) panel.add(i) else gutter.add(i)
        }
        return WhiteComponents(cc, gutter, panel)
    }

    /**
     * 元件包住的線稿量：「小洞內的墨」除以元件面積。只計小洞——大洞是被留白環住的整格，
     * 不是包線稿。
     */
    internal fun holeInkRatio(cc: CC, id: Int, g: Gray, p: NightReadParams): Double {
        val x = cc.left[id]
        val y = cc.top[id]
        val cw = cc.width[id]
        val ch = cc.height[id]
        val comp = Mask(cw, ch)
        for (yy in 0 until ch) {
            val src = (y + yy) * g.w
            for (xx in 0 until cw) comp.data[yy * cw + xx] = cc.labels[src + x + xx] == id
        }
        val holes = Cv.holes(comp)
        if (!holes.any()) return 0.0
        val hcc = Cv.ccStats(holes, 8)
        val limit = p.holeMaxFrac * g.data.size
        var ink = 0
        for (j in 1 until hcc.n) {
            if (hcc.area[j] >= limit) continue
            for (yy in 0 until ch) {
                val src = (y + yy) * g.w + x
                for (xx in 0 until cw) {
                    if (hcc.labels[yy * cw + xx] == j && g.data[src + xx] < p.inkDarkTh) ink++
                }
            }
        }
        return ink.toDouble() / max(1, cc.area[id])
    }

    // ── 核心填色 ─────────────────────────────────────────────────────

    /**
     * 從種子出發、不擠過窄頸的寬闊區才算背景：先開運算切窄頸，只留種子所在的寬闊連通塊，
     * 再往墨線邊做小半徑測地回收（貼合線稿、不留白圈）。
     *
     * 臉與白衣即使因線稿缺口與背景同元件，也會在頸口被切斷——**幾何保護，不是門檻保護**。
     */
    fun broadCoreFill(comp: Mask, seeds: Mask, neckR: Int, recoverR: Int): Mask {
        val core = Cv.open(comp, Cv.ellipse(neckR * 2 + 1))
        if (!core.any()) return Mask(comp.w, comp.h)
        val cc = Cv.ccStats(core, 8)
        val keepIds = HashSet<Int>()
        for (i in seeds.data.indices) {
            if (seeds.data[i] && core.data[i]) keepIds.add(cc.labels[i])
        }
        keepIds.remove(0)
        if (keepIds.isEmpty()) return Mask(comp.w, comp.h)
        val filled = Mask(comp.w, comp.h)
        for (i in filled.data.indices) filled.data[i] = cc.labels[i] in keepIds
        return Cv.geodesicGrow(filled, comp, recoverR, step = 3)
    }

    // ── 氣泡 ─────────────────────────────────────────────────────────

    /**
     * 氣泡遮罩的結果，附上下游要用的元件分類。
     *
     * [bubble] 含漏泡封縫救回的泡；[sealed]＝其中只有封縫才新增的部分（研究端 `local_out["mask"]`）：它只進泡的重繪層
     * （泡重繪、偽泡、亮島、人物還原的泡優先），不進格溝／留白／出血過濾／線稿密度否決／貼紙／泡外圈這些結構層。
     */
    class BubbleResult(val bubble: Mask, val cored: Set<Int>, val sealed: Mask)

    /**
     * 氣泡內部遮罩：每個文字區的 bbox 外擴一個搜尋窗，找貼著文字筆畫的白色連通元件。
     *
     * 兩道守門擋掉整片背景被當成泡：整頁佔比上限，以及「面積不得超過搜尋窗的四倍」的局部性。
     * 夠大的泡不整顆填，改走文字種子核心填色。還有一道面積比：泡核心不得超過字框長邊平方的
     * `safeBubbleRatio` 倍——分母用長邊平方而不是字框面積，否則單行直排的字框會讓比值假性爆表。
     * 核心再過一道字壓背景閘：非字邊界貼墨比例 ≥ [NightReadParams.bubbleOutlineMin]（字寫在天空／牆面上的白
     * 不是泡；外圈有彩不判）。
     *
     * [chroma] 每像素彩度（max−min 通道），閘門的彩頁豁免用；null＝不豁免、全判。
     *
     * 漏泡封縫（[NightReadParams.bubbleSeal]，r > 0 時）：字碰到的白元件因「太大」或「列為留白／格內白」被拒時，另把它交給
     * [BubbleSealer]——只封 ≤ 2r px 的極窄縫，字所在的白若因此自成一塊，那一塊當成新元件走同一條泡路徑（另加幾道閘）。
     * [charRaw] 是原始人物遮罩（未收邊未平滑），封縫的人物關用；null＝封縫一律不收（人物關必拒，所以乾脆不建 [BubbleSealer]，
     * 判定與研究端 `char_raw=None` 相同）。
     */
    fun buildBubbleMask(
        g: Gray,
        regions: List<TextRegion>,
        seg: Mask,
        cc: CC,
        excluded: Set<Int>,
        p: NightReadParams,
        chroma: Gray? = null,
        debug: NightReadDebug? = null,
        charRaw: Mask? = null,
    ): BubbleResult {
        val w = g.w
        val h = g.h
        val segDil = Cv.dilate(seg, Cv.rect(9, 9))
        // 外擴 7 的筆畫＝「字附近」：字壓背景閘的非字邊界要扣掉它（同 compose 的 clean 判準）
        val segClean = Cv.dilate(seg, Cv.ellipse(7))
        val bubble = Mask(w, h)
        val sealer = if (p.bubbleSeal.r > 0 && charRaw != null) {
            BubbleSealer(g, regions, seg, segDil, segClean, cc, chroma, charRaw, p)
        } else {
            null
        }
        val merged = HashSet<Int>()
        val rejected = HashSet<Int>()
        val cored = HashSet<Int>()

        // 相連的雙泡是同一個白元件，用單一字框當分母會讓比值假性超標 ⇒ 分母是該元件所有
        // 命中字區的長邊平方總和
        val compDen = HashMap<Int, Long>()
        // 局部性檢查（面積 ≤ bubbleLocalK × 搜尋窗）用該元件所有命中字區的最大窗，不用當下字區的窗：
        // c362_011 雙泡的大泡白元件也被鄰近小字框「當然」碰到（K×窗 69156 < 元件 102025）先被拒收，
        // rejected 又是黏的，輪到它自己的大字框（K×窗 211480）時已救不回 ⇒ 迭代順序決定收拒、整顆泡留白
        val compWin = HashMap<Int, Long>()
        // 封縫用：每個元件最後一個碰到它的字區（下面第二趟迴圈同一個判法），過了就放掉它的前置（整窗元件／深白／標號）
        val lastRegion = if (sealer != null) IntArray(cc.n) { -1 } else null
        for ((ri, r) in regions.withIndex()) {
            val cx0 = max(0, r.x0 - p.bubblePad)
            val cy0 = max(0, r.y0 - p.bubblePad)
            val cx1 = min(w, r.x1 + p.bubblePad)
            val cy1 = min(h, r.y1 + p.bubblePad)
            val long = max(1, max(r.x1 - r.x0, r.y1 - r.y0))
            val add = long.toLong() * long
            val winArea = (cx1 - cx0).toLong() * (cy1 - cy0)
            val seen = HashSet<Int>()
            for (y in cy0 until cy1) {
                val base = y * w
                for (x in cx0 until cx1) {
                    if (!segDil.data[base + x]) continue
                    val l = cc.labels[base + x]
                    if (l > 0) seen.add(l)
                }
            }
            for (l in seen) {
                compDen[l] = (compDen[l] ?: 0L) + add
                compWin[l] = max(compWin[l] ?: 0L, winArea)
                if (lastRegion != null) lastRegion[l] = ri
            }
        }

        for ((ri, r) in regions.withIndex()) {
            val cx0 = max(0, r.x0 - p.bubblePad)
            val cy0 = max(0, r.y0 - p.bubblePad)
            val cx1 = min(w, r.x1 + p.bubblePad)
            val cy1 = min(h, r.y1 + p.bubblePad)
            val winArea = (cx1 - cx0).toLong() * (cy1 - cy0)
            val touch = LinkedHashSet<Int>()
            for (y in cy0 until cy1) {
                val base = y * w
                for (x in cx0 until cx1) {
                    if (!segDil.data[base + x]) continue
                    val l = cc.labels[base + x]
                    if (l > 0) touch.add(l)
                }
            }
            val long = max(1, max(r.x1 - r.x0, r.y1 - r.y0))
            for (i in touch) {
                val a = cc.area[i]
                val big = a > p.bubbleCompMaxFrac * g.data.size || a > p.bubbleLocalK * (compWin[i] ?: winArea)
                // 漏泡封縫：整顆不能當泡，但字所在的白可能只是經 ≤ 2r px 的縫漏出去 ⇒ 封縫後若自成一塊，走原本的泡路徑。
                // 放在黏著判斷之前：大元件第一次被拒後，別的字區碰到它照樣要看自己那塊（封縫自己有逐塊的黏著）。
                if (sealer != null && (big || i in excluded)) sealer.region(i, r)
                if ((i in merged || i in rejected) && i !in excluded) continue
                if (big) {
                    rejected.add(i); continue
                }
                if (i in excluded) continue      // 留白/格內白元件不當泡（字交偽泡貼身填色）

                val den = (compDen[i] ?: (long.toLong() * long)).toDouble()
                val ratioDen = p.safeBubbleRatio * den
                if (a < p.bubbleCoreMinFrac * g.data.size && a > ratioDen) {
                    rejected.add(i); continue
                }
                if (a >= p.bubbleCoreMinFrac * g.data.size) {
                    val bx = cc.left[i]
                    val by = cc.top[i]
                    val bw = cc.width[i]
                    val bh = cc.height[i]
                    val comp = Mask(bw, bh)
                    for (yy in 0 until bh) {
                        val src = (by + yy) * w + bx
                        for (xx in 0 until bw) comp.data[yy * bw + xx] = cc.labels[src + xx] == i
                    }
                    val seed = Mask(bw, bh)
                    val sx0 = max(0, r.x0 - bx)
                    val sy0 = max(0, r.y0 - by)
                    val sx1 = min(bw, r.x1 - bx)
                    val sy1 = min(bh, r.y1 - by)
                    if (sx1 > sx0 && sy1 > sy0) {
                        for (yy in sy0 until sy1) for (xx in sx0 until sx1) seed.data[yy * bw + xx] = true
                    }
                    val core = broadCoreFill(comp, comp and seed, p.bubbleNeckR, p.bubbleNeckR)
                    if (!core.any()) { rejected.add(i); continue }
                    if (core.count() > ratioDen) { rejected.add(i); continue }
                    if (p.bubbleOutlineMin > 0) {
                        // 字壓背景閘（2026-09-27）：core 是「字所在的白」，但字寫在天空／牆面上時那片白不是泡——
                        // 泡由自己的框線圍住（非字邊界幾乎全貼墨），背景白的邊界是網點灰／雲線／別人的線稿。
                        // 彩頁例外：泡框／底可能是淡彩（demo04 淡紫框、爆炸泡的斜線底），墨判準不成立 ⇒ 外圈有彩就不判。
                        // 量測窗＝元件 bbox 外擴 bubbleOutlinePad（距離變換要看得到 bbox 外的墨），核心貼進窗內座標。
                        val px0 = max(0, bx - p.bubbleOutlinePad)
                        val py0 = max(0, by - p.bubbleOutlinePad)
                        val px1 = min(w, bx + bw + p.bubbleOutlinePad)
                        val py1 = min(h, by + bh + p.bubbleOutlinePad)
                        val ww = px1 - px0
                        val wh = py1 - py0
                        val coreW = Mask(ww, wh)
                        for (yy in 0 until bh) {
                            val dst = (by - py0 + yy) * ww + (bx - px0)
                            for (xx in 0 until bw) if (core.data[yy * bw + xx]) coreW.data[dst + xx] = true
                        }
                        // 非字邊界＝core 的 1 px 內邊界（3×3 腐蝕的差；影像外側視為前景，與 cv2 同）扣掉外擴筆畫
                        val eroded = Cv.erode(coreW, Cv.rect(3, 3))
                        val bnd = Mask(ww, wh)
                        var nb = 0
                        for (yy in 0 until wh) {
                            val src = (py0 + yy) * w + px0
                            for (xx in 0 until ww) {
                                val k = yy * ww + xx
                                if (coreW.data[k] && !eroded.data[k] && !segClean.data[src + xx]) { bnd.data[k] = true; nb++ }
                            }
                        }
                        if (nb >= p.bubbleOutlineMinPx) {
                            var achromatic = true
                            if (chroma != null) {
                                // 外圈 1–3 px（core 膨脹 7×7 橢圓減 core、扣字）的平均彩度
                                val ring = Cv.dilate(coreW, Cv.ellipse(7))
                                var sum = 0L
                                var cnt = 0
                                for (yy in 0 until wh) {
                                    val src = (py0 + yy) * w + px0
                                    for (xx in 0 until ww) {
                                        val k = yy * ww + xx
                                        if (ring.data[k] && !coreW.data[k] && !segClean.data[src + xx]) { sum += chroma.data[src + xx]; cnt++ }
                                    }
                                }
                                achromatic = cnt == 0 || sum.toDouble() / cnt <= p.stickerChromaMax
                            }
                            if (achromatic) {
                                // 窗內對「非墨」做距離變換 ⇒ 每個邊界像素到最近墨像素的距離（python 是 3×3 chamfer 近似，
                                // 這裡精確歐氏；審查者精確圓盤重算 118 顆、本機 fixture 7 顆核心的比例都與 cv2 值到小數第三位相同）
                                val nonInk = Mask(ww, wh)
                                for (yy in 0 until wh) {
                                    val src = (py0 + yy) * w + px0
                                    for (xx in 0 until ww) nonInk.data[yy * ww + xx] = g.data[src + xx] >= p.inkDarkTh
                                }
                                val dd = Cv.distanceL2(nonInk)
                                var near = 0
                                for (k in bnd.data.indices) if (bnd.data[k] && dd.data[k] <= p.bubbleOutlineDist) near++
                                val bi = near.toDouble() / nb
                                debug?.invoke("bubbleOutline[$bx,$by,$bw,$bh]‰", (bi * 1000).roundToInt())
                                if (bi < p.bubbleOutlineMin) {
                                    rejected.add(i); continue   // 邊界不貼墨＝沒有框線圍住＝字壓背景，不是泡
                                }
                            }
                        }
                    }
                    for (yy in 0 until bh) {
                        val dst = (by + yy) * w + bx
                        for (xx in 0 until bw) if (core.data[yy * bw + xx]) bubble.data[dst + xx] = true
                    }
                    merged.add(i)
                    cored.add(i)
                    continue
                } else {
                    // ⚠️ 只掃該元件的 bbox，不掃全頁：這條路徑每顆小泡走一次，
                    // 掃全頁的話成本是「泡數 × 2.6 MPx」
                    for (yy in cc.top[i] until cc.top[i] + cc.height[i]) {
                        val base = yy * w
                        for (xx in cc.left[i] until cc.left[i] + cc.width[i]) {
                            if (cc.labels[base + xx] == i) bubble.data[base + xx] = true
                        }
                    }
                }
                merged.add(i)
            }
            // 之後不會再有字區碰到的元件：放掉它的封縫前置（前置窗加總可以跟整頁一樣大，別全部留到最後）
            if (sealer != null && lastRegion != null) for (i in touch) if (lastRegion[i] == ri) sealer.release(i)
            // 區內筆畫本身一定算氣泡內容
            for (y in r.y0 until min(h, r.y1)) {
                val base = y * w
                for (x in r.x0 until min(w, r.x1)) if (seg.data[base + x]) bubble.data[base + x] = true
            }
        }
        val local = sealer?.local
        if (local == null || !local.any()) return BubbleResult(bubble, cored, Mask(w, h))
        val sealed = local.andNot(bubble)
        debug?.invoke("bubbleSealed", sealed.count())
        return BubbleResult(bubble or local, cored, sealed)
    }

    /** 墨（[ink]）裡離非墨 ≥ [NightReadParams.pbAuraThick] 的像素。 */
    private fun thickPixels(ink: Mask, p: NightReadParams): Mask {
        val dist = Cv.distanceL2(ink)
        return Mask(ink.w, ink.h, BooleanArray(ink.data.size) { ink.data[it] && dist.data[it] >= p.pbAuraThick })
    }

    /**
     * 厚墨灰暈：距離「厚墨塊」一定距離內不填。厚墨塊＝距離變換夠大、面積夠大的暗區，
     * 所以字框與泡框那種細筆畫不算。這是臉旁髮團的第二道保險。
     */
    fun thickInkAura(g: Gray, seg: Mask?, p: NightReadParams): Mask {
        var ink = g.lt(p.whiteTh)
        if (seg != null) ink = ink.andNot(seg)      // 粗體字筆畫本身也 ≥6px，不扣掉會在每個字周圍挖洞
        // 「厚」是元件的性質不是像素的性質：面積夠大、且元件內部最厚處 ≥ thick，整顆才算厚墨。
        // 「最厚處 ≥ thick」＝元件裡有任何一個像素的距離 ≥ thick：先把距離變換收成一張遮罩再做連通元件，
        // 兩個窗大小的大陣列（距離 4 B/px、標號＋並查集 6 B/px）就不會同時活著
        val deep = thickPixels(ink, p)
        val cc = Cv.ccStats(ink, 8)
        val hasDeep = BooleanArray(cc.n)
        for (i in ink.data.indices) if (deep.data[i]) hasDeep[cc.labels[i]] = true
        val thickIds = BooleanArray(cc.n)
        for (i in 1 until cc.n) {
            thickIds[i] = cc.area[i] >= p.pbAuraMinArea && hasDeep[i]
        }
        val big = Mask(g.w, g.h)
        var anyBig = false
        for (i in big.data.indices) {
            val l = cc.labels[i]
            if (l > 0 && thickIds[l]) { big.data[i] = true; anyBig = true }
        }
        if (!anyBig) return Mask(g.w, g.h)
        return Cv.dilate(big, Cv.ellipse(p.pbAuraR * 2 + 1))
    }
}

/**
 * 漏泡封縫（v3，2026-09-30；研究端 `research/nightread.py` 的 `seal_prep`／`seal_unit`／`seal_labels`／`_seal_region`／
 * `_seal_judge`，逐像素對齊 cv2）。
 *
 * 病根：泡框上 1–2 px 的縫讓泡內的白與外面的大片白連成同一個白元件，元件因「太大」或「列為留白／格內白」整顆被拒，
 * 泡內留場景灰（c362_005:3）。這裡不長局部核心（v1／v2 被退回的做法），只封極窄的縫：
 * 1. 元件 i 的白以 (2r+1) 橢圓侵蝕（r=1 ⇒ 3×3 十字）→ 深白 D（離墨 > r 的白），8 連通標號。頁緣不算墨（外側視為前景，同 cv2
 *    `borderValue=1`）；窗＝元件 bbox 外擴 2r+2，窗的其他邊離元件夠遠。
 * 2. 字（外擴筆畫 segDil，限該字區 bubblePad 窗）碰到的每一塊深白 t，依面積由大到小（同面積依 cv2 標號序＝2×2 區塊掃描序）
 *    各自還原成單元：S＝t ∪（t 外擴 r 內的元件白 − 別塊深白外擴 r 也搆得到的像素＝封縫弧），只留與 t 8 連通的部分，
 *    填洞後 ∩ 元件＝U。外擴只落在「以深白為圓心、半徑 r 的無墨圓盤」裡 ⇒ 不會跨過任何墨線。
 * 3. 縫＝元件內、U（含洞）外、與 U 8 相鄰的白；它的 8 連通群數＝縫的個數。
 * 4. 單元當成新元件走 [Regions.buildBubbleMask] 的泡路徑（閘照舊），另加：碰頁緣（≤ 2 px）拒、單元外深白 ≥ restMin、
 *    縫 ≤ maxGaps、字框筆畫 ≥ textIn 落在單元（含洞）裡、每個縫到泡身（U 以 bubbleNeckR 開運算後含字框的塊）的測地距離
 *    ≤ cutGeo、小單元也過貼墨閘、外圈有彩拒（沒給彩度＝灰階頁，彩度 0）、單元 ∩ 原始人物遮罩 ≤ charMax。
 * 5. 黏著：每個（元件, 深白塊）只判一次（第一個碰到它的字區決定）；已收單元（含洞）裡的深白塊是泡裡的口袋、不另判；
 *    被拒的單元不遮住別人（大單元被拒，它洞裡被封起來的泡照判：c362_004:5）。
 *
 * ⚠️ 逐位元對齊的細節：貼墨閘的距離用 3×3 chamfer（[Cv.distanceChamfer]，同 cv2 `DIST_L2, 3`；精確歐氏在 (5,3) 這種偏移上
 * 會跨過門檻 6）；字進比例、外圈彩度、貼墨比例、人物佔比都是先照 python `round(x, 4／2)` 四捨五入（銀行家捨入、對二進位
 * 精確值）再比門檻；研究端的 `NIGHTREAD_REQUIRE_CLEAN`（研究開關、預設關）不移植。
 */
internal class BubbleSealer(
    private val g: Gray,
    private val regions: List<TextRegion>,
    private val seg: Mask,
    private val segDil: Mask,
    private val segClean: Mask,
    private val cc: CC,
    private val chroma: Gray?,
    private val charRaw: Mask,
    private val p: NightReadParams,
) {
    private val w = g.w
    private val h = g.h
    private val sp = p.bubbleSeal
    private val kernel = Cv.ellipse(2 * sp.r + 1)

    /** 收下的封縫泡（整頁）。 */
    val local = Mask(w, h)

    /** 元件的前置：窗（整頁座標，半開）、窗內元件、深白與其標號。 */
    private class Prep(
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val comp: Mask, val deep: Mask, val lab: CC, val deepTotal: Int,
    ) {
        val pw = x1 - x0
        /** 已收單元（含洞）：之後碰到的深白塊若落在裡面＝泡裡的口袋。 */
        val accepted = ArrayList<SealUnit>()
        private var keys: IntArray? = null

        /** 各深白塊在 cv2 標號序中的位置：cv2 8 連通（Spaghetti）的標號＝首個含該塊像素的 2×2 區塊的掃描序。 */
        fun blockKey(): IntArray {
            keys?.let { return it }
            val k = IntArray(lab.n) { Int.MAX_VALUE }
            val bw2 = (pw + 1) / 2
            val ph = y1 - y0
            for (yy in 0 until ph) {
                val row = (yy / 2) * bw2
                val base = yy * pw
                for (xx in 0 until pw) {
                    val l = lab.labels[base + xx]
                    if (l > 0) {
                        val v = row + xx / 2
                        if (v < k[l]) k[l] = v
                    }
                }
            }
            keys = k
            return k
        }
    }

    /** 一個單元：窗（整頁座標，半開）、U、U 含洞、縫、縫群數、單元外剩的深白。 */
    private class SealUnit(
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val u: Mask, val filled: Mask, val cut: Mask, val cutCc: CC, val rest: Int,
    ) {
        val uw = x1 - x0
        val uh = y1 - y0
        val cutN get() = cutCc.n - 1
    }

    private val preps = HashMap<Int, Prep>()
    /** 判過的（元件, 深白塊）。 */
    private val done = HashSet<Long>()

    /** 元件 [i] 之後不會再有字區碰到：放掉它的前置（判過的記錄留著，很小）。 */
    fun release(i: Int) {
        preps.remove(i)
    }

    /** 字區 [r] 碰到的元件 [i]（太大或留白／格內白）：它碰到的深白塊依序還原成單元、判一次。 */
    fun region(i: Int, r: TextRegion) {
        val prep = preps.getOrPut(i) { prep(i) }
        val lab = prep.lab
        // 字的外擴筆畫（限字區 bubblePad 窗）碰到的深白塊
        val c0 = max(0, r.x0 - p.bubblePad)
        val d0 = max(0, r.y0 - p.bubblePad)
        val c1 = min(w, r.x1 + p.bubblePad)
        val d1 = min(h, r.y1 + p.bubblePad)
        val ix0 = max(c0, prep.x0)
        val iy0 = max(d0, prep.y0)
        val ix1 = min(c1, prep.x1)
        val iy1 = min(d1, prep.y1)
        if (ix1 <= ix0 || iy1 <= iy0) return
        val seen = BooleanArray(lab.n)
        val ids = ArrayList<Int>()
        for (y in iy0 until iy1) {
            val src = y * w
            val dst = (y - prep.y0) * prep.pw - prep.x0
            for (x in ix0 until ix1) {
                if (!segDil.data[src + x]) continue
                val l = lab.labels[dst + x]
                if (l > 0 && !seen[l]) { seen[l] = true; ids.add(l) }
            }
        }
        if (ids.isEmpty()) return
        if (ids.size > 1) {
            val key = prep.blockKey()
            ids.sortWith(compareByDescending<Int> { lab.area[it] }.thenBy { key[it] })
        }
        for (t in ids) {
            val k = (i.toLong() shl 32) or t.toLong()
            if (k in done) continue
            if (insideAccepted(prep, t)) { done.add(k); continue }
            // 深白塊碰頁緣：單元 ⊇ 這塊 ⇒ 單元 bbox 也碰頁緣、edge 閘必拒——不必還原單元（整頁那塊背景白就是這型，省下幾趟整頁運算；
            // 判定與研究端相同）
            val bx0 = prep.x0 + lab.left[t]
            val by0 = prep.y0 + lab.top[t]
            if (bx0 <= 2 || by0 <= 2 || bx0 + lab.width[t] >= w - 2 || by0 + lab.height[t] >= h - 2) { done.add(k); continue }
            // 深白塊自己就超過整頁佔比上限：單元 ⊇ 這塊 ⇒ 整頁佔比（或更前面的閘）必拒——同樣不必還原單元（判定不變；
            // 還原單元的暫存跟單元窗成正比，大塊最貴）
            if (lab.area[t] > p.bubbleCompMaxFrac * g.data.size) { done.add(k); continue }
            val u = unit(prep, t)
            val paint = judge(u, r)
            done.add(k)
            if (paint != null) {
                for (yy in 0 until u.uh) {
                    val dst = (u.y0 + yy) * w + u.x0
                    val sb = yy * u.uw
                    for (xx in 0 until u.uw) if (paint.data[sb + xx]) local.data[dst + xx] = true
                }
                prep.accepted.add(u)
            }
        }
    }

    private fun prep(i: Int): Prep {
        val m = 2 * sp.r + 2
        val x0 = max(0, cc.left[i] - m)
        val y0 = max(0, cc.top[i] - m)
        val x1 = min(w, cc.left[i] + cc.width[i] + m)
        val y1 = min(h, cc.top[i] + cc.height[i] + m)
        val pw = x1 - x0
        val comp = Mask(pw, y1 - y0)
        for (yy in 0 until y1 - y0) {
            val src = (y0 + yy) * w + x0
            for (xx in 0 until pw) comp.data[yy * pw + xx] = cc.labels[src + xx] == i
        }
        val deep = Cv.erode(comp, kernel)          // 外側視為前景＝頁緣不算墨
        return Prep(x0, y0, x1, y1, comp, deep, Cv.ccStats(deep, 8), deep.count())
    }

    /** 深白塊 [t] 的左上角落在某個已收單元窗內、且該塊在窗內有像素落在單元（含洞）裡 ⇒ 泡裡的口袋（研究端同一個判法）。 */
    private fun insideAccepted(prep: Prep, t: Int): Boolean {
        val bx = prep.lab.left[t] + prep.x0
        val by = prep.lab.top[t] + prep.y0
        for (a in prep.accepted) {
            if (bx < a.x0 || bx >= a.x1 || by < a.y0 || by >= a.y1) continue
            for (y in a.y0 until a.y1) {
                val src = (y - prep.y0) * prep.pw - prep.x0
                val fb = (y - a.y0) * a.uw - a.x0
                for (x in a.x0 until a.x1) {
                    if (prep.lab.labels[src + x] == t && a.filled.data[fb + x]) return true
                }
            }
        }
        return false
    }

    /** 深白塊 [t] 還原成單元；只在該塊 bbox 外擴 r+2 的小窗裡算（與整窗算逐像素相同）。 */
    private fun unit(prep: Prep, t: Int): SealUnit {
        val lab = prep.lab
        val m = sp.r + 2
        val ph = prep.y1 - prep.y0
        val cx0 = max(0, lab.left[t] - m)
        val cy0 = max(0, lab.top[t] - m)
        val cx1 = min(prep.pw, lab.left[t] + lab.width[t] + m)
        val cy1 = min(ph, lab.top[t] + lab.height[t] + m)
        val uw = cx1 - cx0
        val uh = cy1 - cy0
        val n = uw * uh
        val comp = Mask(uw, uh)
        val s0 = Mask(uw, uh)
        val oth = Mask(uw, uh)
        var s0n = 0
        for (yy in 0 until uh) {
            val src = (cy0 + yy) * prep.pw + cx0
            for (xx in 0 until uw) {
                val k = yy * uw + xx
                comp.data[k] = prep.comp.data[src + xx]
                val l = lab.labels[src + xx]
                if (l == t) { s0.data[k] = true; s0n++ } else if (l > 0) oth.data[k] = true
            }
        }
        val rin = Cv.dilate(s0, kernel)
        val rout = Cv.dilate(oth, kernel)
        val s = Mask(uw, uh)
        for (k in 0 until n) s.data[k] = (s0.data[k] || (rin.data[k] && !rout.data[k])) && comp.data[k]
        // 只留與 t 8 連通的部分（t 本身 8 連通 ⇒ 從 t 泛洪即可）
        val keep = Mask(uw, uh)
        val stack = IntArray(n)
        var spn = 0
        for (k in 0 until n) if (s0.data[k]) { keep.data[k] = true; stack[spn++] = k }
        while (spn > 0) {
            val k = stack[--spn]
            val x = k % uw
            val y = k / uw
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= uh) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= uw) continue
                    val j = yy * uw + xx
                    if (s.data[j] && !keep.data[j]) { keep.data[j] = true; stack[spn++] = j }
                }
            }
        }
        val holes = Cv.holes(keep)
        val filled = Mask(uw, uh, BooleanArray(n) { keep.data[it] || holes.data[it] })
        val u = filled and comp
        val near = Cv.dilate(filled, Cv.rect(3, 3))
        val cut = Mask(uw, uh, BooleanArray(n) { comp.data[it] && !filled.data[it] && near.data[it] })
        var inDeep = 0
        for (yy in 0 until uh) {
            val src = (cy0 + yy) * prep.pw + cx0
            for (xx in 0 until uw) if (filled.data[yy * uw + xx] && prep.deep.data[src + xx]) inDeep++
        }
        return SealUnit(prep.x0 + cx0, prep.y0 + cy0, prep.x0 + cx1, prep.y0 + cy1, u, filled, cut,
            Cv.ccStats(cut, 8), prep.deepTotal - inDeep)
    }

    /** python `round(x, nd)`：對 double 的二進位精確值做銀行家捨入（門檻比的是捨入後的值）。 */
    private fun pyRound(x: Double, nd: Int): Double =
        java.math.BigDecimal(x).setScale(nd, java.math.RoundingMode.HALF_EVEN).toDouble()

    /** 單元走 HEAD 的泡路徑＋封縫附加閘；收＝回傳要畫的（單元窗內），拒＝null。閘的順序同研究端 `_seal_judge`。 */
    private fun judge(u: SealUnit, r: TextRegion): Mask? {
        val uw = u.uw
        val uh = u.uh
        val um = u.u
        var aU = 0
        var mnx = Int.MAX_VALUE
        var mny = Int.MAX_VALUE
        var mxx = -1
        var mxy = -1
        for (yy in 0 until uh) for (xx in 0 until uw) {
            if (!um.data[yy * uw + xx]) continue
            aU++
            if (xx < mnx) mnx = xx
            if (xx > mxx) mxx = xx
            if (yy < mny) mny = yy
            if (yy > mxy) mxy = yy
        }
        if (aU == 0) return null
        // edge：碰頁緣（同 classifyWhiteComponents 的留白候選判準）
        val ux0 = mnx + u.x0
        val uy0 = mny + u.y0
        val ux1 = mxx + u.x0 + 1
        val uy1 = mxy + u.y0 + 1
        if (ux0 <= 2 || uy0 <= 2 || ux1 >= w - 2 || uy1 >= h - 2) return null
        if (u.rest < sp.restMin) return null                // no-split
        if (u.cutN > sp.maxGaps) return null               // porous
        // textIn：字框內筆畫落在單元（含洞）裡的比例
        var ns = 0
        for (y in max(0, r.y0) until min(h, r.y1)) {
            val base = y * w
            for (x in max(0, r.x0) until min(w, r.x1)) if (seg.data[base + x]) ns++
        }
        val sx0 = max(r.x0, u.x0)
        val sy0 = max(r.y0, u.y0)
        val sx1 = min(r.x1, u.x1)
        val sy1 = min(r.y1, u.y1)
        var inside = 0
        if (sx1 > sx0 && sy1 > sy0) {
            for (y in sy0 until sy1) {
                val base = y * w
                val fb = (y - u.y0) * uw - u.x0
                for (x in sx0 until sx1) if (seg.data[base + x] && u.filled.data[fb + x]) inside++
            }
        }
        val textIn = if (ns > 0) pyRound(inside.toDouble() / ns, 4) else 0.0
        if (textIn < sp.textIn) return null
        // HEAD：整頁佔比、局部性（分母＝碰到這個單元的所有字區的最大窗）
        val (den0, cw) = denominators(u)
        if (aU > p.bubbleCompMaxFrac * g.data.size) return null
        if (aU > p.bubbleLocalK * max(cw, 1L)) return null
        // cut-far：每個縫到泡身的測地距離
        if (!cutNearBody(u, r)) return null
        val den = p.safeBubbleRatio * max(den0, 1L)
        var paint = um
        if (aU < p.bubbleCoreMinFrac * g.data.size) {
            if (p.safeBubbleRatio > 0 && aU > den) return null
        } else {
            val seeds = Mask(uw, uh)
            val bx0 = max(0, r.x0 - u.x0)
            val by0 = max(0, r.y0 - u.y0)
            val bx1 = min(uw, r.x1 - u.x0)
            val by1 = min(uh, r.y1 - u.y0)
            if (bx1 > bx0 && by1 > by0) {
                for (yy in by0 until by1) for (xx in bx0 until bx1) seeds.data[yy * uw + xx] = um.data[yy * uw + xx]
            }
            val core = Regions.broadCoreFill(um, seeds, p.bubbleNeckR, p.bubbleNeckR)
            val nc = core.count()
            if (nc == 0) return null
            if (p.safeBubbleRatio > 0 && nc > den) return null
            paint = core
        }
        // 貼墨閘（大小單元都判）；外圈有彩一律不收
        val px0 = max(0, u.x0 - p.bubbleOutlinePad)
        val py0 = max(0, u.y0 - p.bubbleOutlinePad)
        val px1 = min(w, u.x1 + p.bubbleOutlinePad)
        val py1 = min(h, u.y1 + p.bubbleOutlinePad)
        val ww = px1 - px0
        val wh = py1 - py0
        val coreW = Mask(ww, wh)
        for (yy in 0 until uh) {
            val dst = (u.y0 - py0 + yy) * ww + (u.x0 - px0)
            for (xx in 0 until uw) if (paint.data[yy * uw + xx]) coreW.data[dst + xx] = true
        }
        val eroded = Cv.erode(coreW, Cv.rect(3, 3))
        val ring = Cv.dilate(coreW, Cv.ellipse(7))
        val bnd = Mask(ww, wh)
        var nb = 0
        var ringN = 0
        var ringSum = 0L
        for (yy in 0 until wh) {
            val src = (py0 + yy) * w + px0
            for (xx in 0 until ww) {
                val k = yy * ww + xx
                if (segClean.data[src + xx]) continue
                if (coreW.data[k]) {
                    if (!eroded.data[k]) { bnd.data[k] = true; nb++ }
                } else if (ring.data[k]) {
                    ringN++
                    if (chroma != null) ringSum += chroma.data[src + xx]
                }
            }
        }
        if (ringN == 0) return null                        // 外圈量不到＝不收（研究端 None）
        if (pyRound(ringSum.toDouble() / ringN, 2) > p.stickerChromaMax) return null
        if (nb >= p.bubbleOutlineMinPx) {
            val nonInk = Mask(ww, wh)
            for (yy in 0 until wh) {
                val src = (py0 + yy) * w + px0
                for (xx in 0 until ww) nonInk.data[yy * ww + xx] = g.data[src + xx] >= p.inkDarkTh
            }
            val dd = Cv.distanceChamfer(nonInk, 3)
            var near = 0
            for (k in bnd.data.indices) if (bnd.data[k] && dd.data[k] <= p.bubbleOutlineDist) near++
            if (pyRound(near.toDouble() / nb, 4) < p.bubbleOutlineMin) return null
        }
        // 人物關：實際要填的 ∩ 原始人物遮罩（沒給人物遮罩時根本不建 BubbleSealer，同研究端全拒）
        val cr = charRaw
        var pn = 0
        var pc = 0
        for (yy in 0 until uh) {
            val src = (u.y0 + yy) * w + u.x0
            for (xx in 0 until uw) {
                if (!paint.data[yy * uw + xx]) continue
                pn++
                if (cr.data[src + xx]) pc++
            }
        }
        if (pn == 0 || pyRound(pc.toDouble() / pn, 4) > sp.charMax) return null
        return paint
    }

    /** 局部性／比值分母：碰到這個單元（外擴筆畫 ∩ U）的所有字區——長邊² 總和、最大搜尋窗（同 HEAD 的 compDen／compWin）。 */
    private fun denominators(u: SealUnit): Pair<Long, Long> {
        var den = 0L
        var cw = 0L
        for (r in regions) {
            val e0 = max(0, r.x0 - p.bubblePad)
            val f0 = max(0, r.y0 - p.bubblePad)
            val e1 = min(w, r.x1 + p.bubblePad)
            val f1 = min(h, r.y1 + p.bubblePad)
            val ix0 = max(e0, u.x0)
            val iy0 = max(f0, u.y0)
            val ix1 = min(e1, u.x1)
            val iy1 = min(f1, u.y1)
            if (ix1 <= ix0 || iy1 <= iy0) continue
            var hit = false
            loop@ for (y in iy0 until iy1) {
                val base = y * w
                val ub = (y - u.y0) * u.uw - u.x0
                for (x in ix0 until ix1) if (u.u.data[ub + x] && segDil.data[base + x]) { hit = true; break@loop }
            }
            if (!hit) continue
            val l = max(r.x1 - r.x0, r.y1 - r.y0).toLong()
            den += max(1L, l * l)
            cw = max(cw, (e1 - e0).toLong() * (f1 - f0))
        }
        return den to cw
    }

    /**
     * cut-far：泡身＝U 以 bubbleNeckR 開運算後、含字框像素的寬闊塊；在 U∪縫內 8 連通走（每步 1 px），每個縫到泡身的距離
     * 都 ≤ cutGeo（null＝3 × bubbleNeckR）才過。沒有泡身＝不過；沒有縫＝0。走到 cutGeo+1 步為止（研究端的膨脹迴圈同上限）。
     */
    private fun cutNearBody(u: SealUnit, r: TextRegion): Boolean {
        val uw = u.uw
        val uh = u.uh
        val n = uw * uh
        val op = Cv.open(u.u, Cv.ellipse(2 * p.bubbleNeckR + 1))
        val occ = Cv.ccStats(op, 8)
        val want = BooleanArray(occ.n)
        val bx0 = max(0, r.x0 - u.x0)
        val by0 = max(0, r.y0 - u.y0)
        val bx1 = min(uw, r.x1 - u.x0)
        val by1 = min(uh, r.y1 - u.y0)
        var anyBody = false
        if (bx1 > bx0 && by1 > by0) {
            for (yy in by0 until by1) for (xx in bx0 until bx1) {
                val l = occ.labels[yy * uw + xx]
                if (l > 0) { want[l] = true; anyBody = true }
            }
        }
        if (!anyBody) return false
        if (u.cutN <= 0) return true
        val lim = sp.cutGeo ?: (3 * p.bubbleNeckR)
        val geo = IntArray(n) { -1 }
        val queue = IntArray(n)
        var qe = 0
        for (k in 0 until n) {
            val l = occ.labels[k]
            if (l > 0 && want[l]) { geo[k] = 0; queue[qe++] = k }
        }
        var qs = 0
        while (qs < qe) {
            val k = queue[qs++]
            val d = geo[k]
            if (d >= lim + 1) continue
            val x = k % uw
            val y = k / uw
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= uh) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= uw) continue
                    val j = yy * uw + xx
                    if (geo[j] < 0 && (u.u.data[j] || u.cut.data[j])) { geo[j] = d + 1; queue[qe++] = j }
                }
            }
        }
        val best = IntArray(u.cutCc.n) { lim + 1 }
        for (k in 0 until n) {
            val l = u.cutCc.labels[k]
            if (l > 0 && geo[k] >= 0 && geo[k] < best[l]) best[l] = geo[k]
        }
        for (l in 1 until u.cutCc.n) if (best[l] > lim) return false
        return true
    }
}
