// 夜讀 C++ 核心：第 3b 階影像原語（灰階形態學、可分離濾波、最大值濾波、整數高斯的取樣版與單點版、uint8 中值、Canny）。移植自
// nightread/src/main/kotlin/li/joye/yakuyomi/nightread/Cv.kt @ d48c5d3：morphGray（dilateGray／erodeGray／dilateGrayI／erodeGrayI）、
// morphMerge、partialExtreme、slidingExtreme、blackhat、gaussianBlur、boxBlur、sepFilter、maxFilterWrapRows、GrayRows、gaussQ16Sparse、
// MedianRows、medianBlur8、canny；gaussAt 移植自 BgObjects.kt @ d48c5d3（同一次改動搬到 Cv，算法一字不改）。
// 逐步照抄 Kotlin 的算法、運算順序與型別（Int 環繞：-fwrapv；Float 乘加不合成：-ffp-contract=off）。Kotlin 陣列會越界的地方在同一個
// 位置檢查、丟 std::out_of_range；不遞迴、堆疊上沒有大陣列、沒有全域可變狀態。
#include <cstring>
#include <stdexcept>
#include <vector>

#include "bits.h"
#include "jmath.h"
#include "prims.h"
#include "tables.h"

namespace nr {
namespace prims {

namespace {

inline int32_t imin(int32_t a, int32_t b) { return a < b ? a : b; }
inline int32_t imax(int32_t a, int32_t b) { return a > b ? a : b; }
inline int32_t iabs(int32_t a) { return a < 0 ? -a : a; }

[[noreturn]] void oob(const char* what) { throw std::out_of_range(what); }

/** JVM 的陣列索引檢查（Int 索引 i 對長度 n）。 */
inline size_t at(int64_t i, size_t n, const char* what) {
    if (i < 0 || static_cast<uint64_t>(i) >= n) oob(what);
    return static_cast<size_t>(i);
}

/** Kotlin Int.coerceIn(lo, hi)：lo > hi 丟（IllegalArgumentException）。 */
inline int32_t coerceInChecked(int32_t v, int32_t lo, int32_t hi) {
    if (lo > hi) throw std::invalid_argument("coerceIn: empty range");
    return v < lo ? lo : (v > hi ? hi : v);
}

/** Java 陣列長度：負的丟 std::length_error（NegativeArraySizeException）。 */
inline size_t arrLen(int64_t n) {
    if (n < 0) throw std::length_error("negative array size");
    return static_cast<size_t>(n);
}

/** System.arraycopy 的範圍檢查（長度負的、任一邊越界都丟）。 */
inline void arraycopyCheck(int64_t srcPos, size_t srcLen, int64_t dstPos, size_t dstLen, int64_t len) {
    if (len < 0 || srcPos < 0 || dstPos < 0 || srcPos + len > static_cast<int64_t>(srcLen) || dstPos + len > static_cast<int64_t>(dstLen))
        oob("arraycopy out of bounds");
}

/** reflect101，n ≤ 0 時丟（Kotlin 停不下來）。 */
inline int32_t reflectChecked(int32_t i, int32_t n) {
    if (n <= 0) throw std::invalid_argument("reflect101: n <= 0 (Kotlin does not terminate)");
    return reflect101(i, n);
}

// ── 灰階形態學 ───────────────────────────────────────────────────────────

inline int32_t morphMerge(int32_t cur, int32_t v, bool wantMax) { return wantMax ? jm::max(cur, v) : jm::min(cur, v); }

int32_t partialExtreme(const int32_t* row, int32_t w, int32_t a0, int32_t win, bool wantMax) {
    const int32_t b0 = a0 + win - 1;
    const int32_t lo = imax(a0, 0);
    const int32_t hi = imin(b0, w - 1);
    if (lo > hi) return wantMax ? lo : hi;   // 照抄 Kotlin：整段在界外時回界內段的端點座標（含錨點的核不會走到）
    int32_t acc = row[lo];
    for (int32_t j = lo + 1; j <= hi; j++) acc = wantMax ? jm::max(acc, row[j]) : jm::min(acc, row[j]);
    return acc;
}

/** 一維滑動極值（van Herk／Gil-Werman）；n ＝ 0 時 Kotlin 讀 a[0] 越界。 */
void slidingExtreme(const int32_t* a, int32_t n, int32_t win, bool wantMax, int32_t* dst, int32_t* pre, int32_t* suf) {
    if (win >= n) {
        if (n <= 0) oob("slidingExtreme: empty row");
        int32_t acc = a[0];
        for (int32_t i = 1; i < n; i++) acc = wantMax ? jm::max(acc, a[i]) : jm::min(acc, a[i]);
        dst[0] = acc;
        return;
    }
    int32_t i = 0;
    while (i < n) {
        const int32_t end = imin(i + win, n);
        int32_t acc = a[i];
        pre[i] = acc;
        if (wantMax) {
            for (int32_t j = i + 1; j < end; j++) { if (a[j] > acc) acc = a[j]; pre[j] = acc; }
            acc = a[end - 1];
            suf[end - 1] = acc;
            for (int32_t j = end - 2; j >= i; j--) { if (a[j] > acc) acc = a[j]; suf[j] = acc; }
        } else {
            for (int32_t j = i + 1; j < end; j++) { if (a[j] < acc) acc = a[j]; pre[j] = acc; }
            acc = a[end - 1];
            suf[end - 1] = acc;
            for (int32_t j = end - 2; j >= i; j--) { if (a[j] < acc) acc = a[j]; suf[j] = acc; }
        }
        i = end;
    }
    int32_t phase = 0;   // x mod win
    for (int32_t x = 0; x <= n - win; x++) {
        const int32_t hiI = x + win - 1;
        dst[x] = phase == 0 ? pre[hiI] : (wantMax ? jm::max(suf[x], pre[hiI]) : jm::min(suf[x], pre[hiI]));
        phase++;
        if (phase == win) phase = 0;
    }
}

}  // namespace

ImgI32 morphGray(const ImgI32& g, const Kernel& k, bool wantMax, int32_t lo, int32_t hi, Budget* b) {
    const int32_t w = g.w, h = g.h;
    ImgI32 outImg(w, h, b);
    int32_t* od = outImg.data.data();
    const int32_t fillv = wantMax ? lo : hi;
    for (size_t i = 0; i < outImg.data.size(); i++) od[i] = fillv;
    const size_t sw = static_cast<size_t>(w);
    const size_t skh = static_cast<size_t>(k.h);
    Buf<int32_t> rowB(sw, b);
    int32_t* row = rowB.data();
    // 不同的 run 寬度（核列順序首見）與每個核列用哪一個
    Buf<int32_t> widthOf(skh, b), widths(skh, b);
    int32_t nW = 0;
    for (int32_t ky = 0; ky < k.h; ky++) {
        widthOf[ky] = -1;
        const int32_t win = k.runEnd[ky] - k.runStart[ky];
        if (win <= 0) continue;
        int32_t j = 0;
        while (j < nW && widths[j] != win) j++;
        if (j == nW) { widths[nW] = win; nW++; }
        widthOf[ky] = j;
    }
    Buf<int32_t> slidesB(static_cast<size_t>(nW) * sw, b), pre(sw, b), suf(sw, b);
    int32_t* slides = slidesB.data();
    const int32_t* gd = g.data.data();
    // 以「來源列」為外圈：每條來源行只讀一次、每種寬度只掃一次，再散到所有用得到它的輸出列
    for (int32_t sy = 0; sy < h; sy++) {
        if (w > 0) std::memcpy(row, gd + static_cast<size_t>(sy) * sw, sw * sizeof(int32_t));
        for (int32_t j = 0; j < nW; j++) slidingExtreme(row, w, widths[j], wantMax, slides + static_cast<size_t>(j) * sw, pre.data(), suf.data());
        for (int32_t ky = 0; ky < k.h; ky++) {
            const int32_t wi = widthOf[ky];
            if (wi < 0) continue;
            const int32_t y = sy - (ky - k.ay);
            if (y < 0 || y >= h) continue;
            const int32_t win = widths[wi];
            const int32_t* slide = slides + static_cast<size_t>(wi) * sw;
            const int32_t offL = k.runStart[ky] - k.ax;
            int32_t* o = od + static_cast<size_t>(y) * sw;
            const int32_t xa = imax(0, -offL);
            const int32_t xb = imin(w - 1, w - win - offL);
            if (xa > xb) {
                for (int32_t x = 0; x < w; x++) o[x] = morphMerge(o[x], partialExtreme(row, w, x + offL, win, wantMax), wantMax);
                continue;
            }
            for (int32_t x = 0; x < xa; x++) o[x] = morphMerge(o[x], partialExtreme(row, w, x + offL, win, wantMax), wantMax);
            if (wantMax) {
                for (int32_t x = xa; x <= xb; x++) { const int32_t v = slide[x + offL]; if (v > o[x]) o[x] = v; }
            } else {
                for (int32_t x = xa; x <= xb; x++) { const int32_t v = slide[x + offL]; if (v < o[x]) o[x] = v; }
            }
            for (int32_t x = xb + 1; x < w; x++) o[x] = morphMerge(o[x], partialExtreme(row, w, x + offL, win, wantMax), wantMax);
        }
    }
    return outImg;
}

ImgI32 dilateGray(const ImgI32& g, const Kernel& k, Budget* b) { return morphGray(g, k, true, 0, 255, b); }
ImgI32 erodeGray(const ImgI32& g, const Kernel& k, Budget* b) { return morphGray(g, k, false, 0, 255, b); }
ImgI32 dilateGrayI(const ImgI32& g, const Kernel& k, Budget* b) { return morphGray(g, k, true, INT32_MIN, INT32_MAX, b); }
ImgI32 erodeGrayI(const ImgI32& g, const Kernel& k, Budget* b) { return morphGray(g, k, false, INT32_MIN, INT32_MAX, b); }

ImgI32 blackhat(const ImgI32& g, const Kernel& k, Budget* b) {
    bool anchored = true;
    for (int32_t ky = 0; ky < k.h; ky++) if (k.runStart[ky] > k.ax || k.runEnd[ky] <= k.ax) { anchored = false; break; }
    if (anchored) for (size_t i = 0; i < g.data.size(); i++) if (g.data[i] < 0 || g.data[i] > 255) { anchored = false; break; }
    ImgI32 closed = anchored ? morphGrayGather(morphGrayGather(g, k, true, b), k, false, b) : erodeGray(dilateGray(g, k, b), k, b);
    int32_t* d = closed.data.data();
    const int32_t* gd = g.data.data();
    for (size_t i = 0; i < closed.data.size(); i++) {
        const int32_t v = jm::wsub(d[i], gd[i]);
        d[i] = v > 0 ? v : 0;
    }
    return closed;
}

// ── 濾波 ─────────────────────────────────────────────────────────────────

ImgF32 sepFilter(const ImgF32& f, const double* k, int32_t n, Budget* b) {
    const int32_t w = f.w, h = f.h;
    const int32_t r = n / 2;
    Buf<float> kfB(arrLen(n), b);
    float* kf = kfB.data();
    for (int32_t t = 0; t < n; t++) kf[t] = static_cast<float>(k[t]);
    const size_t sw = static_cast<size_t>(w);
    ImgF32 midImg(w, h, b);
    float* mid = midImg.data.data();
    const float* fd = f.data.data();

    // 橫向
    for (int32_t y = 0; y < h; y++) {
        const size_t base = static_cast<size_t>(y) * sw;
        // 左緣
        const int32_t le = imin(r, w);
        for (int32_t x = 0; x < le; x++) {
            float acc = 0.0f;
            for (int32_t t = 0; t < n; t++) acc += kf[t] * fd[base + static_cast<size_t>(reflect101(x + t - r, w))];
            mid[base + x] = acc;
        }
        // 中段：索引一定在界內
        const int32_t hiX = w - r;
        for (int32_t x = r; x < hiX; x++) {
            float acc = 0.0f;
            const float* p = fd + base + static_cast<size_t>(x - r);
            for (int32_t t = 0; t < n; t++) acc += kf[t] * p[t];
            mid[base + x] = acc;
        }
        // 右緣
        for (int32_t x = imax(r, hiX); x < w; x++) {
            float acc = 0.0f;
            for (int32_t t = 0; t < n; t++) acc += kf[t] * fd[base + static_cast<size_t>(reflect101(x + t - r, w))];
            mid[base + x] = acc;
        }
    }

    // 縱向
    ImgF32 outImg(w, h, b);
    float* out = outImg.data.data();
    for (int32_t y = 0; y < h; y++) {
        float* o = out + static_cast<size_t>(y) * sw;
        if (y < r || y >= h - r) {
            for (int32_t x = 0; x < w; x++) {
                float acc = 0.0f;
                for (int32_t t = 0; t < n; t++) acc += kf[t] * mid[static_cast<size_t>(reflect101(y + t - r, h)) * sw + x];
                o[x] = acc;
            }
        } else {
            for (int32_t t = 0; t < n; t++) {
                const float c = kf[t];
                const float* s = mid + static_cast<size_t>(y + t - r) * sw;
                if (t == 0) {
                    for (int32_t x = 0; x < w; x++) o[x] = c * s[x];
                } else {
                    for (int32_t x = 0; x < w; x++) o[x] += c * s[x];
                }
            }
        }
    }
    return outImg;
}

ImgF32 gaussianBlur(const ImgF32& f, double sigma, Budget* b) {
    if (sigma <= 0.0) {
        ImgF32 c(f.w, f.h, b);
        if (c.data.size() > 0) std::memcpy(c.data.data(), f.data.data(), c.data.size() * sizeof(float));
        return c;
    }
    if (jm::isNaN(sigma)) throw std::invalid_argument("Cannot round NaN value.");
    const int32_t ks = tables::gaussianBlurKsize(sigma);
    // DoubleArray(ks)：超過 Java 陣列上限（Int.MAX_VALUE − 8 附近）Kotlin 一定是 OutOfMemoryError
    if (ks > INT32_MAX - 8) throw std::bad_alloc();
    const std::vector<double> k = tables::gaussianKernel(ks, sigma);
    return sepFilter(f, k.data(), ks, b);
}

ImgF32 boxBlur(const ImgF32& f, int32_t k, Budget* b) {
    std::vector<double> kern(arrLen(k));
    for (int32_t i = 0; i < k; i++) kern[i] = 1.0 / static_cast<double>(k);
    return sepFilter(f, kern.data(), k, b);
}

// ── 最大值濾波（列環狀）───────────────────────────────────────────────────

ImgI32 maxFilterWrapRows(const int32_t* s, size_t sLen, int32_t rows, int32_t cols, int32_t nt, int32_t nr, Budget* b) {
    // 欄方向：van Herk／Gil–Werman（分塊前綴／後綴極大），兩側各補 nr 個 0
    const int32_t k = 2 * nr + 1;
    const int32_t pl = cols + 2 * nr;
    const size_t spl = arrLen(pl);
    Buf<int32_t> padB(spl, b), preB(spl, b), sufB(spl, b);
    int32_t* pad = padB.data();
    int32_t* pre = preB.data();
    int32_t* suf = sufB.data();
    const int32_t rc = rows * cols;   // IntArray(rows * cols)：Int 環繞，負的丟
    Buf<int32_t> tmpB(arrLen(rc), b);
    int32_t* tmp = tmpB.data();
    const size_t ntmp = tmpB.size();
    for (int32_t r = 0; r < rows; r++) {
        const int32_t base = r * cols;
        arraycopyCheck(base, sLen, nr, spl, cols);
        if (cols > 0) std::memcpy(pad + nr, s + base, static_cast<size_t>(cols) * sizeof(int32_t));
        int32_t i = 0;
        while (i < pl) {
            const int32_t e = imin(i + k, pl);
            pre[at(i, spl, "maxFilterWrapRows pre")] = pad[i];
            for (int32_t j = i + 1; j < e; j++) pre[j] = jm::max(pre[j - 1], pad[j]);
            suf[at(int64_t(e) - 1, spl, "maxFilterWrapRows suf")] = pad[e - 1];
            for (int32_t j = e - 2; j >= i; j--) suf[j] = jm::max(suf[j + 1], pad[j]);
            i = e;
        }
        // 窗 [c, c+k−1]（補零後座標）＝ max(suf[c], pre[c+k−1])
        for (int32_t c = 0; c < cols; c++) {
            const int32_t sv = suf[at(c, spl, "maxFilterWrapRows suf")];
            const int32_t pv = pre[at(int64_t(c) + k - 1, spl, "maxFilterWrapRows pre")];
            tmp[at(int64_t(base) + c, ntmp, "maxFilterWrapRows tmp")] = jm::max(sv, pv);
        }
    }
    // 列方向（θ）：環狀、窗只有 2nt+1 列，直接取
    ImgI32 outImg(cols, rows, b);
    int32_t* out = outImg.data.data();
    for (int32_t r = 0; r < rows; r++) {
        const int32_t base = r * cols;
        if (cols > 0) std::memcpy(out + base, tmp + base, static_cast<size_t>(cols) * sizeof(int32_t));
        for (int32_t q = 1; q <= nt; q++) {
            const int32_t ub = (((r - q) % rows + rows) % rows) * cols;
            const int32_t db = ((r + q) % rows) * cols;
            for (int32_t c = 0; c < cols; c++) {
                int32_t v = tmp[ub + c]; if (v > out[base + c]) out[base + c] = v;
                v = tmp[db + c]; if (v > out[base + c]) out[base + c] = v;
            }
        }
    }
    return outImg;
}

// ── 整數高斯：單點（原 BgObjects.gaussAt）──────────────────────────────────

int32_t gaussAt(const ImgI32& g, const int32_t* k, int32_t ks, int32_t x, int32_t y) {
    const int32_t w = g.w, h = g.h;
    const int32_t* gd = g.data.data();
    const size_t n = g.data.size();
    const size_t sks = static_cast<size_t>(ks < 0 ? 0 : ks);
    const int32_t c = ks / 2;
    const int32_t kc = k[at(c, sks, "gaussAt: k[c]")];
    // 窗整個在影像內的不必反射（reflect101 在界內是恆等；整數加總與順序無關）
    const bool inX = x >= c && x < w - c;
    int64_t acc = 0;
    for (int32_t j = 0; j < ks; j++) {
        const int32_t kj = k[j];
        if (kj == 0) continue;
        const int32_t yy = y + j - c;
        const int32_t ry = (yy >= 0 && yy < h) ? yy : reflectChecked(yy, h);
        const int32_t r = ry * w;
        int32_t hs = kc * gd[at(int64_t(r + x), n, "gaussAt: g")];
        if (inX) {
            for (int32_t t = 1; t <= c; t++) {
                const int32_t kt = k[at(int64_t(c) + t, sks, "gaussAt: k")];
                hs += kt * (gd[at(int64_t(r + x - t), n, "gaussAt: g")] + gd[at(int64_t(r + x + t), n, "gaussAt: g")]);
            }
        } else {
            for (int32_t t = 1; t <= c; t++) {
                const int32_t kt = k[at(int64_t(c) + t, sks, "gaussAt: k")];
                const int32_t a = gd[at(int64_t(r + reflectChecked(x - t, w)), n, "gaussAt: g")];
                const int32_t bb = gd[at(int64_t(r + reflectChecked(x + t, w)), n, "gaussAt: g")];
                hs += kt * (a + bb);
            }
        }
        acc += static_cast<int64_t>(kj) * hs;
    }
    return static_cast<int32_t>((acc + 32768) >> 16);
}

// ── 整數高斯：取樣版（Cv.gaussQ16Sparse）與它的來源 ─────────────────────────

void GrayRows::fill(int32_t r, const int32_t* rs, const int32_t* re, int32_t n, int32_t* dst, size_t dstLen) {
    const int32_t base = r * g_.w;
    for (int32_t q = 0; q < n; q++) {
        const int32_t len = re[q] - rs[q];
        arraycopyCheck(int64_t(base + rs[q]), g_.data.size(), rs[q], dstLen, len);
        if (len > 0) std::memcpy(dst + rs[q], g_.data.data() + (base + rs[q]), static_cast<size_t>(len) * sizeof(int32_t));
    }
}

MedianRows::MedianRows(const ImgI32& g, int32_t ksize, Budget* b) : g_(g), ksize_(ksize), fine_(256, b), cols_(arrLen(ksize), b) {}

void MedianRows::fill(int32_t r, const int32_t* rs, const int32_t* re, int32_t n, int32_t* dst, size_t dstLen) {
    const int32_t w = g_.w, h = g_.h;
    const int32_t rr = ksize_ / 2;
    const int32_t need = ksize_ * ksize_ / 2 + 1;
    const int32_t* src = g_.data.data();
    const size_t ns = g_.data.size();
    int32_t* fine = fine_.data();
    int32_t* cols = cols_.data();
    const size_t ncols = cols_.size();
    for (int32_t dy = -rr; dy <= rr; dy++) {
        const int32_t v = coerceInChecked(r + dy, 0, h - 1) * w;
        cols[at(int64_t(dy) + rr, ncols, "MedianRows: cols")] = v;
    }
    for (int32_t q = 0; q < n; q++) {
        const int32_t s0 = rs[q];
        const int32_t e0 = re[q];
        std::memset(fine, 0, 256 * sizeof(int32_t));
        for (int32_t dx = -rr; dx <= rr; dx++) {
            const int32_t xx = coerceInChecked(s0 + dx, 0, w - 1);
            for (size_t ci = 0; ci < ncols; ci++) fine[at(src[at(int64_t(cols[ci] + xx), ns, "MedianRows: g")], 256, "MedianRows: value")]++;
        }
        int32_t m = 0;
        int32_t lt = 0;
        while (lt + fine[at(m, 256, "MedianRows: m")] < need) { lt += fine[m]; m++; }
        for (int32_t x = s0; x < e0; x++) {
            dst[at(x, dstLen, "MedianRows: dst")] = m;
            if (x + 1 < e0) {
                const int32_t xo = x - rr < 0 ? 0 : x - rr;
                const int32_t xn = x + 1 + rr > w - 1 ? w - 1 : x + 1 + rr;
                for (size_t ci = 0; ci < ncols; ci++) {
                    const int32_t cb = cols[ci];
                    const int32_t a = src[at(int64_t(cb + xo), ns, "MedianRows: g")];
                    fine[at(a, 256, "MedianRows: value")]--;
                    if (a < m) lt--;
                    const int32_t bv = src[at(int64_t(cb + xn), ns, "MedianRows: g")];
                    fine[at(bv, 256, "MedianRows: value")]++;
                    if (bv < m) lt++;
                }
                while (lt >= need) { m--; lt -= fine[at(m, 256, "MedianRows: m")]; }
                while (lt + fine[at(m, 256, "MedianRows: m")] < need) { lt += fine[m]; m++; }
            }
        }
    }
}

namespace {

/** Cv.bitRuns 加上 Kotlin 讀 bits[off + i] 的越界檢查（len＝bits 的長度）。 */
int32_t bitRunsChecked(const uint64_t* bits, size_t len, int64_t off, int32_t nw, int32_t* rs, int32_t* re) {
    if (nw > 0 && (off < 0 || off + nw > static_cast<int64_t>(len))) oob("bitRuns out of bounds");
    return bitRuns(bits, off, nw, rs, re);
}

}  // namespace

void gaussQ16Sparse(int32_t w, int32_t h, const int32_t* k, int32_t ks, const uint64_t* need, size_t needLen, int32_t ny0, int32_t nrows,
                    RowSrc& src, RowSink& sink, Budget* b) {
    if (nrows <= 0 || w <= 0) return;
    const size_t sks = static_cast<size_t>(ks < 0 ? 0 : ks);
    const int32_t c = ks / 2;
    const int32_t nw = static_cast<int32_t>((static_cast<uint32_t>(w) + 63u) >> 6);
    // 橫向要算的位置＝need 直向外擴 c（夾在頁內）
    const int32_t hy0 = imax(0, ny0 - c);
    const int32_t hy1 = imin(h, ny0 + nrows + c);
    const int32_t hnN = nw * (hy1 - hy0);   // LongArray(nw * (hy1 - hy0))：Int 環繞，負的丟
    Buf<uint64_t> hnB(arrLen(hnN), b);
    uint64_t* hn = hnB.data();
    const size_t hnLen = hnB.size();
    bool any = false;
    for (int32_t yi = 0; yi < nrows; yi++) {
        const int32_t o = yi * nw;
        bool nz = false;
        for (int32_t i = 0; i < nw; i++) if (need[at(int64_t(o + i), needLen, "gaussQ16Sparse: need")] != 0) { nz = true; break; }
        if (!nz) continue;
        any = true;
        const int32_t y = ny0 + yi;
        const int32_t r1 = imin(hy1 - 1, y + c);
        for (int32_t r = imax(hy0, y - c); r <= r1; r++) {
            const int32_t ro = (r - hy0) * nw;
            for (int32_t i = 0; i < nw; i++) {
                const size_t hi = at(int64_t(ro + i), hnLen, "gaussQ16Sparse: hn");
                hn[hi] = hn[hi] | need[at(int64_t(o + i), needLen, "gaussQ16Sparse: need")];
            }
        }
    }
    if (!any) return;
    const size_t cap = static_cast<size_t>((64 * nw + 1) / 2 + 1);
    Buf<int32_t> rsB(cap, b), reB(cap, b), hsB(cap, b), heB(cap, b), ssB(cap, b), seB(cap, b);
    int32_t *rs = rsB.data(), *re = reB.data(), *hs = hsB.data(), *he = heB.data(), *ss = ssB.data(), *se = seB.data();
    const int32_t nr = 2 * c + 1;
    const size_t snr = arrLen(nr);
    std::vector<Buf<int32_t>> rows(snr);   // 用到才配（Kotlin 的 arrayOfNulls）
    Buf<int32_t> rowId(snr, b);
    for (size_t i = 0; i < snr; i++) rowId[i] = -1;
    const size_t sw = static_cast<size_t>(w);
    Buf<int32_t> srcRowB(sw, b);
    int32_t* srcRow = srcRowB.data();
    const size_t lineLen = arrLen(int64_t(w) + 2 * int64_t(c));
    Buf<int32_t> lineB(lineLen, b);
    int32_t* line = lineB.data();
    const int32_t kc = k[at(c, sks, "gaussQ16Sparse: k[c]")];
    auto hrow = [&](int32_t r) -> const int32_t* {
        const int32_t slot = r % nr;
        const size_t sl = at(slot, snr, "gaussQ16Sparse: rows");
        if (rows[sl].size() == 0 && sw > 0) rows[sl] = Buf<int32_t>(sw, b);
        int32_t* dst = rows[sl].data();
        if (rowId[sl] == r) return dst;
        const int32_t off = (r - hy0) * nw;
        const int32_t n = bitRunsChecked(hn, hnLen, off, nw, hs, he);
        // 來源要的欄：每段外擴 c（夾在頁內），接起來
        int32_t m = 0;
        for (int32_t q = 0; q < n; q++) {
            const int32_t a = imax(0, hs[q] - c);
            const int32_t bb = imin(w, he[q] + c);
            if (m > 0 && a <= se[m - 1]) { if (bb > se[m - 1]) se[m - 1] = bb; } else { ss[m] = a; se[m] = bb; m++; }
        }
        src.fill(r, ss, se, m, srcRow, sw);
        for (int32_t q = 0; q < n; q++) {
            const int32_t s0 = hs[q];
            const int32_t len = he[q] - s0;
            // line[i]＝來源第 reflect101(s0 − c + i) 欄
            const int32_t ll = len + 2 * c;
            const int32_t a = s0 - c;
            // Kotlin 在 ll 超出 line、或 s0 + len 超出頁寬時越界（只有 need 在頁寬外有位元時）
            if (static_cast<int64_t>(ll) > static_cast<int64_t>(lineLen)) oob("gaussQ16Sparse: line");
            int32_t i = 0;
            while (i < ll && a + i < 0) { line[i] = srcRow[reflectChecked(a + i, w)]; i++; }
            const int32_t mid = imin(ll, w - a);
            if (mid > i) {
                arraycopyCheck(int64_t(a) + i, sw, i, lineLen, mid - i);
                std::memcpy(line + i, srcRow + (a + i), static_cast<size_t>(mid - i) * sizeof(int32_t));
                i = mid;
            }
            while (i < ll) { line[i] = srcRow[reflectChecked(a + i, w)]; i++; }
            if (int64_t(s0) + len > static_cast<int64_t>(sw)) oob("gaussQ16Sparse: row");
            // 核在外、x 在內（同 gaussQ16）
            int32_t* d = dst + s0;
            for (int32_t x = 0; x < len; x++) d[x] = kc * line[x + c];
            for (int32_t j = 1; j <= c; j++) {
                const int32_t kj = k[at(int64_t(c) + j, sks, "gaussQ16Sparse: k")];
                const int32_t* lo = line + (c - j);
                const int32_t* hi = line + (c + j);
                for (int32_t x = 0; x < len; x++) d[x] += kj * (lo[x] + hi[x]);
            }
        }
        rowId[sl] = r;
        return dst;
    };
    Buf<int64_t> accB(sw, b);
    Buf<int32_t> outB(sw, b);
    int64_t* acc = accB.data();
    int32_t* out = outB.data();
    for (int32_t yi = 0; yi < nrows; yi++) {
        const int32_t y = ny0 + yi;
        const int32_t n = bitRunsChecked(need, needLen, yi * nw, nw, rs, re);
        if (n == 0) continue;
        const int32_t* hc = hrow(y);
        for (int32_t q = 0; q < n; q++) if (int64_t(re[q]) > static_cast<int64_t>(sw)) oob("gaussQ16Sparse: acc");
        const int64_t kcl = kc;
        for (int32_t q = 0; q < n; q++) for (int32_t x = rs[q]; x < re[q]; x++) acc[x] = kcl * hc[x];
        for (int32_t j = 1; j <= c; j++) {
            const int64_t kj = k[at(int64_t(c) + j, sks, "gaussQ16Sparse: k")];
            if (kj == 0) continue;
            if (h <= 0) throw std::invalid_argument("reflect101: n <= 0 (Kotlin does not terminate)");
            const int32_t* ha = hrow(reflect101(y - j, h));
            const int32_t* hb = hrow(reflect101(y + j, h));
            for (int32_t q = 0; q < n; q++) for (int32_t x = rs[q]; x < re[q]; x++) {
                const int32_t sum = ha[x] + hb[x];
                acc[x] += kj * sum;
            }
        }
        for (int32_t q = 0; q < n; q++) for (int32_t x = rs[q]; x < re[q]; x++) out[x] = static_cast<int32_t>((acc[x] + 32768) >> 16);
        sink.row(y, out, rs, re, n);
    }
}

// ── uint8 中值 ───────────────────────────────────────────────────────────

ImgI32 medianBlur8(const ImgI32& g, int32_t ksize, Budget* b) {
    const int32_t w = g.w, h = g.h;
    const int32_t r = ksize / 2;
    const int32_t need = ksize * ksize / 2 + 1;   // 第 need 小（1 起算）
    const int32_t* src = g.data.data();
    const size_t ns = g.data.size();
    ImgI32 outImg(w, h, b);
    int32_t* out = outImg.data.data();
    Buf<int32_t> fineB(256, b);
    int32_t* fine = fineB.data();
    Buf<int32_t> colsB(arrLen(ksize), b);
    int32_t* cols = colsB.data();
    const size_t ncols = colsB.size();
    for (int32_t y = 0; y < h; y++) {
        std::memset(fine, 0, 256 * sizeof(int32_t));
        for (int32_t dy = -r; dy <= r; dy++) {
            const int32_t v = coerceInChecked(y + dy, 0, h - 1) * w;
            cols[at(int64_t(dy) + r, ncols, "medianBlur8: cols")] = v;
        }
        // x = 0 的窗：列 −r..r（複製邊）
        for (int32_t dx = -r; dx <= r; dx++) {
            const int32_t xx = coerceInChecked(dx, 0, w - 1);
            for (size_t ci = 0; ci < ncols; ci++) fine[at(src[at(int64_t(cols[ci] + xx), ns, "medianBlur8: g")], 256, "medianBlur8: value")]++;
        }
        int32_t m = 0;
        int32_t lt = 0;   // 窗內 < m 的個數
        while (lt + fine[at(m, 256, "medianBlur8: m")] < need) { lt += fine[m]; m++; }
        int32_t* ob = out + static_cast<size_t>(y) * static_cast<size_t>(w);
        for (int32_t x = 0; x < w; x++) {
            ob[x] = m;
            if (x + 1 < w) {
                const int32_t xo = x - r < 0 ? 0 : x - r;
                const int32_t xn = x + 1 + r > w - 1 ? w - 1 : x + 1 + r;
                for (size_t ci = 0; ci < ncols; ci++) {
                    const int32_t cb = cols[ci];
                    const int32_t a = src[at(int64_t(cb + xo), ns, "medianBlur8: g")];
                    fine[at(a, 256, "medianBlur8: value")]--;
                    if (a < m) lt--;
                    const int32_t bv = src[at(int64_t(cb + xn), ns, "medianBlur8: g")];
                    fine[at(bv, 256, "medianBlur8: value")]++;
                    if (bv < m) lt++;
                }
                while (lt >= need) { m--; lt -= fine[at(m, 256, "medianBlur8: m")]; }
                while (lt + fine[at(m, 256, "medianBlur8: m")] < need) { lt += fine[m]; m++; }
            }
        }
    }
    return outImg;
}

// ── Canny ────────────────────────────────────────────────────────────────

Mask canny(const int32_t* src, size_t srcLen, int32_t w, int32_t h, int32_t shift, int32_t lo, int32_t hi, Budget* b) {
    if (w < 0 || h < 0) throw std::length_error("negative size");
    if (static_cast<uint64_t>(w) * static_cast<uint64_t>(h) > srcLen) oob("canny: src shorter than w*h");
    const size_t sw = static_cast<size_t>(w);
    // dx、dy、mag、srow、hsum 各三列環形；mag 與 srow 兩側各一格
    Buf<int32_t> dxB(3 * sw, b), dyB(3 * sw, b), magB(3 * (sw + 2), b), srowB(3 * (sw + 2), b), hsumB(3 * sw, b);
    int32_t* dx = dxB.data();
    int32_t* dy = dyB.data();
    int32_t* mag = magB.data();
    int32_t* srow = srowB.data();
    int32_t* hsum = hsumB.data();
    int32_t srowId[3] = {-1, -1, -1};
    Buf<int32_t> vsumB(sw + 2, b), zeroB(sw + 2, b);
    int32_t* vsum = vsumB.data();
    const int32_t* zero = zeroB.data();
    auto srcRow = [&](int32_t r) -> int32_t {
        const int32_t sl = r % 3;
        if (srowId[sl] == r) return sl;
        int32_t* sr = srow + static_cast<size_t>(sl) * (sw + 2);
        const int32_t* sp = src + static_cast<size_t>(r) * sw;
        for (int32_t x = 0; x < w; x++) sr[x + 1] = sp[x] >> (shift & 31);
        sr[0] = sr[1];
        sr[w + 1] = sr[w];
        int32_t* hs = hsum + static_cast<size_t>(sl) * sw;
        for (int32_t x = 0; x < w; x++) hs[x] = sr[x] + 2 * sr[x + 1] + sr[x + 2];
        srowId[sl] = r;
        return sl;
    };
    auto row = [&](int32_t y, int32_t slot) {
        const int32_t su = srcRow(imax(0, y - 1));
        const int32_t sm = srcRow(y);
        const int32_t sd = srcRow(imin(h - 1, y + 1));
        const int32_t* u = srow + static_cast<size_t>(su) * (sw + 2);
        const int32_t* m = srow + static_cast<size_t>(sm) * (sw + 2);
        const int32_t* d = srow + static_cast<size_t>(sd) * (sw + 2);
        const int32_t* hu = hsum + static_cast<size_t>(su) * sw;
        const int32_t* hd = hsum + static_cast<size_t>(sd) * sw;
        int32_t* ddx = dx + static_cast<size_t>(slot) * sw;
        int32_t* ddy = dy + static_cast<size_t>(slot) * sw;
        int32_t* mm = mag + static_cast<size_t>(slot) * (sw + 2);
        // 直向 [1 2 1] 和（補過邊的欄）：gx＝右欄和 − 左欄和；gy＝下列橫向和 − 上列橫向和
        for (int32_t x = 0; x < w + 2; x++) vsum[x] = u[x] + 2 * m[x] + d[x];
        for (int32_t x = 0; x < w; x++) {
            const int32_t gx = vsum[x + 2] - vsum[x];
            const int32_t gy = hd[x] - hu[x];
            ddx[x] = gx;
            ddy[x] = gy;
            mm[x + 1] = iabs(gx) + iabs(gy);
        }
    };
    // 0＝不是邊、1＝候選、2＝強邊
    Buf<uint8_t> stB(pixelCount(w, h), b);
    uint8_t* st = stB.data();
    Buf<int32_t> stack(1024, b);
    int32_t sp = 0;
    auto push = [&](int32_t i) {
        if (static_cast<size_t>(sp) == stack.size()) {
            Buf<int32_t> n2(stack.size() * 2, b);
            std::memcpy(n2.data(), stack.data(), stack.size() * sizeof(int32_t));
            stack = std::move(n2);
        }
        stack[static_cast<size_t>(sp++)] = i;
    };
    if (h > 0) row(0, 0);
    for (int32_t y = 0; y < h; y++) {
        const int32_t cur = y % 3;
        if (y + 1 < h) row(y + 1, (y + 1) % 3);
        const int32_t* mp = y > 0 ? mag + static_cast<size_t>((y + 2) % 3) * (sw + 2) : zero;
        const int32_t* mn = y + 1 < h ? mag + static_cast<size_t>((y + 1) % 3) * (sw + 2) : zero;
        const int32_t* ma = mag + static_cast<size_t>(cur) * (sw + 2);
        const int32_t* ddx = dx + static_cast<size_t>(cur) * sw;
        const int32_t* ddy = dy + static_cast<size_t>(cur) * sw;
        for (int32_t x = 0; x < w; x++) {
            const int32_t mv = ma[x + 1];
            if (mv <= lo) continue;
            const int32_t xs = ddx[x];
            const int32_t ys = ddy[x];
            const int32_t ax = iabs(xs);
            const int32_t ay = jm::shl(iabs(ys), 15);
            const int32_t tg22x = ax * 13573;
            bool isMax;
            if (ay < tg22x) {
                isMax = mv > ma[x] && mv >= ma[x + 2];
            } else {
                const int32_t tg67x = tg22x + jm::shl(ax, 16);
                if (ay > tg67x) {
                    isMax = mv > mp[x + 1] && mv >= mn[x + 1];
                } else {
                    const int32_t sg = (xs ^ ys) < 0 ? -1 : 1;
                    isMax = mv > mp[x + 1 - sg] && mv > mn[x + 1 + sg];
                }
            }
            if (!isMax) continue;
            const int32_t i = y * w + x;
            if (mv > hi) {
                st[i] = 2;
                push(i);
            } else {
                st[i] = 1;
            }
        }
    }
    while (sp > 0) {
        const int32_t i = stack[static_cast<size_t>(--sp)];
        const int32_t x = i % w;
        const int32_t y = i / w;
        const int32_t y1 = imin(h - 1, y + 1), x1 = imin(w - 1, x + 1);
        for (int32_t yy = imax(0, y - 1); yy <= y1; yy++) {
            for (int32_t xx = imax(0, x - 1); xx <= x1; xx++) {
                const int32_t j = yy * w + xx;
                if (st[j] == 1) {
                    st[j] = 2;
                    push(j);
                }
            }
        }
    }
    Mask out(w, h, b);
    for (size_t i = 0; i < out.data.size(); i++) out.data[i] = st[i] == 2 ? 1 : 0;
    return out;
}

}  // namespace prims
}  // namespace nr
