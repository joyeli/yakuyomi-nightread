// nr_dump_check：檢查 Kotlin 錄出器（NativeDumpTest）寫的錄出，並重放 C++ 已經搬好的部分。
//
// 用法：nr_dump_check [--expect-families 族,族…] [--expect-ops 運算,運算…] [--expect-pages N] [--expect-prim-pages N]
//                     [--expect-call-sources N] [--expect-call-ops 運算,運算…] <錄出目錄>
//   一律失敗的情況：一項都沒驗到（0 項）、index 列的某一族 0 筆、錄放的某個來源 0 筆。
//   --expect-families：這幾族一定要在 prims/index.tsv 裡、而且 C++ 真的重放了至少一筆。
//   --expect-ops：這幾個運算（prims 紀錄的 op）C++ 真的重放了至少一筆（一族裡只搬了幾個運算時，只看族會漏）。
//   --expect-pages：至少 N 頁真的有東西重算（輸入、參數或原始資料；只有 manifest 的頁不算）。
//   --expect-prim-pages：至少 N 頁真的跑完原語步驟表（primpages/）。
//   --expect-call-sources：錄放（calls/）至少 N 個來源整份重放完。
//   --expect-call-ops：這幾個運算在錄放裡真的重放了至少一次。
//   1. prims/index.tsv 列的每一族：.nrd 逐筆解得開、筆數與 index 相同、每筆有 op／case 與輸出或 throws。
//      C++ 已有的運算（portedOp：第 2 階的 5 族＋第 3a 階的二值部分與位元小工具）直接重放、逐位元比。Kotlin 丟例外的案例 C++ 也要丟。
//      其他運算要等各階段搬完再加重放；現在只驗格式。
//   2. pages/index.tsv 列的每一頁：manifest 的格式、規則版本、參數 schema 與這份 C++ 相同；
//      有 input.bin（NRIN）就重算輸入的成分摘要對 manifest；有 params.nrd 就逐筆 fromFlat（要收）→ toFlat（要原樣）、
//      攤平的摘要對 manifest；有 raw.nrd 就重算每筆的成分摘要對 manifest（遮罩、灰階、浮點影像、陣列、Int 集合）。
//   3. primpages/index.tsv 列的每一頁：照 steps.nrd 重算每一步（準備步驟與原語，真頁尺寸），輸出摘要逐步比。
//   4. calls/index.tsv 列的每個來源（NativeCallDump：Kotlin 跑真頁或隨機測試時 Cv 回報的每一次呼叫）：blob 解壓（zlib）、
//      每一次呼叫用 C++ 重算、輸出摘要對 Kotlin 的。
// 成分摘要的編碼見錄出目錄的 README.txt（NativeDump.kt 的 Enc／feed）。不需要 JVM：主機與 qemu-aarch64 上都能跑。
// 全部工作在一條 256 KB 堆疊的執行緒上跑（同 nr_core_tests：原語不能在堆疊放大陣列）。
#include <dirent.h>
#include <pthread.h>
#include <sys/stat.h>
#include <zlib.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "../core/bits.h"
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
long g_callSources = 0;                   // 整份重放完的錄放來源數
std::map<std::string, long> g_callOps;    // 錄放：運算 → 重放次數

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

bool sameLongs(const nrd::Entry* e, const uint64_t* v, size_t n) {
    return e != nullptr && e->type == nrd::I64 && e->count() == n && (n == 0 || std::memcmp(e->data.data(), v, n * 8) == 0);
}

bool sameF32Img(const nrd::Entry* e, const nr::ImgF32& f) {
    return e != nullptr && e->type == nrd::F32 && e->dims.size() == 2 && e->dims[0] == f.h && e->dims[1] == f.w &&
           e->count() == f.data.size() && (f.data.size() == 0 || std::memcmp(e->data.data(), f.data.data(), f.data.size() * 4) == 0);
}

bool sameDouble(const nrd::Entry* e, double v) {
    return e != nullptr && e->type == nrd::F64 && e->count() == 1 && static_cast<uint64_t>(e->i64At(0)) == nr::jm::dbits(v);
}

/** 浮點影像的輸入（dims [高, 寬]，f32 原始位元）。 */
nr::ImgF32 fimgOf(const nrd::Entry& e) {
    nr::ImgF32 f(e.dims.at(1), e.dims.at(0));
    if (f.data.size() > 0) std::memcpy(f.data.data(), e.data.data(), f.data.size() * 4);
    return f;
}

