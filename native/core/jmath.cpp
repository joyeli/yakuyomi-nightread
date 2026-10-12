// 夜讀 C++ 核心：Java／Kotlin 語意數值原語的非內聯部分。語意說明見 jmath.h。
// 移植自 nightread/src/main/kotlin/li/joye/yakuyomi/nightread/Cv.kt（npSum、npArgsort、eigh2）、
// BgObjects.kt（npSumChunked）、Bleed.kt／Regions.kt／Sticker.kt／Separators.kt 的 BigDecimal 捨入 @ 27d1e96。
#include "jmath.h"

namespace nr {
namespace jm {

namespace {

using u128 = unsigned __int128;

int bitLength(u128 v) noexcept {
    int n = 0;
    while (v != 0) { v >>= 1; n++; }
    return n;
}

constexpr uint64_t POW10[19] = {
    1ULL, 10ULL, 100ULL, 1000ULL, 10000ULL, 100000ULL, 1000000ULL, 10000000ULL, 100000000ULL, 1000000000ULL,
    10000000000ULL, 100000000000ULL, 1000000000000ULL, 10000000000000ULL, 100000000000000ULL,
    1000000000000000ULL, 10000000000000000ULL, 100000000000000000ULL, 1000000000000000000ULL,
};

// q / 10^scale 正確捨入（五成雙）成 double；q > 0。BigDecimal.doubleValue() 的規格就是正確捨入。
double divPow10(u128 q, int scale) noexcept {
    const uint64_t d = POW10[scale];
    if (q < (u128(1) << 53)) {
        // 兩個運算元都是精確的 double，IEEE 除法本身就正確捨入
        return static_cast<double>(static_cast<uint64_t>(q)) / static_cast<double>(d);
    }
    // 大的 q：把被除數左移到第 119 位，取 ≥ 90 位的商加黏滯位，自己捨入到 53 位
    const int k = 120 - bitLength(q);
    const u128 num = q << k;
    u128 quo = num / d;
    const bool sticky = (num % d) != 0;
    const int bq = bitLength(quo);
    const int drop = bq - 53;
    uint64_t mant = static_cast<uint64_t>(quo >> drop);
    const u128 low = quo & ((u128(1) << drop) - 1);
    const u128 half = u128(1) << (drop - 1);
    if (low > half || (low == half && (sticky || (mant & 1)))) mant++;
    int e = drop - k;
    if (mant == (uint64_t(1) << 53)) { mant >>= 1; e++; }
    // mant·2^e，mant 為 53 位、結果是正常數（q／10^s ≥ 2⁵³／10¹⁸），直接組位元
    const int unbiased = e + 52;
    return dfrom((static_cast<uint64_t>(unbiased + 1023) << 52) | (mant & 0x000FFFFFFFFFFFFFULL));
}

}  // namespace

double bdHalfEven(double v, int scale) noexcept {
    if (scale < 0 || scale > 18) return dfrom(0x7ff8000000000000ULL);
    const uint64_t bits = dbits(v);
    const int ex = static_cast<int>((bits >> 52) & 0x7ff);
    if (ex == 0x7ff) return dfrom(0x7ff8000000000000ULL);   // NaN／±∞：Kotlin 丟 NumberFormatException
    if (v == 0.0) return 0.0;                              // BigDecimal 沒有 −0
    const bool neg = (bits >> 63) != 0;
    uint64_t m = bits & 0x000FFFFFFFFFFFFFULL;
    int e;
    if (ex == 0) e = -1074; else { m |= uint64_t(1) << 52; e = ex - 1075; }   // |v| = m·2^e
    if (e >= 0) return v;                                  // 整數：setScale 不改值，doubleValue 精確還原
    const int sh = -e;
    const u128 num = u128(m) * POW10[scale];               // < 2⁵³·10¹⁸ < 2¹¹³
    u128 q;
    if (sh >= 128) {
        q = 0;                                             // num < 2¹¹³ ≤ 半個單位
    } else {
        q = num >> sh;
        const u128 rem = num - (q << sh);
        const u128 half = u128(1) << (sh - 1);
        if (rem > half || (rem == half && (q & 1))) q++;
    }
    if (q == 0) return 0.0;
    const double r = divPow10(q, scale);
    return neg ? -r : r;
}

namespace {

double npSumLeaf(const double* a, int64_t n) noexcept {
    if (n < 8) {
        double s = 0.0;
        for (int64_t i = 0; i < n; i++) s += a[i];
        return s;
    }
    double r[8];
    for (int j = 0; j < 8; j++) r[j] = a[j];
    int64_t i = 8;
    const int64_t lim = n - (n % 8);
    while (i < lim) {
        for (int j = 0; j < 8; j++) r[j] += a[i + j];
        i += 8;
    }
    double res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]));
    while (i < n) { res += a[i]; i++; }
    return res;
}

}  // namespace

