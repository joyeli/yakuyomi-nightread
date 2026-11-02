package li.joye.yakuyomi.nightread

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 錄放的同 JVM 版：[Cv.tap] 回報的每一次呼叫，當場用 C++（[NightNativeBin]、[NightNativePrims]、[NightNativeGray]）拿同一份輸入重算、
 * 逐位元比。CppCvCallReplayTest（CI 語料的每一次呼叫、Kotlin 隨機測試的每一次呼叫）、CppBinParityTest、CppGrayParityTest 共用。
 * 運算名＝[Cv.tapped] 的第一個參數；逐點的 gaussAt、medianRows 先經 [CallBatcher] 併成 "gaussAt[]"、"medianRows[]" 再重算。
 */
internal object CvCallReplay {

    /** [Cv.tap] 會報的運算（第 3b 階起是 Cv 的全部公開原語；C++ 都有）。 */
    val OPS: List<String> = listOf(
        "dilate", "erode", "open", "close", "dilatePacked", "erodePacked", "openPacked", "closePacked",
        "packBits", "packBitsNot", "unpackBits", "unpackBitsNot", "notBitsInPlace", "erodeBits", "dilateBits",
        "dilateRectSep", "closeRectSep", "ccStats", "ccRuns", "ccRunsBits",
        "distanceL2", "distanceSq", "distanceChamfer", "chamfer5Padded",
        "resizeArea", "resizeAreaOf", "resizeBilinear", "resizeNearest", "resizeNearestGray",
        "holes", "totalContourLength", "polygonArea", "geodesicDistance", "geodesicGrow", "medianBlurMask",
        "gaussQ16", "morphGrayGather", "ring.packBits",
        // 第 3b 階
        "dilateGray", "erodeGray", "dilateGrayI", "erodeGrayI", "blackhat", "gaussianBlur", "boxBlur", "line", "fillPoly",
        "maxFilterWrapRows", "gaussW", "npArgsort", "npSum", "eigh2", "gaussQ16Sparse", "medianRows", "medianBlur8", "canny", "gaussAt",
    )

    /** [CallBatcher] 併好的逐點運算（下游看到的名字）。 */
    val BATCHED_OPS: List<String> = listOf("gaussAt[]", "medianRows[]")

