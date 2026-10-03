package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.image.BufferedImage
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * 規則版本的守門（[NightRead.RULES_VERSION]，docs/DECISIONS.md「規則版本」）：產品兩檔（標準＝[NightTier.L2]、更多＝
 * [NightTier.L3]，產品路徑 [NightRead.renderTiers]、預設亮度）在五頁 fixture 上的成品摘要（SHA-256），每個版本記一個值。
 *
 * 這個測試失敗＝產品輸出變了。是刻意的規則改動：把 RULES_VERSION 加 1、DECISIONS 歷史補一列，再把失敗訊息裡的新摘要記成
 * 新版本的值（舊版本的值留著當歷史）。不是刻意的：就是改壞了。少了這一道，已經交給使用者的 APK 產生的夜讀頁會跟新規則
 * 記成同一個版本，章節列不會標「可更新」。
 *
 * 只守這個模組的規則與預設參數。engine 的 NightReadRenderer（縮圖上限、偵測／分割配方）、fork 餵進來的字框、人物模型換版
 * 不在這裡，改了一樣要加版本（見 DECISIONS）。亮度偏好不算規則，所以只用預設亮度。
 * 摘要只在同一套 JVM 浮點下穩定：換了機器或 JDK 而摘要變了、parity 測試卻都過，先確認輸出真的有變再決定要不要加版本。
 */
class RulesVersionGuardTest {

    /** 版本 → 五頁兩檔的成品摘要。新版本加一列，舊的不要刪。 */
    private val digests = mapOf(
        1 to "852996dcb2d3dc5b0cf674feee343bfce9e3c115e6d3d2a8db014b5f2294bab0",
        // 2：人物外灰圈收細（折衷版＋開運算後只留與種子相連）。五頁裡 demo05 沒有認領、成品與版本 1 相同
        2 to "78a821623962844b53f7ff4c5f395c5dcc514df659a7ed3ef2293bce6dabbec7",
    )

    private val pages = listOf("ch34_011", "demo01", "demo02", "demo05", "demo06")

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

    @Test
    fun productOutputMatchesRulesVersion() {
        val tiers = listOf(NightTier.L2, NightTier.L3)
        val md = MessageDigest.getInstance("SHA-256")
        val perPage = ArrayList<String>()
        for (page in pages) {
            val pageMd = MessageDigest.getInstance("SHA-256")
            NightRead.renderTiers(input(page), tiers.map { it.apply() }) { k, gray ->
                val head = "$page/${tiers[k].key}:"
                md.update(head.toByteArray())
                pageMd.update(head.toByteArray())
                if (gray == null) {
                    // 更多與標準相同（產品不另存 more 檔）
                    md.update("=".toByteArray())
                    pageMd.update("=".toByteArray())
                } else {
                    val bytes = ByteArray(gray.data.size) { gray.data[it].toByte() }
                    val size = "${gray.w}x${gray.h}".toByteArray()
                    md.update(size)
                    md.update(bytes)
                    pageMd.update(size)
                    pageMd.update(bytes)
                }
            }
            perPage += "$page=${hex(pageMd.digest()).take(16)}"
        }
        val actual = hex(md.digest())
        println("規則版本 ${NightRead.RULES_VERSION} 產品兩檔摘要 $actual（${perPage.joinToString(" ")}）")
        assertEquals(
            "產品兩檔的成品變了（新摘要 $actual）。刻意改規則：NightRead.RULES_VERSION 加 1、DECISIONS「規則版本」歷史補一列、" +
                "把新摘要記成新版本的值；不是刻意的就是改壞了。",
            digests[NightRead.RULES_VERSION],
            actual,
        )
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
