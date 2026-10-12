// 夜讀 C++ 核心：常數表。移植自 Cv.kt（gaussW、gaussianBlur 核長、gaussianKernel）、Ring.kt（gaussKernel）、
// BgObjects.kt（textLut、inkLut、lineOffsets）@ 27d1e96（數學函式依 2026-10-12 的 StrictMath 改動）。
// 運算順序與型別逐處照抄 Kotlin（Int 與 Double 混算時 Int 先轉 Double；a * b * c 由左往右）。
#include "tables.h"

#include "jmath.h"

namespace nr {
namespace tables {

std::vector<int32_t> gaussW(double sigma) {
    // val ks = Math.rint(sigma * 8 + 1).toInt() or 1
    const int32_t ks = jm::d2i(jm::rint(sigma * 8.0 + 1.0)) | 1;
    const int32_t c = ks / 2;
    std::vector<double> k(static_cast<size_t>(ks));
    for (int32_t i = 0; i < ks; i++) {
        const double d = static_cast<double>(i - c);
        k[i] = jm::exp(-(d * d) / (2.0 * sigma * sigma));
    }
    double t = 0.0;
    for (double v : k) t += v;
    std::vector<int32_t> w(static_cast<size_t>(ks));
    for (int32_t i = 0; i < ks; i++) w[i] = jm::d2i(jm::floor(k[i] / t * 65536.0 + 0.5));
    int32_t sum = 0;
    for (int32_t v : w) sum = jm::wadd(sum, v);
    w[c] = jm::wadd(w[c], jm::wsub(65536, sum));
    return w;
}

int32_t gaussianBlurKsize(double sigma) {
    // var ks = (sigma * 4.0 * 2.0 + 1.0).roundToInt() or 1; if (ks < 3) ks = 3
    int32_t ks = jm::roundToInt(sigma * 4.0 * 2.0 + 1.0) | 1;
    if (ks < 3) ks = 3;
    return ks;
}

std::vector<double> gaussianKernel(int32_t ks, double sigma) {
    std::vector<double> k(static_cast<size_t>(ks));
    const double scale2X = -0.5 / (sigma * sigma);
    double sum = 0.0;
    for (int32_t i = 0; i < ks; i++) {
        const double x = static_cast<double>(i) - static_cast<double>(ks - 1) * 0.5;
        const double t = jm::exp(scale2X * x * x);
        k[i] = t;
        sum += t;
    }
    for (int32_t i = 0; i < ks; i++) k[i] /= sum;
    return k;
}

std::vector<double> ringGaussKernel(double sigma) {
    const int32_t ks = jm::d2i(jm::rint(sigma * 8.0 + 1.0)) | 1;
    const double c = -0.5 / (sigma * sigma);
    std::vector<double> t(static_cast<size_t>(ks));
    double s = 0.0;
    for (int32_t i = 0; i < ks; i++) {
        const double x = static_cast<double>(i) - static_cast<double>(ks - 1) * 0.5;
        t[i] = jm::exp(c * x * x);
        s += t[i];
    }
    for (int32_t i = 0; i < ks; i++) t[i] = t[i] / s;
    return t;
}

void textLut(int32_t bg, int32_t ink, float out[256]) {
    for (int32_t it = 0; it < 256; it++) {
        const double av = jm::pow((255.0 - static_cast<double>(it)) / 255.0, 1.4);
        const double a3 = jm::min(1.0, jm::max(0.0, (av - 0.35) / 0.65));
        out[it] = jm::d2f(jm::floor(static_cast<double>(bg) + a3 * static_cast<double>(jm::wsub(ink, bg))));
    }
}

void inkLut(int32_t bg, int32_t top, bool inv, float out[256]) {
    for (int32_t it = 0; it < 256; it++) {
        const double a = inv ? jm::pow((255.0 - static_cast<double>(it)) / 255.0, 1.4)
                             : jm::pow(static_cast<double>(it) / 255.0, 1.4);
        out[it] = jm::d2f(jm::floor(static_cast<double>(bg) + a * static_cast<double>(jm::wsub(top, bg))));
    }
}

bool lineOffsets(int32_t len, std::vector<std::vector<int32_t>>& out) {
    out.clear();
    const int32_t c = jm::div(len, 2);
    const int32_t n = jm::wmul(4, len);
    const int32_t cells = jm::wmul(len, len);                    // Kotlin BooleanArray(len * len)：Int 環繞
    if (cells < 0) return false;                                 // NegativeArraySizeException
    std::vector<uint8_t> k;
    for (int32_t d = 0; d < 8; d++) {
        const double t = static_cast<double>(d) * (3.141592653589793 / 8.0);   // d * (Math.PI / 8)
        k.assign(static_cast<size_t>(cells), 0);
        const double step = (2.0 * static_cast<double>(c)) / static_cast<double>(n - 1);
        for (int32_t i = 0; i < n; i++) {
            const double u = (i == n - 1) ? static_cast<double>(c)
                                          : static_cast<double>(i) * step + static_cast<double>(-c);
            const int32_t ry = jm::d2i(jm::rint(static_cast<double>(c) + u * jm::sin(t)));
            const int32_t rx = jm::d2i(jm::rint(static_cast<double>(c) + u * jm::cos(t)));
            const int32_t idx = jm::wadd(jm::wmul(ry, len), rx);
            if (idx < 0 || idx >= cells) { out.clear(); return false; }
            k[static_cast<size_t>(idx)] = 1;
        }
        std::vector<int32_t> pts;
        for (int32_t i = 0; i < cells; i++) {
            if (k[static_cast<size_t>(i)]) { pts.push_back(jm::div(i, len) - c); pts.push_back(jm::rem(i, len) - c); }
        }
        out.push_back(std::move(pts));
    }
    return true;
}

}  // namespace tables
}  // namespace nr
