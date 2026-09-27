package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * 背景填黑三檔（L1／L2／L3）的整頁 parity：同 [PageParityTest] 的輸入，換成產品三檔的參數組，比對
 * `fixtures/baseline/tiers/<檔>/<頁>_final.png`（研究端 `nightread.py` 以對應環境變數跑出的成品，見
 * docs/DECISIONS.md「背景填黑三檔」）。容差與 [PageParityTest] 相同；泡／留白遮罩在三檔都不該動，逐像素比對
 * 測試資源裡的 `<頁>_bubble.png`／`<頁>_gutter.png`（研究端實測三檔的遮罩與預設逐像素相同）。任意角度格溝（SEP）不吃
 * 檔位參數，實際塗的那份同樣逐像素比 `<頁>_sep.png`，有框頁還要求非空（格溝壓過人物、出血格過濾也在三檔一律開）。
 *
 * 貼紙的 keep 集合（三檔篩選的直接輸出）也比：期望值抄自研究端各檔位 `<頁>_regions.json` 的 sticker 審計
 * `keep` 欄（2026-09-27），以元件 bbox 表示（cv2 與 Kotlin 的元件標號在少數頁差 1，bbox 才穩）。ch34_011 只有
 * bbox [431,131,548,168]（rough 2.3、佔比 0.001）過安全網：PLAIN 落選（外圈碰線稿）、SIMPLE 10／0.005 因面積落選、
 * SIMPLE 20／0 留下；demo05 沒有元件過安全網。
 */
class TierParityTest {

    private fun readGray(name: String): Gray {
        val stream = javaClass.classLoader!!.getResourceAsStream("page/$name")
            ?: error("缺 fixture：page/$name — 先跑 research/make_page_fixture.py")
        return toGray(stream.use { ImageIO.read(it) })
    }

    private fun toGray(img: BufferedImage): Gray {
        val w = img.width
        val h = img.height
        val g = Gray(w, h)
        val raster = img.raster
        for (y in 0 until h) for (x in 0 until w) g.data[y * w + x] = raster.getSample(x, y, 0)
        return g
    }

    private fun readMask(name: String): Mask {
        val g = readGray(name)
        return Mask(g.w, g.h, BooleanArray(g.data.size) { g.data[it] > 127 })
    }

    private fun readRegions(name: String): List<TextRegion> {
        val text = javaClass.classLoader!!.getResourceAsStream("page/$name")!!
            .bufferedReader().readText()
        return text.lineSequence().filter { it.isNotBlank() }.map {
            val p = it.trim().split(" ").map(String::toInt)
            TextRegion(p[0], p[1], p[2], p[3])
        }.toList()
    }

    /** 三檔基線在 repo 的 fixtures/ 下（不進測試資源）；單元測試的工作目錄是模組夾，往上找。 */
    private fun baseline(tier: String, page: String): Gray {
        val f = sequenceOf("../fixtures/baseline/tiers", "fixtures/baseline/tiers", "../../fixtures/baseline/tiers")
            .map { File(it, "$tier/${page}_final.png") }.firstOrNull { it.isFile }
            ?: error("缺三檔基線 fixtures/baseline/tiers/$tier/${page}_final.png（工作目錄 ${File(".").absolutePath}）")
        return toGray(ImageIO.read(f))
    }

    /** 產品三檔＝呼叫端整組設的參數（對應研究端 NIGHTREAD_STICKER_MODE／ROUGH／MINFRAC／PB／HM）。 */
    private fun tierParams(tier: String): NightReadParams = when (tier) {
        "L1" -> NightReadParams(stickerMode = StickerMode.PLAIN, pseudoBubbles = false, harmonize = false)
        "L2" -> NightReadParams(stickerMode = StickerMode.SIMPLE, stickerRoughMax = 10.0, stickerSimpleMinFrac = 0.005,
            pseudoBubbles = false, harmonize = false)
        "L3" -> NightReadParams(stickerMode = StickerMode.SIMPLE, stickerRoughMax = 20.0, stickerSimpleMinFrac = 0.0,
            pseudoBubbles = false, harmonize = false)
        else -> error(tier)
    }

    /** 期望的 keep 集合，元件以 bbox（x0 y0 x1 y1）表示。 */
    private val expectedKeep: Map<String, Map<String, Set<List<Int>>>> = mapOf(
        "ch34_011" to mapOf("L1" to emptySet(), "L2" to emptySet(), "L3" to setOf(listOf(431, 131, 548, 168))),
        "demo05" to mapOf("L1" to emptySet(), "L2" to emptySet(), "L3" to emptySet()),
    )

