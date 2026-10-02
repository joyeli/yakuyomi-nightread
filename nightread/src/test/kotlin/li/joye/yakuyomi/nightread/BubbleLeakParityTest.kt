package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * 漏泡判準（[NightReadParams.bubbleLeak]；研究端 `bubble_leak`）的 parity，用 demo04：第 2 格那顆沒有框線的泡，泡的白直接
 * 連到女主右側垂髮的白色高光。泡判乾淨、整顆塗黑的話頭髮跟著被塗（守護框「第2格女主右側垂髮下段(肩旁)」72.1%）；
 * 漏泡判準把「乾淨泡 ∩ 人物原輸出、貼著的泡外緣沒有框線」的那一塊還給人物（4.4%）。
 *
 * 其他 parity 頁（ch34_011、demo02、demo05、demo06）在這條規則下 0 px 變化，整頁容差又寬（demo04 的差只佔頁面 0.09%），
 * 只有逐像素比的泡遮罩抓得到漏移植：開著時 Kotlin 的泡遮罩與 python（`page/demo04_bubble.png`，research/make_page_fixture.py
 * 以完整管線預設值產出）逐像素相同；關掉時差 6,589 px（[withoutLeakRuleBubbleDiffers] 守「這頁真的走到這條規則」）。
 */
class BubbleLeakParityTest {

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

    /** 泡遮罩（人物修剪後）與期望值的差：(Kotlin 多, Kotlin 少)。只跑分析段，不合成。 */
    private fun bubbleDiff(inp: NightReadInput, p: NightReadParams, py: Mask): Pair<Int, Int> {
        val kt = NightRead.analyze(inp, p, null, null, false).bubble
        var only = 0
        var miss = 0
        for (i in py.data.indices) {
            if (kt.data[i] && !py.data[i]) only++
            if (!kt.data[i] && py.data[i]) miss++
        }
        return only to miss
    }

    /** 漏泡判準開著（預設）：泡遮罩與 python 逐像素相同。漏移植或判準寫錯（環、窗、門檻）這裡會不符。 */
    @Test
    fun leakRuleBubbleMatchesPython() {
        val (only, miss) = bubbleDiff(input("demo04"), NightReadParams(), readMask("demo04_bubble.png"))
        println("demo04 泡遮罩（漏泡判準開）：多=$only 少=$miss")
        assertEquals("demo04 泡遮罩 多", 0, only)
        assertEquals("demo04 泡遮罩 少", 0, miss)
    }

    /**
     * 漏泡判準關掉＝修法 e 的行為：漏進頭髮的那兩塊（研究端量到 6,589 px）留在泡裡。守「demo04 真的走到這條規則」——
     * 哪天上游改動讓這頁不再觸發，[leakRuleBubbleMatchesPython] 就守不到漏移植了。
     */
    @Test
    fun withoutLeakRuleBubbleDiffers() {
        val (only, miss) = bubbleDiff(input("demo04"), NightReadParams(bubbleLeak = false), readMask("demo04_bubble.png"))
        println("demo04 泡遮罩（漏泡判準關）：多=$only 少=$miss")
        assertEquals("關掉漏泡判準只會讓泡變大", 0, miss)
        assertTrue("demo04 關掉漏泡判準泡遮罩應多出漏進頭髮的那幾塊（期望約 6,589 px，實得 $only）", only in 5000..8000)
    }

    /**
     * 整頁成品：容差同 [PageParityTest]；另量守護框「第2格女主右側垂髮下段(肩旁)」（research/nightread_guard.json
     * [1565,600,1650,800)）框內原白被塗黑的比例——研究端 4.4%，沒有漏泡判準時 72.1%；守護框的違規門檻是 15%。
     */
    @Test
    fun hairBesideFramelessBubbleStaysUnpainted() {
        val inp = input("demo04")
        val gray = inp.gray
        val expected = readGray("demo04_expected.png")
        val got = NightRead.render(inp).out

        var white = 0
        var black = 0
        for (y in 600 until 800) {
            for (x in 1565 until 1650) {
                val i = y * gray.w + x
                if (gray.data[i] >= 235) { white++; if (got.data[i] < 60) black++ }
            }
        }
        val frac = black.toDouble() / white
        println("demo04 垂髮守護框：原白 $white、塗黑 $black（${"%.1f".format(frac * 100)}%）")
        assertTrue("demo04 垂髮守護框原白太少（$white）", white >= 50)
        assertTrue("demo04 垂髮被塗黑 ${frac * 100}%（守護框門檻 15%）", frac <= 0.15)

        var sumAbs = 0L
        var big = 0
        var huge = 0
        var redline = 0
        for (i in expected.data.indices) {
            val d = abs(expected.data[i] - got.data[i])
            sumAbs += d
            if (d > 32) big++
            if (d > 96) huge++
            if (gray.data[i] >= 235 && expected.data[i] >= 110 && got.data[i] < 60) redline++
        }
        val n = expected.data.size
        val mae = sumAbs.toDouble() / n
        println(
            "demo04 ${gray.w}×${gray.h}：MAE=${"%.2f".format(mae)}  >32階=${"%.3f".format(big * 100.0 / n)}%  " +
                ">96階=${"%.3f".format(huge * 100.0 / n)}%  紅線=${"%.4f".format(redline * 100.0 / n)}%"
        )
        assertTrue("demo04 平均絕對差 $mae 過大（期望 < 2）", mae < 2.0)
        assertTrue("demo04 肉眼可見差異過多（期望 < 1%）", big.toDouble() / n < 0.01)
        assertTrue("demo04 分區判斷差異過多（期望 < 0.8%）", huge.toDouble() / n < 0.008)
        assertTrue("demo04 紅線違規過多（期望 < 0.3%）", redline.toDouble() / n < 0.003)
    }
}
