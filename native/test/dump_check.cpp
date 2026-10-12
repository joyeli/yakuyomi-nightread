// nr_dump_check：檢查 Kotlin 錄出器（NativeDumpTest）寫的錄出，並重放 C++ 已經搬好的部分。
//
// 用法：nr_dump_check [--expect-families 族,族…] [--expect-pages N] <錄出目錄>
//   一律失敗的情況：一項都沒驗到（0 項）、index 列的某一族 0 筆。
//   --expect-families：這幾族一定要在 prims/index.tsv 裡、而且 C++ 真的重放了至少一筆（CI 傳 packbits,kernel）。
//   --expect-pages：至少 N 頁真的有東西重算（輸入、參數或原始資料；只有 manifest 的頁不算）。
//   1. prims/index.tsv 列的每一族：.nrd 逐筆解得開、筆數與 index 相同、每筆有 op／case 與輸出或 throws。
//      C++ 已有的族直接重放、逐位元比：packbits（nr::packBits）、kernel（nr::Kernel 的錨點與 run 表）。
//      其他族（形態學、連通元件、距離…）要等各階段搬完再加重放；現在只驗格式。
//   2. pages/index.tsv 列的每一頁：manifest 的格式、規則版本、參數 schema 與這份 C++ 相同；
//      有 input.bin（NRIN）就重算輸入的成分摘要對 manifest；有 params.nrd 就逐筆 fromFlat（要收）→ toFlat（要原樣）、
//      攤平的摘要對 manifest；有 raw.nrd 就重算每筆的成分摘要對 manifest（遮罩、灰階、浮點影像、陣列、Int 集合）。
// 成分摘要的編碼見錄出目錄的 README.txt（NativeDump.kt 的 Enc／feed）。不需要 JVM：主機與 qemu-aarch64 上都能跑。
#include <dirent.h>
#include <sys/stat.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "../core/params_gen.h"
#include "../core/types.h"
#include "nightread/nr.h"
#include "nrd.h"
#include "sha256.h"

namespace {

int g_fail = 0;
long g_checked = 0;
std::map<std::string, long> g_replayed;   // 族 → C++ 重放筆數（index 裡有的族都會有一筆，可能是 0）
long g_pagesChecked = 0;                  // 真的重算了東西的頁數

void failf(const std::string& what) {
    if (++g_fail <= 30) std::printf("FAIL %s\n", what.c_str());
}

bool exists(const std::string& p) {
    struct stat st;
    return stat(p.c_str(), &st) == 0;
}

std::vector<std::vector<std::string>> readTsv(const std::string& path) {
    std::vector<std::vector<std::string>> rows;
    std::ifstream in(path);
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty()) continue;
        std::vector<std::string> cols;
        std::stringstream ss(line);
        std::string c;
        while (std::getline(ss, c, '\t')) cols.push_back(c);
        rows.push_back(cols);
    }
    return rows;
}

// ── 成分摘要（NativeDump.Enc）──────────────────────────────────────────────

std::string hashMask(int32_t w, int32_t h, const uint8_t* d) {
    nrt::Sha256 s;
    s.byte('M'); s.i32(w); s.i32(h);
    s.update(d, static_cast<size_t>(w) * static_cast<size_t>(h));
    return s.hex16();
}

std::string hashI32Img(char tag, int32_t w, int32_t h, const std::vector<int32_t>& v) {
    nrt::Sha256 s;
    s.byte(static_cast<uint8_t>(tag)); s.i32(w); s.i32(h);
    for (int32_t x : v) s.i32(x);
    return s.hex16();
}

std::string hashArray(char tag, const nrd::Entry& e) {
    nrt::Sha256 s;
    s.byte(static_cast<uint8_t>(tag));
    s.i32(static_cast<int32_t>(e.count()));
    s.update(e.data.data(), e.data.size());   // 小端原樣（i32／i64／f32／f64 的原始位元）
    return s.hex16();
}