double npSum(const double* a, int64_t n) noexcept {
    // Kotlin 版遞迴 npSum(off, n2) + npSum(off + n2, n − n2)；這裡用固定的顯式堆疊照同樣的順序算（不用遞迴：手機工作執行緒堆疊小）。
    // 深度 ≤ log2(n / 128) + 1；n < 2⁶³ 時 < 64。
    struct Frame { const double* p; int64_t n; int64_t n2; int state; double left; };
    Frame st[64];
    int sp = 0;
    st[sp++] = Frame{a, n, 0, 0, 0.0};
    double ret = 0.0;
    while (sp > 0) {
        Frame& f = st[sp - 1];
        if (f.state == 0) {
            if (f.n <= 128) { ret = npSumLeaf(f.p, f.n); sp--; continue; }
            int64_t n2 = f.n / 2;
            n2 -= n2 % 8;
            f.n2 = n2;
            f.state = 1;
            st[sp++] = Frame{f.p, n2, 0, 0, 0.0};
        } else if (f.state == 1) {
            f.left = ret;
            f.state = 2;
            st[sp++] = Frame{f.p + f.n2, f.n - f.n2, 0, 0, 0.0};
        } else {
            ret = f.left + ret;
            sp--;
        }
    }
    return ret;
}

double npSumChunked(const double* a, int64_t n) noexcept {
    if (n == 0) return 0.0;
    double res = npSum(a, n < 8192 ? n : 8192);
    int64_t off = 8192;
    while (off < n) {
        const int64_t c = (n - off) < 8192 ? (n - off) : 8192;
        res += npSum(a + off, c);
        off += 8192;
    }
    return res;
}

void npArgsort(const int32_t* v, int32_t n, int32_t* ts) noexcept {
    npArgsortT(n, ts, [v](int32_t i, int32_t j) { return v[i] < v[j]; }, [] {});
}

void eigh2(double a, double b, double c, double out[6]) noexcept {
    const double eps = 1.1102230246251565e-16;            // Math.ulp(1.0) / 2 ＝ 2⁻⁵³（dlamch('E')）
    const double safmin = 2.2250738585072014e-308;        // Double.MIN_NORMAL
    double e = b;
    double w0, w1;
    double z00 = 1.0, z01 = 0.0, z10 = 0.0, z11 = 1.0;
    if (e != 0.0 && abs(e) <= (sqrt(abs(a)) * sqrt(abs(c))) * eps) e = 0.0;
    if (e == 0.0 || abs(e) * abs(e) <= (eps * eps * abs(a)) * abs(c) + safmin) {
        w0 = a; w1 = c;
    } else {
        // dlaev2
        const double sm = a + c;
        const double df = a - c;
        const double adf = abs(df);
        const double tb = e + e;
        const double ab = abs(tb);
        double acmx, acmn;
        if (abs(a) > abs(c)) { acmx = a; acmn = c; } else { acmx = c; acmn = a; }
        double rt;
        if (adf > ab) rt = adf * sqrt(1.0 + (ab / adf) * (ab / adf));
        else if (adf < ab) rt = ab * sqrt(1.0 + (adf / ab) * (adf / ab));
        else rt = ab * sqrt(2.0);
        double rt1, rt2;
        int sgn1;
        if (sm < 0.0) { rt1 = 0.5 * (sm - rt); sgn1 = -1; rt2 = (acmx / rt1) * acmn - (e / rt1) * e; }
        else if (sm > 0.0) { rt1 = 0.5 * (sm + rt); sgn1 = 1; rt2 = (acmx / rt1) * acmn - (e / rt1) * e; }
        else { rt1 = 0.5 * rt; rt2 = -0.5 * rt; sgn1 = 1; }
        double cs;
        int sgn2;
        if (df >= 0.0) { cs = df + rt; sgn2 = 1; } else { cs = df - rt; sgn2 = -1; }
        double cs1, sn1;
        if (abs(cs) > ab) {
            const double ct = -tb / cs;
            sn1 = 1.0 / sqrt(1.0 + ct * ct);
            cs1 = ct * sn1;
        } else if (ab == 0.0) {
            cs1 = 1.0; sn1 = 0.0;
        } else {
            const double tn = -cs / tb;
            cs1 = 1.0 / sqrt(1.0 + tn * tn);
            sn1 = tn * cs1;
        }
        if (sgn1 == sgn2) { const double tn = cs1; cs1 = -sn1; sn1 = tn; }
        w0 = rt1; w1 = rt2;
        z00 = cs1; z01 = -sn1; z10 = sn1; z11 = cs1;
    }
    if (w1 < w0) {
        const double tw = w0; w0 = w1; w1 = tw;
        double t = z00; z00 = z01; z01 = t;
        t = z10; z10 = z11; z11 = t;
    }
    out[0] = w0; out[1] = w1; out[2] = z00; out[3] = z10; out[4] = z01; out[5] = z11;
}

}  // namespace jm
}  // namespace nr
