package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.image.BufferedImage
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * 規則版本的守門（[NightRead.RULES_VERSION]，docs/DECISIONS.md「規則版本」）：產品兩檔（標準＝[NightTier.L2]、更多＝
 * [NightTier.L3]，產品路徑 [NightRead.renderTiers]、預設亮度）在 fixture 頁上的成品摘要（SHA-256），每個版本記一個值。
 * 頁：版本 1–2 是五頁（ch34_011、demo01、demo02、demo05、demo06）；版本 3 起加 ch34_015（五頁都沒有效果線族，「更多」的效果線
 * 只動得到這頁）與合成頁 syn_more（research/make_syn_guard_fixture.py：閃光、人物旁淡線的外圈、只有字遮罩沒有字框的樹叢——公開
 * fixture 沒有一頁用得到這三條，關掉照樣同一個摘要）。舊版本的摘要是當時那幾頁的，只當歷史。
 *
 * 這個測試失敗＝產品輸出變了。是刻意的規則改動：把 RULES_VERSION 加 1、DECISIONS 歷史補一列，再把失敗訊息裡的新摘要記成
 * 新版本的值（舊版本的值留著當歷史）。版本交給使用者（含 debug APK）時把它記進 [delivered]，之後它的摘要就不能再改。不是刻意的：就是改壞了。少了這一道，已經交給使用者的 APK 產生的夜讀頁會跟新規則
 * 記成同一個版本，章節列不會標「可更新」。
 *
 * 只守這個模組的規則與預設參數。engine 的 NightReadRenderer（縮圖上限、偵測／分割配方）、fork 餵進來的字框、人物模型換版
 * 不在這裡，改了一樣要加版本（見 DECISIONS）。亮度偏好不算規則，所以只用預設亮度。
 * 摘要只在同一套 JVM 浮點下穩定：換了機器或 JDK 而摘要變了、parity 測試卻都過，先確認輸出真的有變再決定要不要加版本。
 */
class RulesVersionGuardTest {

    /**
     * 版本 → 五頁兩檔的成品摘要。新版本加一列，舊的不要刪。
     * **已交給使用者的版本（[delivered]）摘要不能改**：裝置上已經有用那版規則產生、記成那個版本的頁，改它的摘要等於讓不同的
     * 輸出共用一個版本號，那些頁永遠不會標「可更新」。輸出變了就加新版本。
     */
    private val digests = mapOf(
        1 to "852996dcb2d3dc5b0cf674feee343bfce9e3c115e6d3d2a8db014b5f2294bab0",
        // 2：人物外灰圈收細（折衷版＋開運算後只留與種子相連）。五頁裡 demo05 沒有認領、成品與版本 1 相同
        2 to "78a821623962844b53f7ff4c5f395c5dcc514df659a7ed3ef2293bce6dabbec7",
        // 3：「更多」背景物件規則（BgObjects；只動更多、標準不變）＋效果線與閃光（EffectLines；裁定 2 A／C）。五頁裡 demo06 多了
        // 亮背景區塗黑（沒有否決）；demo05 的「更多」以前與標準同一個合成鍵、不合成（摘要記 "="），現在規則開著就照樣合成（成品與標準
        // 相同）。效果線與閃光不動那五頁（五頁的摘要仍是 70ad1ca1…），所以這版起加 ch34_015（集中線塗黑）；複核（2026-10-04）
        // 加人物旁淡線的外圈、字畫亮只限有字框的字塊，再加合成頁 syn_more（閃光、淡線的手、沒有字框的樹叢；六頁的摘要不受這三條影響）。
        // 還沒交出
        3 to "4aa60a1bb49a7c0a46380e1ca4140ed801a66a06fe3f701e1c433eccc3c446a7",
    )

    /**
     * 已交給使用者（含真機測的 debug APK）的版本 → 交出時的摘要（與 DECISIONS「規則版本」歷史表的交出日期一致）。
     * 這張表只加不改；[digests] 裡這些版本的值必須等於這裡（[versionTableIsConsistent] 守）。
     */
    private val delivered = mapOf(
        1 to "852996dcb2d3dc5b0cf674feee343bfce9e3c115e6d3d2a8db014b5f2294bab0",     // 2026-10-03 debug APK
        2 to "78a821623962844b53f7ff4c5f395c5dcc514df659a7ed3ef2293bce6dabbec7",     // 2026-10-03 debug APK（灰圈收細）
    )

    private val pages = listOf("ch34_011", "demo01", "demo02", "demo05", "demo06", "ch34_015", "syn_more")

    /** 版本表本身：從 1 連續編到 [NightRead.RULES_VERSION]、各版本摘要互不相同、已交出的版本摘要沒被改。 */
    @Test
    fun versionTableIsConsistent() {
        assertEquals("版本號要從 1 連續編到 RULES_VERSION", (1..NightRead.RULES_VERSION).toList(), digests.keys.sorted())
        assertEquals("各版本的摘要要互不相同（同一份輸出不該有兩個版本號）", digests.size, digests.values.toSet().size)
        for ((v, d) in delivered) {
            assertEquals("版本 $v 已交給使用者，摘要不能改（輸出變了要加新版本）", d, digests[v])
        }
    }

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
        val shipped = if (NightRead.RULES_VERSION in delivered) {
            "版本 ${NightRead.RULES_VERSION} 已交給使用者，一定要加新版本、不能改它的摘要。"
        } else {
            "版本 ${NightRead.RULES_VERSION} 還沒交給使用者，也可以直接更新它的摘要（DECISIONS 歷史那一列跟著改）。"
        }
        assertEquals(
            "產品兩檔的成品變了（新摘要 $actual）。刻意改規則：NightRead.RULES_VERSION 加 1、DECISIONS「規則版本」歷史補一列、" +
                "把新摘要記成新版本的值；$shipped 不是刻意的就是改壞了。",
            digests[NightRead.RULES_VERSION],
            actual,
        )
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
