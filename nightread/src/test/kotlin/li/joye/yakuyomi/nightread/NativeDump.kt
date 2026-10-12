package li.joye.yakuyomi.nightread

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * [NativeDumpTest] 的工具：NRD 容器的寫入、成分摘要的正規編碼、NRIN 頁輸入的讀寫。格式定義寫進每份錄出的 README.txt
 *（[README]），C++ 端的讀取器在 native/test/nrd.h、檢查與重放工具 nr_dump_check（native/test/dump_check.cpp）。
 */
internal object NativeDump {

    // ── NRD 容器 ────────────────────────────────────────────────────────

    const val T_U8 = 1
    const val T_I32 = 2
    const val T_I64 = 3
    const val T_F32 = 4
    const val T_F64 = 5
    const val T_UTF8 = 6

    /** 一筆紀錄的一個欄位：型別、維度、資料（小端位元組）。 */
    class Entry(val name: String, val type: Int, val dims: IntArray, val data: ByteArray)

    /** 一筆紀錄（依加入順序寫出）。 */
    class Rec {
        val entries = ArrayList<Entry>()
        fun str(name: String, v: String) = apply { val b = v.toByteArray(Charsets.UTF_8); entries += Entry(name, T_UTF8, intArrayOf(b.size), b) }
        fun u8(name: String, v: ByteArray, vararg dims: Int) = apply { entries += Entry(name, T_U8, dimsOf(dims, v.size), v) }
        fun bools(name: String, v: BooleanArray, vararg dims: Int) =
            u8(name, ByteArray(v.size) { if (v[it]) 1 else 0 }, *dims)
        fun i32(name: String, v: IntArray, vararg dims: Int) = apply {
            val bb = le(v.size * 4); bb.asIntBuffer().put(v); entries += Entry(name, T_I32, dimsOf(dims, v.size), bb.array())
        }
        fun i64(name: String, v: LongArray, vararg dims: Int) = apply {
            val bb = le(v.size * 8); bb.asLongBuffer().put(v); entries += Entry(name, T_I64, dimsOf(dims, v.size), bb.array())
        }
        fun f32(name: String, v: FloatArray, vararg dims: Int) = apply {
            val bb = le(v.size * 4); for (x in v) bb.putInt(x.toRawBits()); entries += Entry(name, T_F32, dimsOf(dims, v.size), bb.array())
        }
        fun f64(name: String, v: DoubleArray, vararg dims: Int) = apply {
            val bb = le(v.size * 8); for (x in v) bb.putLong(x.toRawBits()); entries += Entry(name, T_F64, dimsOf(dims, v.size), bb.array())
        }
        fun mask(name: String, m: Mask) = bools(name, m.data, m.h, m.w)
        fun gray(name: String, g: Gray) = i32(name, g.data, g.h, g.w)
        fun fimg(name: String, f: FImg) = f32(name, f.data, f.h, f.w)
        fun int(name: String, v: Int) = i32(name, intArrayOf(v))
        fun dbl(name: String, v: Double) = f64(name, doubleArrayOf(v))

        private fun dimsOf(dims: IntArray, n: Int): IntArray {
            val d = if (dims.isEmpty()) intArrayOf(n) else dims
            var p = 1L
            for (x in d) p *= x
            require(p == n.toLong()) { "NRD 欄位維度 ${d.joinToString("x")} 與元素數 $n 不合" }
            return d
        }
    }

