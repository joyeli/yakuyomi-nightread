package li.joye.yakuyomi.nightread

import java.lang.reflect.Modifier

/**
 * 參數攤平測試（[ParamsFlatTest]、[CppParamsParityTest]）共用：用 Java 反射逐欄讀 [NightReadParams]（不經 [ParamsFlat]，
 * 才能獨立驗證產生器寫的槽位），以及每個欄位各不相同的測試值。
 */
internal object ParamsTestSupport {

    /** 一個欄位的值，與 C++ `nr::params::Value` 同形：Int／Bool／Enum／OptInt 在 [i]，Double／Float 在 [d]（Float 轉成 double）。 */
    data class PV(val i: Long, val d: Double, val present: Boolean) {
        /** Double 比原始位元（±0 要分得出來），NaN 只看兩邊都是 NaN（Java 不規定運算出來的 NaN 位元）。 */
        fun same(o: PV): Boolean =
            i == o.i && present == o.present && (d.toRawBits() == o.d.toRawBits() || (d.isNaN() && o.d.isNaN()))

        override fun toString(): String = "PV(i=$i, d=$d [0x${java.lang.Long.toHexString(d.toRawBits())}], present=$present)"
    }

    /** 反射找出的葉欄位：路徑 → 型別代碼（[ParamsFlat.KIND_INT] 等）。 */
    fun reflectKinds(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        walk(NightReadParams::class.java, "", NightReadParams()) { path, kind, _ -> out[path] = kind }
        return out
    }

    /** 反射讀 [p] 的每個葉欄位值。 */
    fun reflectValues(p: NightReadParams): Map<String, PV> {
        val out = LinkedHashMap<String, PV>()
        walk(NightReadParams::class.java, "", p) { path, _, v -> out[path] = v }
        return out
    }

    /** 參數組＝本套件裡名字以 Params 結尾的 data class。 */
    private fun isGroup(c: Class<*>) = c.`package`?.name == NightReadParams::class.java.`package`?.name && c.simpleName.endsWith("Params")

    private fun walk(c: Class<*>, prefix: String, obj: Any, sink: (String, Int, PV) -> Unit) {
        for (f in c.declaredFields) {
            if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
            f.isAccessible = true
            val path = prefix + f.name
            val v = f.get(obj)
            val t = f.type
            when {
                t == Int::class.javaPrimitiveType -> sink(path, ParamsFlat.KIND_INT, PV((v as Int).toLong(), 0.0, true))
                t == Double::class.javaPrimitiveType -> sink(path, ParamsFlat.KIND_DOUBLE, PV(0, v as Double, true))
                t == Float::class.javaPrimitiveType -> sink(path, ParamsFlat.KIND_FLOAT, PV(0, (v as Float).toDouble(), true))
                t == Boolean::class.javaPrimitiveType -> sink(path, ParamsFlat.KIND_BOOL, PV(if (v as Boolean) 1 else 0, 0.0, true))
                t == java.lang.Integer::class.java -> sink(path, ParamsFlat.KIND_OPT_INT, PV(((v as Int?) ?: 0).toLong(), 0.0, v != null))
                t.isEnum -> sink(path, ParamsFlat.KIND_ENUM, PV((v as Enum<*>).ordinal.toLong(), 0.0, true))
                isGroup(t) -> walk(t, "$path.", v!!, sink)
                else -> error("參數欄位 $path 的型別 ${t.name} 不在 gen_params.py 支援的範圍（先擴充產生器）")
            }
        }
    }

