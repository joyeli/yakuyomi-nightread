// 夜讀 C++ 核心：第一批影像原語（計畫階段 2 的「5 族熱原語」）。每個函式都要跟 Kotlin 版（Cv.kt）逐位元相同：
// 同樣的輸入，輸出的每個 int／float 位元、CC 的標號順序、CcRuns 陣列的容量都一樣（CppPrimsParityTest、nr_dump_check）。
//
// 規則（native/README.md）：不遞迴、堆疊上沒有大陣列、沒有全域可變狀態、不開執行緒；不同呼叫可在不同執行緒同時跑，唯讀的輸入可以共用。
// 暫存都是 Buf（給了 Budget 就記帳），配置失敗丟 std::bad_alloc。
//
// Kotlin 會丟例外或停不下來的輸入，這裡丟 std::invalid_argument：
//   dilateBits：寬 0、高 > 0 而且核有整段落在錨點左側的列（Kotlin 是 ArrayIndexOutOfBounds）。
//   gaussQ16：寬 0 而且核半徑 > 0（Kotlin 的 reflect101(i, 0) 不會結束）。
#pragma once

#include <cstdint>

#include "types.h"

namespace nr {
namespace prims {

/** Cv.dilateBits：打包列的二值膨脹（[packBits] 格式進出；影像外當 0）。 */
PackedBits dilateBits(const PackedBits& src, const Kernel& k, Budget* b = nullptr);

/** Cv.dilatePacked：packBits → dilateBits → unpackBits。 */
Mask dilatePacked(const Mask& m, const Kernel& k, Budget* b = nullptr);

/** Cv.ccRuns：游程標號（rs／re 的容量與 Kotlin 陣列相同：max(64, 2h) 起倍增，多出來的部分是 0）。 */
CcRuns ccRuns(const Mask& m, int32_t connectivity = 8, Budget* b = nullptr);

/** Cv.ccStats：cv2.connectedComponentsWithStats 的結果（標號＝首見掃描序）。 */
CC ccStats(const Mask& m, int32_t connectivity = 8, Budget* b = nullptr);

/** Cv.distanceL2：精確歐氏（Felzenszwalb），全程 Float 運算。 */
ImgF32 distanceL2(const Mask& m, Budget* b = nullptr);

/** Cv.gaussQ16：Cv.gaussW 的整數核兩趟可分離卷積，輸出 Q16（灰階 ×65536）。 */
ImgI32 gaussQ16(const ImgI32& g, double sigma, Budget* b = nullptr);

/** Cv.morphGrayGather：灰階膨脹（wantMax）／侵蝕，外側不影響極值。 */
ImgI32 morphGrayGather(const ImgI32& g, const Kernel& k, bool wantMax, Budget* b = nullptr);

}  // namespace prims
}  // namespace nr
