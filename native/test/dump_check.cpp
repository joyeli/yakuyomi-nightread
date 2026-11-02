// nr_dump_check：檢查 Kotlin 錄出器（NativeDumpTest）寫的錄出，並重放 C++ 已經搬好的部分。
//
// 用法：nr_dump_check [--expect-families 族,族…] [--expect-ops 運算,運算…] [--expect-pages N] [--expect-prim-pages N] <錄出目錄>
//   一律失敗的情況：一項都沒驗到（0 項）、index 列的某一族 0 筆。
//   --expect-families：這幾族一定要在 prims/index.tsv 裡、而且 C++ 真的重放了至少一筆。
//   --expect-ops：這幾個運算（紀錄的 op）C++ 真的重放了至少一筆（一族裡只搬了幾個運算時，只看族會漏）。
//   --expect-pages：至少 N 頁真的有東西重算（輸入、參數或原始資料；只有 manifest 的頁不算）。
//   --expect-prim-pages：至少 N 頁真的跑完原語步驟表（primpages/）。
//   1. prims/index.tsv 列的每一族：.nrd 逐筆解得開、筆數與 index 相同、每筆有 op／case 與輸出或 throws。
//      C++ 已有的運算直接重放、逐位元比：packbits（nr::packBits）、kernel（nr::Kernel 的錨點與 run 表）、morph 的 dilatePacked、dilateBits、
//      cc 的 ccStats／ccRuns、dist 的 distanceL2、gray 的 gaussQ16、morphGrayGather。Kotlin 丟例外的案例 C++ 也要丟。
//      其他運算要等各階段搬完再加重放；現在只驗格式。
//   2. pages/index.tsv 列的每一頁：manifest 的格式、規則版本、參數 schema 與這份 C++ 相同；
//      有 input.bin（NRIN）就重算輸入的成分摘要對 manifest；有 params.nrd 就逐筆 fromFlat（要收）→ toFlat（要原樣）、
//      攤平的摘要對 manifest；有 raw.nrd 就重算每筆的成分摘要對 manifest（遮罩、灰階、浮點影像、陣列、Int 集合）。
//   3. primpages/index.tsv 列的每一頁：照 steps.nrd 重算每一步（準備步驟與原語，真頁尺寸），輸出摘要逐步比。
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
#include "../core/prims.h"
#include "../core/types.h"
#include "nightread/nr.h"
#include "../core/jmath.h"
#include "nrd.h"
#include "sha256.h"

