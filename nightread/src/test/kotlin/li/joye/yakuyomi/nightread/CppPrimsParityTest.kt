package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.awt.image.BufferedImage
import java.util.SplittableRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * 第一批 C++ 影像原語（native/core/prims.cpp，[NightNativePrims]）對 Kotlin 版（[Cv]）逐位元比，同一個 JVM：
 *  - 邊界寬度 1、63、64、65、127、128、129（＋2、200、1351）× 各種高度、密度與形狀 × 各種核（橢圓、方核、長條、單邊、遠離錨點、有洞、隨機）；
 *  - 非 8 位元的灰階（Q16、負值、會溢位的值）、σ 從 0 到 10；0 寬／0 高（Kotlin 丟例外的輸入 C++ 也要丟）；
 *  - 真頁：全部 fixture 頁跑 [NightPrimWorkload]；並行（多條執行緒同時用同一份輸入）。
 * 需要主機原生庫（沒給 `-PnativeLib` 就略過）。
 */
class CppPrimsParityTest {

    private val edgeWidths = listOf(1, 2, 63, 64, 65, 127, 128, 129, 200, 1351)
    private val heights = listOf(1, 2, 3, 7, 64, 65)

    private fun masks(w: Int, h: Int, rnd: SplittableRandom): List<Pair<String, Mask>> {
        val n = w * h
        val out = ArrayList<Pair<String, Mask>>()
        out += "全空" to Mask(w, h)
        out += "全滿" to Mask(w, h, BooleanArray(n) { true })
        for (d in listOf(0.03, 0.3, 0.7)) out += "隨機 $d" to Mask(w, h, BooleanArray(n) { rnd.nextDouble() < d })
        out += "邊框" to Mask(w, h, BooleanArray(n) { val x = it % w; val y = it / w; x == 0 || y == 0 || x == w - 1 || y == h - 1 })
        out += "棋盤" to Mask(w, h, BooleanArray(n) { (it % w + it / w) % 2 == 0 })
        out += "右緣一欄" to Mask(w, h, BooleanArray(n) { it % w == w - 1 })
        out += "左緣一欄" to Mask(w, h, BooleanArray(n) { it % w == 0 })
        out += "細線" to Mask(w, h, BooleanArray(n) { val x = it % w; val y = it / w; x % 7 == 3 || (y % 11 == 5 && x % 3 != 0) })
        return out
    }

    private fun kernel(w: Int, h: Int, f: (Int, Int) -> Boolean) = Kernel(w, h, BooleanArray(w * h) { f(it % w, it / w) })

    private fun kernels(rnd: SplittableRandom): List<Pair<String, Kernel>> {
        val out = ArrayList<Pair<String, Kernel>>()
        for (s in listOf(1, 3, 5, 9, 11, 15, 41, 65)) out += "ellipse $s" to Cv.ellipse(s)
        for ((w, h) in listOf(3 to 1, 1 to 5, 7 to 3, 65 to 1, 129 to 3, 3 to 3)) out += "rect ${w}x$h" to Cv.rect(w, h)
        // 整段在錨點同一側（shiftInto 的兩個方向），含跨過一個字（q ≥ 1）
        out += "右側單邊" to kernel(9, 3) { x, _ -> x >= 6 }
        out += "左側單邊" to kernel(9, 3) { x, _ -> x <= 2 }
        out += "遠右" to kernel(141, 1) { x, _ -> x >= 135 }
        out += "遠左" to kernel(141, 1) { x, _ -> x <= 5 }
        out += "遠右兩列" to kernel(201, 2) { x, y -> if (y == 0) x in 165..170 else x in 180..181 }
        out += "空核" to kernel(5, 5) { _, _ -> false }
        out += "只有中間一列" to kernel(5, 5) { _, y -> y == 2 }
        out += "偶數尺寸" to kernel(4, 6) { x, y -> (x + y) % 3 != 0 }
        out += "上下同寬異位" to kernel(7, 5) { x, y -> if (y == 0) x <= 2 else if (y == 4) x >= 4 else y == 2 }
        // 列的 run 不對稱、中間有空列（morphGrayGather 的 partner 判斷）
        out += "不對稱" to kernel(7, 5) { x, y -> if (y == 0) x in 0..2 else if (y == 4) x in 3..6 else y == 2 }
        repeat(4) {
            val kw = 1 + rnd.nextInt(15)
            val kh = 1 + rnd.nextInt(15)
            out += "隨機 ${kw}x$kh" to Kernel(kw, kh, BooleanArray(kw * kh) { rnd.nextInt(3) == 0 })
        }
        return out
    }