    private fun mix(k: Int, r: Int): Long {
        var z = k.toLong() * -0x61c8864680b583ebL + r.toLong() * 0x2545f4914f6cdd1dL + 0x1234567L
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** 一般回合數（每回合每個數值欄位各不相同）；回合 [SPECIAL_ROUND] 是特殊值（±0、次正規數、極值、∞、NaN、Int 邊界）。 */
    const val ROUNDS = 16
    const val SPECIAL_ROUND = ROUNDS

    /**
     * 第 [k] 個欄位（[ParamsFlat.FIELD_NAMES] 的順序）在第 [r] 回合的測試值。一般回合：同型別的欄位兩兩不同、也跟預設值不同
     * （Int 與 Double 都帶不規則的低位，float 截斷、槽位錯位、型別錯配都會被抓到）；Boolean／enum／Int? 的有無每回合重新擲。
     */
    fun value(k: Int, kind: Int, r: Int): PV {
        val m = mix(k, r)
        if (r == SPECIAL_ROUND) {
            val ints = longArrayOf(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), -1, 0, 1)
            val dbls = doubleArrayOf(-0.0, Double.MIN_VALUE, -Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NaN, Double.fromBits(0x000c000000000000L))
            val flts = floatArrayOf(-0.0f, Float.MIN_VALUE, Float.MAX_VALUE, Float.NEGATIVE_INFINITY, Float.NaN, -1.5e-40f)
            return when (kind) {
                ParamsFlat.KIND_INT -> PV(ints[k % ints.size], 0.0, true)
                ParamsFlat.KIND_DOUBLE -> PV(0, dbls[k % dbls.size], true)
                ParamsFlat.KIND_FLOAT -> PV(0, flts[k % flts.size].toDouble(), true)
                ParamsFlat.KIND_BOOL -> PV((k and 1).toLong(), 0.0, true)
                ParamsFlat.KIND_OPT_INT -> if (k % 3 == 0) PV(0, 0.0, false) else PV(ints[k % ints.size], 0.0, true)
                ParamsFlat.KIND_ENUM -> PV(((k + 1) % ParamsFlat.ENUMS.getValue(ParamsFlat.FIELD_ENUMS[k]!!).size).toLong(), 0.0, true)
                else -> error("kind $kind")
            }
        }
        val sign = if ((m ushr 7) and 1L == 1L) -1 else 1
        val distinctInt = sign * (0x100000 + k * 4099 + r * 977 + 13)
        return when (kind) {
            ParamsFlat.KIND_INT -> PV(distinctInt.toLong(), 0.0, true)
            ParamsFlat.KIND_OPT_INT -> if ((m ushr 3) and 1L == 1L) PV(distinctInt.toLong(), 0.0, true) else PV(0, 0.0, false)
            // (k+1)·(1 + 隨機 52 位小數)：同回合各欄位的整數部分不同 ⇒ 兩兩不同；小數部分不是 float 能表示的
            ParamsFlat.KIND_DOUBLE -> PV(0, sign * (k + 1 + r * 1000) * Double.fromBits(0x3ff0000000000000L or (m and 0xfffffffffffffL)), true)
            // float：(k+1)／8 ＋ 回合偏移，精確可表示且兩兩不同
            ParamsFlat.KIND_FLOAT -> PV(0, (sign * ((k + 1) / 8.0f + r * 4096f)).toDouble(), true)
            ParamsFlat.KIND_BOOL -> PV((m ushr 11) and 1L, 0.0, true)
            ParamsFlat.KIND_ENUM -> PV(((m ushr 17) and 0x7fffffffL) % ParamsFlat.ENUMS.getValue(ParamsFlat.FIELD_ENUMS[k]!!).size, 0.0, true)
            else -> error("kind $kind")
        }
    }

    /** 把逐欄的值照 [ParamsFlat.FIELD_SLOTS] 放進攤平陣列（不經 [ParamsFlat.toFlat]）。 */
    fun toSlots(values: List<PV>): Pair<IntArray, DoubleArray> {
        val i = IntArray(ParamsFlat.INT_SLOTS)
        val d = DoubleArray(ParamsFlat.DOUBLE_SLOTS)
        for (k in values.indices) {
            val v = values[k]
            val s = ParamsFlat.FIELD_SLOTS[k]
            when (ParamsFlat.FIELD_KINDS[k]) {
                ParamsFlat.KIND_DOUBLE, ParamsFlat.KIND_FLOAT -> d[s] = v.d
                ParamsFlat.KIND_OPT_INT -> { i[s] = if (v.present) 1 else 0; i[s + 1] = v.i.toInt() }
                else -> i[s] = v.i.toInt()
            }
        }
        return i to d
    }

    /** 第 [r] 回合的逐欄值（[ParamsFlat.FIELD_NAMES] 順序）。 */
    fun round(r: Int): List<PV> = ParamsFlat.FIELD_NAMES.indices.map { value(it, ParamsFlat.FIELD_KINDS[it], r) }

    /** schema 雜湊（定義同 gen_params.py 檔頭）：用欄位表在 Kotlin 端重算。 */
    fun schemaHash(): Long {
        val kindName = mapOf(
            ParamsFlat.KIND_INT to "int", ParamsFlat.KIND_DOUBLE to "double", ParamsFlat.KIND_FLOAT to "float",
            ParamsFlat.KIND_BOOL to "bool", ParamsFlat.KIND_OPT_INT to "opt_int",
        )
        val sb = StringBuilder(ParamsFlat.SCHEMA_TAG).append('\n')
        val enumsUsed = LinkedHashSet<String>()
        for (k in ParamsFlat.FIELD_NAMES.indices) {
            val kind = ParamsFlat.FIELD_KINDS[k]
            val t = if (kind == ParamsFlat.KIND_ENUM) "enum:" + ParamsFlat.FIELD_ENUMS[k]!!.also { enumsUsed += it } else kindName.getValue(kind)
            sb.append(ParamsFlat.FIELD_NAMES[k]).append(':').append(t).append('\n')
        }
        for (e in enumsUsed) sb.append("enum ").append(e).append('=').append(ParamsFlat.ENUMS.getValue(e).joinToString(",")).append('\n')
        val dig = java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        var h = 0L
        for (b in 0 until 8) h = (h shl 8) or (dig[b].toLong() and 0xff)
        return h
    }
}
