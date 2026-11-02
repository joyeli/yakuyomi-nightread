package li.joye.yakuyomi.nightread

import org.junit.Assert.fail
import org.junit.Assume
import java.io.File
import java.io.PrintWriter

/**
 * 載入主機的原生庫（native/build/host/libnightread_host.so，native/tools/build_host.sh 產生）。
 * 路徑：系統屬性 `nightread.nativeLib`（Gradle `-PnativeLib=…` 會設）或環境變數 `NIGHTREAD_NATIVE_LIB`。
 * 沒給路徑＝略過這些測試（平常的 `./gradlew test` 不需要 C++ 工具鏈）；給了卻載不起來、或 `nightread.requireNative=true`
 * 而沒給路徑＝失敗（CI 的 native job 用，避免「全部略過」被當成通過）。
 * 在 native 以外的 CI 組（`-PtestGroup=fast` 等）沒有原生庫時也是失敗、不略過：用到原生庫的類別要命名 `Cpp*` 才會進 native 組
 *（build.gradle.kts），命名錯了會落進 fast 組被默默略過，CI 卻是綠的。
 */
internal object NativeLib {
    private val path: String? = System.getProperty("nightread.nativeLib")?.takeIf { it.isNotBlank() }
        ?: System.getenv("NIGHTREAD_NATIVE_LIB")?.takeIf { it.isNotBlank() }
    private val required = System.getProperty("nightread.requireNative") == "true"
    private val group: String? = System.getProperty("nightread.testGroup")?.takeIf { it.isNotBlank() }

    val loaded: Boolean by lazy {
        val p = path ?: return@lazy false
        System.load(File(p).absolutePath)   // 載不起來直接丟 UnsatisfiedLinkError：給了路徑就該載得起來
        true
    }

    fun assumeLoaded() {
        if (loaded) return
        if (required) fail("nightread.requireNative=true 但沒有給原生庫路徑（-PnativeLib=…）")
        if (group != null && group != "native") {
            fail("這個測試用到原生庫，卻跑在 testGroup=$group：類別名稱要以 Cpp 開頭，native 組才收得到（build.gradle.kts）")
        }
        Assume.assumeTrue("沒有原生庫（-PnativeLib=native/build/host/libnightread_host.so），略過", false)
    }
}

/**
 * golden 檔（給 native/test/golden_check.cpp，在 qemu-aarch64 上檢查 arm64 的 C++）：系統屬性 `nightread.goldenOut`
 *（Gradle `-PgoldenOut=…`）有給時，每組寫一個 `<名稱>.txt`（共 6 個）。雜湊定義與 golden_check.cpp 相同。
 */
internal object NativeGolden {
    private val dir: File? = System.getProperty("nightread.goldenOut")?.takeIf { it.isNotBlank() }?.let { File(it) }

    fun open(name: String): PrintWriter? {
        val d = dir ?: return null
        d.mkdirs()
        return PrintWriter(File(d, "$name.txt").bufferedWriter())
    }

    const val FNV_INIT: Long = NightNumericProbe.FNV_INIT
    fun fnv(h: Long, v: Long): Long = NightNumericProbe.fnv(h, v)
    fun hex(v: Long): String = java.lang.Long.toHexString(v)
}