    private fun check(what: String, a: Any, b: Any) {
        if (!NightPrimWorkload.same(a, b)) fail("$what：C++ 與 Kotlin 不同")
    }

    private fun checkMaskPrims(tag: String, m: Mask, ks: List<Pair<String, Kernel>>): Int {
        var n = 0
        for ((kd, k) in ks) {
            check("dilatePacked $tag / $kd", Cv.dilatePacked(m, k), NightNativePrims.dilatePacked(m, k).value)
            val bits = Cv.packBits(m)
            val want = Cv.dilateBits(bits, m.w, m.h, k)
            if (!want.contentEquals(NightNativePrims.dilateBits(bits, m.w, m.h, k).value)) fail("dilateBits $tag / $kd：不同")
            n += 2
        }
        for (conn in listOf(8, 4)) {
            check("ccStats $tag / $conn", Cv.ccStats(m, conn), NightNativePrims.ccStats(m, conn).value)
            check("ccRuns $tag / $conn", NightNativePrims.ccRunsKotlin(m, conn), NightNativePrims.ccRuns(m, conn).value)
            n += 2
        }
        check("distanceL2 $tag", Cv.distanceL2(m), NightNativePrims.distanceL2(m).value)
        return n + 1
    }

    private fun checkGrayPrims(tag: String, g: Gray, ks: List<Pair<String, Kernel>>, sigmas: List<Double>): Int {
        var n = 0
        for (s in sigmas) {
            check("gaussQ16 $tag / σ$s", Cv.gaussQ16(g, s), NightNativePrims.gaussQ16(g, s).value)
            n++
        }
        for ((kd, k) in ks) for (mx in listOf(true, false)) {
            check("morphGrayGather $tag / $kd / $mx", Cv.morphGrayGather(g, k, mx), NightNativePrims.morphGrayGather(g, k, mx).value)
            n++
        }
        return n
    }

