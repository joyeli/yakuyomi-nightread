package li.joye.yakuyomi.nightread

import kotlin.math.max
import kotlin.math.min

/**
 * 線稿密度否決：有線稿的白不是留白。
 *
 * 判準＝真留白是空的，格內背景有窗格線／牆面陰影／網點。這是唯一能分開「頁邊白」與「有畫的
 * 背景剛好連到頁邊」的訊號——出血格的背景在像素層就是頁邊留白（demo01 第 1 格拱門），格框線
 * 切割動不了它。作法：對**要填的像素**量「同一格框區域內的非白比例」，夠高的整塊剔掉。
 *
 * 四條都是踩出來的，別動：
 *  ① 只在要填的像素外擴一個窗的範圍內算，不掃全頁；每個格框區域只在（區 ∩ 候選）的 bbox 內算。
 *  ② 離泡 [textureBubbleNear] 內的像素不算密度（泡輪廓會污染），但**跟著外圈走**：外圈被否決就
 *     一起否決、外圈留黑就一起留黑——否則背景變灰時那圈會浮成黑環。
 *  ③ 整塊決定，不逐像素：低門檻抓候選塊、塊平均密度過高門檻才否決、再閉合補洞——逐像素會在
 *     門檻附近斑掉。
 *  ④ 泡的輪廓線不算線稿、且泡當區域隔板：不然它會從泡外 25～40px 的窗裡被看到，把頁邊窄條判成
 *     「有畫」；泡壓在框線上又會把框線斷開一個泡那麼寬的缺口，頁邊條和格子內部連成同一區。
 */
internal object Texture {

    /** [exclude]＝不算線稿的像素（人物外灰圈收細拿人物遮罩重算一次，看哪些留白只因人物自己的墨被否決）。 */
    fun veto(fill: Mask, g: Gray, frame: Mask, seg: Mask, bubble: Mask, p: NightReadParams, exclude: Mask? = null): Mask {
        val prep = prepare(fill, g, frame, seg, bubble, p) ?: return fill
        return decide(prep, fill, g, p, exclude)
    }

    /**
     * [veto] 裡與 exclude 無關的部分（候選、ROI、格框／泡隔板的區域標號、各區的候選 bbox、未扣 exclude 的線稿）。
     * 留白帶同一個 fill 要否決兩次（一般、不算人物的墨）：準備一次、[decide] 兩次，結果與各自呼叫 [veto] 逐位元相同。
     * null＝不必否決（fill 空、候選空），[veto] 原樣回傳 fill（同一個物件：呼叫端用 === 判斷有沒有否決）。
     */
    class Prep internal constructor(
        val rx0: Int, val ry0: Int, val rw: Int, val rh: Int,
        val bubNear: Mask, val cands: Mask, val content: Mask, val cc: CC,
        val minX: IntArray, val minY: IntArray, val maxX: IntArray, val maxY: IntArray,
    )

    fun prepare(fill: Mask, g: Gray, frame: Mask, seg: Mask, bubble: Mask, p: NightReadParams): Prep? {
        if (!fill.any()) return null
        val w = g.w
        val h = g.h
        val k = p.textureWin
        val fr = if (frame.any()) dilateSquare(frame, p.textureFrameDil) else frame
        // 候選＝要填、且離泡夠遠
        val bubNear = if (bubble.any()) dilateSquare(bubble, p.textureBubbleNear) else Mask(w, h)
        val cand = fill.andNot(bubNear)
        if (!cand.any()) return null
        val bb = bbox(cand) ?: return null
        val rx0 = max(0, bb[0] - k)
        val ry0 = max(0, bb[1] - k)
        val rx1 = min(w, bb[2] + k + 1)
        val ry1 = min(h, bb[3] + k + 1)
        val rw = rx1 - rx0
        val rh = ry1 - ry0

        // ── 以下全在 ROI 內 ──
        val frs = cropMask(fr, rx0, ry0, rw, rh)
        val cands = cropMask(cand, rx0, ry0, rw, rh)
        val content = Mask(rw, rh)
        val cd = content.data
        val gd = g.data
        val whiteTh = p.whiteTh
        for (y in 0 until rh) {
            val src = (ry0 + y) * w + rx0
            val dst = y * rw
            for (x in 0 until rw) cd[dst + x] = gd[src + x] < whiteTh && !frs.data[dst + x]
        }
        if (seg.any()) {
            val segD = dilateSquare(cropMask(seg, rx0, ry0, rw, rh), p.textureSegDil)
            for (i in cd.indices) if (segD.data[i]) cd[i] = false
        }
        var barrier = frs
        if (bubble.any()) {
            val bubD = dilateSquare(cropMask(bubble, rx0, ry0, rw, rh), p.textureBubbleOutline * 2 + 1)
            for (i in cd.indices) if (bubD.data[i]) cd[i] = false
            barrier = frs or bubD
        }
        val cc = Cv.ccStats(barrier.not(), 8)

        // 每個格框區域只看「有候選像素」的、且只在（區 ∩ 候選）的 bbox + k 內算
        val minX = IntArray(cc.n) { Int.MAX_VALUE }
        val minY = IntArray(cc.n) { Int.MAX_VALUE }
        val maxX = IntArray(cc.n) { -1 }
        val maxY = IntArray(cc.n) { -1 }
        val labels = cc.labels
        for (y in 0 until rh) {
            val base = y * rw
            for (x in 0 until rw) {
                if (!cands.data[base + x]) continue
                val l = labels[base + x]
                if (x < minX[l]) minX[l] = x
                if (x > maxX[l]) maxX[l] = x
                if (y < minY[l]) minY[l] = y
                if (y > maxY[l]) maxY[l] = y
            }
        }
        return Prep(rx0, ry0, rw, rh, bubNear, cands, content, cc, minX, minY, maxX, maxY)
    }

