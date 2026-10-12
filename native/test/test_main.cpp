// 夜讀 C++ 核心的原生單元測試（ctest：nr_core_tests）。不需要 JVM。
//
// 全部測試都在一條 256 KB 堆疊的執行緒上跑（手機上 C++ 跑在 Kotlin 的工作執行緒上，堆疊約 1 MB；核心規定不遞迴、不在堆疊放大陣列，
// 這裡用更小的堆疊抓違規）。並行測試另開執行緒（TSan 建置用它抓資料競爭）。
//
// 這裡只放「不用 Java 也能確定答案」的檢查：已知值、不變量、錯誤碼、邊界。逐位元對 Java 的比對在 JUnit
// （CppJmathParityTest 等）與 golden_check（arm64 用）。
#include <pthread.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <thread>
#include <vector>

#include "../core/convert.h"
#include "../core/jmath.h"
#include "../core/params_gen.h"
#include "../core/tables.h"
#include "../core/types.h"
#include "cases.h"
#include "nightread/nr.h"

namespace {

// 用 using 宣告（不是 using namespace）：libc++ 會把 ::round、::abs… 放進全域，using namespace 會讓呼叫變成有歧義。
using nr::jm::dbits;
using nr::jm::dfrom;
using nr::jm::fbits;
using nr::jm::ffrom;
using nr::jm::isNaN;
using nr::jm::rint;
using nr::jm::round;
using nr::jm::roundToInt;
using nr::jm::d2i;
using nr::jm::d2l;
using nr::jm::f2i;
using nr::jm::f2l;
using nr::jm::bdHalfEven;
using nr::jm::floorDiv;
using nr::jm::floorMod;
using nr::jm::shl;
using nr::jm::shr;
using nr::jm::ushr;
using nr::jm::wmul;
using nr::jm::wadd;
using nr::jm::coerceIn;
using nr::jm::mod;
using nr::jm::min;
using nr::jm::max;
using nr::jm::compare;
using nr::jm::signum;
using nr::jm::sin;
using nr::jm::cos;
using nr::jm::exp;
using nr::jm::pow;
using nr::jm::atan;
using nr::jm::atan2;
using nr::jm::sqrt;
using nr::jm::npSum;
using nr::jm::npSumChunked;
using nr::jm::npArgsort;
using nr::jm::npArgsortT;
using nr::jm::eigh2;


int g_fail = 0;
int g_checks = 0;

#define CHECK(cond)                                                                    \
    do {                                                                               \
        g_checks++;                                                                    \
        if (!(cond)) { g_fail++; std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } \
    } while (0)

#define CHECK_BITS(a, b)                                                                                        \
    do {                                                                                                        \
        g_checks++;                                                                                             \
        const double _a = (a), _b = (b);                                                                        \
        if (dbits(_a) != dbits(_b)) {                                                                           \
            g_fail++;                                                                                           \
            std::printf("FAIL %s:%d: %s = %.17g (0x%016llx) != %.17g (0x%016llx)\n", __FILE__, __LINE__, #a, _a, \
                        (unsigned long long)dbits(_a), _b, (unsigned long long)dbits(_b));                     \
        }                                                                                                       \
    } while (0)

double D(uint64_t b) { return dfrom(b); }
const double NaN = D(0x7ff8000000000000ULL);
const double INF = D(0x7ff0000000000000ULL);

void testRounding() {
    CHECK_BITS(rint(2.5), 2.0);
    CHECK_BITS(rint(3.5), 4.0);
    CHECK_BITS(rint(-2.5), -2.0);
    CHECK_BITS(rint(-0.4), -0.0);
    CHECK_BITS(rint(-0.5), -0.0);
    CHECK_BITS(rint(0.5), 0.0);
    CHECK_BITS(rint(1e300), 1e300);
    CHECK_BITS(rint(4503599627370495.5), 4503599627370496.0);
    CHECK(round(0.49999999999999994) == 0);
    CHECK(round(-0.5) == 0);
    CHECK(round(-2.5) == -2);
    CHECK(round(2.5) == 3);
    CHECK(round(NaN) == 0);
    CHECK(round(1e20) == INT64_MAX);
    CHECK(round(-1e20) == INT64_MIN);
    CHECK(round(4503599627370497.0) == 4503599627370497LL);
    CHECK(round(0.49999997f) == 0);
    CHECK(round(-0.5f) == 0);
    CHECK(round(2.5f) == 3);
    CHECK(round(1e10f) == INT32_MAX);
    CHECK(round(ffrom(0x7fc00000U)) == 0);
    CHECK(roundToInt(3e9) == INT32_MAX);
    CHECK(roundToInt(-3e9) == INT32_MIN);
    CHECK(roundToInt(2.5) == 3);
    CHECK(roundToInt(-2.5) == -2);
    CHECK(roundToInt(-2147483648.6) == INT32_MIN);
    CHECK(d2i(NaN) == 0);
    CHECK(d2i(1e10) == INT32_MAX);
    CHECK(d2i(-1e10) == INT32_MIN);
    CHECK(d2i(-2147483648.9) == INT32_MIN);
    CHECK(d2i(2147483647.9) == INT32_MAX);
    CHECK(d2i(-0.9) == 0);
    CHECK(d2l(9.3e18) == INT64_MAX);
    CHECK(d2l(-9.3e18) == INT64_MIN);
    CHECK(d2l(NaN) == 0);
    CHECK(f2i(3e9f) == INT32_MAX);
    CHECK(f2l(-1e19f) == INT64_MIN);
}

void testBigDecimal() {
    CHECK_BITS(bdHalfEven(0.125, 2), 0.12);
    CHECK_BITS(bdHalfEven(0.375, 2), 0.38);
    CHECK_BITS(bdHalfEven(2.5, 0), 2.0);
    CHECK_BITS(bdHalfEven(3.5, 0), 4.0);
    CHECK_BITS(bdHalfEven(-0.004, 2), 0.0);          // BigDecimal 沒有 −0
    CHECK_BITS(bdHalfEven(-0.0, 3), 0.0);
    CHECK_BITS(bdHalfEven(0.0005, 3), 0.001);        // 0.0005 的二進位值略大於 0.0005
    CHECK_BITS(bdHalfEven(1.0005, 3), 1.0);          // 1.0005 的二進位值略小
    CHECK_BITS(bdHalfEven(-2.675, 2), -2.67);
    CHECK_BITS(bdHalfEven(1e-320, 3), 0.0);
    CHECK_BITS(bdHalfEven(1e20, 2), 1e20);
    CHECK_BITS(bdHalfEven(2251799813685248.5, 9), 2251799813685248.5);   // 大商路徑
    CHECK_BITS(bdHalfEven(0.1, 18), 0.1);                                 // 大商路徑
    CHECK(isNaN(bdHalfEven(NaN, 1)));
    CHECK(isNaN(bdHalfEven(INF, 1)));
    CHECK(isNaN(bdHalfEven(1.0, 19)));
}

void testIntegers() {
    CHECK(floorDiv(-7, 2) == -4);
    CHECK(floorMod(-7, 2) == 1);
    CHECK(floorMod(7, -2) == -1);
    CHECK(floorDiv(INT32_MIN, -1) == INT32_MIN);
    CHECK(floorMod(INT32_MIN, -1) == 0);
    CHECK(nr::jm::div(INT32_MIN, -1) == INT32_MIN);
    CHECK(nr::jm::rem(INT32_MIN, -1) == 0);
    CHECK(nr::jm::div(INT64_MIN, int64_t(-1)) == INT64_MIN);
    CHECK(nr::jm::rem(-7, 2) == -1);
    CHECK(floorDiv(int64_t(-7), int64_t(2)) == -4);
    CHECK(shl(1, 33) == 2);
    CHECK(shl(1, -1) == INT32_MIN);
    CHECK(ushr(-1, 28) == 15);
    CHECK(shr(-16, 2) == -4);
    CHECK(shl(int64_t(1), 65) == 2);
    CHECK(ushr(int64_t(-1), 60) == 15);
    CHECK(wmul(65536, 65536) == 0);
    CHECK(wadd(INT32_MAX, 1) == INT32_MIN);
    CHECK(coerceIn(5, 0, 3) == 3);
    CHECK(coerceIn(-5, 0, 3) == 0);
}

void testCompare() {
    CHECK_BITS(mod(-1.0, 3.0), 2.0);
    CHECK_BITS(mod(1.0, -3.0), -2.0);
    CHECK_BITS(mod(-0.0, 3.0), -0.0);
    CHECK_BITS(mod(5.0, INF), 5.0);
    CHECK_BITS(mod(-5.0, INF), INF);
    CHECK_BITS(mod(-190.0, 180.0), 170.0);
    CHECK_BITS(min(-0.0, 0.0), -0.0);
    CHECK_BITS(min(0.0, -0.0), -0.0);
    CHECK_BITS(max(-0.0, 0.0), 0.0);
    CHECK_BITS(max(0.0, -0.0), 0.0);
    CHECK(isNaN(min(NaN, 1.0)) && isNaN(min(1.0, NaN)) && isNaN(max(1.0, NaN)));
    CHECK(fbits(min(-0.0f, 0.0f)) == 0x80000000U);
    CHECK(compare(0.0, -0.0) == 1);
    CHECK(compare(-0.0, 0.0) == -1);
    CHECK(compare(NaN, D(0xfff8000000000001ULL)) == 0);
    CHECK(compare(NaN, INF) == 1);
    CHECK(compare(1.0, 2.0) == -1);
    CHECK(compare(0.0f, -0.0f) == 1);
    CHECK_BITS(signum(-0.0), -0.0);
    CHECK_BITS(signum(-3.0), -1.0);
    CHECK(isNaN(coerceIn(NaN, 0.0, 1.0)));
    CHECK_BITS(coerceIn(-1.0, 0.0, 1.0), 0.0);
}

void testFdlibm() {
    CHECK_BITS(sin(0.0), 0.0);
    CHECK_BITS(sin(-0.0), -0.0);
    CHECK_BITS(cos(0.0), 1.0);
    CHECK_BITS(exp(0.0), 1.0);
    CHECK_BITS(exp(1.0), D(0x4005BF0A8B14576AULL));             // StrictMath.exp(1.0)：比 Math.E 大 1 ulp（HotSpot 的 Math.exp 內建版回 Math.E）
    CHECK_BITS(pow(2.0, 10.0), 1024.0);
    CHECK_BITS(pow(3.0, 2.0), 9.0);
    CHECK_BITS(pow(0.25, 0.5), 0.5);
    CHECK_BITS(atan2(0.0, -0.0), D(0x400921FB54442D18ULL));     // π
    CHECK_BITS(atan2(-0.0, -0.0), D(0xC00921FB54442D18ULL));    // −π
    CHECK_BITS(atan(INF), D(0x3FF921FB54442D18ULL));            // π/2
    CHECK_BITS(sin(D(0x400921FB54442D18ULL)), 1.2246467991473532e-16);
    CHECK(isNaN(pow(-8.0, 1.0 / 3.0)));
    CHECK(isNaN(sin(INF)));
    CHECK_BITS(sqrt(2.0), D(0x3FF6A09E667F3BCDULL));
}

void testArrays() {
    std::vector<double> a(1000);
    for (int i = 0; i < 1000; i++) a[i] = i + 1;
    CHECK_BITS(npSum(a.data(), 1000), 500500.0);
    const double z3[] = {-0.0, -0.0, -0.0};
    CHECK_BITS(npSum(z3, 3), 0.0);                               // n < 8：從 +0.0 開始加
    const double z8[] = {-0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0};
    CHECK_BITS(npSum(z8, 8), -0.0);                              // 八路累加器從元素本身開始
    CHECK_BITS(npSum(nullptr, 0), 0.0);
    std::vector<double> ones(20000, 1.0);
    CHECK_BITS(npSumChunked(ones.data(), 20000), 20000.0);
    CHECK_BITS(npSumChunked(nullptr, 0), 0.0);
    // 很長的陣列（顯式堆疊夠深）
    std::vector<double> big(5000000, 0.5);
    CHECK_BITS(npSum(big.data(), static_cast<int64_t>(big.size())), 2500000.0);

    // npArgsort：結果是排列、且依值不遞減
    for (int64_t idx = 0; idx < 300; idx++) {
        const std::vector<int32_t> v = nrt::genArgsortArray(idx);
        std::vector<int32_t> ts(v.size());
        npArgsort(v.data(), static_cast<int32_t>(v.size()), ts.data());
        std::vector<uint8_t> seen(v.size(), 0);
        bool ok = true;
        for (size_t i = 0; i < ts.size(); i++) {
            if (ts[i] < 0 || static_cast<size_t>(ts[i]) >= v.size() || seen[ts[i]]) { ok = false; break; }
            seen[ts[i]] = 1;
            if (i > 0 && v[ts[i - 1]] > v[ts[i]]) { ok = false; break; }
        }
        CHECK(ok);
    }
    // McIlroy 對手要真的逼出 heapsort，否則 npArgsort 的深度保護路徑沒被測到
    for (int32_t n : {200, 1000, 5000}) {
        int heaps = 0;
        const std::vector<int32_t> v = nrt::antiqsort(n, &heaps);
        CHECK(heaps > 0);
        int heaps2 = 0;
        std::vector<int32_t> ts(v.size());
        npArgsortT(n, ts.data(), [&](int32_t i, int32_t j) { return v[i] < v[j]; }, [&] { heaps2++; });
        CHECK(heaps2 == heaps);                                  // 用它當輸入，同樣走到 heapsort
        std::printf("  antiqsort n=%d: heapsort 進入 %d 次\n", n, heaps);
    }

    double e[6];
    eigh2(1.0, 0.0, 1.0, e);
    CHECK_BITS(e[0], 1.0); CHECK_BITS(e[1], 1.0); CHECK_BITS(e[2], 1.0); CHECK_BITS(e[3], 0.0); CHECK_BITS(e[4], 0.0); CHECK_BITS(e[5], 1.0);
    eigh2(3.0, 0.0, 1.0, e);                                     // 對角、需交換
    CHECK_BITS(e[0], 1.0); CHECK_BITS(e[1], 3.0); CHECK_BITS(e[2], 0.0); CHECK_BITS(e[3], 1.0);
}

void testTables() {
    for (double sigma : {0.3, 1.0, 2.5, 7.77, 10.0}) {
        const std::vector<int32_t> w = nr::tables::gaussW(sigma);
        int64_t s = 0;
        for (int32_t v : w) s += v;
        CHECK(s == 65536);
        CHECK(w.size() % 2 == 1);
        bool sym = true;
        for (size_t i = 0; i < w.size() / 2; i++) if (w[i] != w[w.size() - 1 - i]) sym = false;
        CHECK(sym);
    }
    CHECK(nr::tables::gaussW(1.0).size() == 9);
    CHECK(nr::tables::gaussianBlurKsize(1.0) == 9);
    CHECK(nr::tables::gaussianBlurKsize(0.1) == 3);
    float lut[256];
    nr::tables::textLut(16, 240, lut);
    CHECK(lut[0] == 240.0f && lut[255] == 16.0f);
    nr::tables::inkLut(16, 170, false, lut);
    CHECK(lut[0] == 16.0f && lut[255] == 170.0f);
    std::vector<std::vector<int32_t>> off;
    CHECK(nr::tables::lineOffsets(1, off) && off.size() == 8 && off[0].size() == 2 && off[0][0] == 0 && off[0][1] == 0);
    CHECK(!nr::tables::lineOffsets(2, off) && off.empty());       // 偶數長度：Kotlin 丟 ArrayIndexOutOfBounds
    CHECK(nr::tables::lineOffsets(17, off) && off.size() == 8);
    CHECK(off[0].size() == 2 * 17);                              // 水平方向：17 個點
}

void testTypes() {
    nr::Budget b;
    {
        nr::Mask m(1351, 1920, &b);
        bool zero = true;
        for (size_t i = 0; i < m.data.size(); i++) if (m.data[i]) { zero = false; break; }
        CHECK(zero);
        CHECK(b.cur.load() == 1351 * 1920);
        nr::ImgI32 g(10, 10, &b);
        CHECK(b.cur.load() == 1351 * 1920 + 400);
    }
    CHECK(b.cur.load() == 0);
    CHECK(b.peak.load() == 1351 * 1920 + 400);
    bool threw = false;
    try { nr::Mask bad(-1, 3); } catch (const std::length_error&) { threw = true; }
    CHECK(threw);
    threw = false;
    try { nr::Mask bad(65536, 65536); } catch (const std::length_error&) { threw = true; }
    CHECK(threw);
    nr::Mask empty(0, 5);
    CHECK(empty.data.size() == 0 && empty.data.data() == nullptr);

    const uint8_t cross[] = {0, 1, 0, 1, 1, 1, 0, 1, 0};
    nr::Kernel k(3, 3, cross);
    CHECK(k.ax == 1 && k.ay == 1);
    CHECK(k.runStart[0] == 1 && k.runEnd[0] == 2 && k.runStart[1] == 0 && k.runEnd[1] == 3);
    const uint8_t holes[] = {1, 0, 1, 0, 0, 0};
    nr::Kernel k2(3, 2, holes);
    CHECK(k2.runStart[0] == 0 && k2.runEnd[0] == 3 && k2.runStart[1] == 0 && k2.runEnd[1] == 0);

    // 列打包：寬度 63／64／65／127／128／129 的來回，末字超出寬度的位元一律 0
    for (int32_t w : {1, 63, 64, 65, 127, 128, 129, 200}) {
        nr::Mask m(w, 3);
        uint64_t s = 0x1234567 + static_cast<uint64_t>(w);
        for (size_t i = 0; i < m.data.size(); i++) { s = s * 6364136223846793005ULL + 1442695040888963407ULL; m.data[i] = static_cast<uint8_t>((s >> 62) & 1); }
        const nr::PackedBits p = nr::packBits(m);
        CHECK(p.nw == (w + 63) / 64);
        if (w % 64 != 0) {
            const uint64_t tailMask = ~((uint64_t(1) << (w % 64)) - 1);
            bool clean = true;
            for (int32_t y = 0; y < 3; y++) if (p.words[static_cast<size_t>(y) * p.nw + p.nw - 1] & tailMask) clean = false;
            CHECK(clean);
        }
        const nr::Mask back = nr::unpackBits(p);
        CHECK(std::memcmp(back.data.data(), m.data.data(), m.data.size()) == 0);
    }
}

void testPage() {
    CHECK(nr_abi() == ((1 << 16) | 4));
    nr_page* p = nullptr;
    CHECK(nr_create(0, 1, &p) == NR_E_ARG && p == nullptr);
    CHECK(nr_create(65536, 65536, &p) == NR_E_ARG);
    CHECK(nr_create(3, 2, nullptr) == NR_E_ARG);
    CHECK(nr_create(3, 2, &p) == NR_OK && p != nullptr);
    CHECK(nr_width(p) == 3 && nr_height(p) == 2);
    const int32_t px[] = {static_cast<int32_t>(0xFF102030U), static_cast<int32_t>(0x00FFFFFFU), 0, -1, 0x7F808080, 0x12345678};
    int32_t out[6];
    CHECK(nr_get_rows(p, NR_PLANE_GRAY, 0, 1, out, 3) == NR_E_STATE);
    CHECK(nr_put_rows(p, 1, 2, px, 3) == NR_E_ARG);
    CHECK(nr_put_rows(p, 0, 2, px, 2) == NR_E_ARG);              // stride < w
    CHECK(nr_put_rows(p, 0, 1, px, 3) == NR_OK);
    CHECK(nr_get_rows(p, NR_PLANE_GRAY, 0, 2, out, 3) == NR_E_STATE);
    CHECK(nr_put_rows(p, 1, 1, px + 3, 3) == NR_OK);
    int32_t g[6], c[6], argb[6];
    nr::argbToGrayChroma(px, 6, g, c);
    CHECK(nr_get_rows(p, NR_PLANE_GRAY, 0, 2, out, 3) == NR_OK && std::memcmp(out, g, sizeof g) == 0);
    CHECK(nr_get_rows(p, NR_PLANE_CHROMA, 0, 2, out, 3) == NR_OK && std::memcmp(out, c, sizeof c) == 0);
    nr::grayToArgb(g, 6, argb);
    CHECK(nr_get_rows(p, NR_PLANE_GRAY_ARGB, 0, 2, out, 3) == NR_OK && std::memcmp(out, argb, sizeof argb) == 0);
    CHECK(nr_get_rows(p, 7, 0, 1, out, 3) == NR_E_ARG);
    CHECK(g[0] == (0x10 * 299 + 0x20 * 587 + 0x30 * 114 + 500) / 1000 && c[0] == 0x20);
    CHECK(g[3] == 255 && c[3] == 0);
    CHECK(nr_peak_bytes(p) == 3 * 2 * 4 * 2 + 2);
    CHECK(nr_is_cancelled(p) == 0);
    nr_cancel(p);
    CHECK(nr_is_cancelled(p) == 1);
    nr_free(p);
    nr_free(nullptr);
    int32_t clamp[] = {-5, 0, 255, 256, 128};
    int32_t ca[5];
    nr::grayToArgb(clamp, 5, ca);
    CHECK(ca[0] == static_cast<int32_t>(0xFF000000U) && ca[3] == -1 && ca[4] == static_cast<int32_t>(0xFF808080U));
}

/** 多頁同時（不同執行緒各自一頁）＋另一條執行緒一直取消：結果必須正確、TSan 不得報資料競爭。 */
void testConcurrency() {
    const int T = 8;
    const int32_t W = 257, H = 64;
    std::vector<nr_page*> pages(T, nullptr);
    for (int t = 0; t < T; t++) CHECK(nr_create(W, H, &pages[t]) == NR_OK);
    std::atomic<bool> stop{false};
    std::atomic<int> bad{0};
    std::thread canceller([&] {
        while (!stop.load()) for (int t = 0; t < T; t++) { nr_cancel(pages[t]); (void)nr_peak_bytes(pages[t]); }
    });
    std::vector<std::thread> workers;
    for (int t = 0; t < T; t++) {
        workers.emplace_back([&, t] {
            const std::vector<int32_t> px = nrt::genPixels(static_cast<int64_t>(W) * H, static_cast<uint64_t>(t));
            std::vector<int32_t> g(px.size()), c(px.size()), out(px.size());
            nr::argbToGrayChroma(px.data(), static_cast<int64_t>(px.size()), g.data(), c.data());
            for (int32_t y = 0; y < H; y += 16) nr_put_rows(pages[t], y, 16, px.data() + static_cast<size_t>(y) * W, W);
            if (nr_get_rows(pages[t], NR_PLANE_GRAY, 0, H, out.data(), W) != NR_OK || out != g) bad++;
            if (nr_get_rows(pages[t], NR_PLANE_CHROMA, 0, H, out.data(), W) != NR_OK || out != c) bad++;
        });
    }
    for (auto& w : workers) w.join();
    stop = true;
    canceller.join();
    CHECK(bad.load() == 0);
    for (int t = 0; t < T; t++) { CHECK(nr_is_cancelled(pages[t]) == 1); nr_free(pages[t]); }
}

/** 參數（params_gen.h）：預設值攤平來回、逐欄存取與槽位一致、拒收不合法的攤平、與 nr.h 的規則版本／schema 握手。 */
void testParams() {
    namespace P = nr::params;
    CHECK(P::kFieldCount == 379);
    CHECK(nr_params_schema() == static_cast<int64_t>(P::kSchemaHash));
    CHECK(P::kRulesVersion == NR_RULES_VERSION && P::kRulesDigests[P::kRulesDigestCount - 1].version == NR_RULES_VERSION);
    for (int32_t k = 0; k < P::kRulesDigestCount; k++) CHECK(P::kRulesDigests[k].version == k + 1 && std::strlen(P::kRulesDigests[k].sha256) == 64);
    std::vector<int32_t> ints(P::kIntSlots), ints2(P::kIntSlots);
    std::vector<double> dbls(P::kDoubleSlots), dbls2(P::kDoubleSlots);
    const P::NightReadParams def{};
    P::toFlat(def, ints.data(), dbls.data());
    P::NightReadParams back;
    CHECK(P::fromFlat(ints.data(), P::kIntSlots, dbls.data(), P::kDoubleSlots, &back));
    P::toFlat(back, ints2.data(), dbls2.data());
    CHECK(ints == ints2 && std::memcmp(dbls.data(), dbls2.data(), dbls.size() * sizeof(double)) == 0);
    CHECK(def.bg == 16 && def.glowStrength == 55.0f && def.stickerMode == P::StickerMode::ALL && !def.bubbleSeal.cutGeo.present);
    CHECK(def.obj.fx.cosJoin == 0.9945218953682733 && def.sep.peakMax == 800 && !def.more.enabled && def.ring.raySigma == 2.0);
    // 每個欄位：setField 設一個跟欄位序號有關的值 → toFlat 的槽位上要看得到、getField 讀回相同
    P::NightReadParams p;
    int bad = 0;
    for (int32_t k = 0; k < P::kFieldCount; k++) {
        const P::Field& f = P::kFields[k];
        P::Value v{0, 0.0, true};
        switch (f.kind) {
            case P::Kind::Int: v.i = 100000 + k; break;
            case P::Kind::Double: v.d = 1000.25 + k; break;
            case P::Kind::Float: v.d = 0.5 + k; break;
            case P::Kind::Bool: v.i = k & 1; break;
            case P::Kind::OptInt: v.i = 200000 + k; break;
            case P::Kind::Enum: v.i = k % P::kStickerModeCount; break;
        }
        if (!P::setField(&p, k, v)) bad++;
        P::Value r{};
        if (!P::getField(p, k, &r) || r.i != v.i || dbits(r.d) != dbits(v.d) || r.present != v.present) bad++;
    }
    CHECK(bad == 0);
    P::toFlat(p, ints.data(), dbls.data());
    for (int32_t k = 0; k < P::kFieldCount; k++) {
        const P::Field& f = P::kFields[k];
        P::Value r{};
        P::getField(p, k, &r);
        if (f.doubleSlot >= 0) {
            if (f.intSlot != -1 || dbits(dbls[static_cast<size_t>(f.doubleSlot)]) != dbits(r.d)) bad++;
        } else if (f.kind == P::Kind::OptInt) {
            if (ints[static_cast<size_t>(f.intSlot)] != 1 || ints[static_cast<size_t>(f.intSlot) + 1] != r.i) bad++;
        } else if (ints[static_cast<size_t>(f.intSlot)] != r.i) {
            bad++;
        }
    }
    CHECK(bad == 0);
    // 不合法的值：Bool 2、enum 超出範圍、Float 轉不回、Int 超出 int32、OptInt 沒有值卻帶值
    for (int32_t k = 0; k < P::kFieldCount; k++) {
        const P::Field& f = P::kFields[k];
        P::NightReadParams q;
        if (f.kind == P::Kind::Bool) CHECK(!P::setField(&q, k, P::Value{2, 0.0, true}));
        if (f.kind == P::Kind::Enum) CHECK(!P::setField(&q, k, P::Value{P::kStickerModeCount, 0.0, true}));
        if (f.kind == P::Kind::Float) CHECK(!P::setField(&q, k, P::Value{0, 0.1, true}) && !P::setField(&q, k, P::Value{0, 1e300, true}));
        if (f.kind == P::Kind::Int) CHECK(!P::setField(&q, k, P::Value{int64_t(INT32_MAX) + 1, 0.0, true}));
        if (f.kind == P::Kind::OptInt) CHECK(!P::setField(&q, k, P::Value{5, 0.0, false}) && P::setField(&q, k, P::Value{0, 0.0, false}));
    }
    P::toFlat(def, ints.data(), dbls.data());
    CHECK(!P::fromFlat(ints.data(), P::kIntSlots - 1, dbls.data(), P::kDoubleSlots, &back));
    CHECK(!P::fromFlat(ints.data(), P::kIntSlots, dbls.data(), P::kDoubleSlots + 1, &back));
    CHECK(!P::fromFlat(nullptr, P::kIntSlots, dbls.data(), P::kDoubleSlots, &back));
}

void* runAll(void*) {
    testRounding();
    testBigDecimal();
    testIntegers();
    testCompare();
    testFdlibm();
    testArrays();
    testTables();
    testTypes();
    testPage();
    testParams();
    testConcurrency();
    return nullptr;
}

}  // namespace

int main() {
    // 256 KB 堆疊的執行緒（PTHREAD_STACK_MIN 以上）
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    if (pthread_attr_setstacksize(&attr, 256 * 1024) != 0) { std::printf("setstacksize failed\n"); return 2; }
    pthread_t th;
    if (pthread_create(&th, &attr, runAll, nullptr) != 0) { std::printf("pthread_create failed\n"); return 2; }
    pthread_join(th, nullptr);
    pthread_attr_destroy(&attr);
    std::printf("nr_core_tests: %d checks, %d failed\n", g_checks, g_fail);
    return g_fail == 0 ? 0 : 1;
}
