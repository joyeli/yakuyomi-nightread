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

    /**
     * 寬或高不到 9 px 的頁（外法向的高斯窗半徑 8，要反射不只一次）：研究端 `outward_normals` 在四張小遮罩上的外法向（float.hex）、
     * `evidence` 在四張小頁上的有輪廓邊界點與可以收細，Kotlin 逐位元相同；整頁 [NightRead.render] 不拋例外（eb78e98 在 8×8、40×6
     * 越界）。研究端的值用 `research/nightread_ring.py` 的 `_refl`（反覆反射）算。
     */
    @Test
    fun tinyPagesMatchResearch() {
        val k = Ring.gaussKernel(RingParams().raySigma)
        fun mask(w: Int, h: Int, bits: String) = Mask(w, h, BooleanArray(w * h) { bits[it] == '1' })
        fun rect(w: Int, h: Int, on: List<IntArray>, off: List<IntArray> = emptyList()): Mask {
            val m = Mask(w, h)
            for (r in on) for (y in r[1] until r[3]) for (x in r[0] until r[2]) m.data[y * w + x] = true
            for (q in off) m.data[q[1] * w + q[0]] = false
            return m
        }
        // 研究端：每個 raw 像素（逐列）的 x y nx ny
        val normals = listOf(
            rect(12, 5, listOf(intArrayOf(4, 1, 10, 4), intArrayOf(10, 2, 11, 3)), listOf(intArrayOf(4, 1), intArrayOf(9, 3))) to """
                5 1 -0x1.fdf327278e3c2p-1 -0x1.6e2f7afd1fe0bp-4
                6 1 -0x1.fcfcb0528bea2p-1 -0x1.bbb49f5765911p-4
                7 1 0x1.ff9fadda754b5p-1 -0x1.39ff9be254695p-5
                8 1 0x1.fedb4bb4db2dbp-1 0x1.1195aba807895p-4
                9 1 0x1.fdd8d1813d8b2p-1 0x1.773dae47984c1p-4
                4 2 -0x1.fdb373aad9526p-1 -0x1.83b963235f77ap-4
                5 2 -0x1.fdb6baa6cb017p-1 -0x1.82a543e88e8e0p-4
                6 2 -0x1.fe64859f9b984p-1 -0x1.444dc4644358cp-4
                7 2 0x1.f595f4d9ea14fp-1 0x1.9af6cab743442p-3
                8 2 0x1.f9fab9dbb64b0p-1 0x1.3925d7a576b2cp-3
                9 2 0x1.f8049b8f63449p-1 0x1.68380ddd4eb21p-3
                10 2 0x1.eac63bec68f97p-1 0x1.23c8cea2c7d49p-2
                4 3 -0x1.ff503d9521315p-1 -0x1.a8186992e385bp-5
                5 3 -0x1.ff885fe17358dp-1 -0x1.5dea345fdcd8ep-5
                6 3 -0x1.fff44071e7e7ep-1 0x1.b6b63fd283b31p-7
                7 3 0x1.f278e12fc79fdp-1 0x1.d3aa896542ba0p-3
                8 3 0x1.fa9a8c812a4d5p-1 0x1.288ef97188e2bp-3
            """,
            rect(5, 12, listOf(intArrayOf(1, 3, 4, 9), intArrayOf(2, 9, 3, 10)), listOf(intArrayOf(1, 3), intArrayOf(3, 8))) to """
                2 3 -0x1.93d894eb02a07p-4 -0x1.fd815e10ebf3ap-1
                3 3 -0x1.bc4bebba5d701p-5 -0x1.ff3f15c3b08cbp-1
                1 4 -0x1.7384aff3e42e8p-4 -0x1.fde3b90c6ec68p-1
                2 4 -0x1.895a6ef275941p-4 -0x1.fda232c82c2e8p-1
                3 4 -0x1.65e16bd6cd2d6p-5 -0x1.ff82dd0b0ce6bp-1
                1 5 -0x1.ce87c9f1479fcp-4 -0x1.fcb9a3250cba1p-1
                2 5 -0x1.57b2ae1ff2f12p-4 -0x1.fe31bef0e8d8dp-1
                3 5 0x1.961742f0332cdp-7 -0x1.fff5ef1336da8p-1
                1 6 -0x1.077db3d7d309cp-5 0x1.ffbc2e67ff902p-1
                2 6 0x1.2c44c3ec5a994p-3 0x1.fa7793a392545p-1
                3 6 0x1.7757a34a0bb0ep-3 0x1.f753eb26b6904p-1
                1 7 0x1.8cca41e92b253p-5 0x1.ff66283ee8ae2p-1
                2 7 0x1.dc3f292edb24dp-4 0x1.fc870138a0280p-1
                3 7 0x1.cff7e567498c7p-4 0x1.fcb46658e3b93p-1
                1 8 0x1.c4e9635373407p-5 0x1.ff378678655bfp-1
                2 8 0x1.c370da4dce087p-4 0x1.fce17a72cb376p-1
                2 9 0x1.d309a99150a93p-4 0x1.fca92a177abc7p-1
            """,
            rect(3, 3, listOf(intArrayOf(1, 0, 2, 2))) to """
                1 0 -0x0.0p+0 -0x0.0p+0
                1 1 -0x0.0p+0 0x1.fffffdaaefdf7p-1
            """,
            rect(7, 1, listOf(intArrayOf(2, 0, 5, 1))) to """
                2 0 -0x1.ffffffecc0ab6p-1 -0x0.0p+0
                3 0 -0x0.0p+0 -0x0.0p+0
                4 0 0x1.ffffffecc0ab6p-1 -0x0.0p+0
            """,
        )
        for ((raw, text) in normals) {
            val rows = text.trim().lines().map { it.trim().split(" ") }
            assertEquals("${raw.w}×${raw.h} raw 像素數", rows.size, raw.count())
            for (r in rows) {
                val nrm = Ring.normal(raw, r[0].toInt(), r[1].toInt(), k)
                for (c in 0 until 2) {
                    val want = java.lang.Double.parseDouble(r[2 + c])
                    assertEquals(
                        "${raw.w}×${raw.h} (${r[0]},${r[1]}) 外法向第 $c 分量",
                        java.lang.Double.doubleToRawLongBits(want), java.lang.Double.doubleToRawLongBits(nrm[c]),
                    )
                }
            }
        }
        // 研究端 evidence：灰階（逐像素兩位十六進位）、人物原輸出 → 有輪廓邊界點、可以收細
        data class Tiny(val w: Int, val h: Int, val g: String, val raw: String, val conf: String, val ev: String)
        val pages = listOf(
            Tiny(8, 8,
                "ffffff00ffffffffffffff0014ffffffffffff0014ffffffffffff0014ffffffffffff0014ffffffffffff0014ffffffffffff0014ffffffffffff00ffffffff",
                "0000000000001111000011110000111100001111000011110000111100000000",
                "0000000000001111000010000000100000001000000010000000111100000000",
                "1111111111111111111110001111100011111000111110001111111111111111"),
            Tiny(12, 5,
                "ffffffffff00ffffffffffffffffffffff0014ffffffffffffffffffff0014ffffffffffffffffffff0014ffffffffffffffffffff00ffffffffffff",
                "000000000000000000111111000000111111000000111111000000000000",
                "000000000000000000111111000000100000000000111111000000000000",
                "111111111111111111111111111111100000111111111111111111111111"),
            Tiny(5, 12,
                "ffffffffffffffffffffffffffffffffffffffffffffffffff0000000000ff141414ffffffffffffffffffffffffffffffffffffffffffffffffffff",
                "000000000000000000000000000000011100111001110011100111001110",
                "000000000000000000000000000000011100101001010010100101001010",
                "111111111111111111111111111111111111101111011110111101111011"),
        )
        val p = NightReadParams()
        for (t in pages) {
            val g = Gray(t.w, t.h, IntArray(t.w * t.h) { t.g.substring(2 * it, 2 * it + 2).toInt(16) })
            val dbg = HashMap<String, Any>()
            val ev = Ring.evidence(g, mask(t.w, t.h, t.raw), p, dbg)
            assertEquals("${t.w}×${t.h} 有輪廓邊界點", t.conf, (dbg["ring_conf"] as Mask).data.joinToString("") { if (it) "1" else "0" })
            assertEquals("${t.w}×${t.h} 可以收細", t.ev, ev.data.joinToString("") { if (it) "1" else "0" })
        }
        // 整頁 render：小頁上有輪廓邊界點時不越界（頁內有一條直墨線與一塊人物）
        for ((w, h) in listOf(40 to 6, 6 to 40, 8 to 8, 12 to 5, 5 to 12, 3 to 3, 9 to 9)) {
            val gray = Gray(w, h, IntArray(w * h) { 255 })
            val ch = Mask(w, h)
            for (y in 0 until h) gray.data[y * w + minOf(2, w - 1)] = 0
            for (y in h / 4 until 3 * h / 4) for (x in minOf(3, w - 1) until w) ch.data[y * w + x] = true
            val input = NightReadInput(gray, Mask(w, h), emptyList(), ch, Gray(w, h))
            for (tier in listOf(NightTier.L2, NightTier.L3)) {
                val r = NightRead.render(input, tier.apply())
                assertEquals("${w}×$h ${tier.key} 成品大小", w * h, r.out.data.size)
            }
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
