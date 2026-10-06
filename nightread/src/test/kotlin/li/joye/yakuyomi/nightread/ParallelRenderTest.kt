package li.joye.yakuyomi.nightread

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

/**
 * 頁內並行（[NightRead.renderTiers]／[NightRead.render] 的 parallel，2026-10-06）與依序版逐位元相同：
 * 每檔成品、交出順序（含合成鍵去重的 null）、除錯回呼的段名與值都一樣。Executor 換四種：真的並行的池子、只有一條的池子
 * （分支多半在主執行緒補跑）、在呼叫執行緒直接跑、一律拒收（全部回到主執行緒）。頁＝有框、無框、譯後合成頁（帶去字遮罩）。
 * 另守 [NightReadStageTimer]：段名與順序同一般回呼、遮罩計數那幾段的值一律 0。
 */
class ParallelRenderTest {

    private val pages = listOf("ch34_011", "demo04", "demo06", "syn_v4")

    private fun readGray(name: String): Gray {
        val stream = javaClass.classLoader!!.getResourceAsStream("page/$name")
            ?: error("缺 fixture：page/$name — 先跑 research/make_page_fixture.py")
        val img: BufferedImage = stream.use { ImageIO.read(it) }
        val g = Gray(img.width, img.height)
        val raster = img.raster
        for (y in 0 until img.height) for (x in 0 until img.width) g.data[y * img.width + x] = raster.getSample(x, y, 0)
        return g
    }

    private fun readMask(name: String): Mask {
        val g = readGray(name)
        return Mask(g.w, g.h, BooleanArray(g.data.size) { g.data[it] > 127 })
    }

    private fun readRegions(name: String): List<TextRegion> =
        javaClass.classLoader!!.getResourceAsStream("page/$name")!!.bufferedReader().readText()
            .lineSequence().filter { it.isNotBlank() }.map {
                val p = it.trim().split(" ").map(String::toInt)
                TextRegion(p[0], p[1], p[2], p[3])
            }.toList()

    private fun input(page: String) = NightReadInput(
        gray = readGray("${page}_gray.png"),
        seg = readMask("${page}_seg.png"),
        regions = readRegions("${page}_regions.txt"),
        charMask = readMask("${page}_char.png"),
        chroma = readGray("${page}_chroma.png"),
        inpaintMask = if (javaClass.classLoader!!.getResource("page/${page}_inpaint.png") != null) readMask("${page}_inpaint.png") else null,
    )

    private class Run(val outs: List<IntArray?>, val marks: List<String>)

    private fun tiers(base: NightReadParams = NightReadParams()) = listOf(NightTier.L2.apply(base), NightTier.L3.apply(base))

    private fun runTiers(inp: NightReadInput, ex: Executor?, timer: Boolean = false): Run {
        val outs = ArrayList<IntArray?>()
        val marks = ArrayList<String>()
        val dbg: NightReadDebug = if (timer) {
            object : NightReadStageTimer {
                override fun invoke(stage: String, value: Int) { marks += "$stage=$value" }
            }
        } else {
            { stage, value -> marks += "$stage=$value" }
        }
        NightRead.renderTiers(inp, tiers(), dbg, ex) { k, g ->
            assertEquals("交出順序", outs.size, k)
            outs += g?.data?.copyOf()
        }
        return Run(outs, marks)
    }

    private fun assertSame(tag: String, want: Run, got: Run) {
        assertEquals("$tag 檔數", want.outs.size, got.outs.size)
        for (k in want.outs.indices) {
            val a = want.outs[k]
            val b = got.outs[k]
            if (a == null || b == null) assertEquals("$tag 第 $k 檔去重", a == null, b == null)
            else assertArrayEquals("$tag 第 $k 檔", a, b)
        }
        assertEquals("$tag 除錯回呼", want.marks, got.marks)
    }

