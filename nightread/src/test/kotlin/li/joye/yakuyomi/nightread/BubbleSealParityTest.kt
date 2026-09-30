package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * 漏泡封縫（v3，[BubbleSealParams]）的 repo 內回歸：合成缺口頁 `ch34_011_gapA`。
 *
 * 只由 ch34_011 的測試資源合成（`research/make_seal_fixture.py`）：灰階上把「ちょっと待ってください」泡（右下）與右側留白
 * 之間最近的那段框線抹白成 2 px 寬的通道（[GAP] 12 px）。泡的白因此與留白連成同一個大元件 ⇒ 加入前整顆拒收（研究端 L1
 * 泡內紙白 0% 變黑、泡遮罩 202,730 px）；封縫把 ≤ 2 px 的縫封起來，泡自成一塊走原本的泡路徑（研究端 L1 99.98% 變黑）。
 * 其餘輸入＝ch34_011 的資源檔（彩度資源本來就全 0，與研究端「灰階三通道 PNG」的彩度相同）。
 *
 * 期望值＝研究端 research/nightread.py 對同一份輸入的輸出：`_expected`（預設參數；L1 在這頁與預設逐像素相同）、
 * `_bubble`／`_gutter`（逐像素）、`_sealed`（只有封縫才新增的泡，逐像素）。47 頁 × 4 檔、攻擊頁、擾動頁的完整比對在研究端的
 * scratch harness 跑過，數字見 docs/DECISIONS.md「漏泡封縫」。
 */
class BubbleSealParityTest {

    private val page = "ch34_011"
    private val syn = "ch34_011_gapA"

    /** 抹白的像素 (x, y)：同 research/make_seal_fixture.py 的 GAP。 */
    private val gap = listOf(
        614 to 1138, 614 to 1139, 615 to 1137, 615 to 1138, 616 to 1136, 616 to 1137,
        617 to 1136, 617 to 1137, 618 to 1135, 618 to 1136, 619 to 1134, 619 to 1135,
    )

    private fun readGray(name: String): Gray {
        val stream = javaClass.classLoader!!.getResourceAsStream("page/$name")
            ?: error("缺 fixture：page/$name — 先跑 research/make_seal_fixture.py")
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

    private fun input(): NightReadInput {
        val gray = readGray("${page}_gray.png")
        for ((x, y) in gap) gray.data[y * gray.w + x] = 255
        return NightReadInput(
            gray = gray,
            seg = readMask("${page}_seg.png"),
            regions = readRegions("${page}_regions.txt"),
            charMask = readMask("${page}_char.png"),
            chroma = readGray("${page}_chroma.png"),
        )
    }

    private fun diff(kt: Mask, py: Mask): Pair<Int, Int> {
        var more = 0
        var less = 0
        for (i in py.data.indices) {
            if (kt.data[i] && !py.data[i]) more++
            if (!kt.data[i] && py.data[i]) less++
        }
        return more to less
    }

    /** 封縫救回的泡（還沒經人物修剪）逐像素同研究端 `local_out["mask"]`。 */
    @Test
    fun sealedBubbleMatchesPython() {
        val inp = input()
        val p = NightReadParams()
        val g = Regions.normalizePaper(inp.gray, inp.chroma, p)
        val wc = Regions.classifyWhiteComponents(g, p)
        val res = Regions.buildBubbleMask(g, inp.regions, inp.seg, wc.cc, wc.gutterIds + wc.panelIds, p, inp.chroma,
            charRaw = inp.charMask)
        val py = readMask("${syn}_sealed.png")
        val (more, less) = diff(res.sealed, py)
        println("  sealed: python=${py.count()} kotlin=${res.sealed.count()} 多=$more 少=$less")
        assertEquals("封縫遮罩像素數", 50467, py.count())
        assertEquals("封縫遮罩 多", 0, more)
        assertEquals("封縫遮罩 少", 0, less)
    }

    /** r = 0（關）＝加入前：泡整顆被拒（泡遮罩 202,730 px，同研究端 NIGHTREAD_BUBBLE_SEAL_R=0），L1 泡內紙白幾乎不變黑。 */
    @Test
    fun sealOffRejectsTheLeakyBubble() {
        val inp = input()
        val off = NightReadParams(stickerMode = StickerMode.PLAIN, pseudoBubbles = false, harmonize = false,
            bubbleSeal = BubbleSealParams(r = 0))
        val res = NightRead.render(inp, off)
        assertEquals("r=0 的泡遮罩像素數（研究端 202730）", 202730, res.bubble.count())
        val sealed = readMask("${syn}_sealed.png")
        var n = 0
        var dark = 0
        for (i in sealed.data.indices) {
            if (!sealed.data[i] || inp.gray.data[i] < 235) continue
            n++
            if (res.out.data[i] <= 40) dark++
        }
        println("  r=0 L1：泡內紙白 $n px，變黑 $dark")
        assertTrue("r=0 時泡內紙白不該變黑（$dark/$n）", dark < n / 100)
    }

    /** 預設與 L1：泡／留白遮罩逐像素、成品在 PageParityTest 容差內，泡內紙白 ≥ 99% 變黑。 */
    @Test
    fun renderMatchesPython() {
        val inp = input()
        val expected = readGray("${syn}_expected.png")
        val pyBubble = readMask("${syn}_bubble.png")
        val pyGutter = readMask("${syn}_gutter.png")
        val sealed = readMask("${syn}_sealed.png")
        val tiers = listOf(
            "std" to NightReadParams(),
            "L1" to NightReadParams(stickerMode = StickerMode.PLAIN, pseudoBubbles = false, harmonize = false),
        )
        for ((tier, p) in tiers) {
            val res = NightRead.render(inp, p)
            for ((tag, kt, py) in listOf(Triple("bubble", res.bubble, pyBubble), Triple("gutter", res.gutter, pyGutter))) {
                val (more, less) = diff(kt, py)
                println("  $syn/$tier $tag: python=${py.count()} kotlin=${kt.count()} 多=$more 少=$less")
                assertEquals("$syn/$tier $tag 多", 0, more)
                assertEquals("$syn/$tier $tag 少", 0, less)
            }
            val got = res.out
            var sumAbs = 0L
            var big = 0
            var huge = 0
            var red = 0
            var n = 0
            var dark = 0
            for (i in expected.data.indices) {
                val d = abs(expected.data[i] - got.data[i])
                sumAbs += d
                if (d > 32) big++
                if (d > 96) huge++
                if (inp.gray.data[i] >= 235 && expected.data[i] >= 110 && got.data[i] < 60) red++
                if (sealed.data[i] && inp.gray.data[i] >= 235) {
                    n++
                    if (got.data[i] <= 40) dark++
                }
            }
            val total = expected.data.size.toDouble()
            val mae = sumAbs / total
            println(
                "$syn/$tier：MAE=${"%.3f".format(mae)}  >32階=${"%.3f".format(big / total * 100)}%  " +
                    ">96階=${"%.3f".format(huge / total * 100)}%  紅線=${"%.4f".format(red / total * 100)}%  泡內紙白變黑 $dark/$n"
            )
            assertTrue("$syn/$tier 平均絕對差 $mae 過大（期望 < 2）", mae < 2.0)
            assertTrue("$syn/$tier 肉眼可見差異過多", big / total < 0.01)
            assertTrue("$syn/$tier 分區判斷差異過多", huge / total < 0.008)
            assertTrue("$syn/$tier 紅線違規", red / total < 0.003)
            assertTrue("$syn/$tier 泡內紙白該變黑（$dark/$n）", dark >= n * 0.99)
        }
    }
}
