package li.joye.yakuyomi.nightread

/**
 * 只給測試與手機探針用的 JNI 橋（實作在 native/test/test_jni.cpp）：把 C++ 的數值核心、常數表、型別與測試案例產生器暴露出來，
 * 在同一個行程裡跟 Kotlin／Java 逐位元比。實作只編進主機的 libnightread_host.so，以及建置時明確開了 NR_PROBE_JNI 的引擎 .so
 *（sandbox 探針）；產品的 .so 沒有，這個類別在 fork 的 R8 下也會被移除。放 main 是因為探針在別的模組（[NightNumericProbe]）。
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
