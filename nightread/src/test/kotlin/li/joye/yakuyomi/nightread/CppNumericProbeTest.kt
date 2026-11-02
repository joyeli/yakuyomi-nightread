package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手機探針的數值自測（[NightNumericProbe]）在主機上要全部相同：探針在手機上才第一次跑 ART，這裡先確認探針本身沒寫錯（表齊全、案例真的有跑到）。
 * 完整案例數在 CppJmathParityTest、CppTablesParityTest，這裡只跑一小部分。
 */
class CppNumericProbeTest {

    @Test
    fun scalarsArraysAndTablesAreIdenticalOnHost() {
        NativeLib.assumeLoaded()
        assertTrue(NightNumericProbe.available())
        val rs = ArrayList<NightNumericProbe.Result>()
        NightNumericProbe.runScalars(20_000, 4) { rs += it }
        assertEquals("每個函式都要有結果", NightNumericProbe.specs().size, rs.size)
        assertTrue("每個函式都要真的跑到案例", rs.all { it.cases == 20_000L })
        assertTrue("BigDecimal 要有丟例外的案例（非有限值）", rs.first { it.name == "bd_half_even" }.throws > 0)
        NightNumericProbe.runArrays(0.05) { rs += it }
        NightNumericProbe.runTables(4, 37) { rs += it }
        for (name in listOf("npSum", "npSumChunked", "npArgsort", "tables.gauss", "tables.textLut", "tables.inkLut", "tables.lineOffsets")) {
            assertTrue("$name 要有案例", rs.first { it.name == name }.cases > 0)
        }
        val bad = rs.filter { it.diffs != 0L }.map { "${it.name}: ${it.diffs}（${it.note}）" }
        assertEquals("C++ 與 Java 不同：$bad", emptyList<String>(), bad)
    }
}