/** 列打包的輸入：size＝[w, h]，in＝i64 陣列（長度要合）。 */
nr::PackedBits packedOf(const nrd::Record& r) {
    const nrd::Entry* size = r.find("size");
    nr::PackedBits p(size->i32At(0), size->i32At(1));
    const nrd::Entry* in = r.find("in");
    if (in->count() != p.words.size()) throw std::runtime_error("in 的長度與 size 不合");
    if (p.words.size() > 0) std::memcpy(p.words.data(), in->data.data(), p.words.size() * 8);
    return p;
}

bool sameKernelData(const nrd::Entry* e, const nr::Kernel& k) {
    return e != nullptr && e->type == nrd::U8 && e->dims.size() == 2 && e->dims[0] == k.h && e->dims[1] == k.w &&
           e->data.size() == k.data.size() && (k.data.size() == 0 || std::memcmp(e->data.data(), k.data.data(), k.data.size()) == 0);
}

bool sameRuns(const nrd::Record& r, const nr::CcRuns& c) {
    const nrd::Entry* n = r.find("n");
    const nrd::Entry* fg = r.find("fg");
    const nrd::Entry* bg = r.find("bg");
    return n != nullptr && n->i32At(0) == c.n && fg != nullptr && fg->i64At(0) == c.fg && bg != nullptr && bg->count() == 4 &&
           bg->i32At(0) == c.bgLeft && bg->i32At(1) == c.bgTop && bg->i32At(2) == c.bgRight && bg->i32At(3) == c.bgBottom &&
           sameInts(r.find("rs"), c.rs.data(), c.rs.size()) && sameInts(r.find("re"), c.re.data(), c.re.size()) &&
           sameInts(r.find("rowFirst"), c.rowFirst.data(), c.rowFirst.size()) && sameInts(r.find("lab"), c.lab.data(), c.lab.size());
}

/** 這個運算 C++ 已經搬了（紀錄有 throws 時也要重放：Kotlin 丟例外 C++ 也要丟）。 */
bool portedOp(const std::string& fam, const std::string& op) {
    if (fam == "packbits" || fam == "kernel" || fam == "bits") return true;
    if (fam == "morph") return true;   // 第 2 階 dilatePacked、dilateBits；第 3a 階其餘全部
    if (fam == "cc") return op == "ccStats" || op == "ccRuns";
    if (fam == "dist") return true;    // distanceL2（第 2 階）、distanceSq、distanceChamfer、chamfer5Padded（第 3a 階）
    if (fam == "maskmisc") return true;
    if (fam == "gray") return op == "gaussQ16" || op == "morphGrayGather.max" || op == "morphGrayGather.min" || op == "resizeNearestGray";
    if (fam == "fimg") return op == "resizeArea" || op == "resizeBilinear" || op == "resizeAreaOf";
    if (fam == "draw") return op == "polygonArea";
    return false;
}

