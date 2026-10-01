package li.joye.yakuyomi.nightread

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 三檔共用分析（[NightRead.renderTiers]）的回歸：一次分析、依序出 L1／L2／L3，必須與三次單檔 [NightRead.render]
 * **逐位元相同**；合成鍵（keep，加上「更多」的繪製開關，見 [drawKeyOf]）與前一檔相同的那檔傳 null，而且那時單檔的成品也真的
 * 與前一檔逐位元相同（去重在建構上正確）。L3＝產品「更多」（[MoreRuleParams.enabled]），keep 與 L2 相同、但有貼紙要塗時照樣合成。
 *
 * 產品三檔跑五頁 fixture：ch34_011／demo02／demo06 有框；demo04、demo05 無框。demo05 三檔 keep 全空（只合成 L1、L2／L3
 * 都傳 null）；demo04 L1／L2 keep 空、「更多」加一顆。產品三檔在 fixture 上**走不到**兩條快取路徑，另外兩個 case 專門守：
 * - [gutterCacheRekeys]：無框頁留白帶依 gutterShow 快取，keep 換了要重算並覆蓋快取、換回來再重算（demo04 跑
 *   [L1, ALL, L1, L3]，只差 stickerMode）。
 * - [nonEmptyPlainSet]：產品參數下 47 頁的 L1 keep 全空（plain 集合從沒非空過），放寬 plain 門檻讓 selectKeep 的 plain 分支
 *   真的被走到（demo06）。
 * 47 頁（含真機頁）的產品三檔比對在研究端的 scratch harness 跑過，數字見 docs/DECISIONS.md「三檔一次產生」。
 *
 * 暗像素巢狀（低檔 ≤ 40 的像素在高檔也 ≤ 40）**是實測性質、不是結構保證**：keep 巢狀（L1 ⊆ L2 ⊆ L3）由
 * [Sticker.filterPlan] 的寫法與「更多」只加不減保證，但「多填一個元件不會讓別處變亮」要看繪製細節——高一檔新填的元件會畫
 * 220 的前景白描邊（「更多」的 P1 讓它不蓋已經黑的像素）。L1 ⊆ L2 要求 0 px。L2 ⊆ L3（更多）只要求**不成塊**（違規像素沒有
 * 2×2 全是違規的位置）：「更多」新擢升的元件讓泡外圈填色變大，它 1 px 寬的描亮圈會落在原本黑的像素上——泡與剩餘填色同一白元件
 * 的接縫（泡內的淡灰抗鋸齒，提亮到 42–115）與新黑區邊上的墨線（輪廓描亮）。47 頁 python 892／Kotlin 887 px、最大 8 連通塊
 * 14 px、全是 1 px 寬的線（研究 HARDEN.md 已列、使用者拍板採用 A2 時知道；見 docs/DECISIONS.md）。這條斷言失敗代表
 * 使用者語意退步（調高一檔反而有暗處變亮），不一定是共用分析的 bug。
 */
class SharedTierTest {

    private val pages = listOf("ch34_011", "demo02", "demo04", "demo05", "demo06")

    private fun readGray(name: String): Gray {
        val stream = javaClass.classLoader!!.getResourceAsStream("page/$name")
            ?: error("缺 fixture：page/$name — 先跑 research/make_page_fixture.py")
        val img: BufferedImage = stream.use { ImageIO.read(it) }
        val g = Gray(img.width, img.height)
        val raster = img.raster
        for (y in 0 until img.height) for (x in 0 until img.width) g.data[y * img.width + x] = raster.getSample(x, y, 0)
        return g
    }

    private fun readMask(name: String): Mask {
        val g = readGray(name)
        return Mask(g.w, g.h, BooleanArray(g.data.size) { g.data[it] > 127 })
    }

    private fun readRegions(name: String): List<TextRegion> =
        javaClass.classLoader!!.getResourceAsStream("page/$name")!!.bufferedReader().readText()
            .lineSequence().filter { it.isNotBlank() }.map {
                val p = it.trim().split(" ").map(String::toInt)
                TextRegion(p[0], p[1], p[2], p[3])
            }.toList()

