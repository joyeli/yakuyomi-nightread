// 夜讀 C++ 核心：第一批影像原語。移植自 nightread/src/main/kotlin/li/joye/yakuyomi/nightread/Cv.kt @ dd91b14：
//   dilatePacked、dilateBits、PackedRuns、ccStats、ccRuns、distanceL2（edt1d）、gaussQ16（reflect101）、morphGrayGather。
// 逐步照抄 Kotlin 的算法與順序（含 ccRuns 陣列的容量、PackedRuns 的就地增長），沒有另外改寫；瘦身與加速留給之後的階段，要先證明逐位元相同。
// 有號整數溢位照 Java 環繞（-fwrapv）；浮點乘加不合成（-ffp-contract=off）。
#include "prims.h"

#include <cstring>
#include <stdexcept>
#include <utility>

#include "tables.h"

namespace nr {
namespace prims {

namespace {

inline int32_t imin(int32_t a, int32_t b) { return a < b ? a : b; }
inline int32_t imax(int32_t a, int32_t b) { return a > b ? a : b; }
inline int32_t iabs(int32_t a) { return a < 0 ? -a : a; }

/** System.arraycopy：0 長度時指標可能是 nullptr（Buf(0)），不能交給 memcpy。 */
template <class T>
inline void copyN(T* dst, const T* src, size_t n) {
    if (n > 0) std::memcpy(dst, src, n * sizeof(T));
}

/** IntArray.copyOf(cap)：多出來的是 0。 */
void growI32(Buf<int32_t>& a, size_t cap, Budget* b) {
    Buf<int32_t> n(cap, b);
    copyN(n.data(), a.data(), a.size());
    a = std::move(n);
}

/** Cv.reflect101（BORDER_REFLECT_101）；前置條件 n ≥ 1。 */
inline int32_t reflect101(int32_t i, int32_t n) {
    if (n == 1) return 0;
    int32_t x = i;
    while (x < 0 || x >= n) {
        if (x < 0) x = -x;
        if (x >= n) x = 2 * (n - 1) - x;
    }
    return x;
}

// ── 打包列的水平區間 OR（Cv.PackedRuns）──────────────────────────────────────

class PackedRuns {
public:
    PackedRuns(const uint64_t* src, size_t size, int32_t nw, int32_t h, int32_t w, Budget* b)
        : src_(src), size_(size), nw_(nw), h_(h), budget_(b),
          tail_((w & 63) == 0 ? ~uint64_t(0) : (uint64_t(1) << (w & 63)) - 1) {}

    /** out[x] = OR_{o=lo..hi} in[x + o]，範圍外當 0。回傳的緩衝在下一次呼叫前有效（可能是內部緩衝本身）。 */
    const uint64_t* rangeOr(int32_t lo, int32_t hi) {
        if (lo <= 0 && hi >= 0) {
            if (lo == 0) return growRight(hi);
            if (hi == 0) return growLeft(-lo);
            const uint64_t* l = growLeft(-lo);
            const uint64_t* r = growRight(hi);
            uint64_t* t = tmp();
            for (size_t i = 0; i < size_; i++) t[i] = l[i] | r[i];
            return t;
        }
        uint64_t* t = tmp();
        if (lo > 0) shiftInto(growRight(hi - lo), t, lo);
        else shiftInto(growLeft(hi - lo), t, hi);
        return t;
    }

private:
    uint64_t* tmp() {
        if (!hasTmp_) { tmp_ = Buf<uint64_t>(size_, budget_); hasTmp_ = true; }
        return tmp_.data();
    }

    /** OR in[x .. x+b]。 */
    const uint64_t* growRight(int32_t b) {
        if (!hasRight_ || b < rightB_) {
            if (!hasRight_) { right_ = Buf<uint64_t>(size_, budget_); hasRight_ = true; }
            copyN(right_.data(), src_, size_);
            rightB_ = 0;
        }
        while (rightB_ < b) {
            const int32_t s = imin(b - rightB_, rightB_ + 1);
            orShiftRightInPlace(right_.data(), s);
            rightB_ += s;
        }
        return right_.data();
    }

