package li.joye.yakuyomi.nightread.ort

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import li.joye.yakuyomi.nightread.Mask
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 人物語意遮罩的上機推論。
 *
 * 定案配方是 **cseg ∪ yoloseg** 的聯集：
 * - **yoloseg**（`manga_seg_s.onnx`，38.9MB）＝唯一在黑白漫畫上專門訓練過的像素級遮罩模型，
 *   YOLO11-seg，類別 frame / speech_bubble / character，只取 character。
 * - **cseg**（`cartoonseg.onnx`，228MB；int8 版 58MB）＝CartoonSegmentation 的 RTMDet-Ins，
 *   圖內已含 NMS，ORT 直接跑得動，不需要 mmdet。
 *
 * 只給 yoloseg 也能跑（省 228MB，代價是多幾框違規）；兩顆都給則取聯集。
 *
 * ⚠️ 模型一律用**檔案路徑**建 session，不要先 `readBytes()` 進 JVM heap——每個 app 的 heap
 * 上限約 512MB 與實體記憶體無關，228MB 的模型讀進去就 OOM。
 */
class CharMaskOrt(
    yolosegPath: String?,
    csegPath: String? = null,
    private val yolosegSize: Int = 1024,
    private val csegSize: Int = 640,
    private val yolosegConf: Float = 0.25f,
    private val csegScore: Float = 0.3f,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val yoloseg: OrtSession? = yolosegPath?.let { env.createSession(it, options()) }
    private val cseg: OrtSession? = csegPath?.let { env.createSession(it, options()) }

    init {
        require(yoloseg != null || cseg != null) { "至少要給一顆模型" }
    }

    private fun options() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
    }

    /**
     * 算一頁的人物遮罩。
     *
     * @param rgb `Bitmap.getPixels` 那種 ARGB int 陣列（長度 w*h）
     * @return 與輸入同尺寸的遮罩，true＝人物。這是**模型原輸出**，貼墨收邊與平滑由管線負責。
     */
    fun detect(rgb: IntArray, w: Int, h: Int): Mask {
        val out = Mask(w, h)
        yoloseg?.let { runYoloSeg(it, rgb, w, h, out) }
        cseg?.let { runCseg(it, rgb, w, h, out) }
        return out
    }

    override fun close() {
        yoloseg?.close()
        cseg?.close()
    }

    // ── yoloseg（YOLO11-seg）────────────────────────────────────────

    /**
     * ultralytics 的 letterbox（等比縮放 + **置中** pad 114）、輸入 RGB/255、NCHW。
     *
     * 輸出 `output0[1, 4+nc+32, N]`（cx,cy,w,h + 類別分數 + 32 個 mask 係數）與
     * `output1[1, 32, mh, mw]`（prototypes）。後處理＝篩分數 → NMS → sigmoid(係數·protos)
     * → 裁到 bbox → 去 letterbox → 還原尺寸。
     */
    private fun runYoloSeg(session: OrtSession, rgb: IntArray, w: Int, h: Int, out: Mask) {
        val size = yolosegSize
        val r = min(size.toDouble() / h, size.toDouble() / w)
        val nh = (h * r).roundToInt()
        val nw = (w * r).roundToInt()
        val top = (size - nh) / 2
        val left = (size - nw) / 2

        val input = FloatArray(3 * size * size) { 114f / 255f }
        val plane = size * size
        for (y in 0 until nh) {
            val sy = min(h - 1, (y / r).toInt())
            for (x in 0 until nw) {
                val sx = min(w - 1, (x / r).toInt())
                val px = rgb[sy * w + sx]
                val idx = (top + y) * size + (left + x)
                input[idx] = ((px shr 16) and 0xFF) / 255f              // R
                input[plane + idx] = ((px shr 8) and 0xFF) / 255f       // G
                input[2 * plane + idx] = (px and 0xFF) / 255f           // B
            }
        }

        val name = session.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, size.toLong(), size.toLong()))
            .use { tensor ->
                session.run(mapOf(name to tensor)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val o0 = (res[0].value as Array<Array<FloatArray>>)[0]   // [4+nc+32, N]
                    @Suppress("UNCHECKED_CAST")
                    val o1 = (res[1].value as Array<Array<Array<FloatArray>>>)[0]  // [32, mh, mw]

                    val chans = o0.size
                    val n = o0[0].size
                    val nProto = o1.size
                    val nc = chans - 4 - nProto
                    val mh = o1[0].size
                    val mw = o1[0][0].size

                    // 篩：只要 character 類（索引 2）且分數過門檻
                    val picks = ArrayList<Int>()
                    val scores = ArrayList<Float>()
                    for (i in 0 until n) {
                        var best = 0
                        var bestS = -1f
                        for (c in 0 until nc) {
                            val s = o0[4 + c][i]
                            if (s > bestS) { bestS = s; best = c }
                        }
                        if (best == CHAR_CLASS && bestS > yolosegConf) { picks.add(i); scores.add(bestS) }
                    }
                    if (picks.isEmpty()) return

                    val boxes = picks.map {
                        floatArrayOf(o0[0][it], o0[1][it], o0[2][it], o0[3][it])  // cx,cy,w,h
                    }
                    val keep = nms(boxes, scores, IOU_TH)

                    val protoFlat = FloatArray(nProto * mh * mw)
                    for (c in 0 until nProto) {
                        for (y in 0 until mh) {
                            System.arraycopy(o1[c][y], 0, protoFlat, (c * mh + y) * mw, mw)
                        }
                    }

                    for (ki in keep) {
                        val src = picks[ki]
                        val box = boxes[ki]
                        // sigmoid(係數 · prototypes)
                        val m = FloatArray(mh * mw)
                        for (c in 0 until nProto) {
                            val coeff = o0[4 + nc + c][src]
                            if (coeff == 0f) continue
                            val base = c * mh * mw
                            for (j in 0 until mh * mw) m[j] += coeff * protoFlat[base + j]
                        }
                        // 裁到 bbox（ultralytics 的 crop_mask）
                        val x1 = ((box[0] - box[2] / 2) * mw / size).toInt().coerceIn(0, mw)
                        val x2 = ceil(((box[0] + box[2] / 2) * mw / size).toDouble()).toInt().coerceIn(0, mw)
                        val y1 = ((box[1] - box[3] / 2) * mh / size).toInt().coerceIn(0, mh)
                        val y2 = ceil(((box[1] + box[3] / 2) * mh / size).toDouble()).toInt().coerceIn(0, mh)

                        // prototype 座標 → 原圖座標，一步到位（省掉中間的 1024×1024 緩衝）
                        for (oy in 0 until h) {
                            val ly = top + oy * r                     // letterbox 座標
                            val py = (ly * mh / size)
                            val pyi = py.toInt()
                            if (pyi !in y1 until max(y1 + 1, y2)) continue
                            for (ox in 0 until w) {
                                val lx = left + ox * r
                                val pxi = (lx * mw / size).toInt()
                                if (pxi !in x1 until max(x1 + 1, x2)) continue
                                val v = m[pyi * mw + pxi]
                                if (1f / (1f + exp(-v)) > 0.5f) out.data[oy * w + ox] = true
                            }
                        }
                    }
                }
            }
    }

    // ── cseg（RTMDet-Ins，圖內含 NMS）─────────────────────────────────

    /**
     * mmdet 慣例的前處理：等比縮放後 **pad 右下角**（不是置中），BGR 減均值除標準差。
     *
     * ⚠️ 輸入尺寸固定 640：那是訓練解析度，也是實測最佳。照抄 DBNet 的 1024 會讓臉覆蓋率
     * 從 98.3% 掉到 89.6%。
     *
     * ⚠️ 遮罩輸出是**機率**不是 logit，門檻 0.5。用 >0 會整頁前景。
     */
    private fun runCseg(session: OrtSession, rgb: IntArray, w: Int, h: Int, out: Mask) {
        val size = csegSize
        val s = size.toDouble() / max(h, w)
        val nh = (h * s).roundToInt()
        val nw = (w * s).roundToInt()

        val input = FloatArray(3 * size * size)
        val plane = size * size
        // 未覆蓋處是 pad 值 114，同樣要正規化
        for (c in 0 until 3) {
            val v = ((114f - MEAN_BGR[c]) / STD_BGR[c])
            java.util.Arrays.fill(input, c * plane, (c + 1) * plane, v)
        }
        for (y in 0 until nh) {
            val sy = min(h - 1, (y / s).toInt())
            for (x in 0 until nw) {
                val sx = min(w - 1, (x / s).toInt())
                val px = rgb[sy * w + sx]
                val b = (px and 0xFF).toFloat()
                val g = ((px shr 8) and 0xFF).toFloat()
                val rr = ((px shr 16) and 0xFF).toFloat()
                val idx = y * size + x
                input[idx] = (b - MEAN_BGR[0]) / STD_BGR[0]
                input[plane + idx] = (g - MEAN_BGR[1]) / STD_BGR[1]
                input[2 * plane + idx] = (rr - MEAN_BGR[2]) / STD_BGR[2]
            }
        }

        val name = session.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, size.toLong(), size.toLong()))
            .use { tensor ->
                session.run(mapOf(name to tensor)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val dets = (res[0].value as Array<Array<FloatArray>>)[0]          // [N, 5]
                    @Suppress("UNCHECKED_CAST")
                    val masks = (res[2].value as Array<Array<Array<FloatArray>>>)[0]  // [N, size, size]
                    for (i in dets.indices) {
                        if (dets[i][4] <= csegScore) continue
                        val m = masks[i]
                        for (oy in 0 until h) {
                            val my = min(nh - 1, (oy * s).toInt())
                            val row = m[my]
                            val base = oy * w
                            for (ox in 0 until w) {
                                val mx = min(nw - 1, (ox * s).toInt())
                                if (row[mx] > 0.5f) out.data[base + ox] = true
                            }
                        }
                    }
                }
            }
    }

    // ── 工具 ─────────────────────────────────────────────────────────

    /** 標準 NMS（輸入是 cx,cy,w,h）。回傳保留的索引。 */
    private fun nms(boxes: List<FloatArray>, scores: List<Float>, iouTh: Float): List<Int> {
        val order = scores.indices.sortedByDescending { scores[it] }
        val keep = ArrayList<Int>()
        val dead = BooleanArray(boxes.size)
        for (i in order) {
            if (dead[i]) continue
            keep.add(i)
            for (j in order) {
                if (j == i || dead[j]) continue
                if (iou(boxes[i], boxes[j]) > iouTh) dead[j] = true
            }
        }
        return keep
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ax1 = a[0] - a[2] / 2; val ay1 = a[1] - a[3] / 2
        val ax2 = a[0] + a[2] / 2; val ay2 = a[1] + a[3] / 2
        val bx1 = b[0] - b[2] / 2; val by1 = b[1] - b[3] / 2
        val bx2 = b[0] + b[2] / 2; val by2 = b[1] + b[3] / 2
        val iw = max(0f, min(ax2, bx2) - max(ax1, bx1))
        val ih = max(0f, min(ay2, by2) - max(ay1, by1))
        val inter = iw * ih
        val union = a[2] * a[3] + b[2] * b[3] - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        private const val CHAR_CLASS = 2      // yoloseg：0=frame 1=speech_bubble 2=character
        private const val IOU_TH = 0.45f
        private val MEAN_BGR = floatArrayOf(103.53f, 116.28f, 123.675f)
        private val STD_BGR = floatArrayOf(57.375f, 57.12f, 58.395f)
    }
}
