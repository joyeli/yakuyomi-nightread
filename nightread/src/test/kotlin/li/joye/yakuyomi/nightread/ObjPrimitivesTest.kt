package li.joye.yakuyomi.nightread

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 背景物件規則的三個原語（2026-10-06 改寫：[Cv.gaussQ16] 核在外迴圈、[Cv.medianBlur8] 中位數跟著窗走、[Cv.morphGrayGather]
 * 窗寬倍增）對改寫前的程式（下面照抄）逐值相同。
 */
class ObjPrimitivesTest {

    private fun reflect(i: Int, n: Int): Int {
        if (n == 1) return 0
        var x = i
        while (x < 0 || x >= n) {
            if (x < 0) x = -x
            if (x >= n) x = 2 * (n - 1) - x
        }
        return x
    }

    private fun gaussRef(g: Gray, sigma: Double): IntArray {
        val w = g.w
        val h = g.h
        val k = Cv.gaussW(sigma)
        val c = k.size / 2
        val src = g.data
        val hor = Array(h) { r ->
            val line = IntArray(w + 2 * c) { src[r * w + reflect(it - c, w)] }
            IntArray(w) { x ->
                val m = x + c
                var acc = k[c] * line[m]
                for (j in 1..c) acc += k[c + j] * (line[m - j] + line[m + j])
                acc
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var acc = k[c].toLong() * hor[y][x]
            for (j in 1..c) acc += k[c + j].toLong() * (hor[reflect(y - j, h)][x] + hor[reflect(y + j, h)][x])
            out[y * w + x] = ((acc + 32768L) shr 16).toInt()
        }
        return out
    }

    private fun medianRef(g: Gray, ksize: Int): IntArray {
        val w = g.w
        val h = g.h
        val r = ksize / 2
        val out = IntArray(w * h)
        val win = IntArray(ksize * ksize)
        for (y in 0 until h) for (x in 0 until w) {
            var n = 0
            for (dy in -r..r) for (dx in -r..r) win[n++] = g.data[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
            win.sort()
            out[y * w + x] = win[ksize * ksize / 2]
        }
        return out
    }

    /** 改寫前的 morphGrayGather：每種 (偏移, 寬) 各做一次 van Herk。 */
    private fun gatherRef(g: Gray, k: Kernel, wantMax: Boolean): IntArray {
        val w = g.w
        val h = g.h
        val id = if (wantMax) Int.MIN_VALUE else Int.MAX_VALUE
        var pad = 0
        for (ky in 0 until k.h) {
            val win = k.runEnd[ky] - k.runStart[ky]
            if (win <= 0) continue
            val off = k.runStart[ky] - k.ax
            pad = max(pad, max(abs(off), abs(off + win - 1)))
        }
        val out = IntArray(w * h) { id }
        for (y in 0 until h) for (x in 0 until w) {
            var acc = id
            for (ky in 0 until k.h) {
                val win = k.runEnd[ky] - k.runStart[ky]
                if (win <= 0) continue
                val sy = y + (ky - k.ay)
                if (sy < 0 || sy >= h) continue
                val off = k.runStart[ky] - k.ax
                for (xx in x + off until x + off + win) {
                    if (xx < 0 || xx >= w) continue
                    val v = g.data[sy * w + xx]
                    acc = if (wantMax) max(acc, v) else min(acc, v)
                }
            }
            out[y * w + x] = acc
        }
        return out
    }

    @Test
    fun gaussQ16MatchesReference() {
        val rnd = Random(5150)
        for ((w, h) in listOf(1 to 1, 1 to 40, 40 to 1, 7 to 9, 64 to 33, 120 to 80)) {
            val g = Gray(w, h, IntArray(w * h) { rnd.nextInt(256) })
            for (sigma in doubleArrayOf(1.5, 2.0, 2.5, 3.0, 4.0, 5.0)) {
                assertArrayEquals("gaussQ16 ${w}×$h σ$sigma", gaussRef(g, sigma), Cv.gaussQ16(g, sigma).data)
            }
        }
    }

    @Test
    fun medianBlur8MatchesSort() {
        val rnd = Random(77)
        for ((w, h) in listOf(1 to 1, 1 to 30, 30 to 1, 5 to 5, 61 to 47)) {
            for (kind in 0 until 3) {
                val g = Gray(w, h, IntArray(w * h) {
                    when (kind) { 0 -> rnd.nextInt(256); 1 -> if (rnd.nextInt(4) == 0) 255 else rnd.nextInt(3); else -> (it % w) * 255 / max(1, w - 1) }
                })
                for (ks in intArrayOf(3, 5, 7)) assertArrayEquals("median ${w}×$h k$ks kind$kind", medianRef(g, ks), Cv.medianBlur8(g, ks).data)
            }
        }
    }

    @Test
    fun morphGrayGatherMatchesReference() {
        val rnd = Random(1984)
        repeat(200) { t ->
            val w = 1 + rnd.nextInt(70)
            val h = 1 + rnd.nextInt(50)
            val g = Gray(w, h, IntArray(w * h) { rnd.nextInt(-5_000_000, 17_000_000) })
            val k = when (t % 3) {
                0 -> Cv.ellipse(1 + 2 * rnd.nextInt(8))
                1 -> Cv.rect(1 + rnd.nextInt(9), 1 + rnd.nextInt(9))
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
                assertArrayEquals("gather #$t ${w}×$h 核 ${k.w}×${k.h} max=$mx", gatherRef(g, k, mx), Cv.morphGrayGather(g, k, mx).data)
            }
        }
    }
}