    /** OR in[x−a .. x]。 */
    const uint64_t* growLeft(int32_t a) {
        if (!hasLeft_ || a < leftA_) {
            if (!hasLeft_) { left_ = Buf<uint64_t>(size_, budget_); hasLeft_ = true; }
            copyN(left_.data(), src_, size_);
            leftA_ = 0;
        }
        while (leftA_ < a) {
            const int32_t s = imin(a - leftA_, leftA_ + 1);
            orShiftLeftInPlace(left_.data(), s);
            leftA_ += s;
        }
        return left_.data();
    }

    /** a[x] |= a[x + s]（s > 0；由左往右，讀的字都在寫的字右邊或就是它、還沒改）。 */
    void orShiftRightInPlace(uint64_t* a, int32_t s) const {
        const int32_t q = s >> 6;
        const int32_t r = s & 63;
        const int32_t nw = nw_;
        for (int32_t y = 0; y < h_; y++) {
            uint64_t* row = a + static_cast<size_t>(y) * nw;
            for (int32_t i = 0; i < nw - q; i++) {
                const int32_t j = i + q;
                uint64_t v = row[j] >> r;
                if (r != 0 && j + 1 < nw) v |= row[j + 1] << (64 - r);
                row[i] |= v;
            }
        }
    }

    /** a[x] |= a[x − s]（s > 0；由右往左；末字超出寬度的位元清掉）。 */
    void orShiftLeftInPlace(uint64_t* a, int32_t s) const {
        const int32_t q = s >> 6;
        const int32_t r = s & 63;
        const int32_t nw = nw_;
        for (int32_t y = 0; y < h_; y++) {
            uint64_t* row = a + static_cast<size_t>(y) * nw;
            for (int32_t i = nw - 1; i >= q; i--) {
                const int32_t j = i - q;
                uint64_t v = row[j] << r;
                if (r != 0 && j - 1 >= 0) v |= row[j - 1] >> (64 - r);
                if (i == nw - 1) v &= tail_;
                row[i] |= v;
            }
        }
    }

    /** out[x] = a[x + s]（逐列、範圍外 0；s < 0 時末字超出寬度的位元清掉）。 */
    void shiftInto(const uint64_t* a, uint64_t* out, int32_t s) const {
        if (size_ > 0) std::memset(out, 0, size_ * sizeof(uint64_t));
        const int32_t nw = nw_;
        if (s >= 0) {
            const int32_t q = s >> 6;
            const int32_t r = s & 63;
            for (int32_t y = 0; y < h_; y++) {
                const uint64_t* row = a + static_cast<size_t>(y) * nw;
                uint64_t* o = out + static_cast<size_t>(y) * nw;
                for (int32_t i = 0; i < nw - q; i++) {
                    const int32_t j = i + q;
                    uint64_t v = row[j] >> r;
                    if (r != 0 && j + 1 < nw) v |= row[j + 1] << (64 - r);
                    o[i] = v;
                }
            }
        } else {
            if (nw == 0 && h_ > 0) throw std::invalid_argument("dilateBits: zero width with a run left of the anchor");
            const int32_t u = -s;
            const int32_t q = u >> 6;
            const int32_t r = u & 63;
            for (int32_t y = 0; y < h_; y++) {
                const uint64_t* row = a + static_cast<size_t>(y) * nw;
                uint64_t* o = out + static_cast<size_t>(y) * nw;
                for (int32_t i = q; i < nw; i++) {
                    const int32_t j = i - q;
                    uint64_t v = row[j] << r;
                    if (r != 0 && j - 1 >= 0) v |= row[j - 1] >> (64 - r);
                    o[i] = v;
                }
                o[nw - 1] &= tail_;
            }
        }
    }

