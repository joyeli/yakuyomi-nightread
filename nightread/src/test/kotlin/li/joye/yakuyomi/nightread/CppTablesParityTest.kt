package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 夜讀 C++ 常數表（native/core/tables）對 Kotlin 版的整表逐位元比：
 *  - Cv.gaussW、Cv.gaussianKernel（private，用反射呼叫；核長照 Cv.gaussianBlur 的寫法）、Ring.gaussKernel：σ＝0.30..10.00（步長 0.01，971 個）；
 *  - BgObjects.textLut：bg、ink 0..255 全部（65,536 張）；BgObjects.inkLut：bg、top 0..255 × inv（131,072 張）；
 *  - BgObjects.lineOffsets：len 0..100（偶數 len 兩邊都要判定 Kotlin 會丟例外）。
 * float／double 比原始位元。需要主機原生庫（沒給 `-PnativeLib` 就略過）；`-PgoldenOut` 時寫 golden（每族一個雜湊）。
 */
class CppTablesParityTest {

    private val sigmas = (30..1000).map { it / 100.0 }

    private val gaussianKernel = Cv::class.java.getDeclaredMethod("gaussianKernel", Int::class.javaPrimitiveType, Double::class.javaPrimitiveType)
        .apply { isAccessible = true }

    /** Cv.gaussianBlur 裡算核長的那兩行（照抄）。 */
    private fun blurKsize(sigma: Double): Int {
        var ks = (sigma * 4.0 * 2.0 + 1.0).roundToInt() or 1
        if (ks < 3) ks = 3
        return ks
    }

    private fun bits(a: DoubleArray) = a.map { it.toRawBits() }
    private fun bits(a: FloatArray) = a.map { it.toRawBits() }

    @Test
    fun gaussianKernelsMatchWholeTable() {
        NativeLib.assumeLoaded()
        var hW = NativeGolden.FNV_INIT
        var hK = NativeGolden.FNV_INIT
        var hR = NativeGolden.FNV_INIT
        var bad = 0
        for (s in sigmas) {
            val w = Cv.gaussW(s)
            if (!w.contentEquals(NativeTestBridge.gaussW(s))) { if (bad++ < 5) println("  gaussW σ=$s 不同") }
            hW = NativeGolden.fnv(hW, w.size.toLong())
            for (v in w) hW = NativeGolden.fnv(hW, v.toLong())

            val ks = blurKsize(s)
            if (ks != NativeTestBridge.gaussianBlurKsize(s)) { if (bad++ < 5) println("  核長 σ=$s 不同") }
            val k = gaussianKernel.invoke(Cv, ks, s) as DoubleArray
            if (bits(k) != bits(NativeTestBridge.gaussianKernel(ks, s))) { if (bad++ < 5) println("  gaussianKernel σ=$s 不同") }
            hK = NativeGolden.fnv(hK, ks.toLong())
            for (v in k) hK = NativeGolden.fnv(hK, v.toRawBits())

            val r = Ring.gaussKernel(s)
            if (bits(r) != bits(NativeTestBridge.ringGaussKernel(s))) { if (bad++ < 5) println("  Ring.gaussKernel σ=$s 不同") }
            hR = NativeGolden.fnv(hR, r.size.toLong())
            for (v in r) hR = NativeGolden.fnv(hR, v.toRawBits())
        }
        println("CppTablesParityTest 高斯核：${sigmas.size} 個 σ × 3 族，不同 $bad")
        NativeGolden.open("tables_gauss")?.use { g ->
            g.println("T gaussW ${NativeGolden.hex(hW)}")
            g.println("T gk ${NativeGolden.hex(hK)}")
            g.println("T ringk ${NativeGolden.hex(hR)}")
        }
        assertEquals(0, bad)
    }

    @Test
    fun lutsMatchWholeTable() {
        NativeLib.assumeLoaded()
        var hT = NativeGolden.FNV_INIT
        var bad = 0
        var n = 0
        for (bg in 0..255) for (ink in 0..255) {
            val k = BgObjects.textLut(bg, ink)
            if (bits(k) != bits(NativeTestBridge.textLut(bg, ink))) { if (bad++ < 5) println("  textLut($bg, $ink) 不同") }
            for (v in k) hT = NativeGolden.fnv(hT, v.toRawBits().toLong() and 0xffffffffL)
            n++
        }
        var hI = NativeGolden.FNV_INIT
        for (inv in listOf(false, true)) for (bg in 0..255) for (top in 0..255) {
            val k = BgObjects.inkLut(bg, top, inv)
            if (bits(k) != bits(NativeTestBridge.inkLut(bg, top, inv))) { if (bad++ < 5) println("  inkLut($bg, $top, $inv) 不同") }
            for (v in k) hI = NativeGolden.fnv(hI, v.toRawBits().toLong() and 0xffffffffL)
            n++
        }
        println("CppTablesParityTest 查表：$n 張 × 256 項，不同 $bad")
        NativeGolden.open("tables_lut")?.use { g ->
            g.println("T text ${NativeGolden.hex(hT)}")
            g.println("T ink ${NativeGolden.hex(hI)}")
        }
        assertEquals(0, bad)
    }

    @Test
    fun lineOffsetsMatch() {
        NativeLib.assumeLoaded()
        var h = NativeGolden.FNV_INIT
        var bad = 0
        var thrown = 0
        for (len in 0..100) {
            val want: List<IntArray>? = try {
                BgObjects.lineOffsets(len)
            } catch (e: IndexOutOfBoundsException) {
                null
            } catch (e: NegativeArraySizeException) {
                null
            }
            val got = NativeTestBridge.lineOffsets(len)
            val same = if (want == null) got == null else got != null && got.size == want.size && want.indices.all { want[it].contentEquals(got[it]) }
            if (!same) { if (bad++ < 5) println("  lineOffsets($len) 不同（kotlin ${if (want == null) "丟例外" else "${want.size} 方向"}）") }
            if (want == null) {
                thrown++
                h = NativeGolden.fnv(h, -1L)
            } else {
                for (dir in want) {
                    h = NativeGolden.fnv(h, dir.size.toLong())
                    for (v in dir) h = NativeGolden.fnv(h, v.toLong())
                }
            }
        }
        println("CppTablesParityTest lineOffsets：len 0..100（Kotlin 丟例外 $thrown 個），不同 $bad")
        NativeGolden.open("tables_lineoff")?.use { it.println("T lineoff ${NativeGolden.hex(h)}") }
        assertEquals(0, bad)
    }
}
