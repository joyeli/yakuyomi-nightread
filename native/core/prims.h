// 夜讀 C++ 核心：影像原語。每個函式都要跟 Kotlin 版（Cv.kt）逐位元相同：同樣的輸入，輸出的每個 int／float／double 位元、
// CC 的標號順序、CcRuns 陣列的容量都一樣（CppPrimsParityTest、CppBinParityTest、CppCvCallReplayTest、nr_dump_check）。
//   第 2 階（5 族熱原語）：dilatePacked、dilateBits、ccRuns、ccStats、distanceL2、gaussQ16、morphGrayGather。
//   第 3a 階（二值的其餘部分，Cv.kt :115-573、739-1145、1251-1654、2144-2219、2870-2919、3040-3053）：結構元素、二值形態學
//   （一般核、線核、方核分解、打包格式的侵蝕）、ccRunsBits、distanceSq、chamfer、縮放、補洞、輪廓長、多邊形面積、測地生長、
//   二值中值。位元的底層小工具（含 Ring 的位元集合）在 bits.h。
//
// 規則（native/README.md）：不遞迴、堆疊上沒有大陣列、沒有全域可變狀態、不開執行緒；不同呼叫可在不同執行緒同時跑，唯讀的輸入可以共用。
// 暫存都是 Buf（給了 Budget 就記帳），配置失敗丟 std::bad_alloc。
//
// Kotlin 會丟例外或停不下來的輸入，這裡丟 std::invalid_argument（或 std::length_error：負的尺寸）：
//   dilateBits：寬 0、高 > 0 而且核有整段落在錨點左側的列（Kotlin 是 ArrayIndexOutOfBounds）。
//   gaussQ16：寬 0 而且核半徑 > 0（Kotlin 的 reflect101(i, 0) 不會結束）。
//   其餘見各函式。
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

// ── 第 3a 階 ────────────────────────────────────────────────────────────

/** Cv.ellipse：cv2.getStructuringElement(MORPH_ELLIPSE, (size, size)) 的光柵化。size < 0 丟 std::length_error。 */
Kernel ellipse(int32_t size, Budget* b = nullptr);

/** Cv.rect：kw×kh 全 1。負的尺寸丟 std::length_error（Kotlin 在 kh＝0、kw < 0 時給一個寬是負的空核；呼叫端不會這樣用）。 */
Kernel rect(int32_t kw, int32_t kh, Budget* b = nullptr);

/**
 * Cv.dilate／Cv.erode：1×n、n×1 實心核走線窗（lineMorph），其他核走位元打包。iterations ≤ 0 回輸入的複本（Kotlin 回輸入本身）。
 * 寬 0、高 > 0：同 dilateBits 的規則丟例外（線核不會丟）。
 */
Mask dilate(const Mask& m, const Kernel& k, int32_t iterations = 1, Budget* b = nullptr);
Mask erode(const Mask& m, const Kernel& k, int32_t iterations = 1, Budget* b = nullptr);

/** Cv.open／Cv.close（cv2 MORPH_OPEN／CLOSE）。線核在寬 0、高 > 0 時丟（notBitsInPlace），一般核同 openPacked／closePacked。 */
Mask open(const Mask& m, const Kernel& k, Budget* b = nullptr);
Mask close(const Mask& m, const Kernel& k, Budget* b = nullptr);

/** Cv.erodePacked＝¬dilateBits(¬m)。 */
Mask erodePacked(const Mask& m, const Kernel& k, Budget* b = nullptr);
/** Cv.closePacked＝unpack(erodeBits(dilateBits(pack(m))))；寬 0、高 > 0 丟。 */
Mask closePacked(const Mask& m, const Kernel& k, Budget* b = nullptr);
/** Cv.openPacked＝unpack(dilateBits(erodeBits(pack(m))))；寬 0、高 > 0 丟。 */
Mask openPacked(const Mask& m, const Kernel& k, Budget* b = nullptr);

/** Cv.erodeBits：打包位元的侵蝕＝¬dilateBits(¬src)（不改 src）；寬 0、高 > 0 丟。 */
PackedBits erodeBits(const PackedBits& src, const Kernel& k, Budget* b = nullptr);

/** Cv.dilateRectSep：kw×kh 方核膨脹＝橫線再直線（全程打包）。 */
Mask dilateRectSep(const Mask& m, int32_t kw, int32_t kh, Budget* b = nullptr);
/** Cv.closeRectSep：n×n 方核閉運算（全程打包）；n ≤ 1 回複本；寬 0、高 > 0 而且 n > 1 丟。 */
Mask closeRectSep(const Mask& m, int32_t n, Budget* b = nullptr);

