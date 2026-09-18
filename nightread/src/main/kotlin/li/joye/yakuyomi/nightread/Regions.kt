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
    private fun holeInkRatio(cc: CC, id: Int, g: Gray, p: NightReadParams): Double {
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

    /** 氣泡遮罩的結果，附上下游要用的元件分類。 */
    class BubbleResult(val bubble: Mask, val cored: Set<Int>)

    /**
     * 氣泡內部遮罩：每個文字區的 bbox 外擴一個搜尋窗，找貼著文字筆畫的白色連通元件。
     *
     * 兩道守門擋掉整片背景被當成泡：整頁佔比上限，以及「面積不得超過搜尋窗的四倍」的局部性。
     * 夠大的泡不整顆填，改走文字種子核心填色。還有一道面積比：泡核心不得超過字框長邊平方的
     * `safeBubbleRatio` 倍——分母用長邊平方而不是字框面積，否則單行直排的字框會讓比值假性爆表。
     */
    fun buildBubbleMask(
        g: Gray,
        regions: List<TextRegion>,
        seg: Mask,
        cc: CC,
        excluded: Set<Int>,
        p: NightReadParams,
    ): BubbleResult {
        val w = g.w
        val h = g.h
        val segDil = Cv.dilate(seg, Cv.rect(9, 9))
        val bubble = Mask(w, h)
        val merged = HashSet<Int>()
        val rejected = HashSet<Int>()
        val cored = HashSet<Int>()

        // 相連的雙泡是同一個白元件，用單一字框當分母會讓比值假性超標 ⇒ 分母是該元件所有
        // 命中字區的長邊平方總和
        val compDen = HashMap<Int, Long>()
        for (r in regions) {
            val cx0 = max(0, r.x0 - p.bubblePad)
            val cy0 = max(0, r.y0 - p.bubblePad)
            val cx1 = min(w, r.x1 + p.bubblePad)
            val cy1 = min(h, r.y1 + p.bubblePad)
            val long = max(1, max(r.x1 - r.x0, r.y1 - r.y0))
            val add = long.toLong() * long
            val seen = HashSet<Int>()
            for (y in cy0 until cy1) {
                val base = y * w
                for (x in cx0 until cx1) {
                    if (!segDil.data[base + x]) continue
                    val l = cc.labels[base + x]
                    if (l > 0) seen.add(l)
                }
            }
            for (l in seen) compDen[l] = (compDen[l] ?: 0L) + add
        }

        for (r in regions) {
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
                if ((i in merged || i in rejected) && i !in excluded) continue
                val a = cc.area[i]
                if (a > p.bubbleCompMaxFrac * g.data.size || a > p.bubbleLocalK * winArea) {
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
            // 區內筆畫本身一定算氣泡內容
            for (y in r.y0 until min(h, r.y1)) {
                val base = y * w
                for (x in r.x0 until min(w, r.x1)) if (seg.data[base + x]) bubble.data[base + x] = true
            }
        }
        return BubbleResult(bubble, cored)
    }

    /**
     * 厚墨灰暈：距離「厚墨塊」一定距離內不填。厚墨塊＝距離變換夠大、面積夠大的暗區，
     * 所以字框與泡框那種細筆畫不算。這是臉旁髮團的第二道保險。
     */
    fun thickInkAura(g: Gray, seg: Mask?, p: NightReadParams): Mask {
        var ink = g.lt(p.whiteTh)
        if (seg != null) ink = ink.andNot(seg)      // 粗體字筆畫本身也 ≥6px，不扣掉會在每個字周圍挖洞
        val dist = Cv.distanceL2(ink)
        val cc = Cv.ccStats(ink, 8)
        // 「厚」是元件的性質不是像素的性質：面積夠大、且元件內部最厚處 ≥ thick，整顆才算厚墨
        val maxDist = FloatArray(cc.n)
        for (i in ink.data.indices) {
            val l = cc.labels[i]
            if (l > 0 && dist.data[i] > maxDist[l]) maxDist[l] = dist.data[i]
        }
        val thickIds = BooleanArray(cc.n)
        for (i in 1 until cc.n) {
            thickIds[i] = cc.area[i] >= p.pbAuraMinArea && maxDist[i] >= p.pbAuraThick
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
