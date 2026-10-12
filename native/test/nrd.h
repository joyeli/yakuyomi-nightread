// NRD 容器的讀取器（只給測試與工具用）：Kotlin 的錄出器（NativeDumpTest／NativeDump.kt）寫的 .nrd。
//
// 格式（小端）：魔數 "NRD1"，接著一筆筆紀錄到檔尾。紀錄＝u32 欄位數，每個欄位＝u16 名稱長、名稱（UTF-8）、u8 型別、u8 維數、
// 維數×i32 維度、資料（元素數＝維度乘積）。型別：1 u8、2 i32、3 i64、4 f32、5 f64（浮點都是原始位元）、6 UTF-8 字串。
// 本 repo 只跑在小端平台（x86_64、arm64），資料直接 memcpy。
#pragma once

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace nrd {

enum Type : uint8_t { U8 = 1, I32 = 2, I64 = 3, F32 = 4, F64 = 5, UTF8 = 6 };

inline size_t elemSize(uint8_t t) {
    switch (t) {
        case U8: case UTF8: return 1;
        case I32: case F32: return 4;
        case I64: case F64: return 8;
        default: return 0;
    }
}

struct Entry {
    std::string name;
    uint8_t type = 0;
    std::vector<int32_t> dims;
    std::vector<uint8_t> data;

    size_t count() const { return elemSize(type) == 0 ? 0 : data.size() / elemSize(type); }
    std::string str() const { return std::string(data.begin(), data.end()); }
    template <class T>
    std::vector<T> vec() const {
        std::vector<T> v(data.size() / sizeof(T));
        if (!v.empty()) std::memcpy(v.data(), data.data(), v.size() * sizeof(T));
        return v;
    }
    int32_t i32At(size_t i) const { int32_t v; std::memcpy(&v, data.data() + 4 * i, 4); return v; }
    int64_t i64At(size_t i) const { int64_t v; std::memcpy(&v, data.data() + 8 * i, 8); return v; }
};

struct Record {
    std::vector<Entry> entries;
    const Entry* find(const char* name) const {
        for (const Entry& e : entries) if (e.name == name) return &e;
        return nullptr;
    }
    std::string str(const char* name) const {
        const Entry* e = find(name);
        return e == nullptr || e->type != UTF8 ? std::string() : e->str();
    }
};

/** 整個檔讀進來再逐筆解；格式錯誤時 next() 回 false 並設 error。 */
class Reader {
public:
    bool open(const std::string& path) {
        FILE* f = std::fopen(path.c_str(), "rb");
        if (f == nullptr) { error = "打不開 " + path; return false; }
        std::fseek(f, 0, SEEK_END);
        const long n = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        buf_.resize(n < 0 ? 0 : static_cast<size_t>(n));
        const size_t got = buf_.empty() ? 0 : std::fread(buf_.data(), 1, buf_.size(), f);
        std::fclose(f);
        if (got != buf_.size()) { error = "讀不完 " + path; return false; }
        if (buf_.size() < 4 || std::memcmp(buf_.data(), "NRD1", 4) != 0) { error = "不是 NRD1：" + path; return false; }
        pos_ = 4;
        return true;
    }

    bool atEnd() const { return pos_ == buf_.size(); }
    bool ok() const { return error.empty(); }

    bool next(Record& r) {
        r.entries.clear();
        uint32_t n;
        if (!take(&n, 4)) return false;
        if (n > 4096) { error = "欄位數不合理"; return false; }
        for (uint32_t k = 0; k < n; k++) {
            Entry e;
            uint16_t nl;
            if (!take(&nl, 2)) return false;
            if (!have(nl)) return false;
            e.name.assign(reinterpret_cast<const char*>(buf_.data() + pos_), nl);
            pos_ += nl;
            uint8_t nd;
            if (!take(&e.type, 1) || !take(&nd, 1)) return false;
            if (elemSize(e.type) == 0 || nd > 8) { error = "欄位 " + e.name + " 的型別或維數不合法"; return false; }
            uint64_t cnt = 1;
            for (uint8_t d = 0; d < nd; d++) {
                int32_t v;
                if (!take(&v, 4)) return false;
                if (v < 0) { error = "欄位 " + e.name + " 的維度是負的"; return false; }
                e.dims.push_back(v);
                cnt *= static_cast<uint64_t>(v);
                if (cnt > (uint64_t(1) << 40)) { error = "欄位 " + e.name + " 太大"; return false; }
            }
            const uint64_t bytes = cnt * elemSize(e.type);
            if (!have(bytes)) return false;
            e.data.assign(buf_.data() + pos_, buf_.data() + pos_ + bytes);
            pos_ += static_cast<size_t>(bytes);
            r.entries.push_back(std::move(e));
        }
        return true;
    }

    std::string error;

private:
    bool have(uint64_t n) {
        if (n > buf_.size() - pos_) { error = "檔尾截斷"; return false; }
        return true;
    }
    bool take(void* dst, size_t n) {
        if (!have(n)) return false;
        std::memcpy(dst, buf_.data() + pos_, n);
        pos_ += n;
        return true;
    }

    std::vector<uint8_t> buf_;
    size_t pos_ = 0;
};

}  // namespace nrd