    const uint64_t* src_;
    size_t size_;
    int32_t nw_, h_;
    Budget* budget_;
    uint64_t tail_;
    Buf<uint64_t> right_, left_, tmp_;
    bool hasRight_ = false, hasLeft_ = false, hasTmp_ = false;
    int32_t rightB_ = 0, leftA_ = 0;
};

// ── 距離（Cv.edt1d）─────────────────────────────────────────────────────

/** Felzenszwalb 一維下包絡；全程 Float（Kotlin：Float 與 Int 混算時 Int 先轉 Float）。 */
void edt1d(const float* src, int32_t n, float* dst, int32_t* v, float* z) {
    int32_t k = 0;
    v[0] = 0;
    z[0] = -1e20f;
    z[1] = 1e20f;
    for (int32_t q = 1; q < n; q++) {
        const float qf = static_cast<float>(q);
        float s;
        for (;;) {
            const int32_t p = v[k];
            const float pf = static_cast<float>(p);
            s = ((src[q] + qf * qf) - (src[p] + pf * pf)) / (2.0f * qf - 2.0f * pf);
            if (s <= z[k]) k--;
            else break;
        }
        k++;
        v[k] = q;
        z[k] = s;
        z[k + 1] = 1e20f;
    }
    k = 0;
    for (int32_t q = 0; q < n; q++) {
        const float qf = static_cast<float>(q);
        while (z[k + 1] < qf) k++;
        const int32_t p = v[k];
        const float dq = static_cast<float>(q - p);
        dst[q] = dq * dq + src[p];
    }
}

}  // namespace

// ── 二值膨脹 ─────────────────────────────────────────────────────────────

PackedBits dilateBits(const PackedBits& src, const Kernel& k, Budget* b) {
    const int32_t w = src.w, h = src.h, nw = src.nw;
    PackedBits accB(w, h, b);
    uint64_t* acc = accB.words.data();
    const size_t kh = static_cast<size_t>(k.h);
    // 不同的 (lo, hi) 依序（核列順序）；每個 key 用到它的核列位移 dy
    Buf<int32_t> keyLo(kh, b), keyHi(kh, b), rowKey(kh, b);
    int32_t nk = 0;
    for (int32_t ky = 0; ky < k.h; ky++) {
        rowKey[ky] = -1;
        const int32_t runS = k.runStart[ky];
        const int32_t runE = k.runEnd[ky];
        if (runS >= runE) continue;
        const int32_t lo = runS - k.ax;
        const int32_t hi = runE - 1 - k.ax;
        int32_t j = 0;
        while (j < nk && (keyLo[j] != lo || keyHi[j] != hi)) j++;
        if (j == nk) { keyLo[nk] = lo; keyHi[nk] = hi; nk++; }
        rowKey[ky] = j;
    }
    if (nk == 0) return accB;
    // 依兩側延伸量由小到大處理（橢圓、方核兩側一起單調變大 ⇒ 單側 OR 只要往上長）；穩定排序，同 sortedWith(compareBy(…))
    Buf<int32_t> order(static_cast<size_t>(nk), b);
    for (int32_t i = 0; i < nk; i++) {
        const int32_t a1 = imax(0, -keyLo[i]), a2 = imax(0, keyHi[i]);
        int32_t p = i;
        while (p > 0) {
            const int32_t o = order[p - 1];
            const int32_t b1 = imax(0, -keyLo[o]), b2 = imax(0, keyHi[o]);
            if (b1 < a1 || (b1 == a1 && b2 <= a2)) break;
            order[p] = o;
            p--;
        }
        order[p] = i;
    }
    PackedRuns runs(src.words.data(), src.words.size(), nw, h, w, b);
    for (int32_t oi = 0; oi < nk; oi++) {
        const int32_t j = order[oi];
        const uint64_t* hor = runs.rangeOr(keyLo[j], keyHi[j]);
        for (int32_t ky = 0; ky < k.h; ky++) {
            if (rowKey[ky] != j) continue;
            const int32_t dy = ky - k.ay;
            const int32_t y0 = imax(0, dy);
            const int32_t y1 = imin(h, h + dy);
            for (int32_t y = y0; y < y1; y++) {
                uint64_t* o = acc + static_cast<size_t>(y) * nw;
                const uint64_t* si = hor + static_cast<size_t>(y - dy) * nw;
                for (int32_t i = 0; i < nw; i++) o[i] |= si[i];
            }
        }
    }
    return accB;
}

Mask dilatePacked(const Mask& m, const Kernel& k, Budget* b) {
    const PackedBits packed = packBits(m, b);
    const PackedBits d = dilateBits(packed, k, b);
    return unpackBits(d, b);
}

// ── 連通元件 ─────────────────────────────────────────────────────────────

CcRuns ccRuns(const Mask& m, int32_t connectivity, Budget* b) {
    const int32_t w = m.w, h = m.h;
    const uint8_t* d = m.data.data();
    const int32_t adj = connectivity == 8 ? 1 : 0;
    const size_t cap0 = static_cast<size_t>(imax(64, h * 2));
    CcRuns out;
    out.rs = Buf<int32_t>(cap0, b);
    out.re = Buf<int32_t>(cap0, b);
    Buf<int32_t> parent(cap0, b);
    out.rowFirst = Buf<int32_t>(static_cast<size_t>(h) + 1, b);
    int32_t* rs = out.rs.data();
    int32_t* re = out.re.data();
    int32_t* par = parent.data();
    int32_t* rowFirst = out.rowFirst.data();
    int32_t nr = 0;
    int32_t bgLeft = INT32_MAX, bgTop = INT32_MAX, bgRight = INT32_MIN, bgBottom = INT32_MIN;
    int64_t fg = 0;
    for (int32_t y = 0; y < h; y++) {
        rowFirst[y] = nr;
        const uint8_t* row = d + static_cast<size_t>(y) * w;
        const int32_t p0 = y > 0 ? rowFirst[y - 1] : 0;
        const int32_t p1 = nr;
        int32_t pj = p0;
        int32_t x = 0;
        while (x < w) {
            if (!row[x]) { x++; continue; }
            const int32_t s = x;
            while (x < w && row[x]) x++;
            const int32_t e = x - 1;
            if (static_cast<size_t>(nr) == out.rs.size()) {
                const size_t cap = out.rs.size() * 2;
                growI32(out.rs, cap, b);
                growI32(out.re, cap, b);
                growI32(parent, cap, b);
                rs = out.rs.data();
                re = out.re.data();
                par = parent.data();
            }
            rs[nr] = s;
            re[nr] = e;
            par[nr] = nr;
            // 上一列與 [s − adj, e + adj] 重疊的游程（上一列的游程依 x 排序）
            while (pj < p1 && re[pj] < s - adj) pj++;
            int32_t k = pj;
            while (k < p1 && rs[k] <= e + adj) {
                // union（根＝較小的游程索引；path halving）
                int32_t ra = nr;
                while (par[ra] != ra) { par[ra] = par[par[ra]]; ra = par[ra]; }
                int32_t rb = k;
                while (par[rb] != rb) { par[rb] = par[par[rb]]; rb = par[rb]; }
                if (ra != rb) {
                    if (ra < rb) par[rb] = ra;
                    else par[ra] = rb;
                }
                k++;
            }
            fg += e - s + 1;
            nr++;
        }
        // 背景：這一列有沒有非前景像素、最左／最右的是哪個
        const int32_t r0 = rowFirst[y];
        if (nr == r0) {
            if (w > 0) {
                if (0 < bgLeft) bgLeft = 0;
                if (w - 1 > bgRight) bgRight = w - 1;
                if (y < bgTop) bgTop = y;
                bgBottom = y;
            }
        } else if (!(nr - r0 == 1 && rs[r0] == 0 && re[r0] == w - 1)) {
            const int32_t lx = rs[r0] > 0 ? 0 : re[r0] + 1;
            const int32_t rx = re[nr - 1] < w - 1 ? w - 1 : rs[nr - 1] - 1;
            if (lx < bgLeft) bgLeft = lx;
            if (rx > bgRight) bgRight = rx;
            if (y < bgTop) bgTop = y;
            bgBottom = y;
        }
    }
    rowFirst[h] = nr;
    // 按游程（＝掃描）順序配最終標號
    Buf<int32_t> remap(static_cast<size_t>(nr), b);
    out.lab = Buf<int32_t>(static_cast<size_t>(nr), b);
    int32_t n = 1;
    for (int32_t r = 0; r < nr; r++) {
        int32_t root = r;
        while (par[root] != root) root = par[root];
        if (remap[root] == 0) { remap[root] = n; n++; }
        par[r] = root;
        out.lab[r] = remap[root];
    }
    out.n = n;
    out.fg = fg;
    out.bgLeft = bgLeft;
    out.bgTop = bgTop;
    out.bgRight = bgRight;
    out.bgBottom = bgBottom;
    return out;
}

CC ccStats(const Mask& m, int32_t connectivity, Budget* b) {
    const CcRuns r = ccRuns(m, connectivity, b);
    const int32_t w = m.w, h = m.h;
    const int32_t n = r.n;
    const size_t sn = static_cast<size_t>(n);
    CC out;
    out.n = n;
    out.labels = Buf<int32_t>(pixelCount(w, h), b);
    out.left = Buf<int32_t>(sn, b);
    out.top = Buf<int32_t>(sn, b);
    Buf<int32_t> right(sn, b), bottom(sn, b);
    out.area = Buf<int32_t>(sn, b);
    int32_t* labels = out.labels.data();
    int32_t* left = out.left.data();
    int32_t* top = out.top.data();
    int32_t* area = out.area.data();
    for (int32_t i = 0; i < n; i++) { left[i] = INT32_MAX; top[i] = INT32_MAX; right[i] = INT32_MIN; bottom[i] = INT32_MIN; }
    for (int32_t y = 0; y < h; y++) {
        int32_t* base = labels + static_cast<size_t>(y) * w;
        for (int32_t k = r.rowFirst[y]; k < r.rowFirst[y + 1]; k++) {
            const int32_t l = r.lab[k];
            const int32_t s = r.rs[k];
            const int32_t e = r.re[k];
            for (int32_t x = s; x <= e; x++) base[x] = l;
            area[l] += e - s + 1;
            if (s < left[l]) left[l] = s;
            if (y < top[l]) top[l] = y;
            if (e > right[l]) right[l] = e;
            bottom[l] = y;
        }
    }
    area[0] = static_cast<int32_t>(static_cast<int64_t>(w) * h - r.fg);
    left[0] = r.bgLeft;
    top[0] = r.bgTop;
    right[0] = r.bgRight;
    bottom[0] = r.bgBottom;
    out.width = Buf<int32_t>(sn, b);
    out.height = Buf<int32_t>(sn, b);
    int32_t* width = out.width.data();
    int32_t* height = out.height.data();
    for (int32_t i = 0; i < n; i++) {
        if (left[i] == INT32_MAX) { left[i] = 0; top[i] = 0; }
        width[i] = right[i] - left[i] + 1;
        height[i] = bottom[i] - top[i] + 1;
        if (width[i] < 0) width[i] = 0;
        if (height[i] < 0) height[i] = 0;
    }
    return out;
}

// ── 距離 ─────────────────────────────────────────────────────────────────

ImgF32 distanceL2(const Mask& m, Budget* b) {
    const int32_t w = m.w, h = m.h;
    const float inf = 1e20f;
    ImgF32 out(w, h, b);
    float* f = out.data.data();
    const uint8_t* md = m.data.data();
    const size_t total = out.data.size();
    for (size_t i = 0; i < total; i++) f[i] = md[i] ? inf : 0.0f;

    const size_t n = static_cast<size_t>(imax(w, h));
    Buf<float> tmp(n, b), d(n, b), z(n + 1, b);
    Buf<int32_t> v(n, b);

    // 逐行
    for (int32_t y = 0; y < h; y++) {
        float* row = f + static_cast<size_t>(y) * w;
        copyN(tmp.data(), row, static_cast<size_t>(w));
        edt1d(tmp.data(), w, d.data(), v.data(), z.data());
        copyN(row, d.data(), static_cast<size_t>(w));
    }
    // 逐列：一次搬 16 欄進欄優先的暫存，逐欄做完再一起搬回（同 Kotlin；每一欄的運算相同，只是存取順序換了）
    const int32_t bw = 16;
    Buf<float> cols(static_cast<size_t>(bw) * static_cast<size_t>(h), b);
    float* cp = cols.data();
    for (int32_t x0 = 0; x0 < w; x0 += bw) {
        const int32_t nb = imin(bw, w - x0);
        for (int32_t y = 0; y < h; y++) {
            const float* row = f + static_cast<size_t>(y) * w + x0;
            for (int32_t c = 0; c < nb; c++) cp[static_cast<size_t>(c) * h + y] = row[c];
        }
        for (int32_t c = 0; c < nb; c++) {
            float* col = cp + static_cast<size_t>(c) * h;
            copyN(tmp.data(), col, static_cast<size_t>(h));
            edt1d(tmp.data(), h, d.data(), v.data(), z.data());
            copyN(col, d.data(), static_cast<size_t>(h));
        }
        for (int32_t y = 0; y < h; y++) {
            float* row = f + static_cast<size_t>(y) * w + x0;
            for (int32_t c = 0; c < nb; c++) row[c] = cp[static_cast<size_t>(c) * h + y];
        }
    }
    // Kotlin：kotlin.math.sqrt(Float)＝Math.sqrt(x.toDouble()).toFloat()。double 的 sqrt 再捨入成 float 等於 float 的正確捨入 sqrt
    // （53 ≥ 2·24＋2，倍精度夠寬，沒有二次捨入誤差；Figueroa 1995），所以直接用 float 的 sqrt，nr_core_tests 也逐值比過。
    for (size_t i = 0; i < total; i++) f[i] = __builtin_sqrtf(f[i]);
    return out;
}

// ── 整數高斯 ─────────────────────────────────────────────────────────────

ImgI32 gaussQ16(const ImgI32& g, double sigma, Budget* b) {
    const int32_t w = g.w, h = g.h;
    const std::vector<int32_t> kv = tables::gaussW(sigma);
    const int32_t* k = kv.data();
    const int32_t c = static_cast<int32_t>(kv.size()) / 2;
    if (w == 0 && c > 0) throw std::invalid_argument("gaussQ16: zero width");
    const int32_t* src = g.data.data();
    // 橫向那一趟只留直向核涵蓋的 2c+1 列（環形緩衝）：輸出列 y 要的來源列（REFLECT_101 之後）都落在 [y−c, y+c] 裡，
    // 以「列號 mod (2c+1)」放槽不會撞
    const int32_t nr = 2 * c + 1;
    Buf<int32_t> rowsB(static_cast<size_t>(nr) * static_cast<size_t>(w), b);
    Buf<int32_t> rowIdB(static_cast<size_t>(nr), b);
    Buf<int32_t> xiB(static_cast<size_t>(w) + 2 * static_cast<size_t>(c), b);
    Buf<int32_t> lineB(xiB.size(), b);
    int32_t* rows = rowsB.data();
    int32_t* rowId = rowIdB.data();
    int32_t* xi = xiB.data();
    int32_t* line = lineB.data();
    for (int32_t i = 0; i < nr; i++) rowId[i] = -1;
    const int32_t nl = w + 2 * c;
    for (int32_t i = 0; i < nl; i++) xi[i] = reflect101(i - c, w);
    const int32_t kc = k[c];
    auto hrow = [&](int32_t r) -> const int32_t* {
        const int32_t slot = r % nr;
        int32_t* dst = rows + static_cast<size_t>(slot) * w;
        if (rowId[slot] == r) return dst;
        const int32_t* base = src + static_cast<size_t>(r) * w;
        for (int32_t i = 0; i < nl; i++) line[i] = base[xi[i]];
        // 核在外、x 在內
        for (int32_t x = 0; x < w; x++) dst[x] = kc * line[x + c];
        for (int32_t j = 1; j <= c; j++) {
            const int32_t kj = k[c + j];
            const int32_t* lo = line + (c - j);
            const int32_t* hi = line + (c + j);
            for (int32_t x = 0; x < w; x++) dst[x] += kj * (lo[x] + hi[x]);
        }
        rowId[slot] = r;
        return dst;
    };
    ImgI32 out(w, h, b);
    int32_t* od = out.data.data();
    Buf<int64_t> accB(static_cast<size_t>(w), b);
    int64_t* acc = accB.data();
    for (int32_t y = 0; y < h; y++) {
        // 對稱核：上下對稱的兩列先相加（Int，照 Kotlin 環繞）再乘一次
        const int32_t* hc = hrow(y);
        const int64_t kcl = kc;
        for (int32_t x = 0; x < w; x++) acc[x] = kcl * hc[x];
        for (int32_t j = 1; j <= c; j++) {
            const int64_t kj = k[c + j];
            if (kj == 0) continue;
            const int32_t* ha = hrow(reflect101(y - j, h));
            const int32_t* hb = hrow(reflect101(y + j, h));
            for (int32_t x = 0; x < w; x++) {
                const int32_t sum = ha[x] + hb[x];
                acc[x] += kj * sum;
            }
        }
        int32_t* ob = od + static_cast<size_t>(y) * w;
        for (int32_t x = 0; x < w; x++) ob[x] = static_cast<int32_t>((acc[x] + 32768) >> 16);
    }
    return out;
}

// ── 灰階形態學 ───────────────────────────────────────────────────────────

ImgI32 morphGrayGather(const ImgI32& g, const Kernel& k, bool wantMax, Budget* b) {
    const int32_t w = g.w, h = g.h;
    const int32_t id = wantMax ? INT32_MIN : INT32_MAX;
    const int32_t kh = k.h;
    const size_t skh = static_cast<size_t>(kh);
    // 不同的 run 寬度（由小到大）與每個核列的寬度索引、偏移
    Buf<int32_t> winOf(skh, b), offOf(skh, b), wv(skh, b), widIdx(skh, b);
    int32_t nwid = 0;
    int32_t pad = 0;
    for (int32_t ky = 0; ky < kh; ky++) {
        const int32_t win = k.runEnd[ky] - k.runStart[ky];
        winOf[ky] = win;
        if (win <= 0) continue;
        const int32_t off = k.runStart[ky] - k.ax;
        offOf[ky] = off;
        bool seen = false;
        for (int32_t q = 0; q < nwid; q++) if (wv[q] == win) { seen = true; break; }
        if (!seen) wv[nwid++] = win;
        pad = imax(pad, imax(iabs(off), iabs(off + win - 1)));
    }
    for (int32_t i = 1; i < nwid; i++) {   // 由小到大（寬度互不相同）
        const int32_t v = wv[i];
        int32_t p = i;
        while (p > 0 && wv[p - 1] > v) { wv[p] = wv[p - 1]; p--; }
        wv[p] = v;
    }
    for (int32_t ky = 0; ky < kh; ky++) {
        int32_t idx = -1;
        if (winOf[ky] > 0) for (int32_t q = 0; q < nwid; q++) if (wv[q] == winOf[ky]) { idx = q; break; }
        widIdx[ky] = idx;
    }
    const int32_t pl = w + 2 * pad;
    const size_t spl = static_cast<size_t>(pl);
    // 環形快取：每槽每種寬度一條「窗起點極值」陣列（長 pl；起點 p 的窗要整個在 [0, pl) 才有定義，取值只用得到那些）
    Buf<int32_t> cacheB(skh * static_cast<size_t>(nwid) * spl, b);
    Buf<int32_t> cacheRow(skh, b), tmpB(spl, b);
    int32_t* cache = cacheB.data();
    int32_t* tmp = tmpB.data();
    for (int32_t i = 0; i < kh; i++) cacheRow[i] = -1;
    const int32_t* gd = g.data.data();
    /** 回傳第 sy 列那一槽（第 q 種寬度在偏移 q·pl）。 */
    auto rowExt = [&](int32_t sy) -> const int32_t* {
        const int32_t slot = sy % kh;
        int32_t* dst = cache + static_cast<size_t>(slot) * static_cast<size_t>(nwid) * spl;
        if (cacheRow[slot] == sy) return dst;
        // 寬度 1＝補了單位元的來源列本身
        for (int32_t p = 0; p < pl; p++) tmp[p] = id;
        copyN(tmp + pad, gd + static_cast<size_t>(sy) * w, static_cast<size_t>(w));
        const int32_t* cur = tmp;
        int32_t c = 1;
        for (int32_t q = 0; q < nwid; q++) {
            const int32_t target = wv[q];
            int32_t* out = dst + static_cast<size_t>(q) * spl;
            if (c == target && cur == tmp) {
                copyN(out, tmp, spl);
                cur = out;
                continue;
            }
            while (c < target) {
                const int32_t st = imin(target - c, c);
                const int32_t lim = pl - st;
                if (wantMax) {
                    for (int32_t p = 0; p < lim; p++) { const int32_t x = cur[p]; const int32_t y = cur[p + st]; out[p] = x > y ? x : y; }
                } else {
                    for (int32_t p = 0; p < lim; p++) { const int32_t x = cur[p]; const int32_t y = cur[p + st]; out[p] = x < y ? x : y; }
                }
                cur = out;
                c += st;
            }
        }
        cacheRow[slot] = sy;
        return dst;
    };
    // 上下對稱的兩條核列（同寬、同偏移）合成一趟：兩列先取極值再併進輸出
    Buf<int32_t> partner(skh, b);
    Buf<uint8_t> skip(skh, b);
    for (int32_t ky = 0; ky < kh; ky++) {
        partner[ky] = -1;
        const int32_t k2 = kh - 1 - ky;
        if (k2 <= ky || widIdx[ky] < 0 || widIdx[k2] != widIdx[ky] || offOf[k2] != offOf[ky]) continue;
        partner[ky] = k2;
        skip[k2] = 1;
    }
    ImgI32 outImg(w, h, b);
    int32_t* od = outImg.data.data();
    Buf<int32_t> curB(static_cast<size_t>(w), b);
    int32_t* cur = curB.data();
    for (int32_t y = 0; y < h; y++) {
        bool first = true;
        for (int32_t ky = 0; ky < kh; ky++) {
            if (skip[ky]) continue;
            const int32_t r = widIdx[ky];
            if (r < 0) continue;
            const int32_t o = pad + offOf[ky];
            const int32_t sy = y + (ky - k.ay);
            const bool in1 = sy >= 0 && sy < h;
            const int32_t k2 = partner[ky];
            const int32_t sy2 = k2 >= 0 ? y + (k2 - k.ay) : -1;
            const bool in2 = k2 >= 0 && sy2 >= 0 && sy2 < h;
            if (in1 && in2) {
                const int32_t* ra = rowExt(sy) + static_cast<size_t>(r) * spl + o;
                const int32_t* rb = rowExt(sy2) + static_cast<size_t>(r) * spl + o;
                if (wantMax) {
                    if (first) { for (int32_t x = 0; x < w; x++) { const int32_t u = ra[x]; const int32_t v = rb[x]; cur[x] = u > v ? u : v; } }
                    else { for (int32_t x = 0; x < w; x++) { const int32_t u = ra[x]; const int32_t v = rb[x]; const int32_t m = u > v ? u : v; if (m > cur[x]) cur[x] = m; } }
                } else {
                    if (first) { for (int32_t x = 0; x < w; x++) { const int32_t u = ra[x]; const int32_t v = rb[x]; cur[x] = u < v ? u : v; } }
                    else { for (int32_t x = 0; x < w; x++) { const int32_t u = ra[x]; const int32_t v = rb[x]; const int32_t m = u < v ? u : v; if (m < cur[x]) cur[x] = m; } }
                }
                first = false;
            } else if (in1 || in2) {
                const int32_t* s = rowExt(in1 ? sy : sy2) + static_cast<size_t>(r) * spl + o;
                if (first) copyN(cur, s, static_cast<size_t>(w));
                else if (wantMax) { for (int32_t x = 0; x < w; x++) { const int32_t v = s[x]; if (v > cur[x]) cur[x] = v; } }
                else { for (int32_t x = 0; x < w; x++) { const int32_t v = s[x]; if (v < cur[x]) cur[x] = v; } }
                first = false;
            }
        }
        if (first) for (int32_t x = 0; x < w; x++) cur[x] = id;
        copyN(od + static_cast<size_t>(y) * w, cur, static_cast<size_t>(w));
    }
    return outImg;
}

}  // namespace prims
}  // namespace nr