    /** [prepare] 之後的判定（[exclude] 只扣在線稿上；[prep] 不被改寫，可以判定好幾次）。 */
    fun decide(prep: Prep, fill: Mask, g: Gray, p: NightReadParams, exclude: Mask? = null): Mask {
        val w = g.w
        val k = p.textureWin
        val rx0 = prep.rx0
        val ry0 = prep.ry0
        val rw = prep.rw
        val rh = prep.rh
        val cands = prep.cands
        val cc = prep.cc
        val content = prep.content
        // exclude（整頁座標）在累計時才扣：不複製一份 ROI 大小的線稿遮罩
        val excl = exclude?.data
        val minX = prep.minX
        val minY = prep.minY
        val maxX = prep.maxX
        val maxY = prep.maxY
        val labels = cc.labels
        val cdat = content.data
        val dens = FloatArray(rw * rh)
        val minArea = p.textureRegionMin * g.data.size
        val kk = (k * k).toFloat()
        val r = k / 2
        for (l in 1 until cc.n) {
            if (maxX[l] < 0 || cc.area[l] < minArea) continue
            val x0 = max(0, minX[l] - k)
            val y0 = max(0, minY[l] - k)
            val x1 = min(rw, maxX[l] + k + 1)
            val y1 = min(rh, maxY[l] + k + 1)
            val bw = x1 - x0
            val bh = y1 - y0
            // 零填充視窗和（＝cv2.boxFilter BORDER_CONSTANT）：直向用逐欄的滑動累計、橫向用前綴和，
            // 記憶體只要幾條 bw 長的陣列——積分影像要兩張 (bw+1)×(bh+1) 的 IntArray，整頁級的區域
            // 一次就是 14 MB，第一頁的 GC 全落在這。
            val colR = IntArray(bw)
            val colC = IntArray(bw)
            val preR = IntArray(bw + 1)
            val preC = IntArray(bw + 1)
            fun addRow(y: Int, sign: Int) {
                val src = (y0 + y) * rw + x0
                if (excl == null) {
                    for (x in 0 until bw) {
                        if (labels[src + x] == l) {
                            colR[x] += sign
                            if (cdat[src + x]) colC[x] += sign
                        }
                    }
                } else {
                    val pg = (ry0 + y0 + y) * w + rx0 + x0
                    for (x in 0 until bw) {
                        if (labels[src + x] == l) {
                            colR[x] += sign
                            if (cdat[src + x] && !excl[pg + x]) colC[x] += sign
                        }
                    }
                }
            }
            for (y in 0 until min(r + 1, bh)) addRow(y, 1)
            for (y in 0 until bh) {
                if (y > 0) {
                    if (y + r < bh) addRow(y + r, 1)
                    if (y - r - 1 >= 0) addRow(y - r - 1, -1)
                }
                for (x in 0 until bw) {
                    preR[x + 1] = preR[x] + colR[x]
                    preC[x + 1] = preC[x] + colC[x]
                }
                val src = (y0 + y) * rw + x0
                for (x in 0 until bw) {
                    if (labels[src + x] != l) continue
                    val xa = max(0, x - r)
                    val xb = min(bw - 1, x + r) + 1
                    val s2 = preR[xb] - preR[xa]
                    val s1 = preC[xb] - preC[xa]
                    // 照 Python 的算法走：num/k² ÷ max(den/k², 1e-3)，保持同樣的浮點路徑
                    val num = s1 / kk
                    val den = s2 / kk
                    dens[src + x] = num / max(den, 1e-3f)
                }
            }
        }

        val textureTh = p.textureTh
        var veto = Mask(rw, rh, BooleanArray(rw * rh) { cands.data[it] && dens[it] >= textureTh })
        if (veto.any()) {
            // 塊級決定：候選塊的平均密度過高門檻才否決。游程標號（[Cv.ccRuns]）：逐塊加總的順序同逐像素掃描（列序、列內 x 遞增），
            // 不配 ROI 大小的整頁標號（這裡是合成的記憶體峰值）
            val vr = Cv.ccRuns(veto, 8)
            val sum = DoubleArray(vr.n)
            val cnt = IntArray(vr.n)
            for (y in 0 until rh) {
                val base = y * rw
                for (k in vr.rowFirst[y] until vr.rowFirst[y + 1]) {
                    val l = vr.lab[k]
                    for (x in vr.rs[k]..vr.re[k]) {
                        sum[l] += dens[base + x].toDouble()
                        cnt[l]++
                    }
                }
            }
            val keep = BooleanArray(vr.n) { l ->
                l > 0 && cnt[l] >= p.textureMinArea && sum[l] / max(cnt[l], 1) >= p.textureHi
            }
            veto = Mask(rw, rh)
            val vd = veto.data
            for (y in 0 until rh) {
                val base = y * rw
                for (k in vr.rowFirst[y] until vr.rowFirst[y + 1]) {
                    if (keep[vr.lab[k]]) java.util.Arrays.fill(vd, base + vr.rs[k], base + vr.re[k] + 1, true)
                }
            }
            if (veto.any()) {
                veto = closeSquare(veto, p.textureClose * 2 + 1)
                for (i in veto.data.indices) if (!cands.data[i]) veto.data[i] = false
            }
        }
        if (veto.any()) {
            val bubNearR = cropMask(prep.bubNear, rx0, ry0, rw, rh)
            val fillR = cropMask(fill, rx0, ry0, rw, rh)
            veto = dilateSquare(veto, p.texturePad * 2 + 1)
            for (i in veto.data.indices) if (bubNearR.data[i]) veto.data[i] = false
            // 泡附近跟著外圈走
            val grown = dilateSquare(veto, p.textureBubbleNear * 2 + 1)
            for (i in veto.data.indices) {
                if (grown.data[i] && bubNearR.data[i] && fillR.data[i]) veto.data[i] = true
            }
        }
        val out = fill.copy()
        for (y in 0 until rh) {
            val src = y * rw
            val dst = (ry0 + y) * w + rx0
            for (x in 0 until rw) if (veto.data[src + x]) out.data[dst + x] = false
        }
        return out
    }

