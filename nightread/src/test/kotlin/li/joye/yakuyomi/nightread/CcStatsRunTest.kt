package li.joye.yakuyomi.nightread

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 游程版 [Cv.ccStats]（2026-10-06）對原本逐像素 union-find 版（[ref]，下面照抄當時 Cv.kt 的程式）逐值相同：
 * 標號（首見掃描序）、元件數、含背景 0 號的 bbox 與面積，4／8 連通都比。
 */
class CcStatsRunTest {

    private fun ref(m: Mask, connectivity: Int): CC {
        val w = m.w
        val h = m.h
        val labels = IntArray(w * h)
        val parent = IntArray(w * h / 2 + 2)
        var next = 1
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[max(ra, rb)] = min(ra, rb)
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!m.data[i]) continue
                var best = 0
                if (x > 0 && labels[i - 1] != 0) best = labels[i - 1]
                if (y > 0 && labels[i - w] != 0) {
                    best = if (best == 0) labels[i - w] else { union(best, labels[i - w]); min(best, labels[i - w]) }
                }
                if (connectivity == 8) {
                    if (x > 0 && y > 0 && labels[i - w - 1] != 0) {
                        best = if (best == 0) labels[i - w - 1] else { union(best, labels[i - w - 1]); min(best, labels[i - w - 1]) }
                    }
                    if (x < w - 1 && y > 0 && labels[i - w + 1] != 0) {
                        best = if (best == 0) labels[i - w + 1] else { union(best, labels[i - w + 1]); min(best, labels[i - w + 1]) }
                    }
                }
                if (best == 0) {
                    parent[next] = next
                    best = next
                    next++
                }
                labels[i] = best
            }
        }
        val remap = IntArray(next)
        var n = 1
        for (i in labels.indices) {
            if (labels[i] == 0) continue
            val root = find(labels[i])
            if (remap[root] == 0) { remap[root] = n; n++ }
            labels[i] = remap[root]
        }
        val left = IntArray(n) { Int.MAX_VALUE }
        val top = IntArray(n) { Int.MAX_VALUE }
        val right = IntArray(n) { Int.MIN_VALUE }
        val bottom = IntArray(n) { Int.MIN_VALUE }
        val area = IntArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val l = labels[y * w + x]
                area[l]++
                if (x < left[l]) left[l] = x
                if (y < top[l]) top[l] = y
                if (x > right[l]) right[l] = x
                if (y > bottom[l]) bottom[l] = y
            }
        }
        val width = IntArray(n)
        val height = IntArray(n)
        for (i in 0 until n) {
            if (left[i] == Int.MAX_VALUE) { left[i] = 0; top[i] = 0 }
            width[i] = right[i] - left[i] + 1
            height[i] = bottom[i] - top[i] + 1
            if (width[i] < 0) width[i] = 0
            if (height[i] < 0) height[i] = 0
        }
        return CC(n, labels, left, top, width, height, area)
    }

    private fun assertSame(tag: String, want: CC, got: CC) {
        assertEquals("$tag n", want.n, got.n)
        assertArrayEquals("$tag labels", want.labels, got.labels)
        assertArrayEquals("$tag left", want.left, got.left)
        assertArrayEquals("$tag top", want.top, got.top)
        assertArrayEquals("$tag width", want.width, got.width)
        assertArrayEquals("$tag height", want.height, got.height)
        assertArrayEquals("$tag area", want.area, got.area)
    }

    @Test
    fun runLabelingMatchesPixelUnionFind() {
        val rnd = Random(1006)
        val sizes = listOf(1 to 1, 1 to 9, 9 to 1, 2 to 2, 17 to 13, 64 to 64, 65 to 31, 200 to 7, 7 to 200, 128 to 96)
        repeat(800) { t ->
            val (w, h) = sizes[t % sizes.size]
            val m = Mask(w, h)
            when (t % 5) {
                0 -> for (i in m.data.indices) m.data[i] = rnd.nextInt(10) < 4            // 稀疏雜點
                1 -> for (i in m.data.indices) m.data[i] = rnd.nextInt(10) < 6            // 稠密
                2 -> for (y in 0 until h) for (x in 0 until w) m.data[y * w + x] = (x + y) % 2 == 0   // 棋盤（只有斜角相連）
                3 -> m.data.fill(true)                                                     // 沒有背景
                else -> repeat(1 + rnd.nextInt(8)) {                                       // 塊＋U 形
                    val x0 = rnd.nextInt(w)
                    val y0 = rnd.nextInt(h)
                    val x1 = min(w, x0 + 1 + rnd.nextInt(w))
                    val y1 = min(h, y0 + 1 + rnd.nextInt(h))
                    for (y in y0 until y1) for (x in x0 until x1) {
                        if (rnd.nextInt(3) > 0 || y == y1 - 1 || x == x0 || x == x1 - 1) m.data[y * w + x] = true
                    }
                }
            }
            for (conn in intArrayOf(4, 8)) assertSame("#$t ${w}×$h conn=$conn", ref(m, conn), Cv.ccStats(m, conn))
        }
        // 全空
        val e = Mask(5, 4)
        for (conn in intArrayOf(4, 8)) assertSame("empty conn=$conn", ref(e, conn), Cv.ccStats(e, conn))
    }
}
