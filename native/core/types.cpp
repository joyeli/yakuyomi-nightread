// 夜讀 C++ 核心：型別的非內聯部分。移植自 Cv.kt 的 Kernel init、packBits、unpackBits @ 27d1e96。
#include "types.h"

#include <cstdlib>
#include <cstring>

namespace nr {

void* bufAlloc(size_t n, size_t elemSize, Budget* budget) {
    if (n == 0) return nullptr;
    if (n > SIZE_MAX / elemSize) throw std::bad_alloc();
    void* p = std::calloc(n, elemSize);
    if (p == nullptr) throw std::bad_alloc();
    if (budget != nullptr) budget->add(static_cast<int64_t>(n * elemSize));
    return p;
}

void bufFree(void* p, size_t bytes, Budget* budget) noexcept {
    std::free(p);
    if (budget != nullptr) budget->sub(static_cast<int64_t>(bytes));
}

size_t pixelCount(int32_t w, int32_t h) {
    if (w < 0 || h < 0) throw std::length_error("negative size");
    const int64_t n = static_cast<int64_t>(w) * h;
    if (n > INT32_MAX) throw std::length_error("more than Int.MAX_VALUE pixels");
    return static_cast<size_t>(n);
}

Kernel::Kernel(int32_t w_, int32_t h_, const uint8_t* src, Budget* b)
    : w(w_), h(h_), ax(w_ / 2), ay(h_ / 2), data(pixelCount(w_, h_), b), runStart(static_cast<size_t>(h_), b),
      runEnd(static_cast<size_t>(h_), b) {
    if (data.size() > 0) std::memcpy(data.data(), src, data.size());
    for (int32_t y = 0; y < h; y++) {
        int32_t s = -1, e = -1;
        for (int32_t x = 0; x < w; x++) {
            if (data[static_cast<size_t>(y) * w + x]) { if (s < 0) s = x; e = x + 1; }
        }
        runStart[y] = s < 0 ? 0 : s;
        runEnd[y] = s < 0 ? 0 : e;
    }
}

PackedBits::PackedBits(int32_t w_, int32_t h_, Budget* b) : w(w_), h(h_), nw(0) {
    pixelCount(w_, h_);
    nw = static_cast<int32_t>((static_cast<int64_t>(w_) + 63) >> 6);
    words = Buf<uint64_t>(static_cast<size_t>(nw) * static_cast<size_t>(h_), b);
}

PackedBits packBits(const Mask& m, Budget* b) {
    PackedBits out(m.w, m.h, b);
    const int32_t w = m.w, nw = out.nw;
    const uint8_t* d = m.data.data();
    for (int32_t y = 0; y < m.h; y++) {
        const size_t base = static_cast<size_t>(y) * w;
        const size_t wb = static_cast<size_t>(y) * nw;
        for (int32_t i = 0; i < nw; i++) {
            const int32_t x0 = i << 6;
            const int32_t n = (w - x0) < 64 ? (w - x0) : 64;
            uint64_t v = 0;
            const size_t o = base + x0;
            for (int32_t bit = 0; bit < n; bit++) if (d[o + bit]) v |= uint64_t(1) << bit;
            out.words[wb + i] = v;
        }
    }
    return out;
}

Mask unpackBits(const PackedBits& a, Budget* b) {
    Mask out(a.w, a.h, b);
    const int32_t w = a.w, nw = a.nw;
    uint8_t* od = out.data.data();
    for (int32_t y = 0; y < a.h; y++) {
        const size_t base = static_cast<size_t>(y) * w;
        const size_t wb = static_cast<size_t>(y) * nw;
        for (int32_t i = 0; i < nw; i++) {
            const uint64_t v = a.words[wb + i];
            if (v == 0) continue;
            const int32_t x0 = i << 6;
            const int32_t n = (w - x0) < 64 ? (w - x0) : 64;
            const size_t o = base + x0;
            for (int32_t bit = 0; bit < n; bit++) od[o + bit] = static_cast<uint8_t>((v >> bit) & 1);
        }
    }
    return out;
}

}  // namespace nr
