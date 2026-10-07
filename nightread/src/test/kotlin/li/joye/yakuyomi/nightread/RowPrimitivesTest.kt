package li.joye.yakuyomi.nightread

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * 第二批改寫的原語（2026-10-07 加速）對改寫前（2f98ce4，下面照抄）逐值相同：[Cv.canny]（右移後的列與橫向和三列快取）、
 * [BgObjects.lineMeanGt]（陣列索引、頁內免反射）、[EffectLines.convQ]／[EffectLines.tensor]（核在外、反射欄先算）、
 * [Cv.ccRunsBits]（列打包輸入的游程標號）、[Cv.anyBits]。尺寸含 1×1、單列、單欄與比核小的頁。
 */
class RowPrimitivesTest {

        private fun cannyOld(src: IntArray, w: Int, h: Int, shift: Int, lo: Int, hi: Int): Mask {
            val dx = Array(3) { IntArray(w) }
            val dy = Array(3) { IntArray(w) }
            val mag = Array(3) { IntArray(w + 2) }      // [x + 1]；兩側各一格 0
            fun row(y: Int, slot: Int) {
                val ym = kotlin.math.max(0, y - 1) * w
                val y0 = y * w
                val yp = kotlin.math.min(h - 1, y + 1) * w
                val ddx = dx[slot]; val ddy = dy[slot]; val mm = mag[slot]
                for (x in 0 until w) {
                    val xm = kotlin.math.max(0, x - 1)
                    val xp = kotlin.math.min(w - 1, x + 1)
                    val a = src[ym + xm] shr shift; val b = src[ym + x] shr shift; val c = src[ym + xp] shr shift
                    val d = src[y0 + xm] shr shift; val f = src[y0 + xp] shr shift
                    val e = src[yp + xm] shr shift; val gg = src[yp + x] shr shift; val k = src[yp + xp] shr shift
                    val gx = (c + 2 * f + k) - (a + 2 * d + e)
                    val gy = (e + 2 * gg + k) - (a + 2 * b + c)
                    ddx[x] = gx; ddy[x] = gy
                    mm[x + 1] = kotlin.math.abs(gx) + kotlin.math.abs(gy)
                }
            }
            val zero = IntArray(w + 2)
            // 0＝不是邊、1＝候選、2＝強邊
            val st = ByteArray(w * h)
            var stack = IntArray(1024)
            var sp = 0
            if (h > 0) row(0, 0)
            for (y in 0 until h) {
                val cur = y % 3
                if (y + 1 < h) row(y + 1, (y + 1) % 3)
                val mp = if (y > 0) mag[(y + 2) % 3] else zero
                val mn = if (y + 1 < h) mag[(y + 1) % 3] else zero
                val ma = mag[cur]
                val ddx = dx[cur]; val ddy = dy[cur]
                for (x in 0 until w) {
                    val mv = ma[x + 1]
                    if (mv <= lo) continue
                    val xs = ddx[x]
                    val ys = ddy[x]
                    val ax = kotlin.math.abs(xs)
                    val ay = kotlin.math.abs(ys) shl 15
                    val tg22x = ax * 13573
                    val isMax = if (ay < tg22x) {
                        mv > ma[x] && mv >= ma[x + 2]
                    } else {
                        val tg67x = tg22x + (ax shl 16)
                        if (ay > tg67x) {
                            mv > mp[x + 1] && mv >= mn[x + 1]
                        } else {
                            val sg = if ((xs xor ys) < 0) -1 else 1
                            mv > mp[x + 1 - sg] && mv > mn[x + 1 + sg]
                        }
                    }
                    if (!isMax) continue
                    val i = y * w + x
                    if (mv > hi) {
                        st[i] = 2
                        if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[sp++] = i
                    } else st[i] = 1
                }
            }
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                for (yy in kotlin.math.max(0, y - 1)..kotlin.math.min(h - 1, y + 1)) {
                    for (xx in kotlin.math.max(0, x - 1)..kotlin.math.min(w - 1, x + 1)) {
                        val j = yy * w + xx
                        if (st[j].toInt() == 1) {
                            st[j] = 2
                            if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                            stack[sp++] = j
                        }
                    }
                }
            }
            return Mask(w, h, BooleanArray(w * h) { st[it].toInt() == 2 })
        }

        private fun lineMeanGtOld(img: Gray, offs: List<IntArray>, num: Long, den: Long, cand: Mask): Mask {
            val w = img.w
            val h = img.h
            val d = img.data
            val out = Mask(w, h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!cand.data[i]) continue
                    for (o in offs) {
                        var s = 0L
                        var j = 0
                        while (j < o.size) {
                            val yy = y + o[j]
                            val xx = x + o[j + 1]
                            val ry = if (yy < 0 || yy >= h) Cv.reflect101(yy, h) else yy
                            val rx = if (xx < 0 || xx >= w) Cv.reflect101(xx, w) else xx
                            s += d[ry * w + rx]
                            j += 2
                        }
                        if (s * den > num * (o.size / 2)) { out.data[i] = true; break }
                    }
                }
            }
            return out
        }

        private fun convQOld(a: LongArray, rows: Int, cols: Int, k: IntArray): LongArray {
            val c = k.size / 2
            val h1 = LongArray(rows * cols)
            val xi = IntArray(cols + 2 * c) { Cv.reflect101(it - c, cols) }
            val line = LongArray(cols + 2 * c)
            val kc = k[c].toLong()
            for (y in 0 until rows) {
                val b = y * cols
                for (i in line.indices) line[i] = a[b + xi[i]]
                for (x in 0 until cols) {
                    val m = x + c
                    var acc = kc * line[m]
                    for (j in 1..c) acc += k[c + j].toLong() * (line[m - j] + line[m + j])
                    h1[b + x] = (acc + 32768L) shr 16
                }
            }
            val out = LongArray(rows * cols)
            for (y in 0 until rows) {
                val ob = y * cols
                val sc = y * cols
                for (x in 0 until cols) out[ob + x] = kc * h1[sc + x]
                for (j in 1..c) {
                    val kj = k[c + j].toLong()
                    if (kj == 0L) continue
                    val sa = Cv.reflect101(y - j, rows) * cols
                    val sb = Cv.reflect101(y + j, rows) * cols
                    for (x in 0 until cols) out[ob + x] += kj * (h1[sa + x] + h1[sb + x])
                }
                for (x in 0 until cols) out[ob + x] = (out[ob + x] + 32768L) shr 16
            }
            return out
        }

        private fun tensorOld(bh: Gray, cand: Mask, wx0: Int, wx1: Int, r0: Int, r1: Int, k1: IntArray, k4: IntArray): Array<LongArray> {
            val pw = bh.w
            val cols = wx1 - wx0
            val rows = r1 - r0
            val a = LongArray(rows * cols)
            for (y in 0 until rows) for (x in 0 until cols) {
                val i = (y + r0) * pw + x + wx0
                if (cand.data[i]) a[y * cols + x] = bh.data[i].toLong()
            }
            val f = convQOld(a, rows, cols, k1)
            val pxx = LongArray(rows * cols)
            val pyy = LongArray(rows * cols)
            val pxy = LongArray(rows * cols)
            for (y in 0 until rows) {
                val ym = Cv.reflect101(y - 1, rows) * cols
                val y0 = y * cols
                val yp = Cv.reflect101(y + 1, rows) * cols
                for (x in 0 until cols) {
                    val xm = Cv.reflect101(x - 1, cols)
                    val xp = Cv.reflect101(x + 1, cols)
                    val gx = (f[ym + xp] + 2 * f[y0 + xp] + f[yp + xp]) - (f[ym + xm] + 2 * f[y0 + xm] + f[yp + xm])
                    val gy = (f[yp + xm] + 2 * f[yp + x] + f[yp + xp]) - (f[ym + xm] + 2 * f[ym + x] + f[ym + xp])
                    pxx[y0 + x] = (gx * gx + 32768L) shr 16
                    pyy[y0 + x] = (gy * gy + 32768L) shr 16
                    pxy[y0 + x] = (gx * gy + 32768L) shr 16
                }
            }
            return arrayOf(convQOld(pxx, rows, cols, k4), convQOld(pyy, rows, cols, k4), convQOld(pxy, rows, cols, k4))
        }

    private val sizes = listOf(1 to 1, 1 to 23, 23 to 1, 2 to 2, 3 to 7, 17 to 5, 64 to 9, 65 to 40, 140 to 97)

    private fun img(rnd: Random, w: Int, h: Int, kind: Int, scale: Int): IntArray = IntArray(w * h) {
        when (kind) {
            0 -> rnd.nextInt(256) * scale
            1 -> (if (rnd.nextInt(6) == 0) rnd.nextInt(256) else 235) * scale
            2 -> (((it % w) * 9 + (it / w) * 5) and 255) * scale
            else -> (if ((it / w / 3 + it % w / 3) % 2 == 0) 30 else 220) * scale + rnd.nextInt(scale)
        }
    }

    @Test
    fun cannyMatchesOld() {
        val rnd = Random(31)
        for ((w, h) in sizes) for (kind in 0 until 4) for (shift in intArrayOf(0, 16)) {
            val src = img(rnd, w, h, kind, if (shift == 16) 65536 else 1)
            for ((lo, hi) in listOf(10 to 30, 50 to 150, 0 to 1, 200 to 600)) {
                assertArrayEquals("canny ${w}×$h kind$kind shift$shift $lo/$hi", cannyOld(src, w, h, shift, lo, hi).data, Cv.canny(src, w, h, shift, lo, hi).data)
            }
        }
    }

    @Test
    fun lineMeanGtMatchesOld() {
        val rnd = Random(17)
        for ((w, h) in sizes + listOf(80 to 60)) for (kind in 0 until 4) {
            val g = Gray(w, h, img(rnd, w, h, kind, 65536))
            val cand = Mask(w, h, BooleanArray(w * h) { rnd.nextInt(3) != 0 })
            for (len in intArrayOf(11, 17)) for ((num, den) in listOf(12L * 65536 to 1L, 36L * 65536 to 5L, 200L * 65536 to 1L)) {
                val offs = BgObjects.lineOffsets(len)
                assertArrayEquals("lineMeanGt ${w}×$h len$len $num/$den", lineMeanGtOld(g, offs, num, den, cand).data,
                    BgObjects.lineMeanGt(g, offs, num, den, cand).data)
            }
        }
    }

    @Test
    fun convQAndTensorMatchOld() {
        val rnd = Random(4242)
        for ((w, h) in sizes) for (kind in 0 until 4) {
            val a = LongArray(w * h) { img(rnd, 1, 1, kind, 65536)[0].toLong() - 30L * 65536 }
            for (sigma in doubleArrayOf(1.0, 2.0, 4.0)) {
                val k = Cv.gaussW(sigma)
                assertArrayEquals("convQ ${w}×$h σ$sigma", convQOld(a, h, w, k), EffectLines.convQ(a, h, w, k))
            }
            val bh = Gray(w, h, img(rnd, w, h, kind, 65536))
            val cand = Mask(w, h, BooleanArray(w * h) { rnd.nextInt(4) != 0 })
            val k1 = Cv.gaussW(1.0)
            val k4 = Cv.gaussW(4.0)
            repeat(3) {
                val wx0 = rnd.nextInt(w); val wx1 = wx0 + 1 + rnd.nextInt(w - wx0)
                val r0 = rnd.nextInt(h); val r1 = r0 + 1 + rnd.nextInt(h - r0)
                val want = tensorOld(bh, cand, wx0, wx1, r0, r1, k1, k4)
                val got = EffectLines.tensor(bh, cand, wx0, wx1, r0, r1, k1, k4)
                for (q in 0 until 3) assertArrayEquals("tensor $q ${w}×$h", want[q], got[q])
            }
        }
    }

    @Test
    fun ccRunsBitsMatchesCcRuns() {
        val rnd = Random(808)
        repeat(200) {
            val w = 1 + rnd.nextInt(150)
            val h = 1 + rnd.nextInt(60)
            val dens = 1 + rnd.nextInt(4)
            val m = Mask(w, h, BooleanArray(w * h) { rnd.nextInt(dens + 1) != 0 })
            for (conn in intArrayOf(4, 8)) {
                val a = Cv.ccRuns(m, conn)
                val b = Cv.ccRunsBits(Cv.packBits(m), w, h, conn)
                assertEquals(a.n, b.n)
                assertArrayEquals(a.rowFirst, b.rowFirst)
                val nr = a.rowFirst[h]
                assertArrayEquals(a.rs.copyOf(nr), b.rs.copyOf(nr))
                assertArrayEquals(a.re.copyOf(nr), b.re.copyOf(nr))
                assertArrayEquals(a.lab.copyOf(nr), b.lab.copyOf(nr))
            }
        }
    }

    @Test
    fun anyBitsMatchesNaive() {
        val rnd = Random(5)
        repeat(500) {
            val w = 1 + rnd.nextInt(260)
            val m = Mask(w, 1, BooleanArray(w) { rnd.nextInt(20) == 0 })
            val bits = Cv.packBits(m)
            val s = rnd.nextInt(w)
            val e = s + rnd.nextInt(w - s + 1)
            var want = false
            for (x in s until e) if (m.data[x]) want = true
            assertEquals("anyBits w=$w [$s,$e)", want, Cv.anyBits(bits, 0, s, e))
        }
    }
}
