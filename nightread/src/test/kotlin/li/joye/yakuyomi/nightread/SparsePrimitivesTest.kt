package li.joye.yakuyomi.nightread

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 只算要的像素的原語（2026-10-07 加速）對整頁算再取逐值相同：[Cv.gaussQ16Sparse]（來源＝灰階或 [Cv.MedianRows]）、
 * [Cv.bitRuns]、[BgObjects.gaussSamples]（取代逐點 gaussAt）、[BgObjects.toneCount]（取代整頁的調子邊緣，參考＝改寫前 context 裡的
 * 整頁程式，下面照抄）。隨機尺寸含 1×1、單列、單欄與比核小的頁；要的像素含稀疏點、整段、碰頁緣與全頁。
 */
class SparsePrimitivesTest {

    /** 改寫前 BgObjects.context 的調子邊緣（整頁；照抄）。 */
    private fun toneRef(g: Gray, tg2: Double): BooleanArray {
        val w = g.w
        val h = g.h
        val gm = Cv.gaussQ16(Cv.medianBlur8(g, 7), 3.0).data
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val ym = (if (y == 0) Cv.reflect101(-1, h) else y - 1) * w
            val y0 = y * w
            val yp = (if (y == h - 1) Cv.reflect101(h, h) else y + 1) * w
            for (x in 0 until w) {
                val xm = if (x == 0) Cv.reflect101(-1, w) else x - 1
                val xp = if (x == w - 1) Cv.reflect101(w, w) else x + 1
                val dx = (gm[ym + xp].toLong() + 2L * gm[y0 + xp] + gm[yp + xp]) - (gm[ym + xm].toLong() + 2L * gm[y0 + xm] + gm[yp + xm])
                val dy = (gm[yp + xm].toLong() + 2L * gm[yp + x] + gm[yp + xp]) - (gm[ym + xm].toLong() + 2L * gm[ym + x] + gm[ym + xp])
                out[y0 + x] = (dx * dx + dy * dy).toDouble() > tg2
            }
        }
        return out
    }

    /** 改寫前的逐點高斯（BgObjects.gaussAt，照抄）。 */
    private fun gaussAtRef(g: Gray, k: IntArray, x: Int, y: Int): Int {
        val w = g.w
        val h = g.h
        val gd = g.data
        val c = k.size / 2
        val kc = k[c]
        val inX = x >= c && x < w - c
        var acc = 0L
        for (j in 0 until k.size) {
            val kj = k[j]
            if (kj == 0) continue
            val yy = y + j - c
            val r = (if (yy >= 0 && yy < h) yy else Cv.reflect101(yy, h)) * w
            var hs = kc * gd[r + x]
            if (inX) {
                for (t in 1..c) hs += k[c + t] * (gd[r + x - t] + gd[r + x + t])
            } else {
                for (t in 1..c) hs += k[c + t] * (gd[r + Cv.reflect101(x - t, w)] + gd[r + Cv.reflect101(x + t, w)])
            }
            acc += kj.toLong() * hs
        }
        return ((acc + 32768L) shr 16).toInt()
    }

    private fun randGray(rnd: Random, w: Int, h: Int, kind: Int): Gray = Gray(w, h, IntArray(w * h) {
        when (kind) {
            0 -> rnd.nextInt(256)
            1 -> if (rnd.nextInt(5) == 0) rnd.nextInt(256) else 230 + rnd.nextInt(3)   // 紙白上零星墨
            2 -> ((it % w) * 7 + (it / w) * 3) and 255                                   // 漸層
            else -> if ((it / w / 4 + it % w / 4) % 2 == 0) 20 else 240                 // 棋盤
        }
    })

    /** 隨機要的像素（頁面遮罩）：稀疏點／整段／方塊／頁緣框／全頁。 */
    private fun randNeed(rnd: Random, w: Int, h: Int, kind: Int): Mask {
        val m = Mask(w, h)
        when (kind) {
            0 -> for (i in 0 until w * h) m.data[i] = rnd.nextInt(17) == 0
            1 -> repeat(1 + rnd.nextInt(6)) {
                val y = rnd.nextInt(h); val a = rnd.nextInt(w); val b = min(w, a + 1 + rnd.nextInt(w))
                for (x in a until b) m.data[y * w + x] = true
            }
            2 -> repeat(1 + rnd.nextInt(4)) {
                val x0 = rnd.nextInt(w); val y0 = rnd.nextInt(h)
                val x1 = min(w, x0 + 1 + rnd.nextInt(w)); val y1 = min(h, y0 + 1 + rnd.nextInt(h))
                for (y in y0 until y1) for (x in x0 until x1) m.data[y * w + x] = true
            }
            3 -> for (y in 0 until h) for (x in 0 until w) m.data[y * w + x] = x == 0 || y == 0 || x == w - 1 || y == h - 1
            else -> java.util.Arrays.fill(m.data, true)
        }
        return m
    }

    private val sizes = listOf(1 to 1, 1 to 37, 37 to 1, 2 to 3, 5 to 4, 13 to 9, 64 to 2, 65 to 30, 130 to 71, 200 to 128)

    @Test
    fun bitRunsMatchesNaive() {
        val rnd = Random(11)
        repeat(300) {
            val w = 1 + rnd.nextInt(300)
            val m = Mask(w, 1, BooleanArray(w) { rnd.nextInt(if (it % 64 == 63) 2 else 3) != 0 })
            val bits = Cv.packBits(m)
            val nw = (w + 63) ushr 6
            val rs = IntArray((64 * nw + 1) / 2 + 1)
            val re = IntArray(rs.size)
            val n = Cv.bitRuns(bits, 0, nw, rs, re)
            val got = BooleanArray(w)
            for (q in 0 until n) {
                assertTrue(rs[q] < re[q])
                if (q > 0) assertTrue("段要分開", re[q - 1] < rs[q])
                for (x in rs[q] until re[q]) got[x] = true
            }
            assertArrayEquals(m.data, got)
        }
    }

    @Test
    fun gaussSparseMatchesFull() {
        val rnd = Random(2026)
        for ((w, h) in sizes) for (kind in 0 until 4) {
            val g = randGray(rnd, w, h, kind)
            for (sigma in doubleArrayOf(1.0, 1.5, 2.5, 3.0, 5.0)) {
                val full = Cv.gaussQ16(g, sigma).data
                for (nk in 0 until 5) {
                    val need = randNeed(rnd, w, h, nk)
                    // 只送一段列（ny0..）：要的像素只取那幾列
                    val ny0 = if (h > 2 && nk == 1) rnd.nextInt(h) else 0
                    val nrows = h - ny0
                    val nw = (w + 63) ushr 6
                    val bits = Cv.packBits(need)
                    val sub = bits.copyOfRange(ny0 * nw, h * nw)
                    val got = IntArray(w * h) { Int.MIN_VALUE }
                    var lastY = -1
                    Cv.gaussQ16Sparse(w, h, Cv.gaussW(sigma), sub, ny0, nrows, Cv.grayRows(g)) { y, v, rs, re, n ->
                        assertTrue("列要遞增", y > lastY)
                        lastY = y
                        for (q in 0 until n) for (x in rs[q] until re[q]) got[y * w + x] = v[x]
                    }
                    for (y in ny0 until h) for (x in 0 until w) {
                        val i = y * w + x
                        if (need.data[i]) assertEquals("σ$sigma ${w}×$h need$nk ($x,$y)", full[i], got[i])
                        else assertEquals("沒要的不送 ($x,$y)", Int.MIN_VALUE, got[i])
                    }
                }
            }
        }
    }

    @Test
    fun medianSourceMatchesFull() {
        val rnd = Random(7)
        for ((w, h) in sizes) for (kind in 0 until 4) {
            val g = randGray(rnd, w, h, kind)
            val full = Cv.gaussQ16(Cv.medianBlur8(g, 7), 3.0).data
            for (nk in 0 until 5) {
                val need = randNeed(rnd, w, h, nk)
                val got = IntArray(w * h) { Int.MIN_VALUE }
                Cv.gaussQ16Sparse(w, h, Cv.gaussW(3.0), Cv.packBits(need), 0, h, Cv.MedianRows(g, 7)) { y, v, rs, re, n ->
                    for (q in 0 until n) for (x in rs[q] until re[q]) got[y * w + x] = v[x]
                }
                for (i in 0 until w * h) if (need.data[i]) assertEquals("中值→σ3 ${w}×$h need$nk i=$i", full[i], got[i])
            }
        }
    }

    @Test
    fun gaussSamplesMatchGaussAt() {
        val rnd = Random(25)
        val k = Cv.gaussW(2.5)
        for ((w, h) in sizes) for (kind in 0 until 4) {
            val g = randGray(rnd, w, h, kind)
            repeat(4) {
                // 窗＋掃描序取樣（同 regionsPassed：窗內遮罩每 step 點取一點）
                val x0 = rnd.nextInt(w); val y0 = rnd.nextInt(h)
                val sw = 1 + rnd.nextInt(w - x0); val sh = 1 + rnd.nextInt(h - y0)
                val step = 1 + rnd.nextInt(4)
                val ys = ArrayList<Int>(); val xs = ArrayList<Int>()
                var kk = 0
                for (yy in 0 until sh) for (xx in 0 until sw) {
                    if (rnd.nextInt(3) == 0) continue
                    if (kk % step == 0) { ys.add(yy); xs.add(xx) }
                    kk++
                }
                val got = BgObjects.gaussSamples(g, k, ys.toIntArray(), xs.toIntArray(), ys.size, x0, y0, sh)
                val want = IntArray(ys.size) { gaussAtRef(g, k, xs[it] + x0, ys[it] + y0) }
                assertArrayEquals("取樣 ${w}×$h 窗 ($x0,$y0) ${sw}×$sh", want, got)
            }
        }
    }

    @Test
    fun toneCountMatchesFullPage() {
        val rnd = Random(4)
        for ((w, h) in sizes + listOf(300 to 260)) for (kind in 0 until 4) {
            val g = randGray(rnd, w, h, kind)
            for (tgv in doubleArrayOf(0.0, 1e9, 1.76e11, 2e12, 1e14)) {
                val full = toneRef(g, tgv)
                repeat(4) { t ->
                    val x0 = rnd.nextInt(w); val y0 = rnd.nextInt(h)
                    val sw = if (t == 0) w - x0 else 1 + rnd.nextInt(w - x0)
                    val sh = if (t == 0) h - y0 else 1 + rnd.nextInt(h - y0)
                    val me = Mask(sw, sh, BooleanArray(sw * sh) { if (t == 1) true else rnd.nextInt(3) != 0 })
                    var want = 0
                    for (yy in 0 until sh) for (xx in 0 until sw) if (me.data[yy * sw + xx] && full[(yy + y0) * w + xx + x0]) want++
                    assertEquals("調子邊緣 ${w}×$h 窗 ($x0,$y0) ${sw}×$sh 門檻 $tgv", want, BgObjects.toneCount(g, me, x0, y0, tgv))
                }
            }
        }
    }
}