/** 第 3a 階的運算（replayOp 先問這裡；回 false＝不是這一階的）。 */
bool replayOp3a(const std::string& fam, const std::string& op, const nrd::Record& r, const std::string& where) {
    namespace P = nr::prims;
    const bool threw = r.find("throws") != nullptr;
    auto checkMask = [&](const nr::Mask& m) { if (!threw && !sameMask(r.find("out"), m)) failf(where); };
    auto checkLongs = [&](const uint64_t* v, size_t n) { if (!threw && !sameLongs(r.find("out"), v, n)) failf(where); };
    if (fam == "morph") {
        if (op == "dilatePacked" || op == "dilateBits") return false;   // 第 2 階
        if (op == "dilateRectSep") {
            const nrd::Entry* a = r.find("args");
            checkMask(P::dilateRectSep(maskOf(*r.find("in")), a->i32At(0), a->i32At(1)));
            return true;
        }
        if (op == "closeRectSep") { checkMask(P::closeRectSep(maskOf(*r.find("in")), r.find("n")->i32At(0))); return true; }
        const nr::Mask in = maskOf(*r.find("in"));
        const nr::Kernel k = kernelOf(*r.find("kernel"));
        const nrd::Entry* it = r.find("iterations");
        const int32_t iters = it != nullptr ? it->i32At(0) : 1;
        if (op == "dilate") checkMask(P::dilate(in, k, iters));
        else if (op == "erode") checkMask(P::erode(in, k, iters));
        else if (op == "open") checkMask(P::open(in, k));
        else if (op == "close") checkMask(P::close(in, k));
        else if (op == "erodePacked") checkMask(P::erodePacked(in, k));
        else if (op == "openPacked") checkMask(P::openPacked(in, k));
        else if (op == "closePacked") checkMask(P::closePacked(in, k));
        else { failf(where + "：不認得的運算"); }
        return true;
    }
    if (fam == "dist") {
        if (op == "distanceL2") return false;
        const nr::Mask in = maskOf(*r.find("in"));
        if (op == "distanceSq") { const nr::ImgI32 o = P::distanceSq(in); if (!threw && !sameI32Img(r.find("out"), o)) failf(where); }
        else if (op == "distanceChamfer") { const nr::ImgF32 o = P::distanceChamfer(in, r.find("maskSize")->i32At(0)); if (!threw && !sameF32Img(r.find("out"), o)) failf(where); }
        else if (op == "chamfer5Padded") { const nr::ImgI32 o = P::chamfer5Padded(in); if (!threw && !sameInts(r.find("out"), o.data.data(), o.data.size())) failf(where); }
        else failf(where + "：不認得的運算");
        return true;
    }
    if (fam == "maskmisc") {
        if (op == "geodesicDistance" || op == "geodesicGrow") {
            const nr::Mask seed = maskOf(*r.find("seed"));
            const nr::Mask within = maskOf(*r.find("within"));
            const nrd::Entry* a = r.find("args");
            if (op == "geodesicDistance") {
                const nr::ImgI32 o = P::geodesicDistance(seed, within, a->i32At(0), a->i32At(1));
                if (!threw && !sameI32Img(r.find("out"), o)) failf(where);
            } else {
                checkMask(P::geodesicGrow(seed, within, a->i32At(0), a->i32At(1), a->i32At(2)));
            }
            return true;
        }
        const nr::Mask in = maskOf(*r.find("in"));
        if (op == "holes") checkMask(P::holes(in));
        else if (op == "medianBlurMask") checkMask(P::medianBlurMask(in, r.find("r")->i32At(0)));
        else if (op == "totalContourLength") { const double v = P::totalContourLength(in); if (!threw && !sameDouble(r.find("out"), v)) failf(where); }
        else if (op == "resizeNearest") { const nrd::Entry* a = r.find("args"); checkMask(P::resizeNearest(in, a->i32At(0), a->i32At(1))); }
        else failf(where + "：不認得的運算");
        return true;
    }
    if (fam == "gray" && op == "resizeNearestGray") {
        const nrd::Entry* a = r.find("args");
        const nr::ImgI32 o = P::resizeNearestGray(grayOf(*r.find("in")), a->i32At(0), a->i32At(1));
        if (!threw && !sameI32Img(r.find("out"), o)) failf(where);
        return true;
    }
    if (fam == "fimg") {
        const nr::ImgF32 in = fimgOf(*r.find("in"));
        const nrd::Entry* a = r.find("args");
        const int32_t nw = a->i32At(0), nh = a->i32At(1);
        const float* d = in.data.data();
        const nr::ImgF32 o = op == "resizeArea" ? P::resizeArea(in, nw, nh)
            : op == "resizeBilinear" ? P::resizeBilinear(in, nw, nh)
            : P::resizeAreaOf(in.w, in.h, nw, nh, [d](int32_t i) { return d[i]; });
        if (!threw && !sameF32Img(r.find("out"), o)) failf(where);
        return true;
    }
    if (fam == "draw") {   // polygonArea
        const std::vector<float> xs = r.find("xs")->vec<float>(), ys = r.find("ys")->vec<float>();
        if (xs.size() != ys.size()) { failf(where + "：xs、ys 長度不同"); return true; }
        const double v = P::polygonArea(xs.data(), ys.data(), static_cast<int32_t>(xs.size()));
        if (!threw && !sameDouble(r.find("out"), v)) failf(where);
        return true;
    }
    if (fam == "kernel" && (op == "ellipse" || op == "rect")) {
        const nr::Kernel k = op == "ellipse" ? P::ellipse(r.find("size")->i32At(0)) : P::rect(r.find("args")->i32At(0), r.find("args")->i32At(1));
        if (!threw && !sameKernelData(r.find("out"), k)) failf(where);
        return true;
    }
    if (fam == "bits") {
        if (op == "packBitsNot" || op == "ring.packBits") {
            const nr::Mask in = maskOf(*r.find("in"));
            if (op == "packBitsNot") { const nr::PackedBits p = nr::packBitsNot(in); checkLongs(p.words.data(), p.words.size()); }
            else { const nr::FlatBits f = nr::packFlat(in); checkLongs(f.words.data(), f.words.size()); }
        } else if (op == "unpackBits") {
            checkMask(nr::unpackBits(packedOf(r)));
        } else if (op == "unpackBitsNot") {
            checkMask(nr::unpackBitsNot(packedOf(r)));
        } else if (op == "notBitsInPlace") {
            nr::PackedBits p = packedOf(r);
            nr::notBitsInPlace(p);
            checkLongs(p.words.data(), p.words.size());
        } else if (op == "erodeBits") {
            const nr::PackedBits o = P::erodeBits(packedOf(r), kernelOf(*r.find("kernel")));
            checkLongs(o.words.data(), o.words.size());
        } else if (op == "ccRunsBits") {
            const nr::CcRuns c = P::ccRunsBits(packedOf(r), r.find("connectivity")->i32At(0));
            if (!threw && !sameRuns(r, c)) failf(where);
        } else if (op == "bitRuns") {
            const std::vector<uint64_t> bits = r.find("in")->vec<uint64_t>();
            const nrd::Entry* a = r.find("args");
            const int32_t off = a->i32At(0), nw = a->i32At(1);
            const size_t cap = static_cast<size_t>((64 * nw + 1) / 2 + 1);
            std::vector<int32_t> rs(cap), re(cap);
            const int32_t cnt = nr::bitRuns(bits.data(), off, nw, rs.data(), re.data());
            if (!threw && (!sameInts(r.find("outRs"), rs.data(), static_cast<size_t>(cnt)) || !sameInts(r.find("outRe"), re.data(), static_cast<size_t>(cnt)))) failf(where);
        } else if (op == "anyBits") {
            const std::vector<uint64_t> bits = r.find("in")->vec<uint64_t>();
            const int32_t off = r.find("args")->i32At(0);
            const std::vector<int32_t> ss = r.find("s")->vec<int32_t>(), es = r.find("e")->vec<int32_t>();
            std::vector<int32_t> out(ss.size());
            for (size_t i = 0; i < ss.size(); i++) out[i] = nr::anyBits(bits.data(), off, ss[i], es[i]) ? 1 : 0;
            if (!threw && !sameInts(r.find("out"), out.data(), out.size())) failf(where);
        } else if (op == "ring.has" || op == "ring.set") {
            std::vector<uint64_t> bits = r.find("in")->vec<uint64_t>();
            const std::vector<int32_t> idx = r.find("idx")->vec<int32_t>();
            if (op == "ring.has") {
                std::vector<int32_t> out(idx.size());
                for (size_t i = 0; i < idx.size(); i++) out[i] = nr::hasBit(bits.data(), idx[i]) ? 1 : 0;
                if (!threw && !sameInts(r.find("out"), out.data(), out.size())) failf(where);
            } else {
                for (int32_t i : idx) nr::setBit(bits.data(), i);
                checkLongs(bits.data(), bits.size());
            }
        } else if (op == "reflect101") {
            const std::vector<int32_t> xs = r.find("in")->vec<int32_t>();
            const int32_t n = r.find("n")->i32At(0);
            if (n < 1) throw std::invalid_argument("reflect101: n < 1");
            std::vector<int32_t> out(xs.size());
            for (size_t i = 0; i < xs.size(); i++) out[i] = nr::reflect101(xs[i], n);
            if (!threw && !sameInts(r.find("out"), out.data(), out.size())) failf(where);
        } else {
            failf(where + "：不認得的運算");
        }
        return true;
    }
    return false;
}

