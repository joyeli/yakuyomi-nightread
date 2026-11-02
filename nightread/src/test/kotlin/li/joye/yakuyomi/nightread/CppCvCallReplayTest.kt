package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 錄放（同 JVM 版）：Kotlin 跑真頁的產品路徑時，[Cv.tap] 回報的**每一次**原語呼叫（已搬到 C++ 的：第 2 階＋第 3a 階），當場用 C++ 拿
 * 同一份輸入重算、逐位元比（[CvCallReplay]）。
 *  - [ciCorpus]：CI 語料的 10 頁（公開 fixture 與合成頁），產品兩檔（[NightRead.renderTiers] 的 L2＋L3，預設亮度與 fork 預設亮度）
 *    加研究端完整管線（render，標準參數）。每一頁都要真的比到東西，每個第 3a 階管線會用到的運算都要比到。
 *  - [kotlinRandomTests]：本來對照參考實作的 Kotlin 隨機測試（MorphPackedTest、CcStatsRunTest、SparsePrimitivesTest、PfShapeHolesTest）
 *    在監聽下照跑一遍：它們每一次呼叫 Cv 的原語也都拿去給 C++ 重算。
 * 錄到磁碟、給 qemu-aarch64 與 sanitizer 重放的版本在 NativeDumpTest 的 calls/。需要主機原生庫（沒給 `-PnativeLib` 就略過）。
 */
class CppCvCallReplayTest {

    private val pages = listOf("ch34_010", "ch34_011", "ch34_015", "demo01", "demo02", "demo04", "demo05", "demo06", "syn_more", "syn_v4")

    /** 產品管線在 CI 語料上一定會呼叫到的運算（少了＝監聽漏接或管線改了，要查）。 */
    private val mustSee = listOf(
        "dilate", "erode", "open", "close", "dilatePacked", "erodePacked", "closePacked", "openPacked", "packBits", "packBitsNot",
        "unpackBits", "unpackBitsNot", "notBitsInPlace", "erodeBits", "dilateBits", "dilateRectSep", "closeRectSep", "ccStats", "ccRuns",
        "ccRunsBits", "distanceL2", "distanceSq", "distanceChamfer", "chamfer5Padded", "resizeArea", "resizeAreaOf", "resizeNearest",
        "holes", "totalContourLength", "geodesicDistance", "geodesicGrow", "medianBlurMask", "gaussQ16", "morphGrayGather", "ring.packBits",
    )

    private fun res(n: String) = javaClass.classLoader!!.getResource("page/$n")

    private fun readGray(name: String): Gray {
        val img: BufferedImage = res(name)!!.openStream().use { ImageIO.read(it) }
        val g = Gray(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) g.data[y * img.width + x] = img.raster.getSample(x, y, 0)
        return g
    }

    private fun readMask(name: String) = readGray(name).let { g -> Mask(g.w, g.h, BooleanArray(g.data.size) { g.data[it] > 127 }) }

    private fun input(p: String) = NightReadInput(
        gray = readGray("${p}_gray.png"),
        seg = readMask("${p}_seg.png"),
        regions = res("${p}_regions.txt")!!.readText().lineSequence().filter { it.isNotBlank() }.map {
            val v = it.trim().split(" ").map(String::toInt)
            TextRegion(v[0], v[1], v[2], v[3])
        }.toList(),
        charMask = readMask("${p}_char.png"),
        chroma = readGray("${p}_chroma.png"),
        inpaintMask = if (res("${p}_inpaint.png") != null) readMask("${p}_inpaint.png") else null,
    )

    @Test
    fun ciCorpus() {
        NativeLib.assumeLoaded()
        val all = CvCallReplay.Stats()
        val t0 = System.nanoTime()
        for (p in pages) {
            val inp = input(p)
            val st = CvCallReplay.Stats()
            CvCallReplay.run(st, p) {
                for (base in listOf(NightReadParams(), NightReadParams(ink = 205, edgeInk = 170, strokeObjV = 190))) {
                    NightRead.renderTiers(inp, listOf(NightTier.L2.apply(base), NightTier.L3.apply(base)), null) { _, _ -> }
                }
                NightRead.render(inp, NightReadParams())
            }
            if (st.failed > 0) fail("$p：${st.failed} 次呼叫 C++ 與 Kotlin 不同，例如\n" + st.failures.take(10).joinToString("\n"))
            assertTrue("$p：一次呼叫都沒比到（監聽沒裝上？）", st.total > 0)
            println("CppCvCallReplayTest $p ${inp.gray.w}x${inp.gray.h}：${st.total} 次呼叫相同（${st.summary()}）")
            for ((op, c) in st.counts) all.counts.computeIfAbsent(op) { java.util.concurrent.atomic.AtomicLong() }.addAndGet(c.get())
        }
        println("CppCvCallReplayTest CI 語料 ${pages.size} 頁：共 ${all.total} 次呼叫相同、${"%.1f".format((System.nanoTime() - t0) / 1e9)} s")
        val missing = mustSee.filter { (all.counts[it]?.get() ?: 0L) == 0L }
        assertEquals("這些運算在 CI 語料上一次都沒比到", emptyList<String>(), missing)
    }

    /** 本來對照參考實作的 Kotlin 隨機測試：在監聽下照跑，每一次 Cv 呼叫都給 C++ 重算。 */
    @Test
    fun kotlinRandomTests() {
        NativeLib.assumeLoaded()
        val st = CvCallReplay.Stats()
        val ran = ArrayList<String>()
        CvCallReplay.run(st, "random") {
            for (cls in listOf(MorphPackedTest::class.java, CcStatsRunTest::class.java, SparsePrimitivesTest::class.java, PfShapeHolesTest::class.java)) {
                val obj = cls.getDeclaredConstructor().newInstance()
                for (m in cls.methods.filter { it.isAnnotationPresent(Test::class.java) }.sortedBy { it.name }) {
                    m.invoke(obj)
                    ran += "${cls.simpleName}.${m.name}"
                }
            }
        }
        if (st.failed > 0) fail("Kotlin 隨機測試：${st.failed} 次呼叫 C++ 與 Kotlin 不同，例如\n" + st.failures.take(10).joinToString("\n"))
        println("CppCvCallReplayTest Kotlin 隨機測試 ${ran.size} 個：${st.total} 次呼叫相同（${st.summary()}）")
        assertTrue("四個測試類別都要有跑到測試方法：$ran", ran.map { it.substringBefore('.') }.toSet().size == 4)
        for (op in listOf("dilatePacked", "erodePacked", "dilate", "erode", "open", "close", "dilateRectSep", "closeRectSep", "geodesicGrow", "ccStats", "ccRunsBits")) {
            assertTrue("$op 要真的比到", (st.counts[op]?.get() ?: 0L) > 0)
        }
    }
}
