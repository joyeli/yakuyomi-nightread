package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * 版本 4 起加合成頁 syn_v4（research/make_v4_guard_fixture.py，帶去字遮罩 syn_v4_inpaint.png）：版本 4 的四條（灰虛線補黑、沒有
 * 字框的手寫字畫亮、淡線外圈貼線形、去字區旁的小塊不塗）各自關掉，合成頁的摘要都要變（[eachVersion4RuleChangesTheDigest]）。
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
        // 2026-10-04 交出
        3 to "4aa60a1bb49a7c0a46380e1ca4140ed801a66a06fe3f701e1c433eccc3c446a7",
        // 4：「更多」灰虛線補黑、沒有字框的手寫字畫亮、淡線外圈貼線形（含淡小記號留灰）、譯後頁去字區旁的小塊不塗（2026-10-05
        // 使用者決定 q1–q4）＋交出前複核的修法 c（淡線短截照版本 3、兩塊淡線之間不塗：c362_001 前臂）。只動更多、標準不變；
        // 公開 11 頁一個像素都沒變，前六頁的摘要不變；syn_more 因修法 c 變了；這版起加合成頁 syn_v4（八頁）。2026-10-06 交出
        4 to "31f2ed4d88e7d85fc8314e958ff2c6483d85d884427115f24c12efa196b32e85",
    )

    /**
     * 已交給使用者（含真機測的 debug APK）的版本 → 交出時的摘要（與 DECISIONS「規則版本」歷史表的交出日期一致）。
     * 這張表只加不改；[digests] 裡這些版本的值必須等於這裡（[versionTableIsConsistent] 守）。
     */
    private val delivered = mapOf(
        1 to "852996dcb2d3dc5b0cf674feee343bfce9e3c115e6d3d2a8db014b5f2294bab0",     // 2026-10-03 debug APK
        2 to "78a821623962844b53f7ff4c5f395c5dcc514df659a7ed3ef2293bce6dabbec7",     // 2026-10-03 debug APK（灰圈收細）
        3 to "4aa60a1bb49a7c0a46380e1ca4140ed801a66a06fe3f701e1c433eccc3c446a7",     // 2026-10-04 debug APK（「更多」背景物件規則）
        4 to "31f2ed4d88e7d85fc8314e958ff2c6483d85d884427115f24c12efa196b32e85",     // 2026-10-06 debug APK（「更多」規則版本 4）
    )

    private val pages = listOf("ch34_011", "demo01", "demo02", "demo05", "demo06", "ch34_015", "syn_more", "syn_v4")

    /** 合成頁（[eachVersion4RuleChangesTheDigest] 只算這兩頁：規則一條條關掉時要變的是它們）。 */
    private val synPages = listOf("syn_more", "syn_v4")

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
        // 譯後頁的去字遮罩（syn_v4 才有；其他頁＝日文頁，null）
        inpaintMask = if (javaClass.classLoader!!.getResource("page/${page}_inpaint.png") != null) readMask("${page}_inpaint.png") else null,
    )

    /** 一頁產品兩檔的摘要輸入（頁名／檔位標頭＋尺寸＋成品位元組；更多與標準相同時記 "="），依序餵給 [sinks]。 */
    private fun feedPage(page: String, base: NightReadParams, vararg sinks: MessageDigest) {
        val tiers = listOf(NightTier.L2, NightTier.L3)
        NightRead.renderTiers(input(page), tiers.map { it.apply(base) }) { k, gray ->
            val head = "$page/${tiers[k].key}:".toByteArray()
            for (s in sinks) s.update(head)
            if (gray == null) {
                // 更多與標準相同（產品不另存 more 檔）
                for (s in sinks) s.update("=".toByteArray())
            } else {
                val bytes = ByteArray(gray.data.size) { gray.data[it].toByte() }
                val size = "${gray.w}x${gray.h}".toByteArray()
                for (s in sinks) { s.update(size); s.update(bytes) }
            }
        }
    }

    private fun pageDigest(page: String, base: NightReadParams): String {
        val md = MessageDigest.getInstance("SHA-256")
        feedPage(page, base, md)
        return hex(md.digest())
    }

    /**
     * 規則版本 4 的四條各自關掉（研究端開關 NIGHTREAD_OBJ_SEAM／_TXTSTROKE／_PFSHAPE／_INPAINT），合成頁的成品摘要都要變——
     * 公開 fixture 動不到它們，守門只靠合成頁。修法 a（淡小記號留灰，pfMark＝0）、q2 的描亮邊清理（tsBandClean）、修法 c（短截
     * pfStub＝0、兩塊之間 pfPair＝0）也守。修法 b（只清新畫亮的字旁）守不到：字壓背景、有字框的字走泡的路徑，合成頁做不出來。
     */
    @Test
    fun eachVersion4RuleChangesTheDigest() {
        val base = NightReadParams()
        val on = synPages.associateWith { pageDigest(it, base) }
        val switches = listOf(
            "seam（q1 灰虛線補黑）" to base.copy(obj = base.obj.copy(seam = false)),
            "textStroke（q2 沒有字框的手寫字）" to base.copy(obj = base.obj.copy(textStroke = false)),
            "pfShape（q3 淡線外圈貼線形）" to base.copy(obj = base.obj.copy(pfShape = false)),
            "inpaintIslands（q4 去字區旁的小塊）" to base.copy(obj = base.obj.copy(inpaintIslands = false)),
            "pfMark（修法 a 淡小記號留灰）" to base.copy(obj = base.obj.copy(pfMark = 0)),
            "tsBandClean（q2 新畫亮的字旁的描亮邊清回 BG）" to base.copy(obj = base.obj.copy(tsBandClean = false)),
            "pfStub（修法 c 短截照版本 3）" to base.copy(obj = base.obj.copy(pfStub = 0)),
            "pfPair（修法 c 兩塊淡線之間不塗）" to base.copy(obj = base.obj.copy(pfPair = 0)),
        )
        for ((name, p) in switches) {
            val changed = synPages.filter { pageDigest(it, p) != on[it] }
            println("關掉 $name：摘要變了的合成頁 $changed")
            assertTrue("關掉 $name 時合成頁的摘要要變（不然守門抓不到這條的改動）", changed.isNotEmpty())
        }
    }

    @Test
    fun productOutputMatchesRulesVersion() {
        val md = MessageDigest.getInstance("SHA-256")
        val perPage = ArrayList<String>()
        for (page in pages) {
            val pageMd = MessageDigest.getInstance("SHA-256")
            feedPage(page, NightReadParams(), md, pageMd)
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