void replayOp(const std::string& fam, const std::string& op, const nrd::Record& r, const std::string& where) {
    if (replayOp3a(fam, op, r, where)) return;
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


// ── 錄放（calls/；NativeCallDump.kt）──────────────────────────────────────────

std::string hashLongs(const uint64_t* v, size_t n) {
    nrt::Sha256 s;
    s.byte('J'); s.i32(static_cast<int32_t>(n));
    if (n > 0) s.update(reinterpret_cast<const uint8_t*>(v), n * 8);
    return s.hex16();
}

std::string hashIntsArr(const int32_t* v, size_t n) {
    nrt::Sha256 s;
    feedInts(s, v, n);
    return s.hex16();
}

std::string hashDouble(double v) {
    nrt::Sha256 s;
    s.byte('d'); s.i64(static_cast<int64_t>(nr::jm::dbits(v)));
    return s.hex16();
}

struct Blob {
    std::string type;
    std::vector<int32_t> dims;
    int64_t raw = 0;
    std::vector<uint8_t> z;
};

/** zlib 解壓（長度要剛好是 raw）。 */
std::vector<uint8_t> inflateBlob(const Blob& b) {
    std::vector<uint8_t> out(static_cast<size_t>(b.raw));
    uLongf n = static_cast<uLongf>(out.size());
    const int rc = uncompress(out.empty() ? nullptr : out.data(), &n, b.z.data(), static_cast<uLong>(b.z.size()));
    if (rc != Z_OK || n != out.size()) throw std::runtime_error("blob 解壓失敗");
    return out;
}

nr::Mask blobMask(const Blob& b) {
    if (b.type != "mask" || b.dims.size() != 2) throw std::runtime_error("blob 不是遮罩");
    nr::Mask m(b.dims[1], b.dims[0]);
    const std::vector<uint8_t> raw = inflateBlob(b);
    const size_t n = m.data.size();
    if (raw.size() != ((n + 63) / 64) * 8) throw std::runtime_error("遮罩 blob 長度不合");
    for (size_t i = 0; i < n; i++) m.data[i] = static_cast<uint8_t>((raw[i >> 3] >> (i & 7)) & 1);   // 小端：第 i 位在第 i/8 位元組的第 i%8 位
    return m;
}

nr::Kernel blobKernel(const Blob& b) {
    if (b.type != "kernel" || b.dims.size() != 2) throw std::runtime_error("blob 不是核");
    const std::vector<uint8_t> raw = inflateBlob(b);
    if (raw.size() != static_cast<size_t>(b.dims[0]) * static_cast<size_t>(b.dims[1])) throw std::runtime_error("核 blob 長度不合");
    return nr::Kernel(b.dims[1], b.dims[0], raw.data());
}

template <class T>
std::vector<T> blobArray(const Blob& b, const char* type) {
    if (b.type != type || b.dims.size() != 1) throw std::runtime_error(std::string("blob 不是 ") + type);
    const std::vector<uint8_t> raw = inflateBlob(b);
    if (raw.size() != static_cast<size_t>(b.dims[0]) * sizeof(T)) throw std::runtime_error("陣列 blob 長度不合");
    std::vector<T> v(static_cast<size_t>(b.dims[0]));
    if (!v.empty()) std::memcpy(v.data(), raw.data(), raw.size());
    return v;
}

nr::ImgF32 blobFImg(const Blob& b) {
    if (b.type != "fimg" || b.dims.size() != 2) throw std::runtime_error("blob 不是浮點影像");
    nr::ImgF32 f(b.dims[1], b.dims[0]);
    const std::vector<uint8_t> raw = inflateBlob(b);
    if (raw.size() != f.data.size() * 4) throw std::runtime_error("浮點影像 blob 長度不合");
    if (!raw.empty()) std::memcpy(f.data.data(), raw.data(), raw.size());
    return f;
}

nr::ImgI32 blobGray(const Blob& b) {
    if (b.type != "gray" || b.dims.size() != 2) throw std::runtime_error("blob 不是灰階");
    nr::ImgI32 g(b.dims[1], b.dims[0]);
    const std::vector<uint8_t> raw = inflateBlob(b);
    if (raw.size() != g.data.size() * 4) throw std::runtime_error("灰階 blob 長度不合");
    if (!raw.empty()) std::memcpy(g.data.data(), raw.data(), raw.size());
    return g;
}

/** 一次呼叫的參數：ref（blob）、int、dbl、bool、null。 */
struct CallArg {
    char kind = 0;   // 'r' 'i' 'd' 'b' 'n'
    int32_t i = 0;
    double d = 0;
};

nr::PackedBits packedFrom(const std::vector<uint64_t>& v, int32_t w, int32_t h) {
    nr::PackedBits p(w, h);
    if (v.size() != p.words.size()) throw std::runtime_error("打包陣列的長度與尺寸不合");
    if (!v.empty()) std::memcpy(p.words.data(), v.data(), v.size() * 8);
    return p;
}

/** 用 C++ 重算一次呼叫，回傳結果的成分摘要（同 NativeCallDump.hashOf）。 */
std::string replayCall(const std::string& op, const std::vector<CallArg>& a, const std::vector<Blob>& blobs) {
    namespace P = nr::prims;
    auto blob = [&](size_t k) -> const Blob& {
        if (k >= a.size() || a[k].kind != 'r' || a[k].i < 0 || static_cast<size_t>(a[k].i) >= blobs.size()) throw std::runtime_error("參數 " + std::to_string(k) + " 不是已知的 blob");
        return blobs[static_cast<size_t>(a[k].i)];
    };
    auto I = [&](size_t k) -> int32_t {
        if (k >= a.size() || a[k].kind != 'i') throw std::runtime_error("參數 " + std::to_string(k) + " 不是 int");
        return a[k].i;
    };
    auto M = [&](size_t k) { return blobMask(blob(k)); };
    auto K = [&](size_t k) { return blobKernel(blob(k)); };
    auto L = [&](size_t k) { return blobArray<uint64_t>(blob(k), "longs"); };
    auto hm = [](const nr::Mask& m) { return hashMask(m.w, m.h, m.data.data()); };
    auto hp = [](const nr::PackedBits& p) { return hashLongs(p.words.data(), p.words.size()); };
    if (op == "dilate") return hm(P::dilate(M(0), K(1), I(2)));
    if (op == "erode") return hm(P::erode(M(0), K(1), I(2)));
    if (op == "open") return hm(P::open(M(0), K(1)));
    if (op == "close") return hm(P::close(M(0), K(1)));
    if (op == "dilatePacked") return hm(P::dilatePacked(M(0), K(1)));
    if (op == "erodePacked") return hm(P::erodePacked(M(0), K(1)));
    if (op == "openPacked") return hm(P::openPacked(M(0), K(1)));
    if (op == "closePacked") return hm(P::closePacked(M(0), K(1)));
    if (op == "packBits") return hp(nr::packBits(M(0)));
    if (op == "packBitsNot") return hp(nr::packBitsNot(M(0)));
    if (op == "ring.packBits") { const nr::FlatBits f = nr::packFlat(M(0)); return hashLongs(f.words.data(), f.words.size()); }
    if (op == "unpackBits") return hm(nr::unpackBits(packedFrom(L(0), I(1), I(2))));
    if (op == "unpackBitsNot") return hm(nr::unpackBitsNot(packedFrom(L(0), I(1), I(2))));
    if (op == "notBitsInPlace") { nr::PackedBits p = packedFrom(L(0), I(1), I(2)); nr::notBitsInPlace(p); return hp(p); }
    if (op == "erodeBits") return hp(P::erodeBits(packedFrom(L(0), I(1), I(2)), K(3)));
    if (op == "dilateBits") return hp(P::dilateBits(packedFrom(L(0), I(1), I(2)), K(3)));
    if (op == "dilateRectSep") return hm(P::dilateRectSep(M(0), I(1), I(2)));
    if (op == "closeRectSep") return hm(P::closeRectSep(M(0), I(1)));
    if (op == "ccStats") return hashCc(P::ccStats(M(0), I(1)));
    if (op == "ccRuns") return hashRuns(P::ccRuns(M(0), I(1)));
    if (op == "ccRunsBits") return hashRuns(P::ccRunsBits(packedFrom(L(0), I(1), I(2)), I(3)));
    if (op == "distanceL2") return hashImgF(P::distanceL2(M(0)));
    if (op == "distanceSq") { const nr::ImgI32 o = P::distanceSq(M(0)); return hashIntsArr(o.data.data(), o.data.size()); }
    if (op == "distanceChamfer") return hashImgF(P::distanceChamfer(M(0), I(1)));
    if (op == "chamfer5Padded") { const nr::ImgI32 o = P::chamfer5Padded(M(0)); return hashIntsArr(o.data.data(), o.data.size()); }
    if (op == "resizeArea") return hashImgF(P::resizeArea(blobFImg(blob(0)), I(1), I(2)));
    if (op == "resizeAreaOf") {
        const nr::ImgF32 f = blobFImg(blob(0));
        const float* d = f.data.data();
        return hashImgF(P::resizeAreaOf(f.w, f.h, I(1), I(2), [d](int32_t i) { return d[i]; }));
    }
    if (op == "resizeBilinear") return hashImgF(P::resizeBilinear(blobFImg(blob(0)), I(1), I(2)));
    if (op == "resizeNearest") return hm(P::resizeNearest(M(0), I(1), I(2)));
    if (op == "resizeNearestGray") return hashImgI(P::resizeNearestGray(blobGray(blob(0)), I(1), I(2)));
    if (op == "holes") return hm(P::holes(M(0)));
    if (op == "totalContourLength") return hashDouble(P::totalContourLength(M(0)));
    if (op == "polygonArea") {
        const std::vector<float> xs = blobArray<float>(blob(0), "floats"), ys = blobArray<float>(blob(1), "floats");
        if (xs.size() != ys.size()) throw std::runtime_error("polygonArea：xs、ys 長度不同");
        return hashDouble(P::polygonArea(xs.data(), ys.data(), static_cast<int32_t>(xs.size())));
    }
    if (op == "geodesicDistance") { const nr::ImgI32 o = P::geodesicDistance(M(0), M(1), I(2), I(3)); return hashIntsArr(o.data.data(), o.data.size()); }
    if (op == "geodesicGrow") {
        if (a.size() < 5 || (a[4].kind != 'b' && a[4].kind != 'n')) throw std::runtime_error("geodesicGrow 的 bfs 參數");
        return hm(P::geodesicGrow(M(0), M(1), I(2), I(3), a[4].kind == 'n' ? -1 : a[4].i));
    }
    if (op == "medianBlurMask") return hm(P::medianBlurMask(M(0), I(1)));
    if (op == "gaussQ16") {
        if (a.size() < 2 || a[1].kind != 'd') throw std::runtime_error("gaussQ16 的 sigma 參數");
        return hashImgI(P::gaussQ16(blobGray(blob(0)), a[1].d));
    }
    if (op == "morphGrayGather") {
        if (a.size() < 3 || a[2].kind != 'b') throw std::runtime_error("morphGrayGather 的 wantMax 參數");
        return hashImgI(P::morphGrayGather(blobGray(blob(0)), K(1), a[2].i != 0));
    }
    throw std::runtime_error("不認得的運算 " + op);
}

void checkCalls(const std::string& dir, const std::string& source, long wantUnique) {
    nrd::Reader rd;
    if (!rd.open(dir + "/calls/" + source + ".nrd")) { failf(rd.error); return; }
    std::vector<Blob> blobs;
    nrd::Record r;
    long calls = 0;
    const int failBefore = g_fail;
    std::map<std::string, long> ops;
    while (!rd.atEnd()) {
        if (!rd.next(r)) { failf(source + ": " + rd.error); break; }
        if (const nrd::Entry* bid = r.find("blob")) {
            if (bid->i32At(0) != static_cast<int32_t>(blobs.size())) { failf(source + "：blob 編號不連續"); return; }
            Blob b;
            b.type = r.str("type");
            const nrd::Entry* dims = r.find("dims");
            const nrd::Entry* raw = r.find("raw");
            const nrd::Entry* z = r.find("z");
            if (dims == nullptr || raw == nullptr || z == nullptr) { failf(source + "：blob 缺欄位"); return; }
            b.dims = dims->vec<int32_t>();
            b.raw = raw->i64At(0);
            b.z = z->data;
            blobs.push_back(std::move(b));
            continue;
        }
        const std::string op = r.str("op");
        const std::string want = r.str("hash");
        std::vector<CallArg> args;
        for (const nrd::Entry& e : r.entries) {
            CallArg a;
            if (e.name == "ref") { a.kind = 'r'; a.i = e.i32At(0); }
            else if (e.name == "int") { a.kind = 'i'; a.i = e.i32At(0); }
            else if (e.name == "bool") { a.kind = 'b'; a.i = e.i32At(0); }
            else if (e.name == "dbl") { a.kind = 'd'; a.d = nr::jm::dfrom(static_cast<uint64_t>(e.i64At(0))); }
            else if (e.name == "null") { a.kind = 'n'; }
            else continue;
            args.push_back(a);
        }
        const std::string where = source + " #" + std::to_string(calls) + " " + op;
        if (op.empty() || want.empty()) { failf(where + "：缺 op／hash"); continue; }
        try {
            const std::string got = replayCall(op, args, blobs);
            if (got != want) failf(where + "：摘要 " + got + "，Kotlin " + want);
        } catch (const std::exception& e) {
            failf(where + "：C++ 丟了 " + e.what());
        }
        ops[op]++;
        g_callOps[op]++;
        calls++;
    }
    if (calls == 0) failf(source + "：錄放 0 筆");
    if (wantUnique >= 0 && calls != wantUnique) failf(source + "：錄放筆數 " + std::to_string(calls) + "，index 寫 " + std::to_string(wantUnique));
    g_checked += calls;
    if (calls > 0 && g_fail == failBefore) g_callSources++;
    std::string sum;
    for (const auto& kv : ops) sum += (sum.empty() ? "" : "、") + kv.first + " " + std::to_string(kv.second);
    std::printf("  calls %-16s %6ld 次呼叫、blob %zu 個（%s）\n", source.c_str(), calls, blobs.size(), sum.c_str());
}

struct Opts {
    std::vector<std::string> expectFams, expectOps, expectCallOps;
    long expectPages = 0, expectPrimPages = 0, expectCallSources = 0;
    std::string dir;
    int rc = 0;
};

void* runCheck(void* p) {
    Opts& o = *static_cast<Opts*>(p);
    const std::string& dir = o.dir;
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
    if (exists(dir + "/calls/index.tsv")) {
        for (const auto& row : readTsv(dir + "/calls/index.tsv")) {
            if (row.size() < 3 || row[0] == "source") continue;
            checkCalls(dir, row[0], std::atol(row[2].c_str()));
        }
    }
    for (const auto& op : o.expectOps) {
        if (g_replayedOps[op] == 0) failf("預期的運算 " + op + " 一筆都沒有重放");
    }
    for (const auto& op : o.expectCallOps) {
        if (g_callOps[op] == 0) failf("錄放裡預期的運算 " + op + " 一次都沒有重放");
    }
    if (g_primPages < o.expectPrimPages)
        failf("跑完原語步驟表的頁只有 " + std::to_string(g_primPages) + " 頁，預期至少 " + std::to_string(o.expectPrimPages) + " 頁");
    if (g_callSources < o.expectCallSources)
        failf("整份重放完的錄放來源只有 " + std::to_string(g_callSources) + " 個，預期至少 " + std::to_string(o.expectCallSources) + " 個");
    for (const auto& f : o.expectFams) {
        const auto it = g_replayed.find(f);
        if (it == g_replayed.end()) failf("預期的族 " + f + " 不在 prims/index.tsv（錄出器沒錄、或族名打錯）");
        else if (it->second == 0) failf("預期的族 " + f + " 一筆都沒有重放（C++ 還沒有這族的重放，或錄的全是丟例外）");
    }
    if (g_pagesChecked < o.expectPages)
        failf("真的重算了東西的頁只有 " + std::to_string(g_pagesChecked) + " 頁，預期至少 " + std::to_string(o.expectPages) + " 頁");
    if (g_checked == 0) failf("一項都沒有驗到（錄出目錄是空的？）");
    std::printf("nr_dump_check：%ld 項（%ld 頁有重算、錄放 %ld 個來源），%d 個不符\n", g_checked, g_pagesChecked, g_callSources, g_fail);
    o.rc = g_fail == 0 ? 0 : 1;
    return nullptr;
}

}  // namespace

