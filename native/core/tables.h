// 夜讀 C++ 核心：管線用到的常數表（核、查表、線核位置）。每個都要跟 Kotlin 版整表逐位元相同（CppTablesParityTest）。
// 它們用到的 exp／pow／sin／cos 全走 fdlibm（Kotlin 端 2026-10-12 起也全是 StrictMath）。
#pragma once

#include <cstdint>
#include <vector>

namespace nr {
namespace tables {

/** Cv.gaussW(sigma)：整數高斯核（Q16，總和 65536），核長 rint(8σ+1)|1。 */
std::vector<int32_t> gaussW(double sigma);

/** Cv.gaussianBlur 的核長：roundToInt(σ·4·2＋1)|1，至少 3。前置條件：σ 非 NaN。 */
int32_t gaussianBlurKsize(double sigma);
/** Cv.gaussianKernel(ks, sigma)（private）：exp(−0.5/σ²·x·x) 依序加總後各除以總和。 */
std::vector<double> gaussianKernel(int32_t ks, double sigma);

/** Ring.gaussKernel(sigma)：研究端 `_gauss_kernel`，核長 rint(8σ+1)|1。 */
std::vector<double> ringGaussKernel(double sigma);

/** BgObjects.textLut(bg, ink)：256 項。 */
void textLut(int32_t bg, int32_t ink, float out[256]);
/** BgObjects.inkLut(bg, top, inv)：256 項。 */
void inkLut(int32_t bg, int32_t top, bool inv, float out[256]);

/**
 * BgObjects.lineOffsets(len)：8 個方向，每個方向是 (dy, dx) 交錯的位置表。
 * Kotlin 在索引超出 len² 時丟 ArrayIndexOutOfBoundsException（偶數 len 一定會），這裡回 false、out 清空。
 * 注意只檢查攤平後的索引（ry·len＋rx），與 Kotlin 一樣。
 */
bool lineOffsets(int32_t len, std::vector<std::vector<int32_t>>& out);

}  // namespace tables
}  // namespace nr