    private fun input(page: String) = NightReadInput(
        gray = readGray("${page}_gray.png"),
        seg = readMask("${page}_seg.png"),
        regions = readRegions("${page}_regions.txt"),
        charMask = readMask("${page}_char.png"),
        chroma = readGray("${page}_chroma.png"),
    )

    /** [NightTier] 是三檔參數的單一來源：套在預設參數上要等於 TierParityTest（研究端三檔基線）用的那三組。 */
    @Test
    fun tiersAreTheProductTierParams() {
        assertEquals(
            NightReadParams(stickerMode = StickerMode.PLAIN, pseudoBubbles = false, harmonize = false),
            NightTier.L1.apply(),
        )
        assertEquals(
            NightReadParams(stickerMode = StickerMode.SIMPLE, stickerRoughMax = 10.0, stickerSimpleMinFrac = 0.005,
                pseudoBubbles = false, harmonize = false),
            NightTier.L2.apply(),
        )
        assertEquals(
            NightReadParams(stickerMode = StickerMode.SIMPLE, stickerRoughMax = 20.0, stickerSimpleMinFrac = 0.0,
                more = MoreRuleParams(enabled = true), pseudoBubbles = false, harmonize = false),
            NightTier.L3.apply(),
        )
        // 只有 L3（產品「更多」）開 A2
        assertEquals(listOf(false, false, true), NightTier.entries.map { it.apply().more.enabled })
        // 亮度等其餘參數（含 A2 的門檻）照 base；A2 開關由檔位決定
        val base = NightReadParams(bg = 20, ink = 200, edgeInk = 180, dimCeil = 120, glowCap = 100,
            more = MoreRuleParams(enabled = true, tOut = 6.0, keepDarkStroke = false))
        for (t in NightTier.entries) {
            val p = t.apply(base)
            assertEquals(base.copy(stickerMode = p.stickerMode, stickerRoughMax = p.stickerRoughMax,
                stickerSimpleMinFrac = p.stickerSimpleMinFrac, more = base.more.copy(enabled = t == NightTier.L3),
                pseudoBubbles = false, harmonize = false), p)
        }
        assertEquals(listOf("l1", "l2", "l3"), NightTier.entries.map { it.key })
        for (t in NightTier.entries) assertEquals(t, NightTier.fromKey(t.key))
        assertNull(NightTier.fromKey("protect"))
        assertNull(NightTier.fromKey(null))
    }

    @Test
    fun sharedAnalysisEqualsSeparateRenders() {
        for (page in pages) checkPage(page)
    }

    /**
     * 無框頁的留白帶快取換鍵：L1（keep 空）→ ALL（keep 非空、gutterShow 少了被接受的元件 ⇒ 換鍵重算）→ L1（換回、再重算）
     * → L3。四檔只差貼紙篩選三欄，renderTiers 放行。
     */
    @Test
    fun gutterCacheRekeys() {
        val l1 = NightTier.L1.apply()
        val tiers = listOf(l1, l1.copy(stickerMode = StickerMode.ALL), l1, NightTier.L3.apply())
        val inp = input("demo04")
        val separate = checkTiers("demo04", inp, tiers)
        assertTrue("demo04 要是無框頁（留白帶依 keep 變）", separate[0].frameless)
        assertTrue("demo04 ALL 的 keep 要非空（否則沒換鍵）", separate[1].keep.isNotEmpty())
        assertTrue("demo04 L1 的 keep 要空", separate[0].keep.isEmpty())
    }

    /**
     * 「更多」的合成鍵：keep 相同但繪製開關（P1 描亮邊不蓋黑）不同 ⇒ 照樣合成（成品可能不同）；只差不影響 keep 的門檻 ⇒ 傳 null。
     * ch34_011 的「更多」keep 非空（四顆、其中三顆是 A2 加的），所以貼紙層會跑。
     */
    @Test
    fun moreDrawParamsForceCompose() {
        val more = NightTier.L3.apply()
        val tiers = listOf(more, more.copy(more = more.more.copy(keepDarkStroke = false)),
            more.copy(more = more.more.copy(keepDarkStroke = false, tOut = 5.001)))
        val separate = checkTiers("ch34_011", input("ch34_011"), tiers)
        assertTrue("ch34_011 「更多」keep 要非空", separate[0].keep.isNotEmpty())
        assertEquals("三檔 keep 要相同（只差繪製開關與一個沒影響的門檻）", 1, separate.map { it.keep }.toSet().size)
    }