    @Test
    fun parallelTiersMatchSequential() {
        val big = Executors.newFixedThreadPool(4)
        val one = Executors.newFixedThreadPool(1)
        val direct = Executor { it.run() }
        val reject = Executor { throw RejectedExecutionException("測試：一律拒收") }
        try {
            for (page in pages) {
                val inp = input(page)
                val seq = runTiers(inp, null)
                assertSame("$page 四條池", seq, runTiers(inp, big))
                assertSame("$page 一條池", seq, runTiers(inp, one))
                assertSame("$page 直接跑", seq, runTiers(inp, direct))
                assertSame("$page 拒收", seq, runTiers(inp, reject))
                // 計時回呼：段名順序同一般回呼，值除了 tier 都是 0
                val t = runTiers(inp, big, timer = true)
                assertEquals("$page 計時回呼段名", seq.marks.map { it.substringBefore('=') }, t.marks.map { it.substringBefore('=') })
                // 遮罩計數那幾段（只給 parity 看的值）一律 0；其餘段的值本來就順手算好、照送
                val counted = setOf("charRaw", "charMask", "separators", "bubbleRest", "pseudoBubble", "restore", "gutterBand", "bubbleSealed")
                for (m in t.marks) if (m.substringBefore('=') in counted) assertTrue("$page 計時回呼的值要是 0：$m", m.endsWith("=0"))
                for (k in seq.outs.indices) {
                    val a = seq.outs[k]
                    val b = t.outs[k]
                    if (a == null || b == null) assertEquals(a == null, b == null) else assertArrayEquals("$page 計時第 $k 檔", a, b)
                }
                // 單檔版（更多：分析時先算背景物件量測）
                val p = NightTier.L3.apply()
                assertArrayEquals("$page 單檔", NightRead.render(inp, p).out.data, NightRead.render(inp, p, null, big).out.data)
            }
        } finally {
            big.shutdown()
            one.shutdown()
            big.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    /**
     * 中斷與依序版一樣不理會（2026-10-07 審查）：分支在池子裡跑的時候主執行緒被中斷（不只一次），join 照等到分支做完、
     * 回傳值不變，中斷旗標在 join 回傳後補回去。以前 join 直接拋 InterruptedException。
     * 打斷的那條先打完五次、才放行分支：之後主執行緒看到的旗標只可能是 join 補回去的。
     */
    @Test
    fun branchJoinWaitsThroughInterruptsAndRestoresTheFlag() {
        val pool = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val b = NightRead.Branch(pool) {
                started.countDown()
                release.await()
                42
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val main = Thread.currentThread()
            val interrupter = Thread {
                repeat(5) {
                    main.interrupt()
                    Thread.sleep(10)
                }
                release.countDown()
            }
            interrupter.start()
            val v = b.join()
            val flag = Thread.interrupted() // 讀並清掉，免得影響下面的 join 與後面的測試
            interrupter.join()
            assertEquals(42, v)
            assertTrue("join 回傳後中斷旗標要補回去", flag)
        } finally {
            Thread.interrupted()
            pool.shutdownNow()
        }
    }

    /** 整頁：開了並行的 renderTiers 在主執行緒一直被中斷時照樣做完，成品與依序版逐位元相同（依序版本來就不理會中斷）。 */
    @Test
    fun parallelRenderIgnoresInterruptsLikeSequential() {
        val pool = Executors.newFixedThreadPool(3)
        try {
            val inp = input("demo06")
            val seq = runTiers(inp, null)
            val result = AtomicReference<Run?>()
            val error = AtomicReference<Throwable?>()
            val flagAfter = AtomicBoolean(false)
            val worker = Thread {
                try {
                    result.set(runTiers(inp, pool))
                } catch (e: Throwable) {
                    error.set(e)
                }
                flagAfter.set(Thread.currentThread().isInterrupted)
            }
            worker.start()
            while (worker.isAlive) {
                worker.interrupt()
                Thread.sleep(10)
            }
            assertEquals("並行版被中斷不該拋例外", null, error.get())
            assertSame("被中斷的並行版", seq, result.get()!!)
            assertTrue("中斷旗標要留著給呼叫端看", flagAfter.get())
        } finally {
            pool.shutdownNow()
        }
    }
}