namespace {

int g_fail = 0;
long g_checked = 0;
std::map<std::string, long> g_replayed;   // 族 → C++ 重放筆數（index 裡有的族都會有一筆，可能是 0）
std::map<std::string, long> g_replayedOps;   // 運算 → C++ 重放筆數
long g_pagesChecked = 0;                  // 真的重算了東西的頁數
long g_primPages = 0;                     // 跑完原語步驟表的頁數

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

nr::ImgI32 grayOf(const nrd::Entry& e) {
    nr::ImgI32 g(e.dims.at(1), e.dims.at(0));
    if (g.data.size() > 0) std::memcpy(g.data.data(), e.data.data(), g.data.size() * 4);
    return g;
}

nr::Kernel kernelOf(const nrd::Entry& kd) { return nr::Kernel(kd.dims.at(1), kd.dims.at(0), kd.data.data()); }

bool sameI32Img(const nrd::Entry* e, const nr::ImgI32& g) {
    return e != nullptr && e->dims.size() == 2 && e->dims[0] == g.h && e->dims[1] == g.w && sameInts(e, g.data.data(), g.data.size());
}

bool sameMask(const nrd::Entry* e, const nr::Mask& m) {
    return e != nullptr && e->type == nrd::U8 && e->dims.size() == 2 && e->dims[0] == m.h && e->dims[1] == m.w &&
           e->data.size() == m.data.size() && (m.data.size() == 0 || std::memcmp(e->data.data(), m.data.data(), m.data.size()) == 0);
}

/** 這個運算 C++ 已經搬了（紀錄有 throws 時也要重放：Kotlin 丟例外 C++ 也要丟）。 */
bool portedOp(const std::string& fam, const std::string& op) {
    if (fam == "packbits" || fam == "kernel") return true;
    if (fam == "morph") return op == "dilatePacked" || op == "dilateBits";
    if (fam == "cc") return op == "ccStats" || op == "ccRuns";
    if (fam == "dist") return op == "distanceL2";
    if (fam == "gray") return op == "gaussQ16" || op == "morphGrayGather.max" || op == "morphGrayGather.min";
    return false;
}

void replayOp(const std::string& fam, const std::string& op, const nrd::Record& r, const std::string& where) {
    if (fam == "packbits") {
        const nr::Mask m = maskOf(*r.find("in"));
        const nr::PackedBits p = nr::packBits(m);
        const nrd::Entry* out = r.find("out");
        if (out == nullptr || out->count() != p.words.size() ||
            (p.words.size() > 0 && std::memcmp(out->data.data(), p.words.data(), p.words.size() * 8) != 0)) {
            failf(where + " packBits");
        }
        const nr::Mask back = nr::unpackBits(p);
        if (m.data.size() > 0 && std::memcmp(back.data.data(), m.data.data(), m.data.size()) != 0) failf(where + " unpackBits");
    } else if (fam == "kernel") {
        const nrd::Entry* kd = r.find("kernel");
        const nr::Kernel k(kd->dims.at(1), kd->dims.at(0), kd->data.data());
        const nrd::Entry* ax = r.find("ax");
        const nrd::Entry* ay = r.find("ay");
        if (ax == nullptr || ay == nullptr || ax->i32At(0) != k.ax || ay->i32At(0) != k.ay ||
            !sameInts(r.find("runStart"), k.runStart.data(), k.runStart.size()) ||
            !sameInts(r.find("runEnd"), k.runEnd.data(), k.runEnd.size())) {
            failf(where + " Kernel");
        }
    } else if (op == "dilatePacked") {
        const nr::Mask out = nr::prims::dilatePacked(maskOf(*r.find("in")), kernelOf(*r.find("kernel")));
        if (r.find("throws") == nullptr && !sameMask(r.find("out"), out)) failf(where);
    } else if (op == "dilateBits") {
        const nrd::Entry* size = r.find("size");
        nr::PackedBits in(size->i32At(0), size->i32At(1));
        const nrd::Entry* src = r.find("in");
        if (src->count() != in.words.size()) { failf(where + "：in 長度"); return; }
        if (in.words.size() > 0) std::memcpy(in.words.data(), src->data.data(), in.words.size() * 8);
        const nr::PackedBits out = nr::prims::dilateBits(in, kernelOf(*r.find("kernel")));
        if (r.find("throws") != nullptr) return;
        const nrd::Entry* e = r.find("out");
        if (e == nullptr || e->count() != out.words.size() ||
            (out.words.size() > 0 && std::memcmp(e->data.data(), out.words.data(), out.words.size() * 8) != 0)) {
            failf(where);
        }
    } else if (op == "ccStats") {
        const nr::CC c = nr::prims::ccStats(maskOf(*r.find("in")), r.find("connectivity")->i32At(0));
        if (r.find("throws") != nullptr) return;
        const nrd::Entry* n = r.find("n");
        const nrd::Entry* l = r.find("labels");
        if (n == nullptr || n->i32At(0) != c.n || l == nullptr || !sameInts(l, c.labels.data(), c.labels.size()) ||
            !sameInts(r.find("left"), c.left.data(), c.left.size()) || !sameInts(r.find("top"), c.top.data(), c.top.size()) ||
            !sameInts(r.find("width"), c.width.data(), c.width.size()) || !sameInts(r.find("height"), c.height.data(), c.height.size()) ||
            !sameInts(r.find("area"), c.area.data(), c.area.size())) {
            failf(where);
        }
    } else if (op == "ccRuns") {
        const nr::CcRuns c = nr::prims::ccRuns(maskOf(*r.find("in")), r.find("connectivity")->i32At(0));
        if (r.find("throws") != nullptr) return;
        const nrd::Entry* n = r.find("n");
        const nrd::Entry* fg = r.find("fg");
        const nrd::Entry* bg = r.find("bg");
        if (n == nullptr || n->i32At(0) != c.n || fg == nullptr || fg->i64At(0) != c.fg || bg == nullptr || bg->count() != 4 ||
            bg->i32At(0) != c.bgLeft || bg->i32At(1) != c.bgTop || bg->i32At(2) != c.bgRight || bg->i32At(3) != c.bgBottom ||
            !sameInts(r.find("rs"), c.rs.data(), c.rs.size()) || !sameInts(r.find("re"), c.re.data(), c.re.size()) ||
            !sameInts(r.find("rowFirst"), c.rowFirst.data(), c.rowFirst.size()) || !sameInts(r.find("lab"), c.lab.data(), c.lab.size())) {
            failf(where);
        }
    } else if (op == "distanceL2") {
        const nr::ImgF32 f = nr::prims::distanceL2(maskOf(*r.find("in")));
        if (r.find("throws") != nullptr) return;
        const nrd::Entry* e = r.find("out");
        if (e == nullptr || e->type != nrd::F32 || e->dims.size() != 2 || e->dims[0] != f.h || e->dims[1] != f.w ||
            e->count() != f.data.size() || (f.data.size() > 0 && std::memcmp(e->data.data(), f.data.data(), f.data.size() * 4) != 0)) {
            failf(where);
        }
    } else if (op == "gaussQ16") {
        const nr::ImgI32 g = nr::prims::gaussQ16(grayOf(*r.find("in")), static_cast<double>(nr::jm::dfrom(static_cast<uint64_t>(r.find("sigma")->i64At(0)))));
        if (r.find("throws") == nullptr && !sameI32Img(r.find("out"), g)) failf(where);
    } else {   // morphGrayGather.max／.min
        const nr::ImgI32 g = nr::prims::morphGrayGather(grayOf(*r.find("in")), kernelOf(*r.find("kernel")), op == "morphGrayGather.max");
        if (r.find("throws") == nullptr && !sameI32Img(r.find("out"), g)) failf(where);
    }
}

/** 已搬到 C++ 的運算：回傳是否重放了（沒搬的只驗格式）。Kotlin 丟例外的案例，C++ 也必須丟。 */
bool replay(const std::string& fam, const nrd::Record& r, const std::string& where) {
    const std::string op = r.str("op");
    if (!portedOp(fam, op)) return false;
    const bool wantThrow = r.find("throws") != nullptr;
    bool threw = false;
    try {
        replayOp(fam, op, r, where);
    } catch (const std::exception& e) {
        threw = true;
        if (!wantThrow) failf(where + "：C++ 丟了 " + e.what());
    }
    if (wantThrow && !threw) failf(where + "：Kotlin 丟了 " + r.str("throws") + "，C++ 沒丟");
    g_replayedOps[op]++;
    return true;
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

// ── 真頁的原語步驟表（NightPrimWorkload；NativeDumpTest.dumpPrimPages）─────────────────

std::string hashImgF(const nr::ImgF32& f) {
    nrt::Sha256 s;
    s.byte('F'); s.i32(f.w); s.i32(f.h);
    s.update(reinterpret_cast<const uint8_t*>(f.data.data()), f.data.size() * 4);
    return s.hex16();
}

std::string hashImgI(const nr::ImgI32& g) {
    nrt::Sha256 s;
    s.byte('G'); s.i32(g.w); s.i32(g.h);
    s.update(reinterpret_cast<const uint8_t*>(g.data.data()), g.data.size() * 4);
    return s.hex16();
}

void feedInts(nrt::Sha256& s, const int32_t* v, size_t n) {
    s.byte('I'); s.i32(static_cast<int32_t>(n));
    if (n > 0) s.update(reinterpret_cast<const uint8_t*>(v), n * 4);
}

std::string hashCc(const nr::CC& c) {
    nrt::Sha256 s;
    s.byte('L'); s.i32(7);
    const int32_t n = c.n;
    feedInts(s, &n, 1);
    feedInts(s, c.labels.data(), c.labels.size());
    feedInts(s, c.left.data(), c.left.size());
    feedInts(s, c.top.data(), c.top.size());
    feedInts(s, c.width.data(), c.width.size());
    feedInts(s, c.height.data(), c.height.size());
    feedInts(s, c.area.data(), c.area.size());
    return s.hex16();
}

std::string hashRuns(const nr::CcRuns& c) {
    nrt::Sha256 s;
    s.byte('L'); s.i32(6);
    const int32_t meta[5] = {c.n, c.bgLeft, c.bgTop, c.bgRight, c.bgBottom};
    feedInts(s, meta, 5);
    s.byte('J'); s.i32(1); s.i64(c.fg);
    feedInts(s, c.rs.data(), c.rs.size());
    feedInts(s, c.re.data(), c.re.size());
    feedInts(s, c.rowFirst.data(), c.rowFirst.size());
    feedInts(s, c.lab.data(), c.lab.size());
    return s.hex16();
}

void checkPrimPage(const std::string& dir, const std::string& page) {
    const std::string pdir = dir + "/primpages/" + page;
    std::ifstream in(pdir + "/input.bin", std::ios::binary);
    std::vector<uint8_t> b((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    int32_t w = 0, h = 0, nrg = 0;
    if (b.size() < 16 || std::memcmp(b.data(), "NRIN", 4) != 0) { failf(page + "：input.bin 不是 NRIN"); return; }
    std::memcpy(&w, b.data() + 4, 4);
    std::memcpy(&h, b.data() + 8, 4);
    std::memcpy(&nrg, b.data() + 12, 4);
    const size_t n = static_cast<size_t>(w) * static_cast<size_t>(h);
    if (b.size() != 16 + 4 * n + 16 * static_cast<size_t>(nrg)) { failf(page + "：input.bin 長度不合"); return; }

    std::map<std::string, nr::Mask> masks;
    std::map<std::string, nr::ImgI32> grays;
    {
        nr::ImgI32 g(w, h);
        for (size_t i = 0; i < n; i++) g.data[i] = b[16 + i];
        grays.emplace("gray", std::move(g));
        nr::Mask seg(w, h), chr(w, h);
        std::memcpy(seg.data.data(), b.data() + 16 + 2 * n, n);
        std::memcpy(chr.data.data(), b.data() + 16 + 3 * n, n);
        masks.emplace("seg", std::move(seg));
        masks.emplace("char", std::move(chr));
    }
    nrd::Reader rd;
    if (!rd.open(pdir + "/steps.nrd")) { failf(rd.error); return; }
    nrd::Record r;
    long steps = 0, prims = 0;
    while (rd.ok() && !rd.atEnd()) {
        if (!rd.next(r)) { failf(page + " steps.nrd: " + rd.error); break; }
        const std::string id = r.str("id"), op = r.str("op"), inp = r.str("in"), outName = r.str("out"), want = r.str("hash");
        const nrd::Entry* args = r.find("args");
        const std::string where = page + " " + id + " (" + op + ")";
        std::string got;
        try {
            if (op == "ge" || op == "lt") {
                const nr::ImgI32& g = grays.at(inp);
                nr::Mask m(g.w, g.h);
                const int32_t th = args->i32At(0);
                for (size_t i = 0; i < m.data.size(); i++) m.data[i] = (op == "ge" ? g.data[i] >= th : g.data[i] < th) ? 1 : 0;
                got = hashMask(m.w, m.h, m.data.data());
                masks.emplace(outName, std::move(m));
            } else if (op == "not") {
                const nr::Mask& s = masks.at(inp);
                nr::Mask m(s.w, s.h);
                for (size_t i = 0; i < m.data.size(); i++) m.data[i] = s.data[i] ? 0 : 1;
                got = hashMask(m.w, m.h, m.data.data());
                masks.emplace(outName, std::move(m));
            } else if (op == "crop") {
                const nr::Mask& s = masks.at(inp);
                const int32_t cx = args->i32At(0), cy = args->i32At(1), cw = args->i32At(2), ch = args->i32At(3);
                nr::Mask m(cw, ch);
                for (int32_t j = 0; j < ch; j++) std::memcpy(m.data.data() + static_cast<size_t>(j) * cw, s.data.data() + static_cast<size_t>(cy + j) * s.w + cx, static_cast<size_t>(cw));
                got = hashMask(m.w, m.h, m.data.data());
                masks.emplace(outName, std::move(m));
            } else if (op == "dilatePacked") {
                const nr::Mask out = nr::prims::dilatePacked(masks.at(inp), kernelOf(*r.find("kernel")));
                got = hashMask(out.w, out.h, out.data.data());
                prims++;
            } else if (op == "ccStats") {
                got = hashCc(nr::prims::ccStats(masks.at(inp), args->i32At(0)));
                prims++;
            } else if (op == "ccRuns") {
                got = hashRuns(nr::prims::ccRuns(masks.at(inp), args->i32At(0)));
                prims++;
            } else if (op == "distanceL2") {
                got = hashImgF(nr::prims::distanceL2(masks.at(inp)));
                prims++;
            } else if (op == "gaussQ16") {
                nr::ImgI32 g = nr::prims::gaussQ16(grays.at(inp), nr::jm::dfrom(static_cast<uint64_t>(r.find("sigma")->i64At(0))));
                got = hashImgI(g);
                if (!outName.empty()) grays.emplace(outName, std::move(g));
                prims++;
            } else if (op == "morphMax" || op == "morphMin") {
                nr::ImgI32 g = nr::prims::morphGrayGather(grays.at(inp), kernelOf(*r.find("kernel")), op == "morphMax");
                got = hashImgI(g);
                if (!outName.empty()) grays.emplace(outName, std::move(g));
                prims++;
            } else {
                failf(where + "：不認得的 op");
                continue;
            }
        } catch (const std::exception& e) {
            failf(where + "：" + e.what());
            continue;
        }
        if (got != want) failf(where + "：摘要 " + got + "，Kotlin " + want);
        steps++;
    }
    g_checked += steps;
    if (prims > 0 && g_fail == 0) g_primPages++;
    std::printf("  primpage %-16s %dx%d：%ld 步（原語 %ld）\n", page.c_str(), w, h, steps, prims);
}

}  // namespace

int main(int argc, char** argv) {
    std::vector<std::string> expectFams, expectOps;
    long expectPages = 0, expectPrimPages = 0;
    std::string dir;
    for (int i = 1; i < argc; i++) {
        const std::string a = argv[i];
        if (a == "--expect-families" && i + 1 < argc) {
            std::stringstream ss(argv[++i]);
            std::string f;
            while (std::getline(ss, f, ',')) if (!f.empty()) expectFams.push_back(f);
        } else if (a == "--expect-ops" && i + 1 < argc) {
            std::stringstream ss(argv[++i]);
            std::string f;
            while (std::getline(ss, f, ',')) if (!f.empty()) expectOps.push_back(f);
        } else if (a == "--expect-pages" && i + 1 < argc) {
            expectPages = std::atol(argv[++i]);
        } else if (a == "--expect-prim-pages" && i + 1 < argc) {
            expectPrimPages = std::atol(argv[++i]);
        } else if (dir.empty() && a.rfind("--", 0) != 0) {
            dir = a;
        } else {
            dir.clear();
            break;
        }
    }
    if (dir.empty()) {
        std::fprintf(stderr, "用法：nr_dump_check [--expect-families 族,族…] [--expect-ops 運算,運算…] [--expect-pages N] [--expect-prim-pages N] <錄出目錄>\n");
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
    if (exists(dir + "/primpages/index.tsv")) {
        for (const auto& row : readTsv(dir + "/primpages/index.tsv")) {
            if (row.size() < 4 || row[0] == "page") continue;
            checkPrimPage(dir, row[0]);
        }
    }
    for (const auto& op : expectOps) {
        if (g_replayedOps[op] == 0) failf("預期的運算 " + op + " 一筆都沒有重放");
    }
    if (g_primPages < expectPrimPages)
        failf("跑完原語步驟表的頁只有 " + std::to_string(g_primPages) + " 頁，預期至少 " + std::to_string(expectPrimPages) + " 頁");
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