    /** plain 集合非空：放寬 plain 兩個門檻（> 1 ⇒ 外圈佔比永遠過），三檔都由這組 base 套出。 */
    @Test
    fun nonEmptyPlainSet() {
        val base = NightReadParams(stickerPlainArtMax = 1.01, stickerPlainFaintMax = 1.01)
        val tiers = NightTier.entries.map { it.apply(base) }
        val separate = checkTiers("demo06", input("demo06"), tiers)
        assertTrue("demo06 放寬門檻後 L1 keep 要非空（plain 分支有走到）", separate[0].keep.isNotEmpty())
    }

    /**
     * 單檔 render 結果裡這裡要比的部分（成品壓成 1 B/px、不留遮罩：demo04 是 7.2 MPx，三／四檔整份結果加上共用輸出會讓
     * 測試 JVM（Gradle 預設 512 MB）OOM——checkPage 原本留整份結果，2026-10-01 在 demo04 OOM 過一次）。
     */
    private class Slim(val out: ByteArray, val keep: Set<Int>, val frameless: Boolean, val draw: Any?)

    /** 與 [NightRead.renderTiers] 的合成鍵同義的繪製部分：「更多」開了、而且有貼紙要塗，P1／P2 才影響成品。 */
    private fun drawKeyOf(t: NightReadParams, keep: Set<Int>): Any? =
        if (!t.more.enabled || keep.isEmpty()) null
        else listOf(t.more.keepDarkStroke, t.more.edgeSeedFallback, t.more.edgeBand, t.more.edgeReach)

    private fun slim(t: NightReadParams, r: NightReadResult) =
        Slim(bytes(r.out), r.stickerAccept, r.frameless, drawKeyOf(t, r.stickerAccept))

    private fun bytes(g: Gray) = ByteArray(g.data.size) { g.data[it].toByte() }

    /**
     * 任意檔位序列的共用＝分開：sink 依序各一次、第 0 檔非 null；非 null 的輸出與單檔 render 逐位元相同；null 只出現在
     * 合成鍵（keep＋繪製開關）與前一檔相同時、而且那時單檔成品真的與前一檔逐位元相同。回傳各檔單檔結果給呼叫端加斷言。
     */
    private fun checkTiers(page: String, inp: NightReadInput, tiers: List<NightReadParams>): List<Slim> {
        val separate = tiers.map { t -> slim(t, NightRead.render(inp, t)) }
        val order = ArrayList<Int>()
        val shared = arrayOfNulls<ByteArray>(tiers.size)
        NightRead.renderTiers(inp, tiers) { k, g ->
            order.add(k)
            shared[k] = g?.let(::bytes)
        }
        assertEquals("$page sink 依檔位順序各呼叫一次", tiers.indices.toList(), order)
        assertNotNull("$page 第 0 檔不會是 null", shared[0])
        for (k in tiers.indices) {
            val sameAsPrev = k > 0 && separate[k].keep == separate[k - 1].keep && separate[k].draw == separate[k - 1].draw
            val g = shared[k]
            if (g == null) {
                assertTrue("$page/#$k：傳 null 只能是合成鍵與前一檔相同", sameAsPrev)
                assertArrayEquals("$page/#$k：合成鍵相同 ⇒ 單檔成品與前一檔逐位元相同", separate[k - 1].out, separate[k].out)
            } else {
                assertTrue("$page/#$k：合成鍵與前一檔相同卻沒去重", !sameAsPrev)
                assertArrayEquals("$page/#$k：共用分析與單檔 render 逐位元相同", separate[k].out, g)
            }
        }
        println("  $page：檔=${shared.map { if (it == null) "-" else "●" }} keep=${separate.map { it.keep.size }}")
        return separate
    }