    /** C++ 重算一次（輸入＝[Cv.tap] 回報的參數），結果的型別與 [normalize] 之後的 Kotlin 結果相同。 */
    fun native(op: String, a: Array<Any?>): Any? {
        fun m(i: Int) = a[i] as Mask
        fun k(i: Int) = a[i] as Kernel
        fun i(i: Int) = a[i] as Int
        fun l(i: Int) = a[i] as LongArray
        return when (op) {
            "dilate" -> NightNativeBin.dilate(m(0), k(1), i(2)).value
            "erode" -> NightNativeBin.erode(m(0), k(1), i(2)).value
            "open" -> NightNativeBin.open(m(0), k(1)).value
            "close" -> NightNativeBin.close(m(0), k(1)).value
            "dilatePacked" -> NightNativePrims.dilatePacked(m(0), k(1)).value
            "erodePacked" -> NightNativeBin.erodePacked(m(0), k(1)).value
            "openPacked" -> NightNativeBin.openPacked(m(0), k(1)).value
            "closePacked" -> NightNativeBin.closePacked(m(0), k(1)).value
            "packBits" -> NightNativeBin.packBits(m(0)).value
            "packBitsNot" -> NightNativeBin.packBitsNot(m(0)).value
            "unpackBits" -> NightNativeBin.unpackBits(l(0), i(1), i(2)).value
            "unpackBitsNot" -> NightNativeBin.unpackBitsNot(l(0), i(1), i(2)).value
            "notBitsInPlace" -> NightNativeBin.notBits(l(0), i(1), i(2)).value
            "erodeBits" -> NightNativeBin.erodeBits(l(0), i(1), i(2), k(3)).value
            "dilateBits" -> NightNativePrims.dilateBits(l(0), i(1), i(2), k(3)).value
            "dilateRectSep" -> NightNativeBin.dilateRectSep(m(0), i(1), i(2)).value
            "closeRectSep" -> NightNativeBin.closeRectSep(m(0), i(1)).value
            "ccStats" -> NightNativePrims.ccStats(m(0), i(1)).value
            "ccRuns" -> NightNativePrims.ccRuns(m(0), i(1)).value
            "ccRunsBits" -> NightNativeBin.ccRunsBits(l(0), i(1), i(2), i(3)).value
            "distanceL2" -> NightNativePrims.distanceL2(m(0)).value
            "distanceSq" -> NightNativeBin.distanceSq(m(0)).value
            "distanceChamfer" -> NightNativeBin.distanceChamfer(m(0), i(1)).value
            "chamfer5Padded" -> NightNativeBin.chamfer5Padded(m(0)).value
            "resizeArea" -> NightNativeBin.resizeArea(a[0] as FImg, i(1), i(2)).value
            "resizeAreaOf" -> NightNativeBin.resizeAreaOf(a[0] as FImg, i(1), i(2)).value
            "resizeBilinear" -> NightNativeBin.resizeBilinear(a[0] as FImg, i(1), i(2)).value
            "resizeNearest" -> NightNativeBin.resizeNearest(m(0), i(1), i(2)).value
            "resizeNearestGray" -> NightNativeBin.resizeNearestGray(a[0] as Gray, i(1), i(2)).value
            "holes" -> NightNativeBin.holes(m(0)).value
            "totalContourLength" -> NightNativeBin.totalContourLength(m(0)).value
            "polygonArea" -> NightNativeBin.polygonArea(a[0] as FloatArray, a[1] as FloatArray)
            "geodesicDistance" -> NightNativeBin.geodesicDistance(m(0), m(1), i(2), i(3)).value
            "geodesicGrow" -> NightNativeBin.geodesicGrow(m(0), m(1), i(2), i(3), a[4] as Boolean?).value
            "medianBlurMask" -> NightNativeBin.medianBlurMask(m(0), i(1)).value
            "gaussQ16" -> NightNativePrims.gaussQ16(a[0] as Gray, a[1] as Double).value
            "morphGrayGather" -> NightNativePrims.morphGrayGather(a[0] as Gray, k(1), a[2] as Boolean).value
            "ring.packBits" -> NightNativeBin.ringPackBits(m(0)).value
            // 第 3b 階
            "dilateGray" -> NightNativeGray.dilateGray(a[0] as Gray, k(1)).value
            "erodeGray" -> NightNativeGray.erodeGray(a[0] as Gray, k(1)).value
            "dilateGrayI" -> NightNativeGray.dilateGrayI(a[0] as Gray, k(1)).value
            "erodeGrayI" -> NightNativeGray.erodeGrayI(a[0] as Gray, k(1)).value
            "blackhat" -> NightNativeGray.blackhat(a[0] as Gray, k(1)).value
            "gaussianBlur" -> NightNativeGray.gaussianBlur(a[0] as FImg, a[1] as Double).value
            "boxBlur" -> NightNativeGray.boxBlur(a[0] as FImg, i(1)).value
            "line" -> NightNativeGray.line(m(0), i(1), i(2), i(3), i(4), i(5)).value
            "fillPoly" -> NightNativeGray.fillPoly(m(0), a[1] as IntArray, a[2] as IntArray).value
            "maxFilterWrapRows" -> NightNativeGray.maxFilterWrapRows(a[0] as IntArray, i(1), i(2), i(3), i(4)).value
            "gaussW" -> NightNativeGray.gaussW(a[0] as Double)
            "npArgsort" -> NightNativeGray.npArgsort(a[0] as IntArray)
            "npSum" -> NightNativeGray.npSum(a[0] as DoubleArray, i(1), i(2))
            "eigh2" -> NightNativeGray.eigh2(a[0] as Double, a[1] as Double, a[2] as Double)
            "gaussQ16Sparse" -> {
                val src = a[6] as Gray? ?: error("gaussQ16Sparse：來源不是 Cv.GrayRows／Cv.MedianRows，沒辦法重算")
                NightNativeGray.gaussQ16Sparse(i(0), i(1), a[2] as IntArray, l(3), i(4), i(5), src, i(7)).value
            }
            "medianRows" -> NightNativeGray.medianRows(a[0] as Gray, i(1), i(2), a[3] as IntArray, a[4] as IntArray)
            "medianRows[]" -> NightNativeGray.medianRowsMany(a[0] as Gray, i(1), a[2] as IntArray).value
            "medianBlur8" -> NightNativeGray.medianBlur8(a[0] as Gray, i(1)).value
            "canny" -> NightNativeGray.canny(a[0] as IntArray, i(1), i(2), i(3), i(4), i(5)).value
            "gaussAt" -> NightNativeGray.gaussAt(a[0] as Gray, a[1] as IntArray, i(2), i(3))
            "gaussAt[]" -> NightNativeGray.gaussAtMany(a[0] as Gray, a[1] as IntArray, a[2] as IntArray, a[3] as IntArray).value
            else -> error("CvCallReplay：不認得的運算 $op（新加了 tap 卻沒加重放？）")
        }
    }

