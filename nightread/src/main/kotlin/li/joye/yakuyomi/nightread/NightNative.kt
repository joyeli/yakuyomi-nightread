package li.joye.yakuyomi.nightread

/**
 * 夜讀 C++ 核心（本 repo 的 `native/`）的 JNI 綁定。
 *
 * **不自己 `System.loadLibrary`**：C++ 核心不是獨立的 .so，而是由引擎的 CMake 編進唯一的 `libyakuyomi_ncnn.so`
 *（「引擎只有一個 .so」）；那個 .so 由引擎載入（`NcnnBackend`）。載入之後這裡的 native 方法才找得到實作。主機測試則由
 * 測試自己 `System.load` 本 repo 編的 `libnightread_host.so`（`native/README.md`）。所以呼叫任何方法前先問 [available]：
 * 沒載入、或載入的 .so 跟這份 Kotlin 不同版（ABI、規則版本或參數 schema 不同）時回 false，呼叫端一律改用 Kotlin 版。
 *
 * 階段 1 只有骨架：以「頁」為單位的 handle（[create]／[free]），像素分條進出（[putRows] 把 ARGB 轉成灰階與彩度、[getRows]
 * 取回），原生記憶體峰值（[peakBytes]）與取消旗標（[cancel]）。重繪本身還在 Kotlin（[NightRead]）。
 *
 * 例外：參數不合法丟 [IllegalArgumentException]；原生配置失敗丟 [OutOfMemoryError]（與 Kotlin 版每頁 OOM 的補跑路徑相同）；
 * 呼叫順序不對丟 [IllegalStateException]。handle 用完一定要 [free]，同一個 handle 不要在兩條執行緒同時寫。
 *
 * R8：`consumer-rules.pro` 保留這個類別名與 native 方法名（JNI 依名字找符號）。
 */
object NightNative {
    /** C API／JNI 的 ABI 版本，必須等於 `native/include/nightread/nr.h` 的 `NR_ABI_VERSION`。 */
    const val ABI_VERSION: Int = 1

    /** [getRows] 的平面：灰階（BT.601，0..255）。 */
    const val PLANE_GRAY: Int = 0

    /** [getRows] 的平面：彩度 max(R,G,B) − min(R,G,B)。 */
    const val PLANE_CHROMA: Int = 1

    /** [getRows] 的平面：灰階寫成不透明灰 ARGB（可直接 setPixels）。 */
    const val PLANE_GRAY_ARGB: Int = 2

    /** 握手值：(ABI 版本 shl 16) or 規則版本。原生端的 [abi] 必須等於它。 */
    @JvmStatic
    fun expectedAbi(): Int = (ABI_VERSION shl 16) or NightRead.RULES_VERSION

    @Volatile
    private var verified = false

    /**
     * 原生核心可用＝已有某個 .so 提供了這些 native 方法，而且 [abi] 與 [expectedAbi] 相同、[paramsSchema] 與
     * [ParamsFlat.SCHEMA_HASH] 相同（參數攤平格式一致；兩邊都由 native/tools/gen_params.py 從 Params.kt 產生）。
     * 只記住「可用」（之後才載入 .so 的情況，下次再問會重新檢查）。
     */
    @JvmStatic
    fun available(): Boolean {
        if (verified) return true
        val ok = try {
            abi() == expectedAbi() && paramsSchema() == ParamsFlat.SCHEMA_HASH
        } catch (e: UnsatisfiedLinkError) {
            false
        }
        if (ok) verified = true
        return ok
    }

    /** (NR_ABI_VERSION shl 16) or NR_RULES_VERSION。沒有任何 .so 提供實作時丟 [UnsatisfiedLinkError]。 */
    @JvmStatic
    external fun abi(): Int

    /** 參數攤平格式的 schema 雜湊（`nr_params_schema`），必須等於 [ParamsFlat.SCHEMA_HASH]。 */
    @JvmStatic
    external fun paramsSchema(): Long

    /** 建一頁（w、h ≥ 1）；回傳 handle（非 0）。 */
    @JvmStatic
    external fun create(w: Int, h: Int): Long

    /** 釋放 [create] 的 handle；0 不做事。 */
    @JvmStatic
    external fun free(handle: Long)

    /**
     * 放入第 [y0] 起的 [rows] 列 ARGB（Bitmap.getPixels 的格式）：第 r 列取 [argb] 的 [offset]＋r·[stride] 起、頁寬個 int。
     * 原生端當場算好灰階與彩度。
     */
    @JvmStatic
    external fun putRows(handle: Long, y0: Int, rows: Int, argb: IntArray, offset: Int, stride: Int)

    /** 取出第 [y0] 起的 [rows] 列（[plane]：[PLANE_GRAY]／[PLANE_CHROMA]／[PLANE_GRAY_ARGB]），寫法同 [putRows]。這些列必須放過。 */
    @JvmStatic
    external fun getRows(handle: Long, plane: Int, y0: Int, rows: Int, out: IntArray, offset: Int, stride: Int)

    /** 這頁的原生記憶體峰值（位元組）。 */
    @JvmStatic
    external fun peakBytes(handle: Long): Long

    /** 要求取消（任何執行緒都可以呼叫）；之後的長段運算會在段與段之間停下。 */
    @JvmStatic
    external fun cancel(handle: Long)
}
