package li.joye.yakuyomi.nightread

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 位元打包形態學對參考實作逐像素相同。
 *
 * 2026-10-06 起 [Cv.dilate]／[Cv.erode] 的一般核（不是 1×n、n×1 實心線核）改走 [Cv.dilatePacked]／[Cv.erodePacked]；
 * 原本的逐列前綴計數版（[dilateRef]／[erodeRef]，下面照抄當時 Cv.kt 的程式）搬到這裡當參考。取樣規則兩邊相同：
 * 輸出 (x, y) 看輸入 (x + [runStart−ax .. runEnd−1−ax], y − (ky−ay))，膨脹影像外當 0、侵蝕影像外當前景。
 * 核含偶數尺寸與每列任意 run（不對稱）——侵蝕的補集膨脹對不對稱核也成立，不必限定點對稱。
 */
class MorphPackedTest {

    private fun dilateRef(m: Mask, k: Kernel): Mask {
        val w = m.w
        val h = m.h
        val out = Mask(w, h)
        val prefix = IntArray(w + 1)
        for (ky in 0 until k.h) {
            val runS = k.runStart[ky]
            val runE = k.runEnd[ky]
            if (runS >= runE) continue
            val dy = ky - k.ay
            val offL = runS - k.ax
            val offR = runE - 1 - k.ax
            for (y in 0 until h) {
                val sy = y - dy
                if (sy < 0 || sy >= h) continue
                val base = sy * w
                prefix[0] = 0
                for (x in 0 until w) prefix[x + 1] = prefix[x] + if (m.data[base + x]) 1 else 0
                if (prefix[w] == 0) continue
                val obase = y * w
                for (x in 0 until w) {
                    if (out.data[obase + x]) continue
                    val a = max(0, x + offL)
                    val b = min(w - 1, x + offR)
                    if (a > b) continue
                    if (prefix[b + 1] - prefix[a] > 0) out.data[obase + x] = true
                }
            }
        }
        return out
    }

    private fun erodeRef(m: Mask, k: Kernel): Mask {
        val w = m.w
        val h = m.h
        val out = Mask(w, h, BooleanArray(w * h) { true })
        val prefix = IntArray(w + 1)
        for (ky in 0 until k.h) {
            val runS = k.runStart[ky]
            val runE = k.runEnd[ky]
            if (runS >= runE) continue
            val dy = ky - k.ay
            val offL = runS - k.ax
            val offR = runE - 1 - k.ax
            for (y in 0 until h) {
                val sy = y - dy
                val obase = y * w
                if (sy < 0 || sy >= h) continue
                val base = sy * w
                prefix[0] = 0
                for (x in 0 until w) prefix[x + 1] = prefix[x] + if (m.data[base + x]) 1 else 0
                for (x in 0 until w) {
                    if (!out.data[obase + x]) continue
                    val a = max(0, x + offL)
                    val b = min(w - 1, x + offR)
                    if (a > b) continue
                    val need = b - a + 1
                    if (prefix[b + 1] - prefix[a] < need) out.data[obase + x] = false
                }
            }
        }
        return out
    }

    private fun randomMask(rnd: Random, w: Int, h: Int): Mask {
        val m = Mask(w, h)
        // 幾種密度與塊狀結構：稀疏點、中等、稠密、橫條／方塊
        when (rnd.nextInt(4)) {
            0 -> for (i in m.data.indices) m.data[i] = rnd.nextInt(50) == 0
            1 -> for (i in m.data.indices) m.data[i] = rnd.nextBoolean()
            2 -> for (i in m.data.indices) m.data[i] = rnd.nextInt(20) != 0
            else -> repeat(1 + rnd.nextInt(6)) {
                val x0 = rnd.nextInt(w)
                val y0 = rnd.nextInt(h)
                val x1 = min(w, x0 + 1 + rnd.nextInt(w))
                val y1 = min(h, y0 + 1 + rnd.nextInt(h))
                for (y in y0 until y1) for (x in x0 until x1) m.data[y * w + x] = true
            }
        }
        return m
    }

    private fun randomKernel(rnd: Random): Kernel = when (rnd.nextInt(4)) {
        0 -> Cv.ellipse(1 + 2 * rnd.nextInt(12))
        1 -> Cv.rect(1 + rnd.nextInt(10), 1 + rnd.nextInt(10))            // 含偶數尺寸（錨點不置中）
        else -> {
            // 每列一段任意 run（可空）＝任意不對稱核
            val kw = 1 + rnd.nextInt(9)
            val kh = 1 + rnd.nextInt(9)
            val d = BooleanArray(kw * kh)
            for (y in 0 until kh) {
                if (rnd.nextInt(5) == 0) continue
                val a = rnd.nextInt(kw)
                val b = a + rnd.nextInt(kw - a)
                for (x in a..b) d[y * kw + x] = true
            }
            Kernel(kw, kh, d)
        }
    }

    @Test
    fun packedMatchesReference() {
        val rnd = Random(20261006)
        val sizes = listOf(1 to 1, 1 to 7, 7 to 1, 63 to 5, 64 to 9, 65 to 3, 127 to 40, 128 to 17, 130 to 66, 200 to 3, 3 to 200)
        repeat(600) { t ->
            val (w, h) = sizes[t % sizes.size]
            val m = randomMask(rnd, w, h)
            val k = randomKernel(rnd)
            val tag = "#$t ${w}×$h 核 ${k.w}×${k.h}"
            assertArrayEquals("膨脹 $tag", dilateRef(m, k).data, Cv.dilatePacked(m, k).data)
            assertArrayEquals("侵蝕 $tag", erodeRef(m, k).data, Cv.erodePacked(m, k).data)
            // 公開入口：一般核就是打包版（1×n、n×1 實心線核另走 lineMorph，不在這個測試的範圍）
            val lineK = (k.h == 1 && k.runStart[0] == 0 && k.runEnd[0] == k.w) ||
                (k.w == 1 && (0 until k.h).all { k.runStart[it] == 0 && k.runEnd[it] == 1 })
            if (!lineK) {
                assertArrayEquals("dilate $tag", dilateRef(m, k).data, Cv.dilate(m, k).data)
                assertArrayEquals("erode $tag", erodeRef(m, k).data, Cv.erode(m, k).data)
            }
        }
    }
}
