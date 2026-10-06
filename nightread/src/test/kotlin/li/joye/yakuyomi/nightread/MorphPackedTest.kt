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
    // ── 灰階形態學（2026-10-06：緩衝重用、界內快路；下面照抄改寫前的 morphGray／slidingExtreme 當參考）──

    private fun slidingRef(a: IntArray, n: Int, win: Int, wantMax: Boolean, dst: IntArray) {
        if (win >= n) {
            var acc = a[0]
            for (i in 1 until n) acc = if (wantMax) max(acc, a[i]) else min(acc, a[i])
            dst[0] = acc
            return
        }
        val pre = IntArray(n)
        val suf = IntArray(n)
        var i = 0
        while (i < n) {
            val end = min(i + win, n)
            var acc = a[i]
            pre[i] = acc
            for (j in i + 1 until end) {
                acc = if (wantMax) max(acc, a[j]) else min(acc, a[j])
                pre[j] = acc
            }
            acc = a[end - 1]
            suf[end - 1] = acc
            for (j in end - 2 downTo i) {
                acc = if (wantMax) max(acc, a[j]) else min(acc, a[j])
                suf[j] = acc
            }
            i = end
        }
        for (x in 0..n - win) {
            val lo = x
            val hi = x + win - 1
            dst[x] = if (lo / win == hi / win) {
                var acc = a[lo]
                for (j in lo + 1..hi) acc = if (wantMax) max(acc, a[j]) else min(acc, a[j])
                acc
            } else {
                if (wantMax) max(suf[lo], pre[hi]) else min(suf[lo], pre[hi])
            }
        }
    }

    private fun morphGrayRef(g: Gray, k: Kernel, wantMax: Boolean, lo: Int, hi: Int): Gray {
        val w = g.w
        val h = g.h
        val out = Gray(w, h, IntArray(w * h) { if (wantMax) lo else hi })
        val row = IntArray(w)
        val widths = k.runStart.indices.filter { k.runEnd[it] > k.runStart[it] }.map { k.runEnd[it] - k.runStart[it] }.distinct()
        val cache = HashMap<Int, IntArray>(widths.size)
        for (win in widths) cache[win] = IntArray(w)
        for (sy in 0 until h) {
            System.arraycopy(g.data, sy * w, row, 0, w)
            for (win in widths) slidingRef(row, w, win, wantMax, cache[win]!!)
            for (ky in 0 until k.h) {
                val runS = k.runStart[ky]
                val runE = k.runEnd[ky]
                if (runS >= runE) continue
                val y = sy - (ky - k.ay)
                if (y < 0 || y >= h) continue
                val win = runE - runS
                val slide = cache[win]!!
                val offL = runS - k.ax
                val obase = y * w
                for (x in 0 until w) {
                    val a0 = x + offL
                    val b0 = a0 + win - 1
                    val v = if (a0 >= 0 && b0 < w) {
                        slide[a0]
                    } else {
                        val l2 = max(a0, 0)
                        val h2 = min(b0, w - 1)
                        if (l2 > h2) {
                            if (wantMax) l2 else h2
                        } else {
                            var acc = row[l2]
                            for (j in l2 + 1..h2) acc = if (wantMax) max(acc, row[j]) else min(acc, row[j])
                            acc
                        }
                    }
                    val cur = out.data[obase + x]
                    out.data[obase + x] = if (wantMax) max(cur, v) else min(cur, v)
                }
            }
        }
        return out
    }

    @Test
    fun grayMorphMatchesReference() {
        val rnd = Random(61006)
        val sizes = listOf(1 to 1, 1 to 9, 9 to 1, 5 to 5, 33 to 17, 64 to 40, 3 to 90, 120 to 3)
        repeat(400) { t ->
            val (w, h) = sizes[t % sizes.size]
            val g = Gray(w, h, IntArray(w * h) { if (t % 3 == 0) rnd.nextInt(256) else rnd.nextInt(-100000, 100000) })
            val k = randomKernel(rnd)
            val tag = "#$t ${w}×$h 核 ${k.w}×${k.h}"
            if (t % 3 == 0) {
                assertArrayEquals("dilateGray $tag", morphGrayRef(g, k, true, 0, 255).data, Cv.dilateGray(g, k).data)
                assertArrayEquals("erodeGray $tag", morphGrayRef(g, k, false, 0, 255).data, Cv.erodeGray(g, k).data)
            }
            assertArrayEquals("dilateGrayI $tag", morphGrayRef(g, k, true, Int.MIN_VALUE, Int.MAX_VALUE).data, Cv.dilateGrayI(g, k).data)
            assertArrayEquals("erodeGrayI $tag", morphGrayRef(g, k, false, Int.MIN_VALUE, Int.MAX_VALUE).data, Cv.erodeGrayI(g, k).data)
        }
    }
    // ── 線核（2026-10-06 改走位元打包；下面照抄改寫前的「最近目標距離」兩趟掃描當參考）──

    private fun lineRef(m: Mask, len: Int, horizontal: Boolean, anchor: Int, dilate: Boolean): Mask {
        val w = m.w
        val h = m.h
        val out = Mask(w, h)
        val src = m.data
        val dst = out.data
        val a = anchor
        val b = len - 1 - anchor
        val target = dilate
        val far = Int.MIN_VALUE / 2
        if (horizontal) {
            for (y in 0 until h) {
                val base = y * w
                var prev = far
                for (x in 0 until w) {
                    if (src[base + x] == target) prev = x
                    dst[base + x] = x - prev <= a
                }
                var next = -far
                for (x in w - 1 downTo 0) {
                    if (src[base + x] == target) next = x
                    if (next - x <= b) dst[base + x] = true
                }
            }
        } else {
            val prev = IntArray(w) { far }
            for (y in 0 until h) {
                val base = y * w
                for (x in 0 until w) {
                    if (src[base + x] == target) prev[x] = y
                    dst[base + x] = y - prev[x] <= a
                }
            }
            val next = IntArray(w) { -far }
            for (y in h - 1 downTo 0) {
                val base = y * w
                for (x in 0 until w) {
                    if (src[base + x] == target) next[x] = y
                    if (next[x] - y <= b) dst[base + x] = true
                }
            }
        }
        if (!dilate) for (i in dst.indices) dst[i] = !dst[i]
        return out
    }

    @Test
    fun lineKernelsMatchReference() {
        val rnd = Random(10061)
        val sizes = listOf(1 to 1, 1 to 30, 30 to 1, 63 to 7, 64 to 64, 65 to 33, 130 to 70, 200 to 5, 5 to 200)
        repeat(500) { t ->
            val (w, h) = sizes[t % sizes.size]
            val m = randomMask(rnd, w, h)
            val n = 1 + rnd.nextInt(if (t % 4 == 0) 300 else 40)
            val hk = Cv.rect(n, 1)
            val vk = Cv.rect(1, n)
            val tag = "#$t ${w}×$h n=$n"
            val dh = lineRef(m, n, true, hk.ax, true)
            val dv = lineRef(m, n, false, vk.ay, true)
            val eh = lineRef(m, n, true, hk.ax, false)
            val ev = lineRef(m, n, false, vk.ay, false)
            assertArrayEquals("dilate H $tag", dh.data, Cv.dilate(m, hk).data)
            assertArrayEquals("dilate V $tag", dv.data, Cv.dilate(m, vk).data)
            assertArrayEquals("erode H $tag", eh.data, Cv.erode(m, hk).data)
            assertArrayEquals("erode V $tag", ev.data, Cv.erode(m, vk).data)
            assertArrayEquals("open H $tag", lineRef(eh, n, true, hk.ax, true).data, Cv.open(m, hk).data)
            assertArrayEquals("open V $tag", lineRef(ev, n, false, vk.ay, true).data, Cv.open(m, vk).data)
            assertArrayEquals("close H $tag", lineRef(dh, n, true, hk.ax, false).data, Cv.close(m, hk).data)
            assertArrayEquals("close V $tag", lineRef(dv, n, false, vk.ay, false).data, Cv.close(m, vk).data)
            val sq = lineRef(dh, n, false, vk.ay, true)
            assertArrayEquals("dilateRectSep $tag", sq.data, Cv.dilateRectSep(m, n, n).data)
            val cl = lineRef(lineRef(sq, n, true, hk.ax, false), n, false, vk.ay, false)
            assertArrayEquals("closeRectSep $tag", cl.data, Cv.closeRectSep(m, n).data)
        }
    }
    // ── 測地生長迭代版（2026-10-06 改走位元打包；下面照抄改寫前的逐像素八鄰居版當參考）──

    private fun growRef(seed: Mask, within: Mask, iters: Int, step: Int): Mask {
        val w = seed.w
        val h = seed.h
        var cur = (seed and within).data
        var next = BooleanArray(w * h)
        var done = 0
        while (done < iters) {
            val n = min(step, iters - done)
            var changed = false
            repeat(n) { sub ->
                val last = sub == n - 1
                for (y in 0 until h) {
                    val base = y * w
                    val up = base - w
                    val dn = base + w
                    for (x in 0 until w) {
                        val i = base + x
                        if (last && !within.data[i]) { next[i] = false; continue }
                        if (cur[i]) { next[i] = true; continue }
                        val l = x > 0
                        val r = x < w - 1
                        val hit = (l && cur[i - 1]) || (r && cur[i + 1]) ||
                            (y > 0 && (cur[up + x] || (l && cur[up + x - 1]) || (r && cur[up + x + 1]))) ||
                            (y < h - 1 && (cur[dn + x] || (l && cur[dn + x - 1]) || (r && cur[dn + x + 1])))
                        next[i] = hit
                        if (hit) changed = true
                    }
                }
                val t = cur; cur = next; next = t
            }
            done += n
            if (!changed) return Mask(w, h, cur)
        }
        return Mask(w, h, cur)
    }

    @Test
    fun geodesicGrowMatchesReference() {
        val rnd = Random(2026)
        val sizes = listOf(1 to 1, 1 to 40, 40 to 1, 63 to 30, 64 to 64, 65 to 65, 129 to 33)
        repeat(300) { t ->
            val (w, h) = sizes[t % sizes.size]
            val within = Mask(w, h)
            for (i in within.data.indices) within.data[i] = rnd.nextInt(10) < 6 + t % 4
            val seed = Mask(w, h)
            repeat(1 + rnd.nextInt(4)) { seed.data[rnd.nextInt(w * h)] = true }
            val iters = 1 + rnd.nextInt(30)
            val step = 1 + rnd.nextInt(6)
            val want = growRef(seed, within, iters, step)
            assertArrayEquals("迭代 #$t ${w}×$h iters=$iters step=$step", want.data, Cv.geodesicGrow(seed, within, iters, step, bfs = false).data)
            assertArrayEquals("BFS #$t ${w}×$h iters=$iters step=$step", want.data, Cv.geodesicGrow(seed, within, iters, step, bfs = true).data)
        }
    }
    // ── blackhat 走 morphGrayGather（2026-10-06）、distanceL2 欄方向分塊：對照改寫前的寫法 ──

    @Test
    fun blackhatMatchesScatter() {
        val rnd = Random(7007)
        repeat(120) { t ->
            val w = 1 + rnd.nextInt(90)
            val h = 1 + rnd.nextInt(90)
            val g = Gray(w, h, IntArray(w * h) { rnd.nextInt(256) })
            val k = if (t % 3 == 2) randomKernel(rnd) else Cv.ellipse(1 + 2 * rnd.nextInt(6))
            val closed = morphGrayRef(morphGrayRef(g, k, true, 0, 255), k, false, 0, 255)
            val want = IntArray(w * h) { max(0, closed.data[it] - g.data[it]) }
            assertArrayEquals("blackhat #$t ${w}×$h 核 ${k.w}×${k.h}", want, Cv.blackhat(g, k).data)
        }
    }

    private fun edtRef(src: FloatArray, n: Int, dst: FloatArray, v: IntArray, z: FloatArray) {
        var k = 0
        v[0] = 0
        z[0] = -1e20f
        z[1] = 1e20f
        for (q in 1 until n) {
            var s: Float
            while (true) {
                val p = v[k]
                s = ((src[q] + q.toFloat() * q) - (src[p] + p.toFloat() * p)) / (2f * q - 2f * p)
                if (s <= z[k]) k-- else break
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = 1e20f
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val p = v[k]
            val dq = (q - p).toFloat()
            dst[q] = dq * dq + src[p]
        }
    }

    private fun distanceL2Ref(m: Mask): FloatArray {
        val w = m.w
        val h = m.h
        val f = FloatArray(w * h) { if (m.data[it]) 1e20f else 0f }
        val n = max(w, h)
        val tmp = FloatArray(n)
        val d = FloatArray(n)
        val v = IntArray(n)
        val z = FloatArray(n + 1)
        for (y in 0 until h) {
            System.arraycopy(f, y * w, tmp, 0, w)
            edtRef(tmp, w, d, v, z)
            System.arraycopy(d, 0, f, y * w, w)
        }
        for (x in 0 until w) {
            var i = x
            for (y in 0 until h) { tmp[y] = f[i]; i += w }
            edtRef(tmp, h, d, v, z)
            i = x
            for (y in 0 until h) { f[i] = d[y]; i += w }
        }
        for (i in f.indices) f[i] = kotlin.math.sqrt(f[i])
        return f
    }

    @Test
    fun distanceL2MatchesReference() {
        val rnd = Random(4242)
        val sizes = listOf(1 to 1, 1 to 33, 33 to 1, 15 to 16, 16 to 17, 17 to 50, 50 to 17, 100 to 64)
        repeat(160) { t ->
            val (w, h) = sizes[t % sizes.size]
            val m = randomMask(rnd, w, h)
            val want = distanceL2Ref(m)
            val got = Cv.distanceL2(m).data
            for (i in want.indices) {
                if (java.lang.Float.floatToRawIntBits(want[i]) != java.lang.Float.floatToRawIntBits(got[i])) {
                    throw AssertionError("distanceL2 #$t ${w}×$h 第 $i 格：${want[i]} ≠ ${got[i]}")
                }
            }
        }
    }
}