    /** Kotlin 的結果轉成跟 [native] 同一種型別（Cv.CcRuns 是模組內部的型別）。 */
    fun normalize(r: Any?): Any? = when (r) {
        is Cv.CcRuns -> NightNativePrims.CcRunsResult(r.n, r.rs, r.re, r.rowFirst, r.lab, r.fg, r.bgLeft, r.bgTop, r.bgRight, r.bgBottom)
        else -> r
    }

    /** 逐位元相同（Float／Double 比原始位元）。 */
    fun same(a: Any?, b: Any?): Boolean = when {
        a == null || b == null -> a == null && b == null
        a is LongArray && b is LongArray -> a.contentEquals(b)
        a is IntArray && b is IntArray -> a.contentEquals(b)
        a is Double && b is Double -> a.toRawBits() == b.toRawBits()
        a is Int && b is Int -> a == b
        a is DoubleArray && b is DoubleArray -> a.size == b.size && a.indices.all { a[it].toRawBits() == b[it].toRawBits() }
        else -> NightPrimWorkload.same(a, b)
    }

    /** 一輪錄放的統計：每個運算比了幾次、不同的清單（最多記 50 筆）。 */
    class Stats {
        val counts = ConcurrentHashMap<String, AtomicLong>()
        val failures = ConcurrentLinkedQueue<String>()
        private val nFail = AtomicLong()
        fun count(op: String) { counts.computeIfAbsent(op) { AtomicLong() }.incrementAndGet() }
        fun fail(msg: String) { if (nFail.incrementAndGet() <= 50) failures += msg }
        val failed: Long get() = nFail.get()
        val total: Long get() = counts.values.sumOf { it.get() }
        fun summary(): String = counts.entries.sortedBy { it.key }.joinToString("、") { "${it.key} ${it.value.get()}" }
    }

    private val inTap = ThreadLocal.withInitial { false }

    /** 參數的簡述（尺寸與純量），給失敗訊息用。 */
    fun describe(args: Array<Any?>): String = args.joinToString(", ") { x ->
        when (x) {
            is Mask -> "Mask ${x.w}x${x.h}"
            is Gray -> "Gray ${x.w}x${x.h}"
            is FImg -> "FImg ${x.w}x${x.h}"
            is Kernel -> "Kernel ${x.w}x${x.h}"
            is LongArray -> "long[${x.size}]"
            is IntArray -> "int[${x.size}]"
            is DoubleArray -> "double[${x.size}]"
            is FloatArray -> "float[${x.size}]"
            else -> x.toString()
        }
    }

    /** C++ 重算一次（逐點運算是併好的一批）、比位元，不同就記一筆失敗。 */
    private fun replayOne(stats: Stats, tag: String, op: String, args: Array<Any?>, r: Any?) {
        val got = try {
            native(op, args)
        } catch (e: Throwable) {
            stats.fail("$tag $op(${describe(args)})：Kotlin 沒丟、C++ 丟了 $e")
            return
        }
        if (same(normalize(r), got)) return
        if (r is IntArray && got is IntArray && r.size == got.size) {
            val i = r.indices.first { r[it] != got[it] }
            stats.fail("$tag $op(${describe(args)})：C++ 與 Kotlin 不同（第 $i 個：Kotlin ${r[i]}、C++ ${got[i]}）")
        } else {
            stats.fail("$tag $op(${describe(args)})：C++ 與 Kotlin 不同")
        }
    }