/** raw.nrd 的一筆 → 成分摘要（不支援的 kind 回空字串）。 */
std::string hashRaw(const nrd::Record& r) {
    const std::string kind = r.str("kind");
    const nrd::Entry* d = r.find("data");
    if (d == nullptr) return "";
    const int32_t h = d->dims.size() == 2 ? d->dims[0] : 0, w = d->dims.size() == 2 ? d->dims[1] : 0;
    if (kind == "mask") return hashMask(w, h, d->data.data());
    if (kind == "gray") return hashI32Img('G', w, h, d->vec<int32_t>());
    if (kind == "fimg") return hashI32Img('F', w, h, d->vec<int32_t>());   // float 原始位元當 i32
    if (kind == "ints") return hashArray('I', *d);
    if (kind == "longs") return hashArray('J', *d);
    if (kind == "floats") return hashArray('f', *d);
    if (kind == "doubles") return hashArray('D', *d);
    if (kind == "bools") return hashArray('Z', *d);
    if (kind == "set") return hashArray('S', *d);                         // 已排序
    nrt::Sha256 s;
    if (kind == "int") { s.byte('i'); s.i32(d->i32At(0)); return s.hex16(); }
    if (kind == "bool") { s.byte('b'); s.byte(static_cast<uint8_t>(d->i32At(0))); return s.hex16(); }
    if (kind == "double") { s.byte('d'); s.i64(d->i64At(0)); return s.hex16(); }
    return "";
}

// ── 原語 ───────────────────────────────────────────────────────────────

nr::Mask maskOf(const nrd::Entry& e) {
    nr::Mask m(e.dims.at(1), e.dims.at(0));
    std::memcpy(m.data.data(), e.data.data(), m.data.size());
    return m;
}

bool sameInts(const nrd::Entry* e, const int32_t* v, size_t n) {
    return e != nullptr && e->type == nrd::I32 && e->count() == n && (n == 0 || std::memcmp(e->data.data(), v, n * 4) == 0);
}

/** 已搬到 C++ 的族：回傳是否重放了（族名不認得＝只驗格式）。 */
bool replay(const std::string& fam, const nrd::Record& r, const std::string& where) {
    if (r.find("throws") != nullptr) return false;
    if (fam == "packbits") {
        const nr::Mask m = maskOf(*r.find("in"));
        const nr::PackedBits p = nr::packBits(m);
        const nrd::Entry* out = r.find("out");
        if (out == nullptr || out->count() != p.words.size() ||
            (p.words.size() > 0 && std::memcmp(out->data.data(), p.words.data(), p.words.size() * 8) != 0)) {
            failf(where + " packBits");
        }
        const nr::Mask back = nr::unpackBits(p);
        if (std::memcmp(back.data.data(), m.data.data(), m.data.size()) != 0) failf(where + " unpackBits");
        return true;
    }
    if (fam == "kernel") {
        const nrd::Entry* kd = r.find("kernel");
        const nr::Kernel k(kd->dims.at(1), kd->dims.at(0), kd->data.data());
        const nrd::Entry* ax = r.find("ax");
        const nrd::Entry* ay = r.find("ay");
        if (ax == nullptr || ay == nullptr || ax->i32At(0) != k.ax || ay->i32At(0) != k.ay ||
            !sameInts(r.find("runStart"), k.runStart.data(), k.runStart.size()) ||
            !sameInts(r.find("runEnd"), k.runEnd.data(), k.runEnd.size())) {
            failf(where + " Kernel");
        }
        return true;
    }
    return false;
}

void checkPrims(const std::string& dir) {
    const std::string idx = dir + "/prims/index.tsv";
    if (!exists(idx)) { std::printf("（沒有 prims/）\n"); return; }
    for (const auto& row : readTsv(idx)) {
        if (row.empty() || row[0] == "family") continue;
        const std::string fam = row[0];
        const long want = std::atol(row.at(1).c_str());
        nrd::Reader rd;
        if (!rd.open(dir + "/prims/" + fam + ".nrd")) { failf(rd.error); continue; }
        long n = 0, replayed = 0, throws = 0;
        nrd::Record r;
        while (!rd.atEnd()) {
            if (!rd.next(r)) { failf(fam + ": " + rd.error); break; }
            const std::string where = fam + " #" + std::to_string(n) + " " + r.str("op") + " (" + r.str("case") + ")";
            if (r.str("op").empty() || r.find("case") == nullptr) failf(where + "：缺 op／case");
            bool hasOut = false;
            for (const auto& e : r.entries) if (e.name.rfind("out", 0) == 0 || e.name == "ax" || e.name == "n") hasOut = true;
            if (r.find("throws") != nullptr) throws++;
            else if (!hasOut) failf(where + "：沒有輸出也沒有 throws");
            if (replay(fam, r, where)) replayed++;
            n++;
        }
        if (n != want) failf(fam + "：筆數 " + std::to_string(n) + "，index 寫 " + std::to_string(want));
        if (n == 0) failf(fam + "：0 筆（錄出器沒錄到東西）");
        g_checked += n;
        g_replayed[fam] = replayed;
        std::printf("  prims %-10s %6ld 筆（丟例外 %ld）%s\n", fam.c_str(), n, throws,
                    replayed > 0 ? ("，C++ 重放 " + std::to_string(replayed) + " 筆").c_str() : "，只驗格式");
    }
}

