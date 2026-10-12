package li.joye.yakuyomi.nightread

import li.joye.yakuyomi.nightread.ParamsTestSupport.PV
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 產生的 [ParamsFlat]（native/tools/gen_params.py）在 Kotlin 端自己對：欄位表跟反射看到的 [NightReadParams] 一致（379 欄）、
 * schema 雜湊照定義重算相同、逐欄各不相同的值來回（槽位 → fromFlat → 反射逐欄比 → toFlat 原樣）、預設值、不合法輸入。
 * 不需要原生庫（fast 組）；C++ 那半在 [CppParamsParityTest]。
 */
class ParamsFlatTest {

    @Test
    fun fieldTableMatchesReflection() {
        val kinds = ParamsTestSupport.reflectKinds()
        assertEquals("反射看到的葉欄位數", ParamsFlat.FIELD_COUNT, kinds.size)
        assertEquals("葉欄位數（Params.kt 的 132 欄＋六組；改了 Params.kt 要重跑 gen_params.py）", 379, ParamsFlat.FIELD_COUNT)
        assertEquals(ParamsFlat.FIELD_COUNT, ParamsFlat.FIELD_NAMES.size)
        assertEquals(ParamsFlat.FIELD_COUNT, ParamsFlat.FIELD_NAMES.toSet().size)
        val table = ParamsFlat.FIELD_NAMES.indices.associate { ParamsFlat.FIELD_NAMES[it] to ParamsFlat.FIELD_KINDS[it] }
        assertEquals("欄位表（名稱 → 型別）要與反射相同：Params.kt 改了卻沒重跑 gen_params.py？", kinds, table)
        // HotSpot 的 getDeclaredFields 照宣告順序：順序也該相同（攤平順序＝宣告順序）
        assertEquals(kinds.keys.toList(), ParamsFlat.FIELD_NAMES)
    }

    @Test
    fun slotsCoverTheFlatArraysExactlyOnce() {
        val ints = IntArray(ParamsFlat.INT_SLOTS)
        val dbls = IntArray(ParamsFlat.DOUBLE_SLOTS)
        for (k in ParamsFlat.FIELD_NAMES.indices) {
            val s = ParamsFlat.FIELD_SLOTS[k]
            when (ParamsFlat.FIELD_KINDS[k]) {
                ParamsFlat.KIND_DOUBLE, ParamsFlat.KIND_FLOAT -> dbls[s]++
                ParamsFlat.KIND_OPT_INT -> { ints[s]++; ints[s + 1]++ }
                else -> ints[s]++
            }
        }
        assertTrue("ints 每格剛好一個欄位用", ints.all { it == 1 })
        assertTrue("doubles 每格剛好一個欄位用", dbls.all { it == 1 })
    }

    @Test
    fun schemaHashMatchesDefinition() {
        assertEquals(ParamsFlat.SCHEMA_HASH, ParamsTestSupport.schemaHash())
        assertEquals(listOf("ALL", "SIMPLE", "PLAIN"), ParamsFlat.ENUMS["StickerMode"])
    }

    @Test
    fun defaultsRoundTrip() {
        val (i, d) = ParamsFlat.toFlat(NightReadParams())
        assertEquals(NightReadParams(), ParamsFlat.fromFlat(i, d))
        for (t in NightTier.entries) {
            val p = t.apply()
            val (ti, td) = ParamsFlat.toFlat(p)
            assertEquals(p, ParamsFlat.fromFlat(ti, td))
        }
    }

    /** 每回合每個欄位各不相同：照欄位表放進槽位 → fromFlat → 反射逐欄比（獨立於產生器）→ toFlat 要原樣回來。 */
    @Test
    fun distinctValuesRoundTripByName() {
        for (r in 0..ParamsTestSupport.SPECIAL_ROUND) {
            val want = ParamsTestSupport.round(r)
            if (r != ParamsTestSupport.SPECIAL_ROUND) assertDistinct(want, r)
            val (i, d) = ParamsTestSupport.toSlots(want)
            val p = ParamsFlat.fromFlat(i, d)
            val got = ParamsTestSupport.reflectValues(p)
            for (k in ParamsFlat.FIELD_NAMES.indices) {
                val name = ParamsFlat.FIELD_NAMES[k]
                val g = got.getValue(name)
                if (!g.same(want[k])) fail("回合 $r：$name 放進槽 ${ParamsFlat.FIELD_SLOTS[k]} 的 ${want[k]}，fromFlat 後反射讀到 $g")
            }
            val (i2, d2) = ParamsFlat.toFlat(p)
            assertArrayEquals("回合 $r：toFlat 的 ints", i, i2)
            for (s in d.indices) {
                if (d[s].toRawBits() != d2[s].toRawBits() && !(d[s].isNaN() && d2[s].isNaN())) {
                    fail("回合 $r：toFlat 的 doubles[$s] ${d[s]} → ${d2[s]}")
                }
            }
            assertEquals(p, ParamsFlat.fromFlat(i2, d2))
        }
    }