    /**
     * 裝上同 JVM 錄放的監聽，跑 [body]，結束時拆掉。[tag] 給失敗訊息。監聽裡丟的例外都記成失敗，不往外丟（呼叫端是 Kotlin 的管線）。
     * [Stats.counts] 數的是 Cv 回報的呼叫（逐點的每一點各算一次）；重算在呼叫當下做，逐點的整批在換了輸入、來了別的運算或結束時做。
     */
    fun <T> run(stats: Stats, tag: String, body: () -> T): T {
        check(Cv.tap == null) { "Cv.tap 已經有人裝了" }
        val batcher = CallBatcher { op, args, r -> replayOne(stats, tag, op, args, r) }
        Cv.tap = Cv.CallTap { op, args, r ->
            if (inTap.get() == true) return@CallTap
            inTap.set(true)
            try {
                stats.count(op)
                batcher.call(op, args, r)
            } finally {
                inTap.set(false)
            }
        }
        try {
            return body()
        } finally {
            Cv.tap = null
            batcher.flushAll()
        }
    }
}

/**
 * 錄放用：把同一條執行緒上連續、同一份輸入的逐點呼叫併成一筆，其他運算原樣往下傳（傳之前先把這條執行緒累積的送出）。
 *  - gaussAt（同一張灰階、同一個核物件）→ "gaussAt[]"：參數 (g, k, xs, ys)、結果＝每點的值。
 *  - medianRows（同一張灰階、同一個 ksize）→ "medianRows[]"：參數 (g, ksize, spec)、結果＝各次各段的值串起來；spec＝每次 r、n、n 個 rs、
 *    n 個 re（同 [NightNativeGray.medianRowsMany]）。
 * 為什麼：逐點的呼叫一頁幾千次，每次的輸入都是整頁灰階，逐次給 C++ 重算（每次複製整頁）或逐次存檔（每次算整頁的摘要）都太慢。
 * 整批送出時用的是那時的灰階內容：中間被改過的話 C++ 會算出不同的值、記成失敗（不會把不同的當成相同）。
 * 頁內並行時每條執行緒各自累積；body 跑完由呼叫端 [flushAll]。
 */
internal class CallBatcher(private val down: (String, Array<Any?>, Any?) -> Unit) {

    private class IntBuf {
        var a = IntArray(64)
        var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
        fun addAll(v: IntArray) { for (x in v) add(x) }
        fun toArray(): IntArray = a.copyOf(n)
        fun clear() { n = 0 }
    }

    private class Pending {
        var op: String? = null
        var g: Gray? = null
        var key: Any? = null
        var ks = 0
        var count = 0
        val a = IntBuf()
        val b = IntBuf()
        val r = IntBuf()
    }

    private val all = java.util.concurrent.ConcurrentLinkedQueue<Pending>()
    private val local: ThreadLocal<Pending> = ThreadLocal.withInitial { Pending().also { all += it } }

    fun call(op: String, args: Array<Any?>, r: Any?) {
        val p: Pending = local.get()!!
        synchronized(p) {
            when (op) {
                "gaussAt" -> {
                    val g = args[0] as Gray
                    val k = args[1] as IntArray
                    if (p.op != op || p.g !== g || p.key !== k || p.count >= LIMIT) { flush(p); p.op = op; p.g = g; p.key = k }
                    p.a.add(args[2] as Int); p.b.add(args[3] as Int); p.r.add(r as Int); p.count++
                }
                "medianRows" -> {
                    val g = args[0] as Gray
                    val ks = args[1] as Int
                    if (p.op != op || p.g !== g || p.ks != ks || p.count >= LIMIT) { flush(p); p.op = op; p.g = g; p.ks = ks }
                    val rs = args[3] as IntArray
                    val re = args[4] as IntArray
                    p.a.add(args[2] as Int); p.a.add(rs.size); p.a.addAll(rs); p.a.addAll(re)
                    p.r.addAll(r as IntArray); p.count++
                }
                else -> {
                    flush(p)
                    down(op, args, r)
                }
            }
        }
    }

    private fun flush(p: Pending) {
        when (p.op ?: return) {
            "gaussAt" -> down("gaussAt[]", arrayOf(p.g, p.key, p.a.toArray(), p.b.toArray()), p.r.toArray())
            "medianRows" -> down("medianRows[]", arrayOf(p.g, p.ks, p.a.toArray()), p.r.toArray())
        }
        p.op = null; p.g = null; p.key = null; p.ks = 0; p.count = 0
        p.a.clear(); p.b.clear(); p.r.clear()
    }

    /** 把每條執行緒累積的都送出（body 跑完、監聽拆掉之後呼叫）。 */
    fun flushAll() { for (p in all) synchronized(p) { flush(p) } }

    companion object {
        /** 一批最多幾次（記憶體上限）。 */
        const val LIMIT = 1 shl 16
    }
}
