package li.joye.yakuyomi.nightread

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 夜讀重繪的入口與合成階段。
 *
 * 用法：偵測與人物分割在外面做完，把灰階、文字筆畫遮罩、文字區、人物遮罩包成 [NightReadInput]
 * 丟進 [render]，拿回重建好的暗色頁。這個模組不碰任何推論框架也不碰 android.graphics，
 * 所以 JVM 測試可以對 Python 的 fixture 直接比對。
 *
 * 分區的待遇與各階段在判斷什麼，見 `docs/ARCHITECTURE.md`。
 */
/** 各階段的中間量（遮罩面積等），parity 除錯用。正式路徑傳 null。 */
typealias NightReadDebug = (stage: String, value: Int) -> Unit

object NightRead {

    /**
     * 重繪一頁。
     *
     * 前半是分析（頁型、白元件、氣泡、貼紙計畫），後半是合成（場景曲線 → 留白 → 貼紙 →
     * 氣泡 → 偽泡 → 人頭一致化 → 剩餘填色 → 人物還原）。
     */
    fun render(
        input: NightReadInput,
        p: NightReadParams = NightReadParams(),
        debug: NightReadDebug? = null,
    ): NightReadResult {
        val g = Regions.normalizePaper(input.gray, input.chroma, p)
        val seg = input.seg
        val w = g.w
        val h = g.h

        // ── 分析 ──────────────────────────────────────────────────────
        val charRaw = input.charMask
        val charMask = Regions.smoothCharMask(Regions.snapCharMask(charRaw, g, p), g, p)
        debug?.invoke("charRaw", charRaw.count())
        debug?.invoke("charMask", charMask.count())
        val (lh, lv) = Regions.frameLineMask(g, p)
        val frame = lh or lv
        val frameless = Regions.pageIsFrameless(lh, lv, p)
        val wc = Regions.classifyWhiteComponents(g, p)
        debug?.invoke("classifyWhite", 0)
        val excluded = wc.gutterIds + wc.panelIds
        val bubbleRes = Regions.buildBubbleMask(g, input.regions, seg, wc.cc, excluded, p)
        var bubble = bubbleRes.bubble
        debug?.invoke("buildBubble", 0)
        val plan = Sticker.plan(g, input.chroma, wc, frameless, input.regions, frame, p)

        debug?.invoke("stickerPlan", 0)
        val gutterShow = if (frameless) wc.gutterIds - plan.accept else wc.gutterIds
        val gutter = maskOfIds(wc.cc, gutterShow, w, h)

        // ── 剩餘填色：泡元件減掉核心＝泡框外的背景白，沒有別的機制會接手 ──────
        val restIds = bubbleRes.cored + plan.promoted
        var bubbleRest: Mask? = null
        if (restIds.isNotEmpty()) {
            var rest = maskOfIds(wc.cc, restIds, w, h).andNot(bubble)
            if (bubble.any()) {
                // 只填泡框周圍這一圈：全部取消會吃掉白鬍老人的鬍鬚
                rest = rest and Cv.dilate(bubble, Cv.ellipse(p.bubbleRestNear * 2 + 1))
            }
            rest = rest.andNot(charMask)          // 背景填色一律讓開人物
            bubbleRest = rest
            debug?.invoke("bubbleRest", rest.count())
        }

        // ── 圖層優先權：乾淨泡整顆塗黑、泡遮罩不跨進人物 ─────────────────
        var bubbleGuard = charMask
        bubbleGuard = bubbleGuard.andNot(cleanBubbles(g, bubble, seg, p))
        debug?.invoke("cleanBubbles", 0)
        val bubbleBeforeTrim = bubble.copy()
        val removed = bubble and bubbleGuard
        if (removed.any()) {
            // 只扣「從泡邊緣伸進來的人物」：遮罩誤蓋到泡中央時粗暴地扣會把泡挖出洞＝白泡
            val rcc = Cv.ccStats(removed, 8)
            // 「碰到泡外緣」＝自己在泡內、但四鄰有一個在泡外。直接看鄰居就好——
            // 原本是 `dilate(bubble.not(), 3×3)`，那會對整頁取反再膨脹兩趟全頁運算。
            val touching = BooleanArray(rcc.n)
            val bw = bubble.w
            val bh = bubble.h
            for (y in 0 until bh) {
                val base = y * bw
                for (x in 0 until bw) {
                    val i = base + x
                    if (!removed.data[i]) continue
                    val l = rcc.labels[i]
                    if (l == 0 || touching[l]) continue
                    val edge = x == 0 || y == 0 || x == bw - 1 || y == bh - 1 ||
                        !bubble.data[i - 1] || !bubble.data[i + 1] ||
                        !bubble.data[i - bw] || !bubble.data[i + bw]
                    if (edge) touching[l] = true
                }
            }
            var anyTouch = false
            for (l in 1 until rcc.n) if (touching[l]) { anyTouch = true; break }
            if (anyTouch) {
                val b2 = bubble.copy()
                for (i in b2.data.indices) {
                    val l = rcc.labels[i]
                    if (l > 0 && touching[l]) b2.data[i] = false
                }
                bubble = b2
            }
        }
        val lost = bubbleBeforeTrim.andNot(bubble)

        // ── 合成 ──────────────────────────────────────────────────────
        val out = compose(g, seg, gutter, bubble, frameless, wc, plan, frame, lh, lv,
            input.regions, charMask, charRaw, bubbleRest, lost, p, debug)
        return NightReadResult(out, gutter, bubble, charMask, frameless, plan.accept, plan.promoted)
    }

