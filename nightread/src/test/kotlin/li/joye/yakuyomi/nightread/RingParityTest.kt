package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 人物外灰圈收細（[Ring]，研究端 `research/nightread_ring.py`）的逐像素 parity。fixture 由 `research/make_ring_fixture.py` 產。
 *
 *  - 頁面級證據（[Ring.evidence]）只吃紙白正規化後的灰階與人物原輸出，兩邊輸入逐像素相同 ⇒ 輸出要逐像素相同：六頁的
 *    `<頁>_ring_ev.png`（可以收細＝背景側 ∧ 空白紙）與 `<頁>_ring_conf.png`（有輪廓的人物邊界點，含射線與連續兩步）。
 *  - 外法向的高斯核（float64、StrictMath.exp）與研究端 17 個值逐位元相同（研究端純量 math.exp，σ＝2）。
 *  - 生長＋收尾（[Ring.grow]）：demo01 標準檔人物還原前的 `D ∧ 證據` 與種子 → 認領逐像素相同。這頁開運算後有兩塊與種子斷開的
 *    孤立黑塊（40＋134 px），關掉 [RingParams.seedConnected] 要多出正好這 174 px（守「只留與種子相連」真的走到）。
 *
 * 合成狀態（可認領像素 D、種子）吃整條合成的中間量，上游（場景曲線、核心填色的距離）python 與 Kotlin 本來就有已知差異，整頁
 * 成品走 [TierParityTest]／[PageParityTest] 的容差；D 與認領在同一份狀態下逐像素相同另在研究端 scratch 驗（DECISIONS「人物外灰圈收細」）。
 */
class RingParityTest {

    private fun readGray(name: String): Gray {
        val stream = javaClass.classLoader!!.getResourceAsStream("page/$name")
            ?: error("缺 fixture：page/$name — 先跑 research/make_ring_fixture.py")
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

    private fun diff(a: Mask, b: Mask): Pair<Int, Int> {
        var more = 0
        var less = 0
        for (i in a.data.indices) {
            if (a.data[i] && !b.data[i]) more++
            if (!a.data[i] && b.data[i]) less++
        }
        return more to less
    }

    @Test
    fun gaussKernelMatchesResearch() {
        // 研究端 _gauss_kernel(2.0)（math.exp 純量、依序加總後正規化）的 17 個值
        val want = listOf(
            "0x1.18aad19e4159bp-14", "0x1.c98b8c5d0dda5p-12", "0x1.227362b5fc92dp-9", "0x1.1f30504e20207p-7",
            "0x1.ba4d4125ffd2ap-6", "0x1.0941b71ceef37p-4", "0x1.ef9093fc46e5ap-4", "0x1.68856f9ab1983p-3",
            "0x1.98862a07ae7b4p-3",
        )
        val k = Ring.gaussKernel(RingParams().raySigma)
        assertEquals(17, k.size)
        for (i in want.indices) {
            assertEquals("核 $i", want[i], java.lang.Double.toHexString(k[i]))
            assertEquals("核 ${16 - i}（對稱）", want[i], java.lang.Double.toHexString(k[16 - i]))
        }
    }

    /** [Cv.distanceSq]（整數平方距離、交點用有理數比較）對暴力解：隨機遮罩、窄長條、全前景、單一背景點。 */
    @Test
    fun distanceSqIsExact() {
        val rnd = java.util.Random(7)
        val cases = ArrayList<Mask>()
        for ((w, h, dens) in listOf(Triple(37, 23, 0.97), Triple(64, 64, 0.995), Triple(1, 40, 0.9), Triple(50, 1, 0.9), Triple(29, 31, 0.5))) {
            cases += Mask(w, h, BooleanArray(w * h) { rnd.nextDouble() < dens })
        }
        cases += Mask(9, 7, BooleanArray(63) { true })
        cases += Mask(41, 33, BooleanArray(41 * 33) { it != 17 * 41 + 3 })
        for (m in cases) {
            val got = Cv.distanceSq(m)
            val bg = (0 until m.w * m.h).filter { !m.data[it] }
            for (i in got.indices) {
                val x = i % m.w
                val y = i / m.w
                var best = Cv.DIST_SQ_INF
                for (j in bg) {
                    val dx = (j % m.w) - x
                    val dy = (j / m.w) - y
                    best = minOf(best, dx * dx + dy * dy)
                }
                assertEquals("${m.w}×${m.h} ($x,$y)", best, got[i])
            }
        }
    }

    @Test
    fun evidenceMatchesResearch() {
        val p = NightReadParams()
        for (page in listOf("ch34_011", "demo01", "demo02", "demo04", "demo05", "demo06")) {
            val g = Regions.normalizePaper(readGray("${page}_gray.png"), readGray("${page}_chroma.png"), p)
            val dbg = HashMap<String, Any>()
            val ev = Ring.evidence(g, readMask("${page}_char.png"), p, dbg)
            val (evMore, evLess) = diff(ev, readMask("${page}_ring_ev.png"))
            val (cMore, cLess) = diff(dbg["ring_conf"] as Mask, readMask("${page}_ring_conf.png"))
            println("  $page：可以收細 ${ev.count()} px（多 $evMore、少 $evLess）；有輪廓邊界點 ${(dbg["ring_conf"] as Mask).count()} px（多 $cMore、少 $cLess）")
            assertEquals("$page 有輪廓邊界點 多", 0, cMore)
            assertEquals("$page 有輪廓邊界點 少", 0, cLess)
            assertEquals("$page 可以收細 多", 0, evMore)
            assertEquals("$page 可以收細 少", 0, evLess)
            assertTrue("$page 要有有輪廓的邊界點（不然測不到射線與連續）", (dbg["ring_conf"] as Mask).any())
        }
    }

    @Test
    fun growMatchesResearch() {
        val allowed = readMask("demo01_ring_allowed.png")
        val seed = readMask("demo01_ring_seed.png")
        val want = readMask("demo01_ring_claim.png")
        val rp = RingParams()
        val got = Ring.grow(allowed, seed, rp)
        val (more, less) = diff(got, want)
        println("  demo01 標準：認領 ${got.count()} px（研究端 ${want.count()}；多 $more、少 $less）")
        assertEquals("認領 多", 0, more)
        assertEquals("認領 少", 0, less)
        val loose = Ring.grow(allowed, seed, rp.copy(seedConnected = false))
        val (lm, ll) = diff(loose, got)
        assertEquals("關掉「只留與種子相連」只會多、不會少", 0, ll)
        assertEquals("關掉「只留與種子相連」多出 demo01 那兩塊孤立黑塊（40＋134 px）", 174, lm)
    }
}