    @Test
    fun framedPageMatchesTierBaselines() = checkPage("ch34_011", expectSep = true)

    @Test
    fun framelessColourPageMatchesTierBaselines() = checkPage("demo05", expectSep = false)

    private fun checkPage(page: String, expectSep: Boolean) {
        val gray = readGray("${page}_gray.png")
        val input = NightReadInput(
            gray = gray,
            seg = readMask("${page}_seg.png"),
            regions = readRegions("${page}_regions.txt"),
            charMask = readMask("${page}_char.png"),
            chroma = readGray("${page}_chroma.png"),
        )
        val pyGutter = readMask("${page}_gutter.png")
        val pyBubble = readMask("${page}_bubble.png")
        val pySep = readMask("${page}_sep.png")
        // keep 集合以 bbox 比：白元件的標號由 classifyWhiteComponents 決定，與 render 內部同一份
        val cc = Regions.classifyWhiteComponents(Regions.normalizePaper(gray, input.chroma, NightReadParams()), NightReadParams()).cc
        fun bbox(i: Int) = listOf(cc.left[i], cc.top[i], cc.left[i] + cc.width[i], cc.top[i] + cc.height[i])

        for (tier in listOf("L1", "L2", "L3")) {
            val expected = baseline(tier, page)
            val t0 = System.currentTimeMillis()
            val result = NightRead.render(input, tierParams(tier))
            val ms = System.currentTimeMillis() - t0
            val got = result.out

            assertEquals("$page/$tier 貼紙 keep 集合（bbox）", expectedKeep.getValue(page).getValue(tier),
                result.stickerAccept.map { bbox(it) }.toSet())

            val ktSep = result.sep ?: error("$page/$tier：SEP 三檔一律開，result.sep 不該是 null")
            assertEquals("$page/$tier 格溝遮罩 SEP 有無", expectSep, ktSep.any())
            for ((tag, kt, py) in listOf(Triple("gutter", result.gutter, pyGutter), Triple("bubble", result.bubble, pyBubble),
                    Triple("sep", ktSep, pySep))) {
                var only = 0
                var miss = 0
                for (i in py.data.indices) {
                    if (kt.data[i] && !py.data[i]) only++
                    if (!kt.data[i] && py.data[i]) miss++
                }
                println("  $page/$tier $tag: python=${py.count()} kotlin=${kt.count()} 多=$only 少=$miss")
                assertEquals("$page/$tier $tag 多", 0, only)
                assertEquals("$page/$tier $tag 少", 0, miss)
            }

            var sumAbs = 0L
            var big = 0           // 差 > 32 階＝肉眼看得出來的不同
            var huge = 0          // 差 > 96 階＝分區判斷不同（填黑 vs 留灰）
            var redline = 0       // 原圖是白、Python 沒塗黑、Kotlin 卻塗黑了
            for (i in expected.data.indices) {
                val d = abs(expected.data[i] - got.data[i])
                sumAbs += d
                if (d > 32) big++
                if (d > 96) huge++
                if (gray.data[i] >= 235 && expected.data[i] >= 110 && got.data[i] < 60) redline++
            }
            val n = expected.data.size
            val mae = sumAbs.toDouble() / n
            val bigFrac = big.toDouble() / n
            val hugeFrac = huge.toDouble() / n
            val redlineFrac = redline.toDouble() / n
            println(
                "$page/$tier ${gray.w}×${gray.h}：${ms}ms  keep=${result.stickerAccept.sorted()}  MAE=${"%.2f".format(mae)}  " +
                    ">32階=${"%.3f".format(bigFrac * 100)}%  >96階=${"%.3f".format(hugeFrac * 100)}%  " +
                    "紅線=${"%.4f".format(redlineFrac * 100)}%"
            )
            assertTrue("$page/$tier 平均絕對差 $mae 過大（期望 < 2）", mae < 2.0)
            assertTrue("$page/$tier 肉眼可見差異 ${bigFrac * 100}% 過多（期望 < 1%）", bigFrac < 0.01)
            assertTrue("$page/$tier 分區判斷差異 ${hugeFrac * 100}% 過多（期望 < 0.8%）", hugeFrac < 0.008)
            assertTrue("$page/$tier 紅線違規 ${redlineFrac * 100}%（期望 < 0.3%）", redlineFrac < 0.003)
        }
    }
}