    /**
     * 把一組元件 id 攤成遮罩。
     *
     * ⚠️ 用 BooleanArray 查表而不是 `cc.labels[i] in ids`：後者每個像素都做一次 HashSet 查詢
     * 並把 Int 裝箱，2.6 MPx 上是實打實的成本，而這個函式在合成階段被呼叫數次。
     */
    private fun maskOfIds(cc: CC, ids: Set<Int>, w: Int, h: Int): Mask {
        val m = Mask(w, h)
        if (ids.isEmpty()) return m
        val want = BooleanArray(cc.n)
        for (id in ids) if (id in 0 until cc.n) want[id] = true
        for (i in m.data.indices) {
            val l = cc.labels[i]
            if (l > 0 && want[l]) m.data[i] = true
        }
        return m
    }

    /**
     * 「乾淨泡」＝真正的空白容器：填洞後內部的非字墨只有 0 到 0.3%，被誤判成泡的臉則有五官
     * 和陰影、超過 1%。判定為真泡的整顆塗黑、不被人物遮罩扣。
     *
     * 第二道保險是文字佔比：被誤判的白髮、白手區塊幾乎全是字筆畫本身。
     */
    private fun cleanBubbles(g: Gray, bubble: Mask, seg: Mask, p: NightReadParams): Mask {
        val clean = Mask(g.w, g.h)
        if (!bubble.any()) return clean
        val segD = Cv.dilate(seg, Cv.ellipse(7))
        val cc = Cv.ccStats(bubble, 8)
        for (i in 1 until cc.n) {
            val a = cc.area[i]
            if (a < 4000) continue
            val bx = max(0, cc.left[i] - 2)
            val by = max(0, cc.top[i] - 2)
            val bx1 = min(g.w, cc.left[i] + cc.width[i] + 2)
            val by1 = min(g.h, cc.top[i] + cc.height[i] + 2)
            val sw = bx1 - bx
            val sh = by1 - by
            val blob = Mask(sw, sh)
            for (y in 0 until sh) {
                val src = (by + y) * g.w + bx
                for (x in 0 until sw) blob.data[y * sw + x] = cc.labels[src + x] == i
            }
            val holes = Cv.holes(blob)
            var holeInk = 0
            var textN = 0
            var blobN = 0
            for (y in 0 until sh) {
                val src = (by + y) * g.w + bx
                for (x in 0 until sw) {
                    val idx = y * sw + x
                    if (holes.data[idx] && !segD.data[src + x]) holeInk++
                    if (blob.data[idx]) { blobN++; if (seg.data[src + x]) textN++ }
                }
            }
            if (holeInk.toDouble() / a >= p.bubbleCleanWins) continue
            if (blobN > 0 && textN.toDouble() / blobN > p.bubbleCleanTextMax) continue
            for (y in 0 until sh) {
                val dst = (by + y) * g.w + bx
                for (x in 0 until sw) if (blob.data[y * sw + x]) clean.data[dst + x] = true
            }
        }
        return clean
    }

