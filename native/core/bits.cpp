// 夜讀 C++ 核心：位元的底層小工具。移植自 Cv.kt（packBitsNot、unpackBitsNot、notBitsInPlace、bitRuns、anyBits）與
// Ring.kt（packBits）@ a951b7c，逐步照抄。
#include "bits.h"

#include <stdexcept>

namespace nr {

FlatBits packFlat(const Mask& m, Budget* b) {
    const uint8_t* d = m.data.data();
    const int64_t n = static_cast<int64_t>(m.data.size());
    FlatBits out;
    out.n = n;
    out.words = Buf<uint64_t>(static_cast<size_t>((n + 63) >> 6), b);
    const size_t nwords = out.words.size();
    for (size_t wi = 0; wi < nwords; wi++) {
        const int64_t i0 = static_cast<int64_t>(wi) << 6;
        const int64_t e = (n - i0) < 64 ? (n - i0) : 64;
        uint64_t v = 0;
        for (int64_t t = 0; t < e; t++) if (d[i0 + t]) v |= uint64_t(1) << t;
        out.words[wi] = v;
    }
    return out;
}

PackedBits packBitsNot(const Mask& m, Budget* b) {
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
            for (int32_t bit = 0; bit < n; bit++) if (!d[base + x0 + bit]) v |= uint64_t(1) << bit;
            out.words[wb + i] = v;
        }
    }
    return out;
}

Mask unpackBitsNot(const PackedBits& a, Budget* b) {
    Mask out(a.w, a.h, b);
    const int32_t w = a.w, nw = a.nw;
    uint8_t* od = out.data.data();
    for (int32_t y = 0; y < a.h; y++) {
        const size_t base = static_cast<size_t>(y) * w;
        const size_t wb = static_cast<size_t>(y) * nw;
        for (int32_t i = 0; i < nw; i++) {
            const int32_t x0 = i << 6;
            const int32_t n = (w - x0) < 64 ? (w - x0) : 64;
            const uint64_t v = a.words[wb + i];
            for (int32_t bit = 0; bit < n; bit++) od[base + x0 + bit] = ((v >> bit) & 1) == 0 ? 1 : 0;
        }
    }
    return out;
}

void notBitsInPlace(PackedBits& a) {
    const int32_t w = a.w, h = a.h, nw = a.nw;
    if (nw == 0 && h > 0) throw std::invalid_argument("notBitsInPlace: zero width");
    const uint64_t tail = (w & 63) == 0 ? ~uint64_t(0) : (uint64_t(1) << (w & 63)) - 1;
    for (int32_t y = 0; y < h; y++) {
        uint64_t* row = a.words.data() + static_cast<size_t>(y) * nw;
        for (int32_t i = 0; i < nw - 1; i++) row[i] = ~row[i];
        row[nw - 1] = ~row[nw - 1] & tail;
    }
}

int32_t bitRuns(const uint64_t* bits, int64_t off, int32_t nw, int32_t* rs, int32_t* re) noexcept {
    int32_t cnt = 0;
    for (int32_t i = 0; i < nw; i++) {
        uint64_t v = bits[off + i];
        const int32_t base = i << 6;
        while (v != 0) {
            const int32_t t = __builtin_ctzll(v);   // v ≠ 0
            const uint64_t sh = v >> t;
            const int32_t len = sh == ~uint64_t(0) ? 64 - t : __builtin_ctzll(~sh);   // sh ≠ 全 1 ⇒ ~sh ≠ 0
            const int32_t st = base + t;
            const int32_t en = st + len;
            if (cnt > 0 && re[cnt - 1] == st) re[cnt - 1] = en;
            else { rs[cnt] = st; re[cnt] = en; cnt++; }
            const int32_t u = t + len;
            v = u >= 64 ? 0 : (v & (~uint64_t(0) << u));
        }
    }
    return cnt;
}

bool anyBits(const uint64_t* bits, int64_t off, int32_t s, int32_t e) noexcept {
    if (s >= e) return false;
    const int32_t a = static_cast<int32_t>(static_cast<uint32_t>(s) >> 6);
    const int32_t bw = static_cast<int32_t>(static_cast<uint32_t>(e - 1) >> 6);
    const uint64_t lo = ~uint64_t(0) << (s & 63);
    const uint64_t hi = ~uint64_t(0) >> (63 - ((e - 1) & 63));
    if (a == bw) return (bits[off + a] & lo & hi) != 0;
    if ((bits[off + a] & lo) != 0) return true;
    for (int32_t i = a + 1; i < bw; i++) if (bits[off + i] != 0) return true;
    return (bits[off + bw] & hi) != 0;
}

}  // namespace nr
