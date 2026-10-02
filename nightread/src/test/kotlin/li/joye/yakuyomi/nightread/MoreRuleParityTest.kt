package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 「更多」新規則 A2（[MoreRuleParams]、[Sticker.moreSelect]）的逐元件 parity：同 [PageParityTest] 的輸入，以產品「更多」
 * （[NightTier.L3]）分析一頁，比對研究端 `research/make_more_fixture.py` 從 `nightread.py`（L3 的環境變數 ＋ `NIGHTREAD_MORE=1`）
 * 的貼紙審計抄出的 `page/<頁>_more.txt`：
 *  - `G`：安全網每個候選實際沒過的門（[Sticker.Metrics.gates]）；
 *  - `M`：A2 每個候選的判定——來源（acc／rej／gut）、跳過原因（hug／gates）或 軟門、通過、沒過的 S 門，以及特徵
 *    rfs／rfi／fnc（研究端四捨五入後的值）、人物佔比 charf（逐位元）、自由邊界長度 lOut／lIn 與面積（整數像素計數）。
 * 全部要**相等**（不是容差）：特徵都是整數像素計數，連四捨五入都照研究端。元件以 bbox 對（cv2 與 Kotlin 的元件標號在少數頁差 1）。
 *
 * demo02 走到 C3（頁邊留白門檻邊上的旁白卡片 2 顆）與 C1；demo06 走到 C1 與 C2（只卡字壓 textOnP 的軟門元件，要過 rough 上限）。
 * 整頁成品的 parity 在 [TierParityTest] 的 MORE 檔。
 */
class MoreRuleParityTest {

    private fun readGray(name: String): Gray {
        val stream = javaClass.classLoader!!.getResourceAsStream("page/$name")
            ?: error("缺 fixture：page/$name — 先跑 research/make_page_fixture.py／make_more_fixture.py")
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

    private fun readText(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("page/$name")!!.bufferedReader().readText()

    private fun readRegions(name: String): List<TextRegion> =
        readText(name).lineSequence().filter { it.isNotBlank() }.map {
            val p = it.trim().split(" ").map(String::toInt)
            TextRegion(p[0], p[1], p[2], p[3])
        }.toList()

    @Test
    fun gutterCandidatesAndAcceptedMatchResearch() = checkPage("demo02")

    @Test
    fun softGateCandidatesMatchResearch() = checkPage("demo06")

    /**
     * 安全網選強／弱貼框那條路時，貼框分數先四捨五入到 3 位再比 0.4（研究端 `hug[i] = round(frac, 3)`）。原值 0.3995–0.4 的
     * 元件拿原值比會走弱貼框、研究端走強貼框，兩邊收的元件不同（真機頁上遇過 0.39984、0.39990 兩顆，fixture 沒有，所以單獨守）。
     */
    @Test
    fun strongHugRoundsLikeResearchAudit() {
        val p = NightReadParams()
        assertEquals(0.4, p.frameHugStrong, 0.0)
        assertTrue(Sticker.strongHug(0.39983579638752054, p))
        assertTrue(Sticker.strongHug(0.399899, p))
        assertTrue(Sticker.strongHug(0.39951, p))
        assertTrue(Sticker.strongHug(0.4, p))
        assertTrue(!Sticker.strongHug(0.39949, p))
        assertTrue(!Sticker.strongHug(0.3, p))
        assertTrue("沒有貼框分數（格內白、無框頁）＝弱貼框", !Sticker.strongHug(null, p))
    }

    private fun checkPage(page: String) {
        val input = NightReadInput(
            gray = readGray("${page}_gray.png"),
            seg = readMask("${page}_seg.png"),
            regions = readRegions("${page}_regions.txt"),
            charMask = readMask("${page}_char.png"),
            chroma = readGray("${page}_chroma.png"),
        )
        val p = NightTier.L3.apply()
        assertTrue("產品「更多」要開 A2", p.more.enabled)
        val a = NightRead.analyze(input, p, null, null, shared = false)
        val plan = NightRead.keepFor(a, p)
        val cc = a.wc.cc
        fun bbox(i: Int) = "${cc.left[i]} ${cc.top[i]} ${cc.left[i] + cc.width[i]} ${cc.top[i] + cc.height[i]}"

        val gotG = plan.metrics.entries.associate { (i, m) ->
            bbox(i) to (m.gates.map { it.key }.sorted().joinToString(",").ifEmpty { "-" })
        }
        val gotM = plan.more.associate { d ->
            val f = d.features
            bbox(d.id) to if (d.skip != null) {
                "${d.src} ${d.skip}"
            } else {
                "${d.src} - ${if (d.soft) 1 else 0} ${if (d.ok) 1 else 0} ${d.why.ifEmpty { "-" }} " +
                    "${f!!.rfs} ${f.rfi} ${f.fnc} ${f.charf} ${f.lOut} ${f.lIn} ${f.area}"
            }
        }
        val wantG = HashMap<String, String>()
        val wantM = HashMap<String, String>()
        for (line in readText("${page}_more.txt").lineSequence().filter { it.isNotBlank() }) {
            val t = line.trim().split(" ")
            val key = t.subList(1, 5).joinToString(" ")
            when (t[0]) {
                "G" -> wantG[key] = t[5]
                "M" -> wantM[key] = normalize(t.subList(5, t.size))
                else -> error("看不懂：$line")
            }
        }
        val gotMn = gotM.mapValues { normalize(it.value.split(" ")) }
        println("  $page：安全網候選 ${gotG.size}（研究端 ${wantG.size}）；A2 候選 ${gotMn.size}（研究端 ${wantM.size}）；" +
            "新加 ${plan.accept.size - plan.baseAccept.size} 顆")
        for (k in (wantM.keys + gotMn.keys).toSortedSet()) println("    M $k  py=${wantM[k]}  kt=${gotMn[k]}")
        assertEquals("$page 安全網每個候選實際沒過的門", wantG, gotG)
        assertEquals("$page 「更多」每個候選的判定與特徵", wantM, gotMn)
        assertTrue("$page 要有 A2 新加的元件（這頁是拿來守新規則的）", plan.accept.size > plan.baseAccept.size)
    }

    /** 數字欄位統一成 double 的字串（研究端 repr「0.0」與 Kotlin「0.0」一致；整數欄位原樣）。 */
    private fun normalize(t: List<String>): String = t.joinToString(" ") { s ->
        if (s.contains('.') || s.contains('E') || s.contains('e')) s.toDoubleOrNull()?.toString() ?: s else s
    }
}
