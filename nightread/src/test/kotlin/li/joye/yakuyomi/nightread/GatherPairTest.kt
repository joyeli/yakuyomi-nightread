package li.joye.yakuyomi.nightread

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

/**
 * [Cv.morphGrayGather] 上下對稱核列合成一趟（2026-10-07 加速）對改寫前（2f98ce4，下面照抄）逐值相同：橢圓、方核、隨機核（含空列、
 * 不對稱、偶數尺寸、只有一半對稱的）；頁含 1×1、單列、單欄與比核小的頁。
 */
class GatherPairTest {

        private fun gatherOld(g: Gray, k: Kernel, wantMax: Boolean): Gray {
            val w = g.w
            val h = g.h
            val id = if (wantMax) Int.MIN_VALUE else Int.MAX_VALUE
            // 不同的 run 寬度（由小到大）與每個核列的寬度索引、偏移
            val kh = k.h
            val winOf = IntArray(kh)
            val offOf = IntArray(kh)
            val widths = ArrayList<Int>()
            var pad = 0
            for (ky in 0 until kh) {
                val win = k.runEnd[ky] - k.runStart[ky]
                winOf[ky] = win
                if (win <= 0) continue
                val off = k.runStart[ky] - k.ax
                offOf[ky] = off
                if (win !in widths) widths.add(win)
                pad = kotlin.math.max(pad, kotlin.math.max(kotlin.math.abs(off), kotlin.math.abs(off + win - 1)))
            }
            widths.sort()
            val nwid = widths.size
            val wv = IntArray(nwid) { widths[it] }
            val widIdx = IntArray(kh) { if (winOf[it] > 0) wv.indexOf(winOf[it]) else -1 }
            val pl = w + 2 * pad
            // 環形快取：每槽每種寬度一條「窗起點極值」陣列（長 pl；起點 p 的窗要整個在 [0, pl) 才有定義，取值只用得到那些）
            val cache = Array(kh) { Array(nwid) { IntArray(pl) } }
            val cacheRow = IntArray(kh) { -1 }
            val tmp = IntArray(pl)
            fun rowExt(sy: Int): Array<IntArray> {
                val slot = sy % kh
                val dst = cache[slot]
                if (cacheRow[slot] == sy) return dst
                // 寬度 1＝補了單位元的來源列本身
                val base = tmp
                java.util.Arrays.fill(base, id)
                System.arraycopy(g.data, sy * w, base, pad, w)
                var cur = base
                var c = 1
                for (q in 0 until nwid) {
                    val target = wv[q]
                    val out = dst[q]
                    if (c == target && cur === base) {
                        System.arraycopy(base, 0, out, 0, pl)
                        cur = out
                        continue
                    }
                    while (c < target) {
                        val st = kotlin.math.min(target - c, c)
                        val lim = pl - st
                        if (wantMax) {
                            for (p in 0 until lim) { val a = cur[p]; val b = cur[p + st]; out[p] = if (a > b) a else b }
                        } else {
                            for (p in 0 until lim) { val a = cur[p]; val b = cur[p + st]; out[p] = if (a < b) a else b }
                        }
                        cur = out
                        c += st
                    }
                }
                cacheRow[slot] = sy
                return dst
            }
            val out = IntArray(w * h)
            val cur = IntArray(w)
            for (y in 0 until h) {
                java.util.Arrays.fill(cur, id)
                for (ky in 0 until kh) {
                    val r = widIdx[ky]
                    if (r < 0) continue
                    val sy = y + (ky - k.ay)
                    if (sy < 0 || sy >= h) continue
                    val src = rowExt(sy)[r]
                    val o = pad + offOf[ky]
                    if (wantMax) { for (x in 0 until w) { val v = src[x + o]; if (v > cur[x]) cur[x] = v } }
                    else { for (x in 0 until w) { val v = src[x + o]; if (v < cur[x]) cur[x] = v } }
                }
                System.arraycopy(cur, 0, out, y * w, w)
            }
            return Gray(w, h, out)
        }

    @Test
    fun gatherPairsMatchOld() {
        val rnd = Random(20261007)
        repeat(400) { t ->
            val w = 1 + rnd.nextInt(90)
            val h = 1 + rnd.nextInt(60)
            val g = Gray(w, h, IntArray(w * h) { if (t % 2 == 0) rnd.nextInt(256) * 65536 else rnd.nextInt(-5_000_000, 17_000_000) })
            val k = when (t % 4) {
                0 -> Cv.ellipse(1 + 2 * rnd.nextInt(8))
                1 -> Cv.rect(1 + rnd.nextInt(9), 1 + rnd.nextInt(9))
                2 -> {
                    // 上下對稱、每列任意 run
                    val kw = 1 + rnd.nextInt(9)
                    val kh = 1 + rnd.nextInt(9)
                    val d = BooleanArray(kw * kh)
                    for (y in 0 until (kh + 1) / 2) {
                        if (rnd.nextInt(5) == 0) continue
                        val a = rnd.nextInt(kw)
                        val b = a + rnd.nextInt(kw - a)
                        for (x in a..b) { d[y * kw + x] = true; d[(kh - 1 - y) * kw + x] = true }
                    }
                    Kernel(kw, kh, d)
                }
                else -> {
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
            for (mx in booleanArrayOf(true, false)) {
                assertArrayEquals("gather #$t ${w}×$h 核 ${k.w}×${k.h} max=$mx", gatherOld(g, k, mx).data, Cv.morphGrayGather(g, k, mx).data)
            }
        }
    }
}