    private fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    /** 寫一個 .nrd 檔：魔數 "NRD1"，接著一筆筆紀錄。 */
    class Writer(file: File) : Closeable {
        private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16))
        var records = 0
            private set

        init {
            file.parentFile?.mkdirs()
            out.write("NRD1".toByteArray(Charsets.US_ASCII))
        }

        fun write(r: Rec) {
            out.write(le(4).putInt(r.entries.size).array())
            for (e in r.entries) {
                val nb = e.name.toByteArray(Charsets.UTF_8)
                require(nb.size < 65536)
                out.write(le(2).putShort(nb.size.toShort()).array())
                out.write(nb)
                out.write(e.type)
                out.write(e.dims.size)
                val db = le(4 * e.dims.size)
                for (d in e.dims) db.putInt(d)
                out.write(db.array())
                out.write(e.data)
            }
            records++
        }

        override fun close() = out.close()
    }

    // ── 成分摘要（正規編碼的 SHA-256，取前 8 位元組）────────────────────────────

    /** 正規編碼器：見 [README] 的「成分摘要」。 */
    class Enc {
        val md: MessageDigest = MessageDigest.getInstance("SHA-256")
        private val b8 = le(8)
        fun tag(c: Char) = md.update(c.code.toByte())
        fun i32(v: Int) { b8.clear(); b8.putInt(v); md.update(b8.array(), 0, 4) }
        fun i64(v: Long) { b8.clear(); b8.putLong(v); md.update(b8.array(), 0, 8) }
        fun str(s: String) { val b = s.toByteArray(Charsets.UTF_8); i32(b.size); md.update(b) }
        fun ints(a: IntArray) {
            val bb = le(minOf(a.size, 1 shl 16) * 4)
            var k = 0
            while (k < a.size) {
                val n = minOf(1 shl 16, a.size - k)
                bb.clear(); bb.asIntBuffer().put(a, k, n); md.update(bb.array(), 0, n * 4)
                k += n
            }
        }
        fun hex16(): String = md.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    fun hash(o: Any?): String = Enc().also { feed(it, o, 0) }.hex16()

    /** 葉成分：遮罩、影像、陣列、純量、字串、enum、Int 集合。其他物件（含 List／Map）不算葉。 */
    fun isLeaf(o: Any?): Boolean = o == null || o is Mask || o is Gray || o is FImg || o is IntArray || o is LongArray ||
        o is BooleanArray || o is FloatArray || o is DoubleArray || o is ByteArray || o is Number || o is Boolean ||
        o is String || o is Enum<*> || (o is Set<*> && o.all { it is Int })

    fun feed(e: Enc, o: Any?, depth: Int) {
        require(depth < 16) { "成分摘要遞迴太深" }
        when (o) {
            null -> e.tag('N')
            is Mask -> { e.tag('M'); e.i32(o.w); e.i32(o.h); e.md.update(ByteArray(o.data.size) { if (o.data[it]) 1 else 0 }) }
            is Gray -> { e.tag('G'); e.i32(o.w); e.i32(o.h); e.ints(o.data) }
            is FImg -> { e.tag('F'); e.i32(o.w); e.i32(o.h); e.ints(IntArray(o.data.size) { o.data[it].toRawBits() }) }
            is IntArray -> { e.tag('I'); e.i32(o.size); e.ints(o) }
            is LongArray -> { e.tag('J'); e.i32(o.size); for (v in o) e.i64(v) }
            is BooleanArray -> { e.tag('Z'); e.i32(o.size); e.md.update(ByteArray(o.size) { if (o[it]) 1 else 0 }) }
            is FloatArray -> { e.tag('f'); e.i32(o.size); e.ints(IntArray(o.size) { o[it].toRawBits() }) }
            is DoubleArray -> { e.tag('D'); e.i32(o.size); for (v in o) e.i64(v.toRawBits()) }
            is ByteArray -> { e.tag('B'); e.i32(o.size); e.md.update(o) }
            is Int -> { e.tag('i'); e.i32(o) }
            is Long -> { e.tag('l'); e.i64(o) }
            is Short -> { e.tag('i'); e.i32(o.toInt()) }
            is Byte -> { e.tag('i'); e.i32(o.toInt()) }
            is Boolean -> { e.tag('b'); e.md.update(if (o) 1 else 0) }
            is Float -> { e.tag('r'); e.i32(o.toRawBits()) }
            is Double -> { e.tag('d'); e.i64(o.toRawBits()) }
            is String -> { e.tag('s'); e.str(o) }
            is Enum<*> -> { e.tag('e'); e.str(o.name) }
            is Set<*> -> if (o.all { it is Int }) {
                e.tag('S'); e.i32(o.size); e.ints(o.map { it as Int }.sorted().toIntArray())
            } else {
                e.tag('T'); e.i32(o.size)
                for (h in o.map { hash(it) }.sorted()) e.str(h)
            }
            is Map<*, *> -> {
                val keys = o.keys.map { it.toString() to it }.sortedBy { it.first }
                e.tag('H'); e.i32(keys.size)
                for ((ks, k) in keys) { e.str(ks); feed(e, o[k], depth + 1) }
            }
            is Pair<*, *> -> { e.tag('L'); e.i32(2); feed(e, o.first, depth + 1); feed(e, o.second, depth + 1) }
            is Triple<*, *, *> -> { e.tag('L'); e.i32(3); feed(e, o.first, depth + 1); feed(e, o.second, depth + 1); feed(e, o.third, depth + 1) }
            is Collection<*> -> { e.tag('L'); e.i32(o.size); for (x in o) feed(e, x, depth + 1) }
            is Array<*> -> { e.tag('L'); e.i32(o.size); for (x in o) feed(e, x, depth + 1) }
            is Function<*> -> e.tag('Q')
            else -> {
                val fs = fieldsOf(o)
                e.tag('O'); e.str(o.javaClass.simpleName); e.i32(fs.size)
                for ((n, v) in fs) { e.str(n); feed(e, v, depth + 1) }
            }
        }
    }

    /** 物件的欄位（名稱排序；略過 static、合成欄、lazy 委派）。 */
    fun fieldsOf(o: Any): List<Pair<String, Any?>> {
        val out = ArrayList<Pair<String, Any?>>()
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.isSynthetic || f.name.endsWith("\$delegate")) continue
                f.isAccessible = true
                out += f.name to f.get(o)
            }
            c = c.superclass
        }
        return out.sortedBy { it.first }
    }

    /**
     * 把 [o] 拆成成分列（[sink]：名稱, 值）：葉直接一列；物件與 Map 往下拆 [split] 層（欄位名、鍵名以 . 連接），再深的整個
     * 當一列（深度摘要）。List 不拆（整串一列）。
     */
    fun components(prefix: String, o: Any?, split: Int, sink: (String, Any?) -> Unit) {
        when {
            isLeaf(o) || split <= 0 || o is Collection<*> || o is Array<*> || o is Pair<*, *> || o is Triple<*, *, *> -> sink(prefix, o)
            o is Map<*, *> -> for (k in o.keys.sortedBy { it.toString() }) components("$prefix.$k", o[k], split - 1, sink)
            else -> for ((n, v) in fieldsOf(o!!)) components("$prefix.$n", v, split - 1, sink)
        }
    }

    /** 葉成分寫成 NRD 的一筆原始資料；不是葉（或不支援的型別）回 null。 */
    fun rawRecord(name: String, o: Any?): Rec? {
        val r = Rec().str("name", name)
        when (o) {
            is Mask -> r.str("kind", "mask").mask("data", o)
            is Gray -> r.str("kind", "gray").gray("data", o)
            is FImg -> r.str("kind", "fimg").fimg("data", o)
            is IntArray -> r.str("kind", "ints").i32("data", o)
            is LongArray -> r.str("kind", "longs").i64("data", o)
            is BooleanArray -> r.str("kind", "bools").bools("data", o)
            is FloatArray -> r.str("kind", "floats").f32("data", o)
            is DoubleArray -> r.str("kind", "doubles").f64("data", o)
            is Int -> r.str("kind", "int").int("data", o)
            is Boolean -> r.str("kind", "bool").int("data", if (o) 1 else 0)
            is Double -> r.str("kind", "double").dbl("data", o)
            is Set<*> -> if (o.all { it is Int }) r.str("kind", "set").i32("data", o.map { it as Int }.sorted().toIntArray()) else return null
            else -> return null
        }
        return r
    }

    // ── NRIN（頁輸入；cpp-eval 的 Ident 工具同一格式）────────────────────────────

    /**
     * NRIN：魔數 "NRIN"、w、h、字框數（int32 小端），接著 gray、chroma、seg、charMask 各 w·h 位元組，再字框 (x0, y0, x1, y1)×n
     *（int32）。去字遮罩另存同名 .inp.bin（w·h 位元組，0／1）。
     */
    fun readNrin(f: File): NightReadInput {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "NRIN") { "不是 NRIN：$f" }
        val w = b.int
        val h = b.int
        val nr = b.int
        val n = w * h
        fun bytes() = ByteArray(n).also { b.get(it) }
        val gray = bytes()
        val chroma = bytes()
        val seg = bytes()
        val chr = bytes()
        val regions = List(nr) { TextRegion(b.int, b.int, b.int, b.int) }
        val inp = File(f.parentFile, f.name.removeSuffix(".bin") + ".inp.bin").takeIf { it.exists() }?.let { ff ->
            val m = ff.readBytes()
            require(m.size == n) { "去字遮罩尺寸不合：$ff" }
            Mask(w, h, BooleanArray(n) { m[it].toInt() != 0 })
        }
        return NightReadInput(
            gray = Gray(w, h, IntArray(n) { gray[it].toInt() and 0xff }),
            seg = Mask(w, h, BooleanArray(n) { seg[it].toInt() != 0 }),
            regions = regions,
            charMask = Mask(w, h, BooleanArray(n) { chr[it].toInt() != 0 }),
            chroma = Gray(w, h, IntArray(n) { chroma[it].toInt() and 0xff }),
            inpaintMask = inp,
        )
    }

    fun writeNrin(input: NightReadInput, f: File) {
        val g = input.gray
        val n = g.w * g.h
        val chroma = requireNotNull(input.chroma) { "NRIN 一定要有彩度" }
        for (v in g.data) require(v in 0..255)
        for (v in chroma.data) require(v in 0..255)
        val bb = le(16 + 4 * n + 16 * input.regions.size)
        bb.put("NRIN".toByteArray(Charsets.US_ASCII)).putInt(g.w).putInt(g.h).putInt(input.regions.size)
        for (v in g.data) bb.put(v.toByte())
        for (v in chroma.data) bb.put(v.toByte())
        for (v in input.seg.data) bb.put(if (v) 1 else 0)
        for (v in input.charMask.data) bb.put(if (v) 1 else 0)
        for (r in input.regions) bb.putInt(r.x0).putInt(r.y0).putInt(r.x1).putInt(r.y1)
        f.parentFile?.mkdirs()
        f.writeBytes(bb.array())
        input.inpaintMask?.let { m ->
            File(f.parentFile, f.name.removeSuffix(".bin") + ".inp.bin").writeBytes(ByteArray(m.data.size) { if (m.data[it]) 1 else 0 })
        }
    }

    /** 錄出目錄的 README.txt。 */
    val README = """
        |夜讀 C++ 移植的錄出（nightread/src/test/kotlin/li/joye/yakuyomi/nightread/NativeDumpTest.kt 產生）
        |
        |prims/<族>.nrd       原語的隨機與邊界案例：每筆紀錄＝op（運算名）、case（案例說明）、輸入、參數、輸出；Kotlin 丟例外時
        |                     沒有輸出、改記 throws（例外類別名）。prims/index.tsv 列每族的筆數。
        |pages/index.tsv      錄了哪些頁（set、頁名、寬、高、manifest 路徑）。
        |pages/<set>/<頁>/manifest.tsv
        |                     一頁的清單：輸入與參數的摘要，以及每種跑法的每個成分的摘要（第一欄跑法、第二欄成分、第三欄摘要）。
        |pages/<set>/<頁>/input.bin（＋input.inp.bin）   有選 input 時：頁輸入（NRIN）。
        |pages/<set>/<頁>/params.nrd                     有選 params 時：每種跑法用的參數（攤平：ints、doubles、schema）。
        |pages/<set>/<頁>/raw.nrd                        有選其他原始資料時：每筆＝name（跑法.成分）、kind、data。
        |
        |NRD 容器（小端）：魔數 "NRD1"，接著一筆筆紀錄到檔尾。紀錄＝u32 欄位數，每個欄位＝u16 名稱長、名稱（UTF-8）、u8 型別、
        |u8 維數、維數×i32 維度、資料（元素數＝維度乘積）。型別：1 u8、2 i32、3 i64、4 f32（原始位元）、5 f64（原始位元）、
        |6 UTF-8 字串（一維，長度＝位元組數）。影像的維度是 [高, 寬]，列優先。
        |
        |成分摘要＝下面正規編碼的 SHA-256 的前 8 位元組（16 個十六進位字）。整數一律小端：
        |  null 'N'；Mask 'M' w h ＋ w·h 個位元組 0／1；Gray 'G' w h ＋ w·h 個 i32；FImg 'F' w h ＋ w·h 個 f32 原始位元；
        |  IntArray 'I' n ＋ i32；LongArray 'J' n ＋ i64；BooleanArray 'Z' n ＋ 位元組；FloatArray 'f' n ＋ f32 位元；
        |  DoubleArray 'D' n ＋ f64 位元；Int 'i' i32；Long 'l' i64；Boolean 'b' 一個位元組；Float 'r' 位元；Double 'd' 位元；
        |  字串 's' 長度 ＋ UTF-8；enum 'e' 名稱字串；Int 集合 'S' n ＋ 排序後的 i32；其他集合 'T' n ＋ 各元素摘要（排序）；
        |  Map 'H' n ＋（鍵字串、值）依鍵排序；List／Array／Pair／Triple 'L' n ＋ 元素；其他物件 'O' 類別簡名 欄位數 ＋（欄位名、值）
        |  依名稱排序（略過 static、合成欄、lazy 委派；這一型只給 Kotlin 端自己比，C++ 比葉成分）。
        |字串與名稱的長度是 i32。
        |
        |跑法：analysis（分析，標準檔參數）、tiers:def／tiers:fork（產品兩檔，預設／fork 預設亮度）、plain:<檔>（單檔 render 的
        |結果遮罩）、diag:<檔>（加 diag 的中間結果）。檔＝full、L1、L2、L3old、MORE。
        |""".trimMargin()
}
