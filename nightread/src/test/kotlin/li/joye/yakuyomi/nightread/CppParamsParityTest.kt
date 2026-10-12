package li.joye.yakuyomi.nightread

import li.joye.yakuyomi.nightread.ParamsTestSupport.PV
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 參數來回（cpp-eval 計畫階段 1：379 個欄位逐欄相同）：Kotlin 的 [NightReadParams] 與 C++ 的 `nr::params::NightReadParams`
 *（兩邊由 native/tools/gen_params.py 從 Params.kt 產生）經攤平格式互傳，**逐欄用名稱比**——Kotlin 端用 Java 反射讀欄位、
 * C++ 端用直接讀寫結構成員的 getField／setField（都不經攤平槽位），槽位寫錯、型別截斷（Double 存成 float、Int 存窄）、
 * 欄位漏掉都會在某個名字上對不起來。每回合每個數值欄位的值兩兩不同（[ParamsTestSupport.value]），另有一回合特殊值。
 *  - Kotlin → C++：槽位 → C++ fromFlat → getField 逐欄＝Kotlin 反射逐欄；C++ fromFlat → toFlat 原樣。
 *  - C++ → Kotlin：C++ 預設結構 → setField 逐欄 → toFlat → Kotlin fromFlat → 反射逐欄＝設的值。
 *  - 預設值：C++ 預設建構的結構攤平＝Kotlin `NightReadParams()` 攤平（逐位元）。
 *  - 不合法的攤平：Kotlin 拒收的 C++ 也拒收。
 *  - schema 雜湊、欄位表、規則版本與摘要表（RulesVersionGuardTest）兩邊相同。
 * 需要主機原生庫（沒給 `-PnativeLib` 就略過）。
 */
class CppParamsParityTest {

    @Test
    fun schemaAndTablesMatch() {
        NativeLib.assumeLoaded()
        assertEquals("C++ 與 Kotlin 的 schema 雜湊", ParamsFlat.SCHEMA_HASH, NativeTestBridge.paramsSchema())
        assertEquals("產品 JNI 的 schema 雜湊", ParamsFlat.SCHEMA_HASH, NightNative.paramsSchema())
        assertTrue(NightNative.available())
        assertEquals(ParamsFlat.FIELD_COUNT, NativeTestBridge.paramsFieldCount())
        assertEquals(ParamsFlat.INT_SLOTS, NativeTestBridge.paramsIntSlots())
        assertEquals(ParamsFlat.DOUBLE_SLOTS, NativeTestBridge.paramsDoubleSlots())
        assertEquals(NightRead.RULES_VERSION, NativeTestBridge.paramsRulesVersion())
        val names = (0 until NativeTestBridge.paramsFieldCount()).map { NativeTestBridge.paramsFieldName(it) }
        assertEquals("C++ 欄位表的名稱與順序", ParamsFlat.FIELD_NAMES, names)
        val kinds = IntArray(names.size) { NativeTestBridge.paramsFieldKind(it) }
        assertArrayEquals("C++ 欄位表的型別", ParamsFlat.FIELD_KINDS, kinds)
        assertEquals("C++ 欄位表與 Kotlin 反射", ParamsTestSupport.reflectKinds(), names.indices.associate { names[it] to kinds[it] })
    }

