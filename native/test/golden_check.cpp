// golden_check：拿 JUnit 寫下的 Java 答案（分塊雜湊）檢查這個平台上的 C++ 數值核心。
//
// 用途：手機晶片模擬。NDK clang 編成 arm64 靜態執行檔，在 qemu-aarch64 上跑（native/tools/arm64_qemu.sh）；主機上也能直接跑。
// golden 由 CppJmathParityTest／CppTablesParityTest／CppPageParityTest 在 -Dnightread.goldenOut=<dir> 時寫出（每組一個 .txt，共 6 個）。
// 輸入由 cases.cpp 的同一個產生器重產；每一塊先比輸入雜湊（確定兩邊算的是同一組輸入），再比輸出雜湊。
//
// 格式（一行一塊，十六進位雜湊；# 開頭是註解）：
//   S <函式名> <start> <n> <輸入雜湊> <輸出雜湊>       純量函式第 start..start+n−1 個案例
//   A sum|sumc|argsort <idx0> <count> <輸入雜湊> <輸出雜湊>
//   T gaussW|gk|ringk|text|ink|lineoff <輸出雜湊>      整表
//   P <像素數> <種子> <輸出雜湊>                       灰階／彩度／灰 ARGB
// 雜湊的定義見各段（與 Kotlin 端 Cpp*ParityTest 寫 golden 的地方一致）。
// 用法：nr_golden_check [-j 執行緒數] [--min-cases N] [--partial] <golden 目錄或 .txt>...
//   每行獨立，可平行（qemu-user 的多執行緒會用到多顆主機核心）。
//   完整性（不加 --partial 時）：給目錄就要 6 個檔都在；每個純量函式（fnCount() 全部）都要出現，分塊從 0 起連續、不重疊，
//   合計至少 --min-cases 組（預設 1,000,000＝驗收的「數百萬組」下限；本機用 -PnativeCases 縮小時跟著改）；
//   陣列 sum／sumc／argsort 都要有、從 0 起連續；6 張整表都要有；像素至少一行。缺任何一項就失敗——
//   免得 JUnit 某個類別沒跑、golden 少了一份，qemu／ASan 這一步卻照樣「0 mismatches」通過。
#include <dirent.h>
#include <pthread.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "../core/convert.h"
#include "../core/jmath.h"
#include "../core/tables.h"
#include "cases.h"

using namespace nrt;
using nr::jm::dbits;
using nr::jm::fbits;

