package li.joye.yakuyomi.nightread

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 任意角度格溝（[Separators] ＝ research/nightread_sep.py）單獨的 parity：拿整頁 fixture 的輸入（灰階、文字遮罩、
 * 最終泡遮罩；格線用 Kotlin 自己的 [Regions.frameLineMask]），比對 python `build_sep` 的遮罩，**逐像素相同**。
 *
 * 期望遮罩由 `research/make_page_fixture.py` 的 `sep_masks` 產出（`_sep`／`_sep_strip`／`_sep_margin`／`_sep_frame`）。
 * 整頁的 PageParityTest 要等 SEP 與出血過濾都接進 compose 才會對上；這個測試先把 SEP 本身釘住。
 * 47 頁（含真機頁）的完整比對在研究端的 scratch harness 跑過，結果見 docs/DECISIONS.md。
 */
class SeparatorsParityTest {

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

    /** 有框頁：10 對裡收 5 對（其餘沿線沒有重疊），溝帶與頁邊都有。 */
    @Test
    fun framedPage() {
        check("ch34_011")
    }

    /** 52 條框線群組（泡框排除 15 條）、15 對只收 3 對：剖面白度不過 6、沒重疊 4、排線家族 1、兩側不碰框線 1。 */
    @Test
    fun manyRejectedPairs() {
        check("demo02")
    }

    /** 2 對都收、頁邊佔大片。 */
    @Test
    fun wideMargins() {
        check("demo06")
    }

    /** 沒有任何格溝／頁邊的頁（demo04 一條框線都沒有）：一顆都不塗。 */
    @Test
    fun pageWithoutSeparators() {
        val lay = check("demo04")
        assertTrue("demo04：不該有任何格溝／頁邊", !lay.sep.any())
    }

    /**
     * 無框水彩頁：41 條框線群組、27 對全拒（剖面白度 16、沒重疊 11）⇒ 沒有溝帶；只有右頁緣一條頁邊（1,861 px，頁緣到一條近垂直
     * 長線之間的紙白）。這條是 PEAK_MAX 400 → 800 後才有的（400 時那條線的峰排在前 400 之外），使用者看圖接受（docs/DECISIONS.md）。
     */
    @Test
    fun framelessPageEdgeMarginOnly() {
        val lay = check("demo05")
        assertTrue("demo05：溝對全拒、不該有溝帶", !lay.strip.any())
        assertTrue("demo05：右頁緣那條頁邊要在", lay.margin.any())
    }

    private fun check(page: String): Separators.Layer {
        val p = NightReadParams()
        val g = Regions.normalizePaper(readGray("${page}_gray.png"), readGray("${page}_chroma.png"), p)
        val (lh, lv) = Regions.frameLineMask(g, p)
        val t0 = System.currentTimeMillis()
        val lay = Separators.build(g, readMask("${page}_seg.png"), readMask("${page}_bubble.png"), lh or lv, p)
        val ms = System.currentTimeMillis() - t0
        val sb = StringBuilder("$page ${ms}ms 群組=${lay.groups.size} 溝對=${lay.pairs.size} 收=${lay.pairs.count { it.acc }}")
        var total = 0
        for ((tag, kt) in listOf("sep" to lay.sep, "sep_strip" to lay.strip, "sep_margin" to lay.margin, "sep_frame" to lay.frameArb)) {
            val py = readMask("${page}_$tag.png")
            var more = 0
            var less = 0
            for (i in py.data.indices) {
                if (kt.data[i] && !py.data[i]) more++
                if (!kt.data[i] && py.data[i]) less++
            }
            sb.append("  $tag: python=${py.count()} 多=$more 少=$less")
            total += more + less
        }
        println(sb)
        assertTrue("$page：SEP 遮罩與 python 不同（$sb）", total == 0)
        return lay
    }
}
