package li.joye.yakuyomi.nightread

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * 夜讀 C++ 核心的頁 handle（[NightNative]）、像素轉換與型別，對 Kotlin 版逐位元比。
 *  - 灰階／彩度（ARGB → BT.601）：1351×1920＝2,593,920 個像素經 [NightNative.putRows] 分條放進去、[NightNative.getRows] 取回，
 *    對照引擎 NightReadRenderer.toInput 那段迴圈的複本（[kotlinGrayChroma]）；灰 ARGB 輸出對照 writeArgb 的複本。另外把 2²⁴ 種 RGB
 *    （隨機 alpha）整個掃一遍。
 *  - 型別：Cv.packBits／unpackBits 的列打包（寬 63／64／65／127／128／129…）、Kernel 的 run 表（含 Cv.ellipse 的核）。
 *  - [NightNative] 的握手、錯誤（參數不合法、取未放的列）、峰值記憶體、取消。
 * 需要主機原生庫（沒給 `-PnativeLib` 就略過）。
 */
class CppPageParityTest {

    /**
     * 引擎 nightread-android 的 NightReadRenderer.toInput 灰階／彩度迴圈的逐字複本（engine f990b45，
     * li/joye/yakuyomi/engine/NightReadRenderer.kt）。函式庫不依賴引擎，所以複製過來；引擎那段若改了，這裡要跟著改。
     */
    private fun kotlinGrayChroma(px: IntArray): Pair<IntArray, IntArray> {
        val gray = IntArray(px.size)
        val chroma = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // BT.601，與 cv2.imread(IMREAD_GRAYSCALE) 同式（契約第 1 條）
            gray[i] = (r * 299 + g * 587 + b * 114 + 500) / 1000
            chroma[i] = maxOf(r, g, b) - minOf(r, g, b)
        }
        return gray to chroma
    }

    /** NightReadRenderer.writeArgb 的複本。 */
    private fun kotlinWriteArgb(d: IntArray): IntArray = IntArray(d.size) {
        val v = d[it].coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test
    fun handshake() {
        NativeLib.assumeLoaded()
        assertTrue("載入主機原生庫後 NightNative.available() 要是 true", NightNative.available())
        assertEquals(NightNative.expectedAbi(), NightNative.abi())
        assertEquals((1 shl 16) or NightRead.RULES_VERSION, NightNative.abi())
    }

    @Test
    fun grayChromaMatchesKotlinOnAFullPage() {
        NativeLib.assumeLoaded()
        val w = 1351
        val h = 1920
        val n = w * h
        val seed = 20261012L
        val px = NativeTestBridge.genPixels(n, seed)
        val (wantG, wantC) = kotlinGrayChroma(px)
        val wantA = kotlinWriteArgb(wantG)

        val page = NightNative.create(w, h)
        try {
            var y = 0
            while (y < h) {                                   // 64 列一條放進去
                val rows = minOf(64, h - y)
                NightNative.putRows(page, y, rows, px, y * w, w)
                y += rows
            }
            val g = IntArray(n)
            val c = IntArray(n)
            val a = IntArray(n)
            y = 0
            while (y < h) {                                   // 用不同的條高取出來
                val rows = minOf(100, h - y)
                NightNative.getRows(page, NightNative.PLANE_GRAY, y, rows, g, y * w, w)
                NightNative.getRows(page, NightNative.PLANE_CHROMA, y, rows, c, y * w, w)
                NightNative.getRows(page, NightNative.PLANE_GRAY_ARGB, y, rows, a, y * w, w)
                y += rows
            }
            val diffG = (0 until n).count { g[it] != wantG[it] }
            val diffC = (0 until n).count { c[it] != wantC[it] }
            val diffA = (0 until n).count { a[it] != wantA[it] }
            println("CppPageParityTest：$n 個像素（${w}×$h），灰階不同 $diffG、彩度不同 $diffC、灰 ARGB 不同 $diffA；原生峰值 ${NightNative.peakBytes(page)} B")
            assertEquals(0, diffG)
            assertEquals(0, diffC)
            assertEquals(0, diffA)
            assertEquals(w.toLong() * h * 4 * 2 + h, NightNative.peakBytes(page))
        } finally {
            NightNative.free(page)
        }

        NativeGolden.open("page_pixels")?.use { gw ->
            var hh = NativeGolden.FNV_INIT
            for (i in 0 until n) { hh = NativeGolden.fnv(hh, wantG[i].toLong()); hh = NativeGolden.fnv(hh, wantC[i].toLong()) }
            for (i in 0 until n) hh = NativeGolden.fnv(hh, wantA[i].toLong())
            gw.println("P $n $seed ${NativeGolden.hex(hh)}")
        }
    }

    /** 帶偏移與跨距的放入（呼叫端的緩衝比頁寬寬時）。 */
    @Test
    fun putRowsHonoursOffsetAndStride() {
        NativeLib.assumeLoaded()
        val w = 129
        val h = 37
        val stride = w + 7
        val off = 3
        val rnd = Random(7)
        val buf = IntArray(off + stride * h) { rnd.nextInt() }
        val page = NightNative.create(w, h)
        try {
            NightNative.putRows(page, 0, h, buf, off, stride)
            val got = IntArray(off + stride * h) { -12345 }
            NightNative.getRows(page, NightNative.PLANE_GRAY, 0, h, got, off, stride)
            val px = IntArray(w * h) { buf[off + (it / w) * stride + it % w] }
            val (wantG, _) = kotlinGrayChroma(px)
            for (i in 0 until w * h) assertEquals(wantG[i], got[off + (i / w) * stride + i % w])
            for (y in 0 until h) for (x in w until stride) if (off + y * stride + x < got.size) assertEquals(-12345, got[off + y * stride + x])
        } finally {
            NightNative.free(page)
        }
    }

    @Test
    fun exhaustiveRgbMatches() {
        NativeLib.assumeLoaded()
        val rnd = Random(1)
        val block = 1 shl 20
        var diffs = 0
        var base = 0
        while (base < (1 shl 24)) {
            val px = IntArray(block) { ((rnd.nextInt() and 0xFF) shl 24) or (base + it) }
            val (wg, wc) = kotlinGrayChroma(px)
            val g = IntArray(block)
            val c = IntArray(block)
            NativeTestBridge.argbToGrayChroma(px, g, c)
            for (i in 0 until block) if (g[i] != wg[i] || c[i] != wc[i]) diffs++
            val v = IntArray(block) { rnd.nextInt(600) - 150 }    // 超出 0..255 的值要夾住
            if (!kotlinWriteArgb(v).contentEquals(NativeTestBridge.grayToArgb(v))) diffs++
            base += block
        }
        println("CppPageParityTest：2²⁴ 種 RGB 全掃，不同 $diffs")
        assertEquals(0, diffs)
    }

    @Test
    fun errorsAndLifecycle() {
        NativeLib.assumeLoaded()
        expect<IllegalArgumentException> { NightNative.create(0, 5) }
        expect<IllegalArgumentException> { NightNative.create(65536, 65536) }
        val page = NightNative.create(10, 4)
        try {
            val buf = IntArray(40)
            expect<IllegalStateException> { NightNative.getRows(page, NightNative.PLANE_GRAY, 0, 1, buf, 0, 10) }
            expect<IllegalArgumentException> { NightNative.putRows(page, 0, 4, IntArray(39), 0, 10) }     // 陣列太短
            expect<IllegalArgumentException> { NightNative.putRows(page, 3, 2, buf, 0, 10) }               // 超出頁高
            expect<IllegalArgumentException> { NightNative.putRows(page, 0, 1, buf, 0, 9) }                // 跨距小於頁寬
            expect<IllegalArgumentException> { NightNative.putRows(page, 0, 1, buf, -1, 10) }
            expect<IllegalArgumentException> { NightNative.putRows(0L, 0, 1, buf, 0, 10) }
            NightNative.putRows(page, 0, 4, buf, 0, 10)
            expect<IllegalArgumentException> { NightNative.getRows(page, 9, 0, 1, buf, 0, 10) }            // 沒有這個平面
            NightNative.cancel(page)
            NightNative.getRows(page, NightNative.PLANE_GRAY, 0, 4, buf, 0, 10)                           // 骨架階段取消不擋取像素
        } finally {
            NightNative.free(page)
        }
        NightNative.free(0L)
    }

    /** C++ 例外不穿過 JNI：std::bad_alloc → OutOfMemoryError（沿用每頁 OOM 的補跑路徑），其他 → IllegalStateException。 */
    @Test
    fun nativeExceptionsBecomeJavaExceptions() {
        NativeLib.assumeLoaded()
        expect<OutOfMemoryError> { NativeTestBridge.throwCpp(0) }
        expect<IllegalStateException> { NativeTestBridge.throwCpp(1) }
        expect<IllegalStateException> { NativeTestBridge.throwCpp(2) }
        NativeTestBridge.throwForCode(0)
        expect<IllegalArgumentException> { NativeTestBridge.throwForCode(-1) }
        expect<OutOfMemoryError> { NativeTestBridge.throwForCode(-2) }
        expect<IllegalStateException> { NativeTestBridge.throwForCode(-3) }
        expect<java.util.concurrent.CancellationException> { NativeTestBridge.throwForCode(-4) }
        expect<IllegalStateException> { NativeTestBridge.throwForCode(-5) }
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

    @Test
    fun packedBitsAndKernelsMatchKotlin() {
        NativeLib.assumeLoaded()
        val rnd = Random(42)
        var n = 0
        for (w in listOf(1, 2, 63, 64, 65, 127, 128, 129, 200, 1351)) for (h in listOf(1, 2, 7)) {
            for (density in listOf(0.0, 0.03, 0.5, 0.97, 1.0)) {
                val m = Mask(w, h, BooleanArray(w * h) { rnd.nextDouble() < density })
                val want = Cv.packBits(m)
                val got = NativeTestBridge.packBits(m.data, w, h)
                assertArrayEquals("packBits ${w}×$h", want, got)
                val words = LongArray(want.size) { rnd.nextLong() }
                for (y in 0 until h) {                          // 末字超出寬度的位元清掉（unpackBits 的前提）
                    val nw = (w + 63) ushr 6
                    if (w and 63 != 0) words[y * nw + nw - 1] = words[y * nw + nw - 1] and ((1L shl (w and 63)) - 1)
                }
                assertArrayEquals("unpackBits ${w}×$h", Cv.unpackBits(words, w, h).data, NativeTestBridge.unpackBits(words, w, h))
                n++
            }
        }
        val kernels = ArrayList<Kernel>()
        for (s in 1..41 step 2) kernels.add(Cv.ellipse(s))
        repeat(300) {
            val kw = 1 + rnd.nextInt(15)
            val kh = 1 + rnd.nextInt(15)
            kernels.add(Kernel(kw, kh, BooleanArray(kw * kh) { rnd.nextInt(3) == 0 }))
        }
        for (k in kernels) {
            val got = NativeTestBridge.kernelRuns(k.data, k.w, k.h)
            val want = intArrayOf(k.ax, k.ay) + k.runStart + k.runEnd
            assertArrayEquals("Kernel ${k.w}×${k.h}", want, got)
        }
        println("CppPageParityTest 型別：列打包 $n 組、核 ${kernels.size} 個，全部相同")
    }
}
