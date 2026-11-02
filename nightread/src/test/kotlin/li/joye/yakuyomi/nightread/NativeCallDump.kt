package li.joye.yakuyomi.nightread

import li.joye.yakuyomi.nightread.NativeDump.Rec
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Deflater

/**
 * 錄放（錄到磁碟的版本，[NativeDumpTest] 的 calls/）：[Cv.tap] 回報的每一次呼叫，把輸入與輸出的摘要寫進 calls/<來源>.nrd，給
 * nr_dump_check 在主機、qemu-aarch64、ASan／UBSan 下重放（同 JVM 的版本是 [CvCallReplay]）。
 *
 * 一筆紀錄是 `blob`（一份輸入）或 `call`（一次呼叫），依出現順序：
 *  - blob：`blob`＝編號（i32，從 0 連續）、`type`（mask／kernel／longs／ints／floats／doubles／fimg／gray）、`dims`（i32；影像與核是
 *    [高, 寬]，陣列是 [長度]）、`raw`（i64，解壓後的位元組數）、`z`（u8，zlib 壓縮的資料）。資料：mask＝Ring.packBits 的整頁位元（i64 小端，
 *    (n+63)/64 個）、kernel＝每格一個位元組 0／1、longs／ints／floats／doubles／fimg／gray＝小端原樣（浮點是原始位元）。同內容只存一次。
 *  - call：`op`（[Cv.tapped] 的運算名；逐點的 gaussAt、medianRows 經 [CallBatcher] 併成 "gaussAt[]"、"medianRows[]"）、依序每個參數一個
 *    欄位——`ref`（i32，blob 編號）、`int`（i32）、`dbl`（f64）、`bool`（i32 0／1）、`null`（長度 0 的 i32）——最後 `hash`＝Kotlin 結果的
 *    成分摘要（[NativeDump.hash]；CC 與 CcRuns 用原語步驟表的串法）。同一個運算、同一份輸入的呼叫只錄第一次。
 * 寫入是同步的（頁內並行時多條執行緒會同時報）；監聽裡不再呼叫 Cv（自己打包遮罩），免得報回自己。
 */
internal class NativeCallDump(file: File) : Closeable {

    private val w = NativeDump.Writer(file)
    private val blobs = HashMap<String, Int>()
    private val seen = HashSet<String>()
    private val deflater = Deflater(6)
    private val buf = ByteArray(1 shl 16)

    /** Cv 回報的呼叫次數（含重複；逐點的每一點各算一次）、錄下的（不重複）紀錄數、blob 數、壓縮後的 blob 位元組數。 */
    private val reported = java.util.concurrent.atomic.AtomicLong()
    val calls: Long get() = reported.get()
    var unique = 0
        private set
    val blobCount: Int get() = blobs.size
    var blobBytes = 0L
        private set
    val ops = HashMap<String, Int>()

    private fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    /** 參數的 blob 型別、維度與原始位元組；不是陣列型的回 null。 */
    private fun raw(a: Any?): Triple<String, IntArray, ByteArray>? = when (a) {
        is Mask -> {
            val n = a.data.size
            val bb = le(((n + 63) ushr 6) * 8)
            var i = 0
            while (i < n) {
                var v = 0L
                val e = minOf(64, n - i)
                for (t in 0 until e) if (a.data[i + t]) v = v or (1L shl t)
                bb.putLong(v)
                i += 64
            }
            Triple("mask", intArrayOf(a.h, a.w), bb.array())
        }
        is Kernel -> Triple("kernel", intArrayOf(a.h, a.w), ByteArray(a.data.size) { if (a.data[it]) 1 else 0 })
        is LongArray -> Triple("longs", intArrayOf(a.size), le(a.size * 8).also { it.asLongBuffer().put(a) }.array())
        is IntArray -> Triple("ints", intArrayOf(a.size), le(a.size * 4).also { it.asIntBuffer().put(a) }.array())
        is FloatArray -> Triple("floats", intArrayOf(a.size), le(a.size * 4).also { b -> for (x in a) b.putInt(x.toRawBits()) }.array())
        is DoubleArray -> Triple("doubles", intArrayOf(a.size), le(a.size * 8).also { b -> for (x in a) b.putLong(x.toRawBits()) }.array())
        is FImg -> Triple("fimg", intArrayOf(a.h, a.w), le(a.data.size * 4).also { b -> for (x in a.data) b.putInt(x.toRawBits()) }.array())
        is Gray -> Triple("gray", intArrayOf(a.h, a.w), le(a.data.size * 4).also { it.asIntBuffer().put(a.data) }.array())
        else -> null
    }