namespace {

std::atomic<int> g_lines{0}, g_fail{0};
std::atomic<int64_t> g_cases{0};
std::vector<std::string> g_files;
std::vector<std::string> g_work;          // 所有檔案的有效行（依序）
std::atomic<size_t> g_next{0};
std::mutex g_printLock;

uint64_t hi(int64_t v) { return static_cast<uint64_t>(v); }

void fail(const char* what, const std::string& line) {
    if (++g_fail <= 20) {
        std::lock_guard<std::mutex> lk(g_printLock);
        std::printf("MISMATCH %s: %s\n", what, line.c_str());
    }
}

void checkScalar(const std::string& line, const char* name, int64_t start, int64_t n, uint64_t inH, uint64_t outH) {
    const int fn = fnIndex(name);
    if (fn < 0) { fail("unknown function", line); return; }
    const FnSpec& f = fnSpec(fn);
    std::vector<uint64_t> a(static_cast<size_t>(n) * f.nIn), o(static_cast<size_t>(n) * (f.nOut + 1));
    genScalar(fn, start, n, a.data());
    uint64_t h = FNV_INIT;
    for (uint64_t v : a) h = fnv(h, v);
    if (h != inH) { fail("input hash (generator differs)", line); return; }
    evalScalar(fn, a.data(), n, o.data());
    h = FNV_INIT;
    for (int64_t i = 0; i < n; i++) {
        const uint64_t* r = o.data() + i * (f.nOut + 1);
        for (int k = 0; k < f.nOut; k++) h = fnv(h, canon(f.out, r[k]));
        h = fnv(h, r[f.nOut]);
    }
    if (h != outH) fail("output", line);
    g_cases += n;
}

void checkArrays(const std::string& line, const char* kind, int64_t idx0, int64_t count, uint64_t inH, uint64_t outH) {
    uint64_t hin = FNV_INIT, hout = FNV_INIT;
    const bool argsort = std::strcmp(kind, "argsort") == 0;
    const bool chunked = std::strcmp(kind, "sumc") == 0;
    if (!argsort && !chunked && std::strcmp(kind, "sum") != 0) { fail("unknown array kind", line); return; }
    for (int64_t i = idx0; i < idx0 + count; i++) {
        if (argsort) {
            const std::vector<int32_t> v = genArgsortArray(i);
            hin = fnv(hin, v.size());
            for (int32_t x : v) hin = fnv(hin, hi(x));
            std::vector<int32_t> ts(v.size());
            nr::jm::npArgsort(v.data(), static_cast<int32_t>(v.size()), ts.data());
            hout = fnv(hout, ts.size());
            for (int32_t x : ts) hout = fnv(hout, hi(x));
        } else {
            const std::vector<double> v = genSumArray(chunked, i);
            hin = fnv(hin, v.size());
            for (double x : v) hin = fnv(hin, dbits(x));
            const double s = chunked ? nr::jm::npSumChunked(v.data(), static_cast<int64_t>(v.size()))
                                     : nr::jm::npSum(v.data(), static_cast<int64_t>(v.size()));
            hout = fnv(hout, canon(OD, dbits(s)));
        }
    }
    if (hin != inH) { fail("input hash (generator differs)", line); return; }
    if (hout != outH) fail("output", line);
    g_cases += count;
}

uint64_t tableHash(const char* kind, bool* ok) {
    *ok = true;
    uint64_t h = FNV_INIT;
    if (!std::strcmp(kind, "gaussW")) {
        for (int i = 30; i <= 1000; i++) {
            const std::vector<int32_t> w = nr::tables::gaussW(i / 100.0);
            h = fnv(h, w.size());
            for (int32_t v : w) h = fnv(h, hi(v));
        }
    } else if (!std::strcmp(kind, "gk")) {
        for (int i = 30; i <= 1000; i++) {
            const double s = i / 100.0;
            const int32_t ks = nr::tables::gaussianBlurKsize(s);
            h = fnv(h, hi(ks));
            for (double v : nr::tables::gaussianKernel(ks, s)) h = fnv(h, canon(OD, dbits(v)));
        }
    } else if (!std::strcmp(kind, "ringk")) {
        for (int i = 30; i <= 1000; i++) {
            const std::vector<double> k = nr::tables::ringGaussKernel(i / 100.0);
            h = fnv(h, k.size());
            for (double v : k) h = fnv(h, canon(OD, dbits(v)));
        }
    } else if (!std::strcmp(kind, "text")) {
        float lut[256];
        for (int bg = 0; bg < 256; bg++) for (int ink = 0; ink < 256; ink++) {
            nr::tables::textLut(bg, ink, lut);
            for (float v : lut) h = fnv(h, fbits(v));
        }
    } else if (!std::strcmp(kind, "ink")) {
        float lut[256];
        for (int inv = 0; inv < 2; inv++) for (int bg = 0; bg < 256; bg++) for (int top = 0; top < 256; top++) {
            nr::tables::inkLut(bg, top, inv != 0, lut);
            for (float v : lut) h = fnv(h, fbits(v));
        }
    } else if (!std::strcmp(kind, "lineoff")) {
        std::vector<std::vector<int32_t>> out;
        for (int len = 0; len <= 100; len++) {
            if (!nr::tables::lineOffsets(len, out)) { h = fnv(h, ~uint64_t(0)); continue; }
            for (const auto& d : out) {
                h = fnv(h, d.size());
                for (int32_t v : d) h = fnv(h, hi(v));
            }
        }
    } else {
        *ok = false;
    }
    return h;
}

void checkPixels(const std::string& line, int64_t n, uint64_t seed, uint64_t outH) {
    const std::vector<int32_t> px = genPixels(n, seed);
    std::vector<int32_t> g(px.size()), c(px.size()), a(px.size());
    nr::argbToGrayChroma(px.data(), n, g.data(), c.data());
    nr::grayToArgb(g.data(), n, a.data());
    uint64_t h = FNV_INIT;
    for (int64_t i = 0; i < n; i++) { h = fnv(h, hi(g[i])); h = fnv(h, hi(c[i])); }
    for (int64_t i = 0; i < n; i++) h = fnv(h, hi(a[i]));
    if (h != outH) fail("pixels", line);
    g_cases += n;
}

/** golden 目錄裡一定要有的 6 個檔（CppJmathParityTest 2 個、CppTablesParityTest 3 個、CppPageParityTest 1 個）。 */
const char* const kGoldenFiles[] = {"jmath_scalar.txt", "jmath_arrays.txt", "tables_gauss.txt",
                                    "tables_lut.txt", "tables_lineoff.txt", "page_pixels.txt"};
const char* const kTables[] = {"gaussW", "gk", "ringk", "text", "ink", "lineoff"};
const char* const kArrays[] = {"sum", "sumc", "argsort"};

int completenessFail(const std::string& what) {
    std::printf("INCOMPLETE %s\n", what.c_str());
    return 1;
}

/** 分塊 [start, start+n) 從 0 起連續、不重疊；回傳合計（不連續回 −1）。 */
int64_t contiguous(std::vector<std::pair<int64_t, int64_t>> chunks) {
    std::sort(chunks.begin(), chunks.end());
    int64_t next = 0;
    for (const auto& c : chunks) {
        if (c.first != next || c.second <= 0) return -1;
        next += c.second;
    }
    return next;
}

/** 完整性檢查（見檔頭）；回傳失敗項數。 */
int checkCompleteness(int64_t minCases) {
    int bad = 0;
    std::map<std::string, std::vector<std::pair<int64_t, int64_t>>> scalar, arrays;
    std::map<std::string, int> tables;
    int pixels = 0;
    for (const auto& line : g_work) {
        char tag[8], name[64];
        long long a1 = 0, a2 = 0;
        if ((line[0] == 'S' || line[0] == 'A') && std::sscanf(line.c_str(), "%7s %63s %lld %lld", tag, name, &a1, &a2) == 4) {
            (line[0] == 'S' ? scalar : arrays)[name].emplace_back(a1, a2);
        } else if (line[0] == 'T' && std::sscanf(line.c_str(), "%7s %63s", tag, name) == 2) {
            tables[name]++;
        } else if (line[0] == 'P') {
            pixels++;
        }
    }
    for (int fn = 0; fn < fnCount(); fn++) {
        const char* name = fnSpec(fn).name;
        const auto it = scalar.find(name);
        if (it == scalar.end()) { bad += completenessFail(std::string("純量函式 ") + name + " 沒有任何一行"); continue; }
        const int64_t total = contiguous(it->second);
        if (total < 0) bad += completenessFail(std::string("純量函式 ") + name + " 的分塊不是從 0 起連續");
        else if (total < minCases)
            bad += completenessFail(std::string("純量函式 ") + name + " 只有 " + std::to_string(total) + " 組（下限 " +
                                    std::to_string(minCases) + "，見 --min-cases）");
        scalar.erase(it);
    }
    for (const auto& kv : scalar) bad += completenessFail("不認得的純量函式 " + kv.first);
    for (const char* k : kArrays) {
        const auto it = arrays.find(k);
        if (it == arrays.end()) { bad += completenessFail(std::string("陣列 ") + k + " 沒有任何一行"); continue; }
        if (contiguous(it->second) <= 0) bad += completenessFail(std::string("陣列 ") + k + " 的分塊不是從 0 起連續");
    }
    for (const char* t : kTables) {
        if (tables[t] != 1) bad += completenessFail(std::string("整表 ") + t + " 要剛好一行，有 " + std::to_string(tables[t]) + " 行");
    }
    if (pixels == 0) bad += completenessFail("像素（P）沒有任何一行");
    return bad;
}

void readFile(const std::string& path) {
    FILE* f = std::fopen(path.c_str(), "r");
    if (f == nullptr) { std::printf("cannot open %s\n", path.c_str()); g_fail++; return; }
    char buf[512];
    while (std::fgets(buf, sizeof buf, f)) {
        std::string line(buf);
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
        if (line.empty() || line[0] == '#') continue;
        g_work.push_back(line);
    }
    std::fclose(f);
}

void checkLine(const std::string& line) {
    g_lines++;
    char tag[8], name[64];
    long long a1 = 0, a2 = 0;
    unsigned long long h1 = 0, h2 = 0;
    if (line[0] == 'S' && std::sscanf(line.c_str(), "%7s %63s %lld %lld %llx %llx", tag, name, &a1, &a2, &h1, &h2) == 6) {
        checkScalar(line, name, a1, a2, h1, h2);
    } else if (line[0] == 'A' && std::sscanf(line.c_str(), "%7s %63s %lld %lld %llx %llx", tag, name, &a1, &a2, &h1, &h2) == 6) {
        checkArrays(line, name, a1, a2, h1, h2);
    } else if (line[0] == 'T' && std::sscanf(line.c_str(), "%7s %63s %llx", tag, name, &h1) == 3) {
        bool ok;
        const uint64_t h = tableHash(name, &ok);
        if (!ok) fail("unknown table", line);
        else if (h != h1) fail("table", line);
    } else if (line[0] == 'P' && std::sscanf(line.c_str(), "%7s %lld %lld %llx", tag, &a1, &a2, &h1) == 4) {
        checkPixels(line, a1, static_cast<uint64_t>(a2), h1);
    } else {
        fail("unparsable line", line);
    }
}

/** 工作執行緒：從共用計數器領下一行（每行彼此獨立）。 */
void* worker(void*) {
    for (;;) {
        const size_t i = g_next.fetch_add(1);
        if (i >= g_work.size()) return nullptr;
        checkLine(g_work[i]);
    }
}

}  // namespace

