// prims.h 的樣板本體：Cv.resizeAreaOf（Kotlin 是 inline 函式，來源像素由 lambda 給）。移植自 Cv.kt @ a951b7c，運算順序逐字照抄：
// Int·Double 是 Int 先轉 Double、kotlin.math.min／max(Double) 是 Math.min／max（jm::min／max）、floor／ceil 再 toInt 是飽和轉型（jm::d2i）。
#pragma once

#include "jmath.h"

namespace nr {
namespace prims {

template <class Src>
ImgF32 resizeAreaOf(int32_t w, int32_t h, int32_t nw, int32_t nh, Src&& src, Budget* b) {
    const double sx = static_cast<double>(w) / nw;
    const double sy = static_cast<double>(h) / nh;
    ImgF32 outImg(nw, nh, b);
    float* out = outImg.data.data();
    const double hd = static_cast<double>(h), wd = static_cast<double>(w);
    for (int32_t oy = 0; oy < nh; oy++) {
        const double y0 = static_cast<double>(oy) * sy;
        const double y1 = jm::min(hd, static_cast<double>(oy + 1) * sy);
        const int32_t iy0 = jm::d2i(jm::floor(y0));
        const int32_t iy1 = jm::min(h - 1, jm::d2i(jm::ceil(y1)) - 1);
        for (int32_t ox = 0; ox < nw; ox++) {
            const double x0 = static_cast<double>(ox) * sx;
            const double x1 = jm::min(wd, static_cast<double>(ox + 1) * sx);
            const int32_t ix0 = jm::d2i(jm::floor(x0));
            const int32_t ix1 = jm::min(w - 1, jm::d2i(jm::ceil(x1)) - 1);
            double acc = 0.0;
            double wsum = 0.0;
            for (int32_t y = iy0; y <= iy1; y++) {
                const double wy = jm::min(static_cast<double>(y + 1), y1) - jm::max(static_cast<double>(y), y0);
                if (wy <= 0) continue;
                for (int32_t x = ix0; x <= ix1; x++) {
                    const double wx = jm::min(static_cast<double>(x + 1), x1) - jm::max(static_cast<double>(x), x0);
                    if (wx <= 0) continue;
                    const double ww = wx * wy;
                    acc += ww * static_cast<double>(src(y * w + x));
                    wsum += ww;
                }
            }
            out[static_cast<size_t>(oy) * nw + ox] = wsum > 0 ? static_cast<float>(acc / wsum) : 0.0f;
        }
    }
    return outImg;
}

}  // namespace prims
}  // namespace nr