    private fun deflate(b: ByteArray): ByteArray {
        deflater.reset()
        deflater.setInput(b)
        deflater.finish()
        val out = ByteArrayOutputStream(maxOf(64, b.size / 8))
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 這份輸入的 blob 編號（新的就寫一筆 blob）。 */
    private fun blob(type: String, dims: IntArray, bytes: ByteArray): Int {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(type.toByteArray())
        for (d in dims) md.update(le(4).putInt(d).array())
        md.update(bytes)
        val key = md.digest().joinToString("") { "%02x".format(it) }
        blobs[key]?.let { return it }
        val id = blobs.size
        blobs[key] = id
        val z = deflate(bytes)
        blobBytes += z.size
        w.write(Rec().int("blob", id).str("type", type).i32("dims", dims).i64("raw", longArrayOf(bytes.size.toLong())).u8("z", z))
        return id
    }

    /** 結果的成分摘要（nr_dump_check 的 hashCc／hashRuns 同一種串法）。 */
    private fun hashOf(r: Any?): String = when (r) {
        is CC -> NativeDump.hash(listOf(intArrayOf(r.n), r.labels, r.left, r.top, r.width, r.height, r.area))
        is Cv.CcRuns -> NativeDump.hash(listOf(intArrayOf(r.n, r.bgLeft, r.bgTop, r.bgRight, r.bgBottom), longArrayOf(r.fg), r.rs, r.re, r.rowFirst, r.lab))
        else -> NativeDump.hash(r)
    }

    @Synchronized
    fun record(op: String, args: Array<Any?>, result: Any?) {
        val parts = ArrayList<Pair<String, Any?>>(args.size)
        val key = StringBuilder(op)
        for (a in args) {
            val rw = raw(a)
            when {
                rw != null -> { val id = blob(rw.first, rw.second, rw.third); parts += "ref" to id; key.append("|r").append(id) }
                a is Int -> { parts += "int" to a; key.append("|i").append(a) }
                a is Double -> { parts += "dbl" to a; key.append("|d").append(a.toRawBits()) }
                a is Boolean -> { parts += "bool" to a; key.append("|b").append(a) }
                a == null -> { parts += "null" to null; key.append("|n") }
                else -> error("NativeCallDump：$op 的參數型別 ${a.javaClass} 不支援")
            }
        }
        if (!seen.add(key.toString())) return
        val r = Rec().str("op", op)
        for ((n, v) in parts) when (n) {
            "ref" -> r.int("ref", v as Int)
            "int" -> r.int("int", v as Int)
            "dbl" -> r.dbl("dbl", v as Double)
            "bool" -> r.int("bool", if (v as Boolean) 1 else 0)
            else -> r.i32("null", IntArray(0))
        }
        r.str("hash", hashOf(result))
        w.write(r)
        unique++
        ops[op] = (ops[op] ?: 0) + 1
    }

    override fun close() {
        w.close()
        deflater.end()
    }

    companion object {
        private val inTap = ThreadLocal.withInitial { false }

        /**
         * 裝上監聽、跑 [body]、把每一次呼叫（[skip] 裡的運算除外；名字是 Cv 回報的、併批之前的）錄進 [file]。回傳錄出器（已關閉）給
         * index 用。
         */
        fun <T> record(file: File, skip: Set<String> = emptySet(), body: () -> T): NativeCallDump {
            val d = NativeCallDump(file)
            val batcher = CallBatcher { op, args, r -> d.record(op, args, r) }
            check(Cv.tap == null) { "Cv.tap 已經有人裝了" }
            Cv.tap = Cv.CallTap { op, args, r ->
                if (inTap.get() == true) return@CallTap
                inTap.set(true)
                try {
                    if (op !in skip) {
                        d.reported.incrementAndGet()
                        batcher.call(op, args, r)
                    }
                } finally {
                    inTap.set(false)
                }
            }
            try {
                body()
            } finally {
                Cv.tap = null
                try {
                    batcher.flushAll()
                } finally {
                    d.close()
                }
            }
            return d
        }
    }
}