    // ── 場景曲線 ─────────────────────────────────────────────────────

    /** 全線性場景曲線：黑→floor、紙白→ceil。線性＝保序，畫面不會反相。 */
    private fun lutScene(p: NightReadParams): IntArray = IntArray(256) {
        (p.sceneFloor + (p.dimCeil - p.sceneFloor) * it / 255.0).roundToInt().coerceIn(0, 255)
    }

    /** 原圖墨度（1−亮度）×gain 夾 [0,1]：把墨線轉亮時的 alpha，邊緣天然抗鋸齒。 */
    private fun inkAlpha(g: Gray, gain: Double): FloatArray =
        FloatArray(g.data.size) { ((1.0 - g.data[it] / 255.0) * gain).coerceIn(0.0, 1.0).toFloat() }

    /**
     * 軟性墨線遮罩：blackhat（細暗線構）乘暗度權重，再與文字筆畫取聯集。
     * 實心黑塊內部是 0，所以增亮不會把大塊黑的對比拉掉。
     */
    private fun inkLineMask(g: Gray, seg: Mask): FloatArray {
        val bh = Cv.blackhat(g, Cv.ellipse(7))
        val soft = FloatArray(g.data.size)
        // 暗度權重在 g >= 185 時是 0，那些像素的 blackhat 值再大也乘成 0——紙面佔了頁面大半，
        // 先判斷就跳過後面的除法與夾取。⚠️ blackhat 本身不能省：它抓的是細墨線（高頻），
        // 降解析度或先篩輸入都會讓線斷掉。
        for (i in soft.indices) {
            val gv = g.data[i]
            if (seg.data[i]) { soft[i] = 1f; continue }
            if (gv >= 185) continue
            val w = if (gv <= 40) 1.0 else (185.0 - gv) / 145.0
            val v = (bh.data[i] / 45.0).coerceAtMost(1.0) * w
            soft[i] = v.toFloat()
        }
        return soft
    }

    /**
     * 畫面區最終處理：場景曲線加自適應墨線增亮。增亮只在局部背景偏暗處生效，
     * 且夾在 `glowCap` 之下（低於紙白位準 ⇒ 線永遠比紙暗）。
     */
    private fun sceneFinal(g: Gray, seg: Mask, p: NightReadParams): FImg {
        val lut = lutScene(p)
        val dimmed = Gray(g.w, g.h, IntArray(g.data.size) { lut[g.data[it]] })
        val soft = inkLineMask(g, seg)
        // 這個高斯只是估「局部背景亮度」，用來判斷筆畫增亮要不要生效。sigma=8 的大模糊本來就
        // 把細節抹光了，在半解析度算再放大，數值差不到 1 階，成本卻只剩四分之一（99→28 ms）。
        val half = Cv.resizeArea(dimmed.toF(), max(1, g.w / 2), max(1, g.h / 2))
        val bgHalf = Cv.gaussianBlur(half, 4.0)
        val bg = Cv.resizeBilinear(bgHalf, g.w, g.h)
        val out = FImg(g.w, g.h)
        for (i in out.data.indices) {
            var gain = p.glowStrength * soft[i]
            gain *= ((95.0 - bg.data[i]) / 95.0).coerceIn(0.0, 1.0).toFloat()
            val base = dimmed.data[i].toFloat()
            val lifted = min(base + gain, max(base, p.glowCap.toFloat()))
            out.data[i] = lifted.coerceIn(0f, 255f)
        }
        return out
    }

    // ── 繪製 ─────────────────────────────────────────────────────────