int main(int argc, char** argv) {
    Opts o;
    auto list = [](const char* v, std::vector<std::string>& out) {
        std::stringstream ss(v);
        std::string f;
        while (std::getline(ss, f, ',')) if (!f.empty()) out.push_back(f);
    };
    for (int i = 1; i < argc; i++) {
        const std::string a = argv[i];
        if (a == "--expect-families" && i + 1 < argc) {
            list(argv[++i], o.expectFams);
        } else if (a == "--expect-ops" && i + 1 < argc) {
            list(argv[++i], o.expectOps);
        } else if (a == "--expect-call-ops" && i + 1 < argc) {
            list(argv[++i], o.expectCallOps);
        } else if (a == "--expect-pages" && i + 1 < argc) {
            o.expectPages = std::atol(argv[++i]);
        } else if (a == "--expect-prim-pages" && i + 1 < argc) {
            o.expectPrimPages = std::atol(argv[++i]);
        } else if (a == "--expect-call-sources" && i + 1 < argc) {
            o.expectCallSources = std::atol(argv[++i]);
        } else if (o.dir.empty() && a.rfind("--", 0) != 0) {
            o.dir = a;
        } else {
            o.dir.clear();
            break;
        }
    }
    if (o.dir.empty()) {
        std::fprintf(stderr, "用法：nr_dump_check [--expect-families 族,族…] [--expect-ops 運算,運算…] [--expect-pages N] [--expect-prim-pages N] "
                             "[--expect-call-sources N] [--expect-call-ops 運算,運算…] <錄出目錄>\n");
        return 2;
    }
    if (!exists(o.dir + "/README.txt")) { std::printf("FAIL 不是錄出目錄（沒有 README.txt）：%s\n", o.dir.c_str()); return 1; }
    // 256 KB 堆疊的執行緒（同 nr_core_tests：原語不能在堆疊放大陣列，手機的工作執行緒約 1 MB）
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    if (pthread_attr_setstacksize(&attr, 256 * 1024) != 0) { std::printf("setstacksize failed\n"); return 2; }
    pthread_t th;
    if (pthread_create(&th, &attr, runCheck, &o) != 0) { std::printf("pthread_create failed\n"); return 2; }
    pthread_join(th, nullptr);
    pthread_attr_destroy(&attr);
    return o.rc;
}
