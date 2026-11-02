package li.joye.yakuyomi.nightread

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 錄放的同 JVM 版：[Cv.tap] 回報的每一次呼叫，當場用 C++（[NightNativeBin]、[NightNativePrims]）拿同一份輸入重算、逐位元比。
 * CppCvCallReplayTest（CI 語料的每一次呼叫、Kotlin 隨機測試的每一次呼叫）與 CppBinParityTest 共用。運算名＝[Cv.tapped] 的第一個參數。
 */
internal object CvCallReplay {

    /** [Cv.tap] 會報、而且 C++ 已經有的運算（第 2 階＋第 3a 階）。 */
    val OPS: List<String> = listOf(
        "dilate", "erode", "open", "close", "dilatePacked", "erodePacked", "openPacked", "closePacked",
        "packBits", "packBitsNot", "unpackBits", "unpackBitsNot", "notBitsInPlace", "erodeBits", "dilateBits",
        "dilateRectSep", "closeRectSep", "ccStats", "ccRuns", "ccRunsBits",
        "distanceL2", "distanceSq", "distanceChamfer", "chamfer5Padded",
        "resizeArea", "resizeAreaOf", "resizeBilinear", "resizeNearest", "resizeNearestGray",
        "holes", "totalContourLength", "polygonArea", "geodesicDistance", "geodesicGrow", "medianBlurMask",
        "gaussQ16", "morphGrayGather", "ring.packBits",
    )

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
            is FloatArray -> "float[${x.size}]"
            else -> x.toString()
        }
    }

    /**
     * 裝上同 JVM 錄放的監聽，跑 [body]，結束時拆掉。[tag] 給失敗訊息。監聽裡丟的例外都記成失敗，不往外丟（呼叫端是 Kotlin 的管線）。
     */
    fun <T> run(stats: Stats, tag: String, body: () -> T): T {
        check(Cv.tap == null) { "Cv.tap 已經有人裝了" }
        Cv.tap = Cv.CallTap { op, args, r ->
            if (inTap.get() == true) return@CallTap
            inTap.set(true)
            try {
                val got = try {
                    native(op, args)
                } catch (e: Throwable) {
                    stats.fail("$tag $op(${describe(args)})：Kotlin 沒丟、C++ 丟了 $e")
                    return@CallTap
                }
                if (!same(normalize(r), got)) stats.fail("$tag $op(${describe(args)})：C++ 與 Kotlin 不同")
                stats.count(op)
            } finally {
                inTap.set(false)
            }
        }
        try {
            return body()
        } finally {
            Cv.tap = null
        }
    }
}