    /** C++ 的規則版本摘要表（gen_params.py 從 RulesVersionGuardTest.kt 抄）＝那個測試裡的兩張表。 */
    @Test
    fun rulesDigestTableMatchesGuardTest() {
        NativeLib.assumeLoaded()
        val guard = RulesVersionGuardTest()
        @Suppress("UNCHECKED_CAST")
        fun table(name: String) = RulesVersionGuardTest::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(guard) as Map<Int, String>
        }
        val digests = table("digests")
        val delivered = table("delivered")
        val cpp = generateSequence(0) { it + 1 }.map { NativeTestBridge.paramsRulesDigest(it) }.takeWhile { it != null }
            .map { it!!.split(" ") }.toList()
        assertEquals(digests.size, cpp.size)
        for (row in cpp) {
            val v = row[0].toInt()
            assertEquals("版本 $v 的摘要", digests[v], row[1])
            assertEquals("版本 $v 是否已交出", v in delivered, row[2] == "1")
        }
    }

    @Test
    fun defaultsMatchBitForBit() {
        NativeLib.assumeLoaded()
        val ci = IntArray(ParamsFlat.INT_SLOTS)
        val cd = DoubleArray(ParamsFlat.DOUBLE_SLOTS)
        NativeTestBridge.paramsDefaults(ci, cd)
        val (ki, kd) = ParamsFlat.toFlat(NightReadParams())
        val want = ParamsTestSupport.reflectValues(NightReadParams())
        val got = ParamsTestSupport.reflectValues(ParamsFlat.fromFlat(ci, cd))
        val bad = want.keys.filter { !want.getValue(it).same(got.getValue(it)) }
            .map { "$it：Kotlin ${want.getValue(it)}、C++ ${got.getValue(it)}" }
        assertTrue("C++ 預設值與 Kotlin 不同（gen_params.py 剖析預設值）：\n" + bad.joinToString("\n"), bad.isEmpty())
        assertArrayEquals(ki, ci)
        assertArrayEquals(kd.map { it.toRawBits() }.toLongArray(), cd.map { it.toRawBits() }.toLongArray())
        assertEquals(NightReadParams(), ParamsFlat.fromFlat(ci, cd))
    }

    /** Kotlin → C++：每回合 379 欄各不相同，C++ 直接讀成員逐欄比 Kotlin 反射。 */
    @Test
    fun kotlinToCppByName() {
        NativeLib.assumeLoaded()
        val n = ParamsFlat.FIELD_COUNT
        val cppNames = (0 until n).map { NativeTestBridge.paramsFieldName(it) }
        var checked = 0
        for (r in 0..ParamsTestSupport.SPECIAL_ROUND) {
            val (i, d) = ParamsTestSupport.toSlots(ParamsTestSupport.round(r))
            val p = ParamsFlat.fromFlat(i, d)
            val (pi, pd) = ParamsFlat.toFlat(p)
            val kotlin = ParamsTestSupport.reflectValues(p)
            val oi = LongArray(n)
            val od = DoubleArray(n)
            val op = IntArray(n)
            assertTrue("回合 $r：C++ fromFlat 拒收", NativeTestBridge.paramsGetAll(pi, pd, oi, od, op))
            for (k in 0 until n) {
                val name = cppNames[k]
                val c = PV(oi[k], od[k], op[k] == 1)
                val kv = kotlin.getValue(name)
                if (!kv.same(c)) fail("回合 $r：$name Kotlin $kv、C++ $c")
                checked++
            }
            val ri = IntArray(ParamsFlat.INT_SLOTS)
            val rd = DoubleArray(ParamsFlat.DOUBLE_SLOTS)
            assertTrue(NativeTestBridge.paramsRoundTrip(pi, pd, ri, rd))
            assertArrayEquals("回合 $r：C++ fromFlat → toFlat 的 ints", pi, ri)
            for (s in pd.indices) {
                if (pd[s].toRawBits() != rd[s].toRawBits() && !(pd[s].isNaN() && rd[s].isNaN())) fail("回合 $r：doubles[$s] ${pd[s]} → ${rd[s]}")
            }
        }
        println("CppParamsParityTest Kotlin → C++：${ParamsTestSupport.SPECIAL_ROUND + 1} 回合 × $n 欄＝$checked 項逐欄相同")
    }

    /** C++ → Kotlin：C++ 逐欄（直接寫成員）設值、攤平，Kotlin 還原後反射逐欄比。 */
    @Test
    fun cppToKotlinByName() {
        NativeLib.assumeLoaded()
        val n = ParamsFlat.FIELD_COUNT
        val cppNames = (0 until n).map { NativeTestBridge.paramsFieldName(it) }
        val idxOf = ParamsFlat.FIELD_NAMES.withIndex().associate { it.value to it.index }
        var checked = 0
        for (r in 0..ParamsTestSupport.SPECIAL_ROUND) {
            // 值依名稱決定（不靠兩邊順序相同）
            val want = cppNames.map { name ->
                val k = idxOf.getValue(name)
                name to ParamsTestSupport.value(k, ParamsFlat.FIELD_KINDS[k], r)
            }
            val ii = LongArray(n) { want[it].second.i }
            val id = DoubleArray(n) { want[it].second.d }
            val ip = IntArray(n) { if (want[it].second.present) 1 else 0 }
            val oi = IntArray(ParamsFlat.INT_SLOTS)
            val od = DoubleArray(ParamsFlat.DOUBLE_SLOTS)
            assertTrue("回合 $r：C++ setField 拒收", NativeTestBridge.paramsSetAll(ii, id, ip, oi, od))
            val got = ParamsTestSupport.reflectValues(ParamsFlat.fromFlat(oi, od))
            for ((name, w) in want) {
                val g = got.getValue(name)
                if (!w.same(g)) fail("回合 $r：$name C++ 設 $w、Kotlin 讀到 $g")
                checked++
            }
        }
        println("CppParamsParityTest C++ → Kotlin：${ParamsTestSupport.SPECIAL_ROUND + 1} 回合 × $n 欄＝$checked 項逐欄相同")
    }

    /** 不合法的攤平：Kotlin fromFlat 拒收的，C++ fromFlat 也拒收；合法的兩邊都收。 */
    @Test
    fun invalidFlatRejectedOnBothSides() {
        NativeLib.assumeLoaded()
        val (i0, d0) = ParamsFlat.toFlat(NightReadParams())
        assertTrue(NativeTestBridge.paramsAccepts(i0, d0))
        val cases = ParamsFlatTest.badCases(i0, d0)
        for ((what, flat) in cases) {
            val kotlinRejects = try {
                ParamsFlat.fromFlat(flat.first, flat.second)
                false
            } catch (e: IllegalArgumentException) {
                true
            }
            assertTrue("$what：Kotlin 要拒收", kotlinRejects)
            assertFalse("$what：C++ 要拒收", NativeTestBridge.paramsAccepts(flat.first, flat.second))
        }
        assertFalse(NativeTestBridge.paramsAccepts(i0.copyOf(i0.size - 1), d0))
        assertFalse(NativeTestBridge.paramsAccepts(i0, d0.copyOf(d0.size + 1)))
        // Float 欄位的特殊值兩邊都收（∞、NaN、float 的最大值與次正規數）
        val fk = ParamsFlat.FIELD_KINDS.indexOfFirst { it == ParamsFlat.KIND_FLOAT }
        for (v in listOf(Double.POSITIVE_INFINITY, Double.NaN, Float.MAX_VALUE.toDouble(), Float.MIN_VALUE.toDouble(), -0.0)) {
            val d = d0.copyOf().also { it[ParamsFlat.FIELD_SLOTS[fk]] = v }
            ParamsFlat.fromFlat(i0, d)
            assertTrue("Float 欄位 = $v：C++ 要收", NativeTestBridge.paramsAccepts(i0, d))
        }
        println("CppParamsParityTest：不合法的攤平 ${cases.size + 2} 種，兩邊都拒收")
    }
}