int main(int argc, char** argv) {
    int jobs = 1;
    int64_t minCases = 1000000;
    bool partial = false;
    int missing = 0;
    for (int i = 1; i < argc; i++) {
        if (std::strcmp(argv[i], "-j") == 0 && i + 1 < argc) { jobs = std::atoi(argv[++i]); continue; }
        if (std::strcmp(argv[i], "--min-cases") == 0 && i + 1 < argc) { minCases = std::atoll(argv[++i]); continue; }
        if (std::strcmp(argv[i], "--partial") == 0) { partial = true; continue; }
        DIR* d = opendir(argv[i]);
        if (d == nullptr) { g_files.emplace_back(argv[i]); continue; }
        if (!partial) {
            for (const char* want : kGoldenFiles) {
                const std::string p = std::string(argv[i]) + "/" + want;
                if (FILE* f = std::fopen(p.c_str(), "r")) std::fclose(f);
                else missing += completenessFail("缺 golden 檔 " + p);
            }
        }
        std::vector<std::string> names;
        while (dirent* e = readdir(d)) {
            const std::string n = e->d_name;
            if (n.size() > 4 && n.compare(n.size() - 4, 4, ".txt") == 0) names.push_back(std::string(argv[i]) + "/" + n);
        }
        closedir(d);
        for (size_t a = 0; a < names.size(); a++)
            for (size_t b = a + 1; b < names.size(); b++)
                if (names[b] < names[a]) std::swap(names[a], names[b]);
        for (auto& n : names) g_files.push_back(n);
    }
    if (g_files.empty()) {
        std::printf("usage: nr_golden_check [-j 執行緒數] [--min-cases N] [--partial] <golden 目錄或 .txt>...\n");
        return 2;
    }
    for (const auto& p : g_files) readFile(p);
    const int incomplete = partial ? 0 : missing + checkCompleteness(minCases);
    if (jobs < 1) jobs = 1;
    // 每條工作執行緒都用 256 KB 堆疊（同產品規則）
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 256 * 1024);
    std::vector<pthread_t> th(static_cast<size_t>(jobs));
    for (auto& t : th) if (pthread_create(&t, &attr, worker, nullptr) != 0) return 2;
    for (auto& t : th) pthread_join(t, nullptr);
    pthread_attr_destroy(&attr);
    std::printf("nr_golden_check: %zu files, %d lines, %lld cases, %d mismatches, %d incomplete%s\n", g_files.size(),
                g_lines.load(), static_cast<long long>(g_cases.load()), g_fail.load(), incomplete,
                partial ? " (--partial: completeness not checked)" : "");
    return (g_fail == 0 && g_lines > 0 && incomplete == 0) ? 0 : 1;
}