// ── 頁 ─────────────────────────────────────────────────────────────────

void checkPage(const std::string& dir, const std::string& set, const std::string& page, const std::string& manifest) {
    const std::string pdir = dir + "/pages/" + set + "/" + page;
    std::map<std::string, std::string> m;   // "跑法\t成分" → 摘要
    for (const auto& row : readTsv(dir + "/" + manifest)) {
        if (row.size() != 3) { failf(page + "：manifest 欄數不是 3"); continue; }
        m[row[0] + "\t" + row[1]] = row[2];
    }
    const std::string where = set + "/" + page;
    if (m["page\tformat"] != "nightread-dump/1") failf(where + "：manifest 格式");
    if (m["page\trules_version"] != std::to_string(NR_RULES_VERSION)) failf(where + "：規則版本與這份 C++ 不同");
    char sch[32];
    std::snprintf(sch, sizeof sch, "%016llx", static_cast<unsigned long long>(nr::params::kSchemaHash));
    if (m["page\tparams_schema"] != sch) failf(where + "：參數 schema 與這份 C++ 不同（錄出時的 Params.kt 不是這一版）");
    long checks = 0;

    // 輸入（NRIN）
    if (exists(pdir + "/input.bin")) {
        std::ifstream in(pdir + "/input.bin", std::ios::binary);
        std::vector<uint8_t> b((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        int32_t w = 0, h = 0, nr = 0;
        if (b.size() < 16 || std::memcmp(b.data(), "NRIN", 4) != 0) {
            failf(where + "：input.bin 不是 NRIN");
        } else {
            std::memcpy(&w, b.data() + 4, 4);
            std::memcpy(&h, b.data() + 8, 4);
            std::memcpy(&nr, b.data() + 12, 4);
            const size_t n = static_cast<size_t>(w) * static_cast<size_t>(h);
            if (b.size() != 16 + 4 * n + 16 * static_cast<size_t>(nr)) {
                failf(where + "：input.bin 長度不合");
            } else {
                if (m["page\tsize"] != std::to_string(w) + "x" + std::to_string(h)) failf(where + "：input.bin 尺寸與 manifest 不同");
                std::vector<int32_t> g(n), c(n);
                for (size_t i = 0; i < n; i++) { g[i] = b[16 + i]; c[i] = b[16 + n + i]; }
                if (hashI32Img('G', w, h, g) != m["input\tgray"]) failf(where + "：input.gray 摘要");
                if (hashI32Img('G', w, h, c) != m["input\tchroma"]) failf(where + "：input.chroma 摘要");
                if (hashMask(w, h, b.data() + 16 + 2 * n) != m["input\tseg"]) failf(where + "：input.seg 摘要");
                if (hashMask(w, h, b.data() + 16 + 3 * n) != m["input\tcharMask"]) failf(where + "：input.charMask 摘要");
                checks += 4;
                if (exists(pdir + "/input.inp.bin")) {
                    std::ifstream ii(pdir + "/input.inp.bin", std::ios::binary);
                    std::vector<uint8_t> ib((std::istreambuf_iterator<char>(ii)), std::istreambuf_iterator<char>());
                    if (ib.size() != n || hashMask(w, h, ib.data()) != m["input\tinpaintMask"]) failf(where + "：input.inpaintMask 摘要");
                    checks++;
                }
            }
        }
    }

    // 參數：Kotlin 攤平 → C++ fromFlat（要收）→ toFlat（要原樣）；攤平摘要＝manifest 的 params 列
    if (exists(pdir + "/params.nrd")) {
        nrd::Reader rd;
        if (!rd.open(pdir + "/params.nrd")) failf(rd.error);
        nrd::Record r;
        while (rd.ok() && !rd.atEnd()) {
            if (!rd.next(r)) { failf(where + " params.nrd: " + rd.error); break; }
            const std::string v = r.str("variant");
            const nrd::Entry* ie = r.find("ints");
            const nrd::Entry* de = r.find("doubles");
            const nrd::Entry* se = r.find("schema");
            if (ie == nullptr || de == nullptr || se == nullptr) { failf(where + " params " + v + "：缺欄位"); continue; }
            if (static_cast<uint64_t>(se->i64At(0)) != nr::params::kSchemaHash) failf(where + " params " + v + "：schema 不同");
            const std::vector<int32_t> ints = ie->vec<int32_t>();
            const std::vector<double> dbls = de->vec<double>();
            nr::params::NightReadParams p;
            if (!nr::params::fromFlat(ints.data(), static_cast<int32_t>(ints.size()), dbls.data(), static_cast<int32_t>(dbls.size()), &p)) {
                failf(where + " params " + v + "：C++ fromFlat 拒收");
                continue;
            }
            std::vector<int32_t> i2(nr::params::kIntSlots);
            std::vector<double> d2(nr::params::kDoubleSlots);
            nr::params::toFlat(p, i2.data(), d2.data());
            if (i2 != ints || std::memcmp(d2.data(), dbls.data(), dbls.size() * 8) != 0) failf(where + " params " + v + "：toFlat 不是原樣");
            // manifest：hash(listOf(ints, doubles))＝'L' 2 ＋ 'I' n ints ＋ 'D' n doubles
            nrt::Sha256 s;
            s.byte('L'); s.i32(2);
            s.byte('I'); s.i32(static_cast<int32_t>(ints.size())); for (int32_t x : ints) s.i32(x);
            s.byte('D'); s.i32(static_cast<int32_t>(dbls.size())); s.update(de->data.data(), de->data.size());
            if (s.hex16() != m["params\t" + v]) failf(where + " params " + v + "：攤平摘要與 manifest 不同");
            checks += 2;
        }
    }

    // 原始資料：重算成分摘要
    if (exists(pdir + "/raw.nrd")) {
        nrd::Reader rd;
        if (!rd.open(pdir + "/raw.nrd")) failf(rd.error);
        nrd::Record r;
        while (rd.ok() && !rd.atEnd()) {
            if (!rd.next(r)) { failf(where + " raw.nrd: " + rd.error); break; }
            const std::string name = r.str("name");
            const size_t dot = name.find('.');
            const std::string key = name.substr(0, dot) + "\t" + name.substr(dot + 1);
            const std::string hh = hashRaw(r);
            if (hh.empty()) { failf(where + " raw " + name + "：不支援的 kind " + r.str("kind")); continue; }
            if (m.count(key) == 0) failf(where + " raw " + name + "：manifest 沒有這一列");
            else if (m[key] != hh) failf(where + " raw " + name + "：摘要 " + hh + "，manifest " + m[key]);
            checks++;
        }
    }
    g_checked += checks;
    if (checks > 0) g_pagesChecked++;
    std::printf("  page %-24s manifest %zu 列、C++ 重算 %ld 項\n", where.c_str(), m.size(), checks);
}

}  // namespace

int main(int argc, char** argv) {
    std::vector<std::string> expectFams;
    long expectPages = 0;
    std::string dir;
    for (int i = 1; i < argc; i++) {
        const std::string a = argv[i];
        if (a == "--expect-families" && i + 1 < argc) {
            std::stringstream ss(argv[++i]);
            std::string f;
            while (std::getline(ss, f, ',')) if (!f.empty()) expectFams.push_back(f);
        } else if (a == "--expect-pages" && i + 1 < argc) {
            expectPages = std::atol(argv[++i]);
        } else if (dir.empty() && a.rfind("--", 0) != 0) {
            dir = a;
        } else {
            dir.clear();
            break;
        }
    }
    if (dir.empty()) {
        std::fprintf(stderr, "用法：nr_dump_check [--expect-families 族,族…] [--expect-pages N] <錄出目錄>\n");
        return 2;
    }
    if (!exists(dir + "/README.txt")) { std::printf("FAIL 不是錄出目錄（沒有 README.txt）：%s\n", dir.c_str()); return 1; }
    checkPrims(dir);
    if (exists(dir + "/pages/index.tsv")) {
        for (const auto& row : readTsv(dir + "/pages/index.tsv")) {
            if (row.size() < 5 || row[0] == "set") continue;
            checkPage(dir, row[0], row[1], row[4]);
        }
    }
    for (const auto& f : expectFams) {
        const auto it = g_replayed.find(f);
        if (it == g_replayed.end()) failf("預期的族 " + f + " 不在 prims/index.tsv（錄出器沒錄、或族名打錯）");
        else if (it->second == 0) failf("預期的族 " + f + " 一筆都沒有重放（C++ 還沒有這族的重放，或錄的全是丟例外）");
    }
    if (g_pagesChecked < expectPages)
        failf("真的重算了東西的頁只有 " + std::to_string(g_pagesChecked) + " 頁，預期至少 " + std::to_string(expectPages) + " 頁");
    if (g_checked == 0) failf("一項都沒有驗到（錄出目錄是空的？）");
    std::printf("nr_dump_check：%ld 項（%ld 頁有重算），%d 個不符\n", g_checked, g_pagesChecked, g_fail);
    return g_fail == 0 ? 0 : 1;
}