    private fun checkPage(page: String) {
        val inp = input(page)
        val tiers = NightTier.entries.map { it.apply() }
        val separate = tiers.map { t -> slim(t, NightRead.render(inp, t)) }

        val order = ArrayList<Int>()
        val shared = arrayOfNulls<ByteArray>(tiers.size)
        val t0 = System.currentTimeMillis()
        NightRead.renderTiers(inp, tiers) { k, g ->
            order.add(k)
            shared[k] = g?.let(::bytes)
        }
        val ms = System.currentTimeMillis() - t0
        assertEquals("$page sink 依檔位順序各呼叫一次", listOf(0, 1, 2), order)
        assertNotNull("$page 第 0 檔不會是 null", shared[0])

        for (k in tiers.indices) {
            val sameAsPrev = k > 0 && separate[k].keep == separate[k - 1].keep && separate[k].draw == separate[k - 1].draw
            val g = shared[k]
            if (g == null) {
                assertTrue("$page/L${k + 1}：傳 null 只能是合成鍵與前一檔相同", sameAsPrev)
                assertArrayEquals("$page/L${k + 1}：合成鍵相同 ⇒ 單檔成品與前一檔逐位元相同",
                    separate[k - 1].out, separate[k].out)
            } else {
                assertTrue("$page/L${k + 1}：合成鍵與前一檔相同卻沒去重", !sameAsPrev)
                assertArrayEquals("$page/L${k + 1}：共用分析與單檔 render 逐位元相同", separate[k].out, g)
            }
        }

        // keep 巢狀：結構保證（filterPlan：plain 先收、SIMPLE 的門檻 L2 ⊆ L3；「更多」只加不減）
        val keeps = separate.map { it.keep }
        assertTrue("$page keep L1 ⊆ L2", keeps[1].containsAll(keeps[0]))
        assertTrue("$page keep L2 ⊆ L3", keeps[2].containsAll(keeps[1]))

        // 暗像素巢狀：實測性質（見類別說明）。L1 ⊆ L2 一個都不准；L2 ⊆ L3（更多）只准 1 px 寬的描亮線、不准成塊
        val o = separate.map { it.out }
        val w = inp.gray.w
        val h = inp.gray.h
        fun v(t: Int, i: Int) = o[t][i].toInt() and 0xFF
        fun bad(lo: Int, i: Int) = v(lo, i) <= 40 && v(lo + 1, i) > 40
        var viol12 = 0
        var viol23 = 0
        var block23 = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (bad(0, i)) viol12++
                if (!bad(1, i)) continue
                viol23++
                if (x + 1 < w && y + 1 < h && bad(1, i + 1) && bad(1, i + w) && bad(1, i + w + 1)) block23++
            }
        }
        println("  $page：shared ${ms}ms 檔=${shared.map { if (it == null) "-" else "●" }} keep=${keeps.map { it.size }} " +
            "暗像素巢狀違規 L1⊆L2=$viol12 L2⊆L3=$viol23（2×2 成塊 $block23）")
        assertEquals("$page 暗像素巢狀違規 L1 ⊆ L2（低檔暗、高檔不暗）", 0, viol12)
        assertEquals("$page 暗像素巢狀違規 L2 ⊆ L3 不准成塊（2×2 全違規的位置）", 0, block23)
    }

    /** 前提：各檔只能差貼紙篩選三欄與「更多」新規則（[NightReadParams.more]）、偽泡與亮島要關；不符就拒絕（不能共用分析）。 */
    @Test
    fun rejectsTiersThatCannotShareAnalysis() {
        val inp = input("demo05")
        fun expectReject(tiers: List<NightReadParams>, why: String) {
            try {
                NightRead.renderTiers(inp, tiers) { _, _ -> fail("$why：不該合成任何一檔") }
                fail("$why：應該拒絕")
            } catch (e: IllegalArgumentException) {
                println("  拒絕（$why）：${e.message}")
            }
        }
        val l1 = NightTier.L1.apply()
        val l2 = NightTier.L2.apply()
        expectReject(emptyList(), "沒有檔位")
        expectReject(listOf(l1, l2.copy(pseudoBubbles = true)), "偽泡開")
        expectReject(listOf(l1.copy(harmonize = true), l2), "亮島填黑開")
        expectReject(listOf(l1, l2.copy(bg = l2.bg + 1)), "亮度不同")
        expectReject(listOf(l1, l2.copy(bubbleSeal = BubbleSealParams(r = 0))), "巢狀參數組不同")
    }
}
