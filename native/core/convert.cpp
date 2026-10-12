// 夜讀 C++ 核心：像素轉換。語意見 convert.h。
#include "convert.h"

namespace nr {

void argbToGrayChroma(const int32_t* argb, int64_t n, int32_t* gray, int32_t* chroma) noexcept {
    for (int64_t i = 0; i < n; i++) {
        const int32_t p = argb[i];
        const int32_t r = (p >> 16) & 0xFF;
        const int32_t g = (p >> 8) & 0xFF;
        const int32_t b = p & 0xFF;
        gray[i] = (r * 299 + g * 587 + b * 114 + 500) / 1000;
        int32_t mx = r, mn = r;
        if (g > mx) mx = g;
        if (b > mx) mx = b;
        if (g < mn) mn = g;
        if (b < mn) mn = b;
        chroma[i] = mx - mn;
    }
}

void grayToArgb(const int32_t* g, int64_t n, int32_t* argb) noexcept {
    for (int64_t i = 0; i < n; i++) {
        int32_t v = g[i];
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        argb[i] = static_cast<int32_t>(0xFF000000U | (static_cast<uint32_t>(v) << 16) | (static_cast<uint32_t>(v) << 8) |
                                       static_cast<uint32_t>(v));
    }
}

}  // namespace nr