/** Cv.ccRunsBits：ccRuns 的列打包輸入版（背景外接框填 0）。 */
CcRuns ccRunsBits(const PackedBits& bits, int32_t connectivity = 8, Budget* b = nullptr);

/** Cv.DIST_SQ_INF／DIST_SQ_SAT。 */
constexpr int32_t kDistSqInf = INT32_MAX;
constexpr int64_t kDistSqSat = INT32_MAX - 1LL;
/** Cv.distanceSq：精確的平方歐氏距離（全程整數；沒有背景＝kDistSqInf、超過 kDistSqSat 存 kDistSqSat）。 */
ImgI32 distanceSq(const Mask& m, Budget* b = nullptr);

/** Cv.distanceChamfer：cv2.distanceTransform(DIST_L2, 3／5) 的非 IPP 定點版。maskSize 不是 3 或 5 丟 std::invalid_argument。 */
ImgF32 distanceChamfer(const Mask& m, int32_t maskSize, Budget* b = nullptr);
/** Cv.chamfer5Padded：5×5 chamfer 的 Int 定點表，外圍補 2 px（寬 w+4、高 h+4）。 */
ImgI32 chamfer5Padded(const Mask& m, Budget* b = nullptr);

/**
 * Cv.resizeAreaOf：INTER_AREA 縮放，來源像素由 src(索引) 給（float）。Kotlin 是 inline 函式（lambda 只算值，沒有副作用）。
 * nw、nh 都 ≥ 0（負的丟 std::length_error）。
 */
template <class Src>
ImgF32 resizeAreaOf(int32_t w, int32_t h, int32_t nw, int32_t nh, Src&& src, Budget* b = nullptr);
/** Cv.resizeArea：同尺寸回複本，否則 resizeAreaOf。 */
ImgF32 resizeArea(const ImgF32& f, int32_t nw, int32_t nh, Budget* b = nullptr);
/** Cv.resizeBilinear：同尺寸回複本。來源寬或高是 0 而輸出非空時丟 std::invalid_argument（Kotlin 的 coerceIn 丟）。 */
ImgF32 resizeBilinear(const ImgF32& f, int32_t nw, int32_t nh, Budget* b = nullptr);
/** Cv.resizeNearest：同尺寸回複本。來源是空的而輸出非空時丟 std::out_of_range（Kotlin 越界）。 */
Mask resizeNearest(const Mask& m, int32_t nw, int32_t nh, Budget* b = nullptr);
/** Cv.resizeNearestGray：同上（灰階）。 */
ImgI32 resizeNearestGray(const ImgI32& g, int32_t nw, int32_t nh, Budget* b = nullptr);

/** Cv.holes：非前景、且從影像外側經 4-連通背景到不了的像素。 */
Mask holes(const Mask& m, Budget* b = nullptr);
/** Cv.totalContourLength：cv2.findContours(RETR_LIST, CHAIN_APPROX_NONE) 全部輪廓的閉合折線長總和（Suzuki 追蹤逐點復刻）。 */
double totalContourLength(const Mask& m, Budget* b = nullptr);
/** Cv.polygonArea：cv2.contourArea（shoelace 絕對值）；xs、ys 各 n 個。 */
double polygonArea(const float* xs, const float* ys, int32_t n) noexcept;

/**
 * Cv.geodesicDistance：每個像素第一次被到達時的累計膨脹次數（種子 0、未到達 -1）。seed 與 within 同尺寸（不同丟 std::invalid_argument）。
 */
ImgI32 geodesicDistance(const Mask& seed, const Mask& within, int32_t step, int32_t maxIters, Budget* b = nullptr);
/**
 * Cv.geodesicGrow：bfs＝-1（null，依成本選）、0（迭代）、1（BFS）。迭代版在寬 0、高 > 0、iters > 0、step > 0 時丟
 * std::invalid_argument（Kotlin 的 dilate3Bits 越界）。seed 與 within 同尺寸。
 */
Mask geodesicGrow(const Mask& seed, const Mask& within, int32_t iters, int32_t step = 5, int32_t bfs = -1, Budget* b = nullptr);

/** Cv.medianBlurMask：二值中值（多數決，BORDER_REPLICATE）。r < 0 丟 std::invalid_argument；影像寬或高是 0 而 r ≥ 2 時也丟（Kotlin 的 coerceIn）。 */
Mask medianBlurMask(const Mask& m, int32_t r, Budget* b = nullptr);

}  // namespace prims
}  // namespace nr

#include "prims_resize.inl"