    @Test
    fun edgeWidthsAndKernels() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(20261102L)
        val ks = kernels(rnd)
        var n = 0
        for (w in edgeWidths) for (h in heights) {
            for ((d, m) in masks(w, h, rnd)) n += checkMaskPrims("${w}x$h $d", m, ks)
        }
        println("CppPrimsParityTest 二值：$n 組相同")
        assertTrue(n > 10000)
    }

    @Test
    fun grayEdgeWidthsAndValueRanges() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(99L)
        val ks = kernels(rnd)
        val sigmas = listOf(0.0, 0.3, 0.7, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0)
        var n = 0
        for (w in edgeWidths) for (h in heights) {
            val c = w * h
            val gs = listOf(
                "隨機" to Gray(w, h, IntArray(c) { rnd.nextInt(256) }),
                "0" to Gray(w, h),
                "255" to Gray(w, h, IntArray(c) { 255 }),
                "椒鹽" to Gray(w, h, IntArray(c) { if (rnd.nextInt(5) == 0) (if (rnd.nextBoolean()) 0 else 255) else 200 }),
                "Q16" to Gray(w, h, IntArray(c) { rnd.nextInt(256 * 65536) }),
                "負值" to Gray(w, h, IntArray(c) { rnd.nextInt(-65536, 65536) }),
                "任意 Int（高斯會溢位環繞）" to Gray(w, h, IntArray(c) { rnd.nextInt() }),
                "極值" to Gray(w, h, IntArray(c) { if (rnd.nextBoolean()) Int.MIN_VALUE else Int.MAX_VALUE }),
            )
            for ((d, g) in gs) n += checkGrayPrims("${w}x$h $d", g, ks, sigmas)
        }
        println("CppPrimsParityTest 灰階：$n 組相同")
        assertTrue(n > 10000)
    }

    /** 距離平方超過 2²⁴ 的細長條：Float 開始捨入，運算順序、相等時選哪條拋物線才會影響結果。 */
    @Test
    fun distanceWhereFloatRounds() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(4242L)
        var n = 0
        for ((w, h) in listOf(20011 to 2, 2 to 20011, 9001 to 3, 3 to 9001, 40001 to 1, 1 to 40001, 6000 to 100, 100 to 6000, 3000 to 3000)) for (per in listOf(50000, 5000, 1000, 200, 12)) {
            val m = Mask(w, h, BooleanArray(w * h) { rnd.nextInt(per) != 0 })
            check("distanceL2 ${w}x$h 1/$per", Cv.distanceL2(m), NightNativePrims.distanceL2(m).value)
            n++
        }
        println("CppPrimsParityTest 細長距離：$n 組相同")
    }

    /** 0 寬／0 高：Kotlin 算得出來就要同值，Kotlin 丟例外 C++ 也要丟（gaussQ16 的 0 寬 Kotlin 不會結束，只測 C++ 會丟）。 */
    @Test
    fun emptySizes() {
        NativeLib.assumeLoaded()
        val ks = kernels(SplittableRandom(5L))
        for ((w, h) in listOf(0 to 0, 0 to 5, 5 to 0)) {
            val m = Mask(w, h)
            val g = Gray(w, h)
            for ((kd, k) in ks) {
                val want = runCatching { Cv.dilatePacked(m, k) }
                val got = runCatching { NightNativePrims.dilatePacked(m, k).value }
                assertEquals("dilatePacked ${w}x$h / $kd：丟不丟例外", want.isFailure, got.isFailure)
                if (want.isSuccess) check("dilatePacked ${w}x$h / $kd", want.getOrThrow(), got.getOrThrow())
                for (mx in listOf(true, false)) {
                    check("morph ${w}x$h / $kd", Cv.morphGrayGather(g, k, mx), NightNativePrims.morphGrayGather(g, k, mx).value)
                }
            }
            for (conn in listOf(8, 4)) {
                check("ccStats ${w}x$h", Cv.ccStats(m, conn), NightNativePrims.ccStats(m, conn).value)
                check("ccRuns ${w}x$h", NightNativePrims.ccRunsKotlin(m, conn), NightNativePrims.ccRuns(m, conn).value)
            }
            check("distanceL2 ${w}x$h", Cv.distanceL2(m), NightNativePrims.distanceL2(m).value)
            if (w > 0) check("gaussQ16 ${w}x$h", Cv.gaussQ16(g, 2.0), NightNativePrims.gaussQ16(g, 2.0).value)
            else expect<IllegalStateException> { NightNativePrims.gaussQ16(g, 2.0) }
        }
    }

    private fun res(n: String) = javaClass.classLoader!!.getResource("page/$n")

    private fun readGray(name: String): Gray {
        val img: BufferedImage = res(name)!!.openStream().use { ImageIO.read(it) }
        val g = Gray(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) g.data[y * img.width + x] = img.raster.getSample(x, y, 0)
        return g
    }

    private fun readMask(name: String) = readGray(name).let { g -> Mask(g.w, g.h, BooleanArray(g.data.size) { g.data[it] > 127 }) }

    /** 全部 fixture 頁跑一遍步驟表；印每族的倍數（桌面 JVM 的時間只是參考，手機要看探針）。 */
    @Test
    fun realPages() {
        NativeLib.assumeLoaded()
        val pages = listOf("ch34_010", "ch34_011", "ch34_015", "demo01", "demo02", "demo04", "demo05", "demo06", "syn_more", "syn_v4")
        val all = ArrayList<NightPrimWorkload.StepResult>()
        for (p in pages) {
            val r = NightPrimWorkload.differential(readGray("${p}_gray.png"), readMask("${p}_seg.png"), readMask("${p}_char.png"), repeat = 3)
            val bad = r.filter { !it.identical }.map { it.step.id }
            assertEquals("$p：這些步驟 C++ 與 Kotlin 不同", emptyList<String>(), bad)
            all += r
        }
        println("CppPrimsParityTest 真頁：${pages.size} 頁、${all.size} 步全部相同（步驟表第 ${NightPrimWorkload.VERSION} 版）")
        for (s in NightPrimWorkload.summarize(all)) {
            println("  %-8s Kotlin %8.1f ms  C++ %8.1f ms  倍數 %.2f".format(s.family?.name ?: "合計", s.kotlinWeightedNs / 1e6, s.nativeWeightedNs / 1e6, s.ratio))
        }
        val steps = NightPrimWorkload.program(1351, 1920)
        assertEquals("每族都要有計入權重的步驟", NightPrimWorkload.Family.values().toSet(), steps.filter { it.weight > 0 }.mapNotNull { it.family }.toSet())
    }

    /** 多條執行緒同時用同一份輸入：結果都要跟依序版相同（C++ 沒有全域狀態）。 */
    @Test
    fun concurrentCalls() {
        NativeLib.assumeLoaded()
        val g = readGray("demo05_gray.png")
        val seg = readMask("demo05_seg.png")
        val chr = readMask("demo05_char.png")
        val want = NightPrimWorkload.differential(g, seg, chr, repeat = 1)
        assertTrue(want.all { it.identical })
        val pool = Executors.newFixedThreadPool(6)
        val fs = List(6) { pool.submit<List<NightPrimWorkload.StepResult>> { NightPrimWorkload.differential(g, seg, chr, repeat = 1) } }
        for (f in fs) assertTrue(f.get().all { it.identical })
        pool.shutdown()
        assertTrue(pool.awaitTermination(1, TimeUnit.MINUTES))
    }

    @Test
    fun statsAndErrors() {
        NativeLib.assumeLoaded()
        val m = Mask(129, 7, BooleanArray(129 * 7) { it % 3 == 0 })
        val t = NightNativePrims.dilatePacked(m, Cv.ellipse(5))
        assertTrue("耗時要有值", t.nanos > 0)
        assertTrue("峰值至少有打包與輸出：${t.peakBytes}", t.peakBytes >= 129 * 7)
        val g = Gray(129, 7)
        expect<IllegalArgumentException> { NightNativePrims.nDilatePacked(BooleanArray(5), 129, 7, Cv.ellipse(3).data, 3, 3, null) }
        expect<IllegalArgumentException> { NightNativePrims.nDilatePacked(m.data, 129, 7, BooleanArray(2), 3, 3, null) }
        expect<IllegalArgumentException> { NightNativePrims.nDilatePacked(m.data, -129, -7, Cv.ellipse(3).data, 3, 3, null) }
        expect<IllegalArgumentException> { NightNativePrims.nDilateBits(LongArray(5), 129, 7, Cv.ellipse(3).data, 3, 3, null) }
        expect<IllegalArgumentException> { NightNativePrims.nCcStats(BooleanArray(5), 129, 7, 8, null) }
        expect<IllegalArgumentException> { NightNativePrims.nCcRuns(BooleanArray(5), 129, 7, 8, null) }
        expect<IllegalArgumentException> { NightNativePrims.nDistanceL2(BooleanArray(5), 129, 7, null) }
        expect<IllegalArgumentException> { NightNativePrims.nGaussQ16(IntArray(5), 129, 7, 2.0, null) }
        expect<IllegalArgumentException> { NightNativePrims.nMorphGrayGather(g.data, 129, 7, BooleanArray(2), 3, 3, true, null) }
        expect<IllegalArgumentException> { NightNativePrims.nDistanceL2(m.data, 129, 7, LongArray(1)) }
        // stats 可以是 null
        check("stats null", Cv.distanceL2(m), FImg(129, 7, NightNativePrims.nDistanceL2(m.data, 129, 7, null)))
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            fail("預期 ${T::class.java.simpleName}，卻是 $e")
        }
        fail("預期 ${T::class.java.simpleName}，卻沒有丟")
    }
}
