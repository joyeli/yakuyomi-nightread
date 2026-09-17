package li.joye.yakuyomi.nightread

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * 整頁 parity：拿 Python 管線的**實際輸入**（灰階、文字筆畫遮罩、文字區、人物遮罩）跑
 * Kotlin 版，比對 Python 的**實際輸出**。
 *
 * fixture 由 `research/make_page_fixture.py` 產出。灰階存成 PNG 而不是沿用原圖 JPEG，
 * 是為了讓兩邊從同一份 8-bit 資料出發，排除色彩轉換的 ±1 差異。
 *
 * 不要求逐位元：距離變換在 Python 是 chamfer 近似、Kotlin 是精確歐氏，這個差會沿著門檻
 * 傳遞。驗收看的是**視覺等價**——平均絕對差、大偏差像素的比例，以及最重要的紅線指標
 * 「原本是白的地方有沒有被塗黑」。
 */
class PageParityTest {

    private fun readGray(name: String): Gray {
        val stream = javaClass.classLoader!!.getResourceAsStream("page/$name")
            ?: error("缺 fixture：page/$name — 先跑 research/make_page_fixture.py")
        val img: BufferedImage = stream.use { ImageIO.read(it) }
        val w = img.width
        val h = img.height
        val g = Gray(w, h)
        val raster = img.raster
        for (y in 0 until h) {
            for (x in 0 until w) g.data[y * w + x] = raster.getSample(x, y, 0)
        }
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

    private fun dump(g: Gray, path: String) {
        val img = BufferedImage(g.w, g.h, BufferedImage.TYPE_BYTE_GRAY)
        // ⚠️ 用 raster.setSample 而不是 setRGB：TYPE_BYTE_GRAY 的 setRGB 會做色彩空間轉換，
        // 存進去的不是你給的值（除錯時會看到憑空變暗的圖）
        val raster = img.raster
        for (y in 0 until g.h) for (x in 0 until g.w) raster.setSample(x, y, 0, g.data[y * g.w + x])
        val f = java.io.File(path)
        f.parentFile?.mkdirs()
        ImageIO.write(img, "png", f)
    }

    /** 有框的黑白頁：留白、格內白、氣泡核心填色、貼框擢升全部走到。 */
    @Test
    fun framedPageMatchesPythonPipeline() = checkPage("ch34_011")

    /** 無框的水彩頁：走 frameless 分支，且紙白峰只有 223、彩度門必須擋住整片拉白。 */
    @Test
    fun framelessColourPageMatchesPythonPipeline() = checkPage("demo05")

    private fun checkPage(page: String) {
        val gray = readGray("${page}_gray.png")
        val input = NightReadInput(
            gray = gray,
            seg = readMask("${page}_seg.png"),
            regions = readRegions("${page}_regions.txt"),
            charMask = readMask("${page}_char.png"),
            chroma = readGray("${page}_chroma.png"),
        )
        val expected = readGray("${page}_expected.png")

        NightRead.debug = { k, v -> println("  [debug] $k = $v") }
        val t0 = System.currentTimeMillis()
        val result = NightRead.render(input)
        val ms = System.currentTimeMillis() - t0
        val got = result.out

        println("  sticker accept=${result.stickerAccept.sorted()} promoted=${result.stickerPromoted.sorted()}")

        // 中間遮罩先比：分歧從哪個階段開始，決定要去哪裡找 bug
        for ((tag, kt) in listOf("gutter" to result.gutter, "bubble" to result.bubble)) {
            val py = readMask("${page}_$tag.png")
            var only = 0
            var miss = 0
            for (i in py.data.indices) {
                if (kt.data[i] && !py.data[i]) only++
                if (!kt.data[i] && py.data[i]) miss++
            }
            println("  $tag: python=${py.count()} kotlin=${kt.count()} 多=$only 少=$miss")
        }

        // 失敗時要看得到圖，不然只能猜
        dump(got, "build/parity_${page}_kotlin.png")
        dump(expected, "build/parity_${page}_python.png")

        var sumAbs = 0L
        var big = 0           // 差 > 32 階＝肉眼看得出來的不同
        var huge = 0          // 差 > 96 階＝分區判斷不同（填黑 vs 留灰）
        for (i in expected.data.indices) {
            val d = abs(expected.data[i] - got.data[i])
            sumAbs += d
            if (d > 32) big++
            if (d > 96) huge++
        }
        val n = expected.data.size
        val mae = sumAbs.toDouble() / n
        val bigFrac = big.toDouble() / n
        val hugeFrac = huge.toDouble() / n

        // 紅線：原圖是白、Python 沒塗黑、Kotlin 卻塗黑了 ⇒ 這是絕不允許的方向
        var redline = 0
        for (i in expected.data.indices) {
            if (gray.data[i] >= 235 && expected.data[i] >= 110 && got.data[i] < 60) redline++
        }
        val redlineFrac = redline.toDouble() / n

        println(
            "$page ${gray.w}×${gray.h}：${ms}ms  MAE=${"%.2f".format(mae)}  " +
                ">32階=${"%.3f".format(bigFrac * 100)}%  >96階=${"%.3f".format(hugeFrac * 100)}%  " +
                "紅線=${"%.4f".format(redlineFrac * 100)}%"
        )

        assertTrue("$page 平均絕對差 $mae 過大（期望 < 2）", mae < 2.0)
        assertTrue("$page 肉眼可見差異 ${bigFrac * 100}% 過多（期望 < 1%）", bigFrac < 0.01)
        assertTrue("$page 分區判斷差異 ${hugeFrac * 100}% 過多（期望 < 0.8%）", hugeFrac < 0.008)
        assertTrue("$page 紅線違規 ${redlineFrac * 100}%（期望 < 0.3%）", redlineFrac < 0.003)
    }
}
