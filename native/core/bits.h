// 夜讀 C++ 核心：位元的底層小工具（計畫階段 3a）。移植自 nightread/src/main/kotlin/li/joye/yakuyomi/nightread/Cv.kt 與 Ring.kt @ a951b7c：
//   列打包（Cv.packBits 格式）的取反打包／取反解包／就地取反（packBitsNot、unpackBitsNot、notBitsInPlace）、
//   一列的游程（bitRuns）、一段有沒有 1（anyBits）、BORDER_REFLECT_101（reflect101），
//   以及 Ring 的整頁位元集合（Ring.packBits／set／has；格式：第 i 像素在第 i>>6 字的第 i&63 位，不分列）。
// Ring 的三個小工具在 Kotlin 屬於灰圈模組，但效果線、背景物件、合成都在用：C++ 把它們放在最底層，上面各模組都可以用，不必互相依賴。
//
// 跟 Kotlin 逐位元相同；Kotlin 會丟例外的輸入這裡丟 std::invalid_argument（各函式註明）。細粒度的（hasBit、setBit、anyBits、bitRuns、
// reflect101）不檢查範圍：越界是呼叫端的錯（Kotlin 是 ArrayIndexOutOfBounds）。
#pragma once

#include <cstdint>

#include "types.h"

namespace nr {

// ── 整頁位元集合（Ring）────────────────────────────────────────────────

/** Ring.packBits 的結果：n＝像素數、words 長 (n+63)/64，末字超出 n 的位元一律 0。 */
struct FlatBits {
    int64_t n = 0;
    Buf<uint64_t> words;
};

/** Ring.set：第 i 位設成 1。 */
inline void setBit(uint64_t* bits, int64_t i) noexcept { bits[i >> 6] |= uint64_t(1) << (i & 63); }

/** Ring.has：bits 是 nullptr 時回 false（同 Kotlin 的 LongArray?）。 */
inline bool hasBit(const uint64_t* bits, int64_t i) noexcept {
    return bits != nullptr && ((bits[i >> 6] >> (i & 63)) & 1) != 0;
}

/** Ring.packBits：遮罩 → 整頁位元集合（逐字組好再寫）。 */
FlatBits packFlat(const Mask& m, Budget* b = nullptr);

// ── 列打包（Cv.packBits 格式）──────────────────────────────────────────────

/** Cv.packBitsNot：¬m 的列打包（只取反影像內的位元：末字超出寬度的位元照樣是 0）。 */
PackedBits packBitsNot(const Mask& m, Budget* b = nullptr);

/** Cv.unpackBitsNot：解包再取反（每個像素都寫）。 */
Mask unpackBitsNot(const PackedBits& a, Budget* b = nullptr);

/**
 * Cv.notBitsInPlace：就地取反（只取反影像內的位元：末字超出寬度的位元維持 0）。
 * 寬 0、高 > 0 時丟 std::invalid_argument（Kotlin 寫 a[y·nw + nw − 1] 越界）。
 */
void notBitsInPlace(PackedBits& a);

/**
 * Cv.bitRuns：列打包位元第 off 字起的一列（nw 字）→ 游程 [s, e)，依序寫進 rs／re，回傳段數（跨字的段接起來）。
 * rs／re 至少要 (64·nw + 1) / 2 格（呼叫端保證）。
 */
int32_t bitRuns(const uint64_t* bits, int64_t off, int32_t nw, int32_t* rs, int32_t* re) noexcept;

/** Cv.anyBits：列打包位元第 off 字起那一列，在 [s, e) 裡有沒有任何一位是 1（s ≥ e 回 false；前置條件 0 ≤ s、e ≤ 64·nw）。 */
bool anyBits(const uint64_t* bits, int64_t off, int32_t s, int32_t e) noexcept;

/** Cv.reflect101（BORDER_REFLECT_101：abcd | cba，邊界像素不重複）。前置條件 n ≥ 1（n ≤ 0 時 Kotlin 不會結束）。 */
inline int32_t reflect101(int32_t i, int32_t n) noexcept {
    if (n == 1) return 0;
    int32_t x = i;
    while (x < 0 || x >= n) {
        if (x < 0) x = -x;
        if (x >= n) x = 2 * (n - 1) - x;
    }
    return x;
}

}  // namespace nr