    /** 留白填深 + 邊界描亮。 */
    private fun paintGutter(out: FImg, g: Gray, fill: Mask, p: NightReadParams) {
        for (i in fill.data.indices) if (fill.data[i]) out.data[i] = p.bg.toFloat()
        val band = Cv.dilate(fill, Cv.rect(p.stroke * 2 + 1, p.stroke * 2 + 1)).andNot(fill)
        val a = inkAlpha(g, 1.6)
        for (i in band.data.indices) {
            if (band.data[i]) out.data[i] = max(out.data[i], p.bg + a[i] * (p.ink - p.bg))
        }
    }

    /** 氣泡重繪：內部填深、文字畫亮（墨度當 alpha）、輪廓描亮。 */
    private fun paintBubbles(out: FImg, g: Gray, bubble: Mask, seg: Mask, p: NightReadParams) {
        if (!bubble.any()) return
        for (i in bubble.data.indices) if (bubble.data[i]) out.data[i] = p.bg.toFloat()
        val text = Cv.dilate(seg and bubble, Cv.rect(p.textPad * 2 + 1, p.textPad * 2 + 1)) and bubble
        val a = inkAlpha(g, p.textGamma)
        for (i in a.indices) {
            // knee：把低墨度（字的抗鋸齒過渡）壓成純黑，字本體不受影響
            a[i] = (((a[i] - p.textKnee) / (1.0 - p.textKnee)).coerceIn(0.0, 1.0)).toFloat()
        }
        for (i in text.data.indices) {
            if (text.data[i]) out.data[i] = max(out.data[i], p.bg + a[i] * (p.ink - p.bg))
        }
        val band = Cv.dilate(bubble, Cv.rect(p.stroke * 2 + 1, p.stroke * 2 + 1)).andNot(bubble)
        val a2 = inkAlpha(g, 1.6)
        for (i in band.data.indices) {
            if (band.data[i]) out.data[i] = max(out.data[i], p.bg + a2[i] * (p.ink - p.bg))
        }
    }