    @Test
    fun invalidFlatIsRejected() {
        val (i0, d0) = ParamsFlat.toFlat(NightReadParams())
        fun rejects(what: String, i: IntArray, d: DoubleArray) {
            try {
                ParamsFlat.fromFlat(i, d)
                fail("$what 應該被拒收")
            } catch (e: IllegalArgumentException) {
                // 預期
            }
        }
        for (case in badCases(i0, d0)) rejects(case.first, case.second.first, case.second.second)
        rejects("ints 少一格", i0.copyOf(i0.size - 1), d0)
        rejects("doubles 多一格", i0, d0.copyOf(d0.size + 1))
    }

    companion object {
        /** 不合法的攤平輸入（C++ 端 [CppParamsParityTest] 也拿同一批比「拒收」）。 */
        fun badCases(i0: IntArray, d0: DoubleArray): List<Pair<String, Pair<IntArray, DoubleArray>>> {
            val out = ArrayList<Pair<String, Pair<IntArray, DoubleArray>>>()
            for (k in ParamsFlat.FIELD_NAMES.indices) {
                val s = ParamsFlat.FIELD_SLOTS[k]
                val name = ParamsFlat.FIELD_NAMES[k]
                fun ints(v: Int, off: Int = 0) = i0.copyOf().also { it[s + off] = v } to d0
                when (ParamsFlat.FIELD_KINDS[k]) {
                    ParamsFlat.KIND_BOOL -> { out += "$name=2" to ints(2); out += "$name=-1" to ints(-1) }
                    ParamsFlat.KIND_ENUM -> {
                        out += "$name=-1" to ints(-1)
                        out += "$name=${ParamsFlat.ENUMS.getValue(ParamsFlat.FIELD_ENUMS[k]!!).size}" to
                            ints(ParamsFlat.ENUMS.getValue(ParamsFlat.FIELD_ENUMS[k]!!).size)
                    }
                    ParamsFlat.KIND_OPT_INT -> {
                        out += "$name 有無旗標=7" to ints(7)
                        out += "$name 沒有值卻帶 5" to (i0.copyOf().also { it[s] = 0; it[s + 1] = 5 } to d0)
                    }
                    ParamsFlat.KIND_FLOAT -> {
                        out += "$name=0.1（不是 float）" to (i0 to d0.copyOf().also { it[s] = 0.1 })
                        out += "$name=1e300（超出 float）" to (i0 to d0.copyOf().also { it[s] = 1e300 })
                    }
                }
            }
            return out
        }

        internal fun assertDistinct(v: List<PV>, r: Int) {
            for (kind in listOf(ParamsFlat.KIND_INT, ParamsFlat.KIND_DOUBLE, ParamsFlat.KIND_FLOAT)) {
                val vals = ParamsFlat.FIELD_NAMES.indices.filter { ParamsFlat.FIELD_KINDS[it] == kind }
                    .map { if (kind == ParamsFlat.KIND_INT) v[it].i.toDouble() else v[it].d }
                assertEquals("回合 $r：型別 $kind 的測試值要兩兩不同", vals.size, vals.toSet().size)
            }
            val ints = ParamsFlat.FIELD_NAMES.indices
                .filter { ParamsFlat.FIELD_KINDS[it] == ParamsFlat.KIND_INT || (ParamsFlat.FIELD_KINDS[it] == ParamsFlat.KIND_OPT_INT && v[it].present) }
                .map { v[it].i }
            assertEquals("回合 $r：Int 與有值的 Int? 兩兩不同", ints.size, ints.toSet().size)
            val defaults = ParamsTestSupport.reflectValues(NightReadParams())
            for (k in ParamsFlat.FIELD_NAMES.indices) {
                val kind = ParamsFlat.FIELD_KINDS[k]
                if (kind == ParamsFlat.KIND_INT || kind == ParamsFlat.KIND_DOUBLE || kind == ParamsFlat.KIND_FLOAT) {
                    assertTrue("回合 $r：${ParamsFlat.FIELD_NAMES[k]} 的測試值不能等於預設值", !v[k].same(defaults.getValue(ParamsFlat.FIELD_NAMES[k])))
                }
            }
        }
    }
}