    /**
     * `cv2.dilate(m, np.ones((n, n)))`：方形核拆成橫線＋直線（Minkowski 分解，逐位元等價），走 lineMorph
     * 快路。補洞／外擴／跟隨三個核也刻意用方形——橢圓核要 O(W·H·kh)，這三處實測多 200 ms。
     */
    private fun dilateSquare(m: Mask, n: Int): Mask = if (n <= 1) m else Cv.dilateRectSep(m, n, n)

    /** `cv2.morphologyEx(m, MORPH_CLOSE, np.ones((n, n)))`。 */
    private fun closeSquare(m: Mask, n: Int): Mask = if (n <= 1) m else Cv.closeRectSep(m, n)

    private fun bbox(m: Mask): IntArray? {
        var x0 = m.w
        var y0 = m.h
        var x1 = -1
        var y1 = -1
        for (y in 0 until m.h) {
            val base = y * m.w
            for (x in 0 until m.w) {
                if (!m.data[base + x]) continue
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
    }

    private fun cropMask(m: Mask, x0: Int, y0: Int, cw: Int, ch: Int): Mask {
        val out = Mask(cw, ch)
        for (y in 0 until ch) System.arraycopy(m.data, (y0 + y) * m.w + x0, out.data, y * cw, cw)
        return out
    }

}