    /**
     * 偽泡：開口泡、泡尾缺口、字直接寫在畫面上時沒有封閉白元件可用，改從字底的白輕切頸
     * 生長出一圈貼身襯底。生長上限用字框長邊，用短邊會讓橫排標題字之間留白。
     */
    private fun buildPseudoBubbles(
        g: Gray, regions: List<TextRegion>, bubble: Mask, seg: Mask, p: NightReadParams,
    ): Mask {
        val w = g.w
        val h = g.h
        // 先看有沒有區需要偽泡：泡遮罩蓋率夠高的區直接跳過。全部都夠高就完全不必算 wCut，
        // 而 wCut 是三個重活（21px 開運算、測地生長、厚墨灰暈的距離變換+連通元件+25px 膨脹）。
        val needs = regions.filter { r ->
            val x0 = max(0, r.x0)
            val y0 = max(0, r.y0)
            val x1 = min(w, r.x1)
            val y1 = min(h, r.y1)
            if (x1 <= x0 || y1 <= y0) return@filter false
            var cov = 0
            for (y in y0 until y1) {
                val base = y * w
                for (x in x0 until x1) if (bubble.data[base + x]) cov++
            }
            cov.toDouble() / ((x1 - x0) * (y1 - y0)) < p.pbCovMax
        }
        if (needs.isEmpty()) return Mask(w, h)

        val white = g.ge(p.whiteTh)
        var wCut = Cv.open(white, Cv.ellipse(2 * p.pbNeckR + 1))
        wCut = Cv.geodesicGrow(wCut, white, p.pbNeckR, step = 3)
        wCut = wCut.andNot(Regions.thickInkAura(g, seg, p))
        val pb = Mask(w, h)
        for (r in needs) {
            val x0 = max(0, r.x0)
            val y0 = max(0, r.y0)
            val x1 = min(w, r.x1)
            val y1 = min(h, r.y1)
            val ref = max(x1 - x0, y1 - y0)
            val cap = (p.pbGrowFrac * ref).toInt()
            val pad = cap + p.pbNeckR + 2
            val wx0 = max(0, x0 - pad)
            val wy0 = max(0, y0 - pad)
            val wx1 = min(w, x1 + pad)
            val wy1 = min(h, y1 + pad)
            val sw = wx1 - wx0
            val sh = wy1 - wy0
            val within = Mask(sw, sh)
            val seed = Mask(sw, sh)
            for (y in 0 until sh) {
                val src = (wy0 + y) * w + wx0
                for (x in 0 until sw) {
                    val inWin = within.data
                    inWin[y * sw + x] = wCut.data[src + x]
                }
            }
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val idx = (y - wy0) * sw + (x - wx0)
                    if (within.data[idx]) seed.data[idx] = true
                }
            }
            val grown = Cv.geodesicGrow(seed, within, cap, step = 5)
            for (y in 0 until sh) {
                val dst = (wy0 + y) * w + wx0
                for (x in 0 until sw) if (grown.data[y * sw + x]) pb.data[dst + x] = true
            }
        }
        return pb.andNot(bubble)
    }

    /**
     * 浮在黑色區域裡的空白人頭一致化：暗區地圖內的殘餘亮島填深、內緣描亮。
     *
     * 亮島不是白元件——群眾的人頭白常與背景白同元件，元件級的跳過會把整顆略過。
     * 防護是面積上限（主角的臉更大）加外環細墨密度（鬍鬚與密髮排除）。
     */
    private fun harmonize(out: FImg, g: Gray, skip: Mask, p: NightReadParams) {
        val w = g.w
        val h = g.h
        val dark = FImg(w, h, FloatArray(w * h) { if (out.data[it] < 60f) 1f else 0f })
        val cw = max(1, w / p.harmonizeZoneCell)
        val ch = max(1, h / p.harmonizeZoneCell)
        var coarse = Cv.resizeArea(dark, cw, ch)
        coarse = Cv.gaussianBlur(coarse, 2.0)
        val coarseMask = Mask(cw, ch, BooleanArray(cw * ch) { coarse.data[it] >= p.harmonizeZoneDark })
        if (!coarseMask.any()) return
        val zone = Cv.resizeNearest(coarseMask, w, h)

        val resid = Mask(w, h)
        for (i in resid.data.indices) {
            resid.data[i] = g.data[i] >= p.whiteTh && out.data[i] >= 110f && !skip.data[i]
        }
        if (!resid.any()) return
        val cc = Cv.ccStats(resid, 8)
        val kc = Cv.ellipse(13)
        for (i in 1 until cc.n) {
            val a = cc.area[i]
            if (a.toDouble() / g.data.size > p.harmonizeAreaMax || a < 150) continue
            val x0 = max(0, cc.left[i] - 8)
            val y0 = max(0, cc.top[i] - 8)
            val x1 = min(w, cc.left[i] + cc.width[i] + 8)
            val y1 = min(h, cc.top[i] + cc.height[i] + 8)
            val sw = x1 - x0
            val sh = y1 - y0
            val isl = Mask(sw, sh)
            var inZone = 0
            var islN = 0
            for (y in 0 until sh) {
                val src = (y0 + y) * w + x0
                for (x in 0 until sw) {
                    val on = cc.labels[src + x] == i
                    isl.data[y * sw + x] = on
                    if (on) { islN++; if (zone.data[src + x]) inZone++ }
                }
            }
            if (islN == 0 || inZone.toDouble() / islN < p.harmonizeInZone) continue
            val collar = Cv.dilate(isl, kc).andNot(isl)
            var collarN = 0
            var collarInk = 0
            for (y in 0 until sh) {
                val src = (y0 + y) * w + x0
                for (x in 0 until sw) {
                    if (!collar.data[y * sw + x]) continue
                    collarN++
                    if (g.data[src + x] < 200) collarInk++
                }
            }
            if (collarN > 0 && collarInk.toDouble() / collarN > p.harmonizeCollarInk) continue
            val edge = isl.andNot(Cv.erode(isl, Cv.rect(5, 5)))
            for (y in 0 until sh) {
                val dst = (y0 + y) * w + x0
                for (x in 0 until sw) {
                    val idx = y * sw + x
                    if (isl.data[idx]) out.data[dst + x] = p.bg.toFloat()
                    if (edge.data[idx]) out.data[dst + x] = max(out.data[dst + x], p.strokeObjV.toFloat())
                }
            }
        }
    }

    /**
     * 貼紙式背景：白背景填深、前景加白描邊。
     *
     * 擢升的元件走核心填色（從格框種子出發、不擠過窄頸），再經兩道幾何保護（測地比刪填、
     * 厚墨灰暈）。**語意放行**：核心區裡明確不是人物的部分一律放行去填黑——那兩道幾何保護
     * 是人物遮罩出現前的粗略替代品，會把背景楔形誤判成人物附屬白。
     */
    private fun paintSticker(
        out: FImg, g: Gray, cc: CC, accept: Set<Int>, bubble: Mask, coreIds: Set<Int>,
        frame: Mask, seg: Mask, charMask: Mask, p: NightReadParams,
    ) {
        val r = (p.strokeObjFrac * min(g.h, g.w)).roundToInt().coerceIn(p.strokeObjMin, p.strokeObjMax)
        val k = Cv.ellipse(2 * r + 1)
        val kc = Cv.ellipse(p.figNoiseClose)
        for (i in accept.sorted()) {
            val win = Sticker.window(g, cc, i, r + 2)
            val sw = win.w
            val sh = win.h
            var fill: Mask
            if (i in coreIds) {
                val fr = Mask(sw, sh)
                for (y in 0 until sh) {
                    val src = (win.y0 + y) * g.w + win.x0
                    for (x in 0 until sw) fr.data[y * sw + x] = frame.data[src + x]
                }
                val seeds = Cv.dilate(fr, Cv.ellipse(p.frameHugDilate * 2 + 1)) and win.comp
                fill = Regions.broadCoreFill(win.comp, seeds, p.coreNeckR, p.coreRecoverR)
                if (fill.any()) {
                    // 測地比刪填：背景從格框直直就到（比值≈1），衣料與皮膚要繞過人物墨線（比值高）
                    val geo = FloatArray(sw * sh) { 1e9f }
                    var cur = seeds and win.comp
                    for (idx in cur.data.indices) if (cur.data[idx]) geo[idx] = 0f
                    var d = 0
                    val k3 = Cv.rect(3, 3)
                    while (cur.any() && d < 4000) {
                        val grown = Cv.dilate(cur, k3, iterations = 6) and win.comp
                        d += 6
                        var added = false
                        for (idx in grown.data.indices) {
                            if (grown.data[idx] && geo[idx] == 1e9f) { geo[idx] = d.toFloat(); added = true }
                        }
                        if (!added) break
                        cur = grown
                    }
                    val euc = Cv.distanceL2(fr.not())
                    val subSeg = Mask(sw, sh)
                    for (y in 0 until sh) {
                        val src = (win.y0 + y) * g.w + win.x0
                        for (x in 0 until sw) subSeg.data[y * sw + x] = seg.data[src + x]
                    }
                    val aura = Regions.thickInkAura(win.sub, subSeg, p)
                    val strict = Mask(sw, sh)
                    for (idx in strict.data.indices) {
                        strict.data[idx] = fill.data[idx] &&
                            geo[idx] <= p.geoRatioMax * euc.data[idx] + p.geoSlack &&
                            !aura.data[idx]
                    }
                    // 語意放行：把人物遮罩外擴當安全邊界，非人物的核心區照填
                    val guardSub = Mask(sw, sh)
                    for (y in 0 until sh) {
                        val src = (win.y0 + y) * g.w + win.x0
                        for (x in 0 until sw) guardSub.data[y * sw + x] = charMask.data[src + x]
                    }
                    val guard = Cv.dilate(guardSub, Cv.ellipse(p.coreReleasePad * 2 + 1))
                    val released = Mask(sw, sh)
                    for (idx in released.data.indices) {
                        released.data[idx] = strict.data[idx] || (fill.data[idx] && !guard.data[idx])
                    }
                    fill = released
                }
                if (!fill.any()) continue
            } else {
                fill = win.comp
            }

            // 前景＝窗內非白且非氣泡的內容；小噪點不描邊、直接併入背景
            val fRaw = Mask(sw, sh)
            for (y in 0 until sh) {
                val src = (win.y0 + y) * g.w + win.x0
                for (x in 0 until sw) {
                    val idx = y * sw + x
                    fRaw.data[idx] = win.sub.data[idx] < p.whiteTh && !bubble.data[src + x]
                }
            }
            val fcc = Cv.ccStats(fRaw, 8)
            val keep = BooleanArray(fcc.n)
            for (j in 1 until fcc.n) keep[j] = fcc.area[j] >= p.figNoiseArea
            val fMain = Mask(sw, sh)
            val noiseRaw = Mask(sw, sh)
            for (idx in fMain.data.indices) {
                val l = fcc.labels[idx]
                if (l > 0) { if (keep[l]) fMain.data[idx] = true else noiseRaw.data[idx] = true }
            }
            val fillClosed = Cv.close(fill, kc)
            val noise = noiseRaw and fillClosed
            val protect = Sticker.protect(Sticker.eaten(win.sub, win.comp, p), win.comp, p)
            val band = (Cv.dilate(fMain, k) and fill).andNot(protect)

            for (y in 0 until sh) {
                val dst = (win.y0 + y) * g.w + win.x0
                for (x in 0 until sw) {
                    val idx = y * sw + x
                    if ((fill.data[idx] || noise.data[idx]) && !protect.data[idx]) {
                        out.data[dst + x] = p.bg.toFloat()
                    }
                    if (band.data[idx]) out.data[dst + x] = p.strokeObjV.toFloat()
                }
            }
        }
    }

    // ── 合成 ─────────────────────────────────────────────────────────

    private fun compose(
        g: Gray, seg: Mask, gutterIn: Mask, bubble: Mask, frameless: Boolean,
        wc: Regions.WhiteComponents, plan: Sticker.Plan, frame: Mask, lh: Mask, lv: Mask,
        regions: List<TextRegion>, charMask: Mask, charRaw: Mask,
        bubbleRest: Mask?, lost: Mask, p: NightReadParams, debug: NightReadDebug?,
    ): Gray {
        val w = g.w
        val h = g.h
        val out = sceneFinal(g, seg, p)
        debug?.invoke("sceneFinal", 0)
        val sceneKeep = out.copy()              // 人物區最終一律還原成場景調

        // 留白：有框頁只填「深入不超過短邊 12%」的部分；無框頁只填真頁邊帶
        if (gutterIn.any()) {
            val bd = borderDistance(g, frame, includeFrame = !frameless)
            if (frameless) {
                val cc = Cv.ccStats(gutterIn, 8)
                val lim = p.framelessMarginDepth * min(h, w)
                val maxDepth = FloatArray(cc.n)
                for (i in gutterIn.data.indices) {
                    val l = cc.labels[i]
                    if (l > 0 && bd[i] > maxDepth[l]) maxDepth[l] = bd[i]
                }
                val keep = Mask(w, h)
                for (i in keep.data.indices) {
                    val l = cc.labels[i]
                    if (l > 0 && maxDepth[l] <= lim) keep.data[i] = true
                }
                val keep2 = Texture.veto(keep, g, frame, seg, bubble, p)   // 有線稿的白不是留白
                if (keep2.any()) paintGutter(out, g, keep2, p)
            } else {
                val lim = p.safeGutterDepth * min(h, w)
                // 格內背景與頁邊留白在像素層連通 ⇒ 先沿格框線切開，只留真的留白
                val cut = Regions.gutterFrameCut(gutterIn, lh, lv, p)
                val band = Mask(w, h)
                for (i in band.data.indices) band.data[i] = cut.data[i] && bd[i] <= lim
                val band2 = Texture.veto(band, g, frame, seg, bubble, p)   // 有線稿的白不是留白
                debug?.invoke("gutterBand", band2.count())
                if (band2.any()) paintGutter(out, g, band2, p)
            }
        }

        if (plan.accept.isNotEmpty()) {
            paintSticker(out, g, wc.cc, plan.accept, bubble, plan.promoted, frame, seg, charMask, p)
        }
        debug?.invoke("paintSticker", 0)
        paintBubbles(out, g, bubble, seg, p)
        debug?.invoke("paintBubbles", 0)
        val pb = buildPseudoBubbles(g, regions, bubble, seg, p)
        debug?.invoke("pseudoBubble", pb.count())
        if (pb.any()) paintBubbles(out, g, pb, seg, p)
        harmonize(out, g, bubble or pb or gutterIn, p)
        debug?.invoke("harmonize", 0)

        // 字永遠在最上層：被人物扣掉的泡區裡，字筆畫及其貼身帶維持深底亮字
        if (lost.any()) {
            val txt = Cv.dilate(seg and lost, Cv.rect(p.textTopPad * 2 + 1, p.textTopPad * 2 + 1)) and lost
            if (txt.any()) {
                val a = inkAlpha(g, p.textGamma)
                for (i in a.indices) a[i] = (((a[i] - p.textKnee) / (1.0 - p.textKnee)).coerceIn(0.0, 1.0)).toFloat()
                for (i in txt.data.indices) {
                    if (!txt.data[i]) continue
                    out.data[i] = p.bg.toFloat()
                    out.data[i] = max(out.data[i], p.bg + a[i] * (p.ink - p.bg))
                }
            }
        }

        if (bubbleRest != null && bubbleRest.any()) {
            for (i in bubbleRest.data.indices) if (bubbleRest.data[i]) out.data[i] = p.bg.toFloat()
            val band = Cv.dilate(bubbleRest, Cv.rect(p.stroke * 2 + 1, p.stroke * 2 + 1)).andNot(bubbleRest)
            val a2 = inkAlpha(g, 1.6)
            for (i in band.data.indices) {
                if (band.data[i]) out.data[i] = max(out.data[i], p.bg + a2[i] * (p.ink - p.bg))
            }
        }

        // ── 人物還原（放最後 ⇒ 任何新填色機制自動受保護）──────────────────
        var restore = charMask.copy()
        restore = restore.andNot(bubble)        // 真泡畫在人物之上，該處看不到人物
        if (pb.any()) {
            // 收邊生長出來的邊緣不得壓過偽泡：那些像素是加工長出來的，屬於畫面不屬於人物
            restore = restore.andNot(pb.andNot(charRaw))
        }
        val textOnChar = pb and charMask and seg
        if (textOnChar.any()) {
            val kb = Cv.ellipse(p.textBackingR * 2 + 1)
            restore = restore.andNot(Cv.dilate(textOnChar, kb))
        }
        if (lost.any()) {
            val kt = Cv.ellipse(p.textTopPad * 2 + 1)
            restore = restore.andNot(Cv.dilate(seg and lost, kt) and lost)
        }
        debug?.invoke("restore", restore.count())
        // 邊界抗鋸齒：遮罩是二值又是放大來的，用小半徑高斯軟化成 alpha 混合
        val alpha = Cv.gaussianBlur(
            FImg(w, h, FloatArray(w * h) { if (restore.data[it]) 1f else 0f }), p.edgeFeather)
        for (i in out.data.indices) {
            val a = alpha.data[i]
            out.data[i] = out.data[i] * (1f - a) + sceneKeep.data[i] * a
        }
        return Gray(w, h, IntArray(w * h) { out.data[it].roundToInt().coerceIn(0, 255) })
    }

    /** 每像素到頁邊（有框頁再併入格線）的距離，決定留白填到多深。 */
    private fun borderDistance(g: Gray, frame: Mask, includeFrame: Boolean): FloatArray {
        val w = g.w
        val h = g.h
        val bd = FloatArray(w * h)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                bd[base + x] = min(min(x, w - 1 - x), min(y, h - 1 - y)).toFloat()
            }
        }
        if (includeFrame && frame.any()) {
            // ⚠️ 這裡的距離變換試過半解析度：只省 10 ms 但 MAE 0.57→0.59。距離門檻雖然有餘裕，
            // 但 `bd` 同時決定留白填色的邊界，那是逐像素的視覺邊界，±1 px 會沿著整條格溝顯現。
            val fd = Cv.distanceL2(frame.not())
            for (i in bd.indices) if (fd.data[i] < bd[i]) bd[i] = fd.data[i]
        }
        return bd
    }
}
