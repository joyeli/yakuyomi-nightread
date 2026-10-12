package li.joye.yakuyomi.nightread

import org.junit.Assert.fail
import org.junit.Assume
import java.io.File
import java.io.PrintWriter

/**
 * 只給測試用的 JNI 橋（實作在 native/test/test_jni.cpp，只編進主機的 libnightread_host.so，不進產品的 .so）：
 * 把 C++ 的數值核心、常數表、型別與測試案例產生器暴露出來，讓 JUnit 在同一個 JVM 裡跟 Kotlin／Java 逐位元比。
 */
internal object NativeTestBridge {
    @JvmStatic external fun fnCount(): Int
    @JvmStatic external fun fnName(fn: Int): String

    /** (nIn shl 16) or (nOut shl 8) or 輸出型別（0 double、1 float、2 int、3 long）。 */
    @JvmStatic external fun fnArity(fn: Int): Int

    /** 引數型別，每個 4 位元（0 double、1 float、2 int、3 long、4 scale、5 位移量）。 */
    @JvmStatic external fun fnInKinds(fn: Int): Int

    /** 第 start 起 n 個案例的引數（每案例 nIn 個 64 位元字）。 */
    @JvmStatic external fun genScalar(fn: Int, start: Long, n: Int, args: LongArray)

    /** C++ 求值：每案例 nOut 個輸出＋狀態（1＝Kotlin 會丟例外）。 */
    @JvmStatic external fun evalScalar(fn: Int, args: LongArray, n: Int, out: LongArray)

    @JvmStatic external fun genSumArray(chunked: Boolean, idx: Long): DoubleArray
    @JvmStatic external fun npSum(a: DoubleArray): Double
    @JvmStatic external fun npSumChunked(a: DoubleArray): Double
    @JvmStatic external fun genArgsortArray(idx: Long): IntArray
    @JvmStatic external fun npArgsort(v: IntArray): IntArray
    @JvmStatic external fun antiqsort(n: Int): IntArray

    @JvmStatic external fun gaussW(sigma: Double): IntArray
    @JvmStatic external fun gaussianBlurKsize(sigma: Double): Int
    @JvmStatic external fun gaussianKernel(ks: Int, sigma: Double): DoubleArray
    @JvmStatic external fun ringGaussKernel(sigma: Double): DoubleArray
    @JvmStatic external fun textLut(bg: Int, ink: Int): FloatArray
    @JvmStatic external fun inkLut(bg: Int, top: Int, inv: Boolean): FloatArray

    /** null＝C++ 判定 Kotlin 版會丟例外。 */
    @JvmStatic external fun lineOffsets(len: Int): Array<IntArray>?

    @JvmStatic external fun packBits(mask: BooleanArray, w: Int, h: Int): LongArray
    @JvmStatic external fun unpackBits(words: LongArray, w: Int, h: Int): BooleanArray

    /** [ax, ay, runStart[0 until h], runEnd[0 until h]]。 */
    @JvmStatic external fun kernelRuns(data: BooleanArray, w: Int, h: Int): IntArray

    /** 在 JNI 邊界的防護裡丟 C++ 例外：0＝std::bad_alloc、1＝std::runtime_error、2＝其他。 */
    @JvmStatic external fun throwCpp(kind: Int)

    /** C API 錯誤碼轉 Java 例外（NR_OK＝0 不丟）。 */
    @JvmStatic external fun throwForCode(code: Int)

    @JvmStatic external fun genPixels(n: Int, seed: Long): IntArray
    @JvmStatic external fun argbToGrayChroma(px: IntArray, gray: IntArray, chroma: IntArray)
    @JvmStatic external fun grayToArgb(g: IntArray): IntArray

    // ── 參數（native/core/params_gen.h；CppParamsParityTest）──
    @JvmStatic external fun paramsSchema(): Long
    @JvmStatic external fun paramsFieldCount(): Int
    @JvmStatic external fun paramsIntSlots(): Int
    @JvmStatic external fun paramsDoubleSlots(): Int
    @JvmStatic external fun paramsRulesVersion(): Int
    @JvmStatic external fun paramsFieldName(idx: Int): String

    /** C++ `nr::params::Kind`（與 [ParamsFlat.KIND_INT] 等相同）。 */
    @JvmStatic external fun paramsFieldKind(idx: Int): Int

    /** 規則版本摘要表第 k 筆 "<版本> <sha256> <delivered 0/1>"；超出範圍 null。 */
    @JvmStatic external fun paramsRulesDigest(k: Int): String?

    /** C++ 預設建構的參數結構攤平。 */
    @JvmStatic external fun paramsDefaults(ints: IntArray, dbls: DoubleArray)

    /** C++ fromFlat 收不收。 */
    @JvmStatic external fun paramsAccepts(ints: IntArray, dbls: DoubleArray): Boolean

    /** fromFlat → toFlat；拒收回 false。 */
    @JvmStatic external fun paramsRoundTrip(ints: IntArray, dbls: DoubleArray, outInts: IntArray, outDbls: DoubleArray): Boolean

    /** fromFlat 後逐欄（直接讀成員）取值，依 C++ 欄位表順序；拒收回 false。 */
    @JvmStatic external fun paramsGetAll(ints: IntArray, dbls: DoubleArray, outI: LongArray, outD: DoubleArray, outPresent: IntArray): Boolean

    /** 預設結構逐欄（直接寫成員）設值後 toFlat；某欄的值不合型別回 false。 */
    @JvmStatic external fun paramsSetAll(inI: LongArray, inD: DoubleArray, inPresent: IntArray, outInts: IntArray, outDbls: DoubleArray): Boolean
}

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

    const val FNV_INIT: Long = -0x340d631b7bdddcdbL   // 0xcbf29ce484222325
    fun fnv(h: Long, v: Long): Long = (h xor v) * 0x100000001b3L
    fun hex(v: Long): String = java.lang.Long.toHexString(v)
}
