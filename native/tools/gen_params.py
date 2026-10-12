#!/usr/bin/env python3
"""由 Kotlin 的 Params.kt 產生夜讀參數的 C++ 結構與攤平格式（cpp-eval 報告第 7 節第 6 條）。

產生三樣東西，全部入庫；CI 的 native-guard 會重跑這支再 `git diff --exit-code`，忘了重產就紅：

  native/core/params_gen.h
      C++ 的參數結構（欄位名、型別、預設值與 Kotlin 一一對應）、攤平／還原（fromFlat／toFlat）、逐欄存取（測試用）、
      schema 雜湊、規則版本與規則版本摘要表（取自 RulesVersionGuardTest.kt）。
  nightread/src/main/kotlin/li/joye/yakuyomi/nightread/ParamsFlat.kt
      Kotlin 端的攤平／還原與欄位表（名稱、型別、槽位），以及同一個 schema 雜湊。
  native/include/nightread/nr.h
      只改 `#define NR_RULES_VERSION` 這一行，跟 NightRead.RULES_VERSION 同步。

攤平格式（JNI 只傳兩個陣列）：
  ints   ：Int（原值）、Boolean（0／1）、enum（ordinal）、Int?（兩格：有沒有值 0／1、值；沒有值時值那格是 0）
  doubles：Double（原值）、Float（轉成 double，精確）
欄位順序＝Params.kt 的宣告順序、巢狀參數組（BubbleSealParams 等）在宣告的位置展開（深度優先）。

schema 雜湊＝SHA-256（UTF-8）的前 8 個位元組（大端）當成 64 位元整數，輸入是下面這段正規字串：
  "nightread-params/1\n" ＋ 每個欄位一行 "<路徑>:<型別>\n"（型別 int／double／float／bool／opt_int／enum:<名稱>）
  ＋ 每個 enum 一行 "enum <名稱>=<項目,…>\n"
只涵蓋欄位的名稱、型別與順序（＝攤平格式），不含預設值（預設值改了靠 git diff 抓）。

用法：
  native/tools/gen_params.py            重產（有變才寫檔）
  native/tools/gen_params.py --check    只檢查，跟入庫的不同就回 1（不寫檔）
只用 Python 標準庫（3.8+）。
"""
import hashlib
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
KT_DIR = os.path.join(ROOT, "nightread", "src", "main", "kotlin", "li", "joye", "yakuyomi", "nightread")
PARAMS_KT = os.path.join(KT_DIR, "Params.kt")
NIGHTREAD_KT = os.path.join(KT_DIR, "NightRead.kt")
GUARD_KT = os.path.join(ROOT, "nightread", "src", "test", "kotlin", "li", "joye", "yakuyomi", "nightread",
                        "RulesVersionGuardTest.kt")
OUT_H = os.path.join(ROOT, "native", "core", "params_gen.h")
OUT_KT = os.path.join(KT_DIR, "ParamsFlat.kt")
NR_H = os.path.join(ROOT, "native", "include", "nightread", "nr.h")

ROOT_CLASS = "NightReadParams"
SCHEMA_TAG = "nightread-params/1"

# C++ 關鍵字（欄位名撞到就停下來：C++ 結構用同一個名字）
CPP_KEYWORDS = set("""
alignas alignof and and_eq asm auto bitand bitor bool break case catch char char8_t char16_t char32_t class compl concept
const consteval constexpr constinit const_cast continue co_await co_return co_yield decltype default delete do double
dynamic_cast else enum explicit export extern false float for friend goto if inline int long mutable namespace new
noexcept not not_eq nullptr operator or or_eq private protected public register reinterpret_cast requires return short
signed sizeof static static_assert static_cast struct switch template this thread_local throw true try typedef typeid
typename union unsigned using virtual void volatile wchar_t while xor xor_eq
""".split())


class GenError(Exception):
    pass


# ── Kotlin 原始碼的最小剖析 ──────────────────────────────────────────────

def strip_comments(src):
    """去掉 // 與巢狀 /* */ 註解（字串裡的不動），換成空白（保留換行，錯誤訊息的行號才對）。"""
    out = []
    i, n = 0, len(src)
    depth = 0
    in_str = None
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if depth > 0:
            if c == "/" and nxt == "*":
                depth += 1
                out.append("  ")
                i += 2
            elif c == "*" and nxt == "/":
                depth -= 1
                out.append("  ")
                i += 2
            else:
                out.append("\n" if c == "\n" else " ")
                i += 1
            continue
        if in_str:
            out.append(c)
            if c == "\\":
                out.append(nxt)
                i += 2
                continue
            if c == in_str:
                in_str = None
            i += 1
            continue
        if c == "/" and nxt == "*":
            depth = 1
            out.append("  ")
            i += 2
        elif c == "/" and nxt == "/":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
        elif c in "\"'":
            in_str = c
            out.append(c)
            i += 1
        else:
            out.append(c)
            i += 1
    if depth:
        raise GenError("Params.kt：註解沒有關閉")
    return "".join(out)


def matching(src, i, open_c, close_c):
    """src[i] == open_c；回傳對應 close_c 的位置（註解已去掉；字串裡沒有括號）。"""
    depth = 0
    for j in range(i, len(src)):
        if src[j] == open_c:
            depth += 1
        elif src[j] == close_c:
            depth -= 1
            if depth == 0:
                return j
    raise GenError("括號沒有關閉（位置 %d）" % i)


def split_top(s):
    """以最上層逗號切開（不進括號）。"""
    parts, depth, cur = [], 0, []
    for c in s:
        if c in "([{<":
            depth += 1
        elif c in ")]}>":
            depth -= 1
        if c == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(c)
    parts.append("".join(cur))
    return [p.strip() for p in parts if p.strip()]


class DataClasses(dict):
    """data class 名稱 → 欄位 [(名稱, 型別, 預設值)]。只在被參數樹用到時才剖析（TextRegion 等沒有預設值的不碰）。"""

    def __init__(self, src):
        super().__init__()
        self.raw = {}
        for m in re.finditer(r"\bdata\s+class\s+(\w+)\s*\(", src):
            lp = m.end() - 1
            self.raw[m.group(1)] = src[lp + 1:matching(src, lp, "(", ")")]

    def __contains__(self, name):
        return name in self.raw

    def __getitem__(self, name):
        if not dict.__contains__(self, name):
            fields = []
            for item in split_top(self.raw[name]):
                fm = re.fullmatch(r"(?:@\w+\s+)*val\s+(\w+)\s*:\s*([\w.]+\??)\s*=\s*(.+)", item, re.S)
                if fm is None:
                    raise GenError("%s：看不懂的建構子參數 %r（每個參數都要是 `val 名稱: 型別 = 預設值`）" % (name, item))
                fields.append((fm.group(1), fm.group(2), " ".join(fm.group(3).split())))
            dict.__setitem__(self, name, fields)
        return dict.__getitem__(self, name)


def parse_data_classes(src):
    return DataClasses(src)


def parse_enums(src):
    enums = {}
    for m in re.finditer(r"\benum\s+class\s+(\w+)\s*(\(?)", src):
        name = m.group(1)
        if m.group(2):
            continue   # 帶建構子的 enum（NightTier）不是參數型別
        lb = src.index("{", m.end())
        rb = matching(src, lb, "{", "}")
        body = src[lb + 1:rb]
        if ";" in body:
            raise GenError("enum %s 有成員宣告，產生器只處理純項目清單" % name)
        entries = split_top(body)
        for e in entries:
            if not re.fullmatch(r"[A-Z][A-Z0-9_]*", e):
                raise GenError("enum %s：看不懂的項目 %r" % (name, e))
        enums[name] = entries
    return enums


# ── 欄位模型 ────────────────────────────────────────────────────────────

class Leaf:
    def __init__(self, path, kind, default_cpp, enum=None):
        self.path = path              # "sep.lenFrac"
        self.kind = kind              # int double float bool opt_int enum
        self.default_cpp = default_cpp
        self.enum = enum
        self.islot = None             # ints 起始槽（opt_int 佔兩格）
        self.dslot = None

    @property
    def schema_type(self):
        return "enum:" + self.enum if self.kind == "enum" else self.kind


INT_RE = re.compile(r"-?\d[\d_]*")
DOUBLE_RE = re.compile(r"-?(\d[\d_]*\.\d[\d_]*([eE][+-]?\d+)?|\d[\d_]*[eE][+-]?\d+)")
FLOAT_RE = re.compile(r"-?(\d[\d_]*(\.\d[\d_]*)?([eE][+-]?\d+)?)[fF]")


def cpp_default(cls, fname, ktype, default, enums):
    d = default
    if ktype == "Int":
        if not INT_RE.fullmatch(d):
            raise GenError("%s.%s：Int 預設值 %r 不是整數字面值" % (cls, fname, d))
        v = int(d.replace("_", ""))
        if not -2 ** 31 <= v < 2 ** 31:
            raise GenError("%s.%s：超出 Int 範圍" % (cls, fname))
        return "INT32_MIN" if v == -2 ** 31 else str(v)
    if ktype == "Double":
        if not DOUBLE_RE.fullmatch(d):
            raise GenError("%s.%s：Double 預設值 %r 不是浮點字面值" % (cls, fname, d))
        return d.replace("_", "")
    if ktype == "Float":
        m = FLOAT_RE.fullmatch(d)
        if not m:
            raise GenError("%s.%s：Float 預設值 %r 不是 Float 字面值" % (cls, fname, d))
        body = d[:-1].replace("_", "")
        if "." not in body and "e" not in body.lower():
            body += ".0"
        return body + "f"
    if ktype == "Boolean":
        if d not in ("true", "false"):
            raise GenError("%s.%s：Boolean 預設值 %r" % (cls, fname, d))
        return d
    if ktype == "Int?":
        if d == "null":
            return "{false, 0}"
        if not INT_RE.fullmatch(d):
            raise GenError("%s.%s：Int? 預設值 %r" % (cls, fname, d))
        return "{true, %d}" % int(d.replace("_", ""))
    if ktype in enums:
        m = re.fullmatch(re.escape(ktype) + r"\.(\w+)", d)
        if not m or m.group(1) not in enums[ktype]:
            raise GenError("%s.%s：enum 預設值 %r" % (cls, fname, d))
        return "%s::%s" % (ktype, m.group(1))
    raise GenError("%s.%s：不支援的型別 %s（產生器要先擴充）" % (cls, fname, ktype))


KIND_OF = {"Int": "int", "Double": "double", "Float": "float", "Boolean": "bool", "Int?": "opt_int"}
CPP_TYPE = {"int": "int32_t", "double": "double", "float": "float", "bool": "bool", "opt_int": "OptI32"}


def build_model(classes, enums):
    if ROOT_CLASS not in classes:
        raise GenError("Params.kt 沒有 data class %s" % ROOT_CLASS)
    order = []          # 參數組的定義順序（被參考到的才收；C++ 要先定義內層）
    leaves = []
    used_enums = []

    def walk(cls, prefix, stack):
        if cls in stack:
            raise GenError("參數組遞迴：%s" % " → ".join(stack + [cls]))
        for fname, ktype, default in classes[cls]:
            if fname in CPP_KEYWORDS:
                raise GenError("%s.%s：欄位名是 C++ 關鍵字" % (cls, fname))
            path = prefix + fname
            if ktype in classes:
                if default != ktype + "()":
                    raise GenError("%s.%s：巢狀參數組的預設值必須是 %s()，收到 %r" % (cls, fname, ktype, default))
                walk(ktype, path + ".", stack + [cls])
                if ktype not in order:
                    order.append(ktype)
                continue
            if ktype in enums:
                if ktype not in used_enums:
                    used_enums.append(ktype)
                leaves.append(Leaf(path, "enum", cpp_default(cls, fname, ktype, default, enums), ktype))
                continue
            if ktype not in KIND_OF:
                raise GenError("%s.%s：不支援的型別 %s（產生器要先擴充）" % (cls, fname, ktype))
            leaves.append(Leaf(path, KIND_OF[ktype], cpp_default(cls, fname, ktype, default, enums)))

    walk(ROOT_CLASS, "", [])
    order.append(ROOT_CLASS)
    ni = nd = 0
    for lf in leaves:
        if lf.kind in ("double", "float"):
            lf.dslot = nd
            nd += 1
        else:
            lf.islot = ni
            ni += 2 if lf.kind == "opt_int" else 1
    return order, leaves, used_enums, ni, nd


def schema_hash(leaves, enums, used_enums):
    s = SCHEMA_TAG + "\n"
    for lf in leaves:
        s += "%s:%s\n" % (lf.path, lf.schema_type)
    for e in used_enums:
        s += "enum %s=%s\n" % (e, ",".join(enums[e]))
    return int.from_bytes(hashlib.sha256(s.encode("utf-8")).digest()[:8], "big")


# ── 規則版本與摘要表 ─────────────────────────────────────────────────────

def parse_rules_version():
    with open(NIGHTREAD_KT, encoding="utf-8") as f:
        m = re.search(r"const\s+val\s+RULES_VERSION\s*:\s*Int\s*=\s*(\d+)", f.read())
    if not m:
        raise GenError("NightRead.kt 找不到 const val RULES_VERSION")
    return int(m.group(1))


def parse_digest_map(src, name):
    m = re.search(r"\bval\s+" + name + r"\s*=\s*mapOf\s*\(", src)
    if not m:
        raise GenError("RulesVersionGuardTest.kt 找不到 %s = mapOf(" % name)
    lp = m.end() - 1
    body = src[lp + 1:matching(src, lp, "(", ")")]
    out = {}
    for item in split_top(body):
        im = re.fullmatch(r'(\d+)\s+to\s+"([0-9a-f]{64})"', item)
        if not im:
            raise GenError("%s：看不懂的項目 %r" % (name, item))
        out[int(im.group(1))] = im.group(2)
    return out


def parse_digests(rules_version):
    with open(GUARD_KT, encoding="utf-8") as f:
        src = strip_comments(f.read())
    digests = parse_digest_map(src, "digests")
    delivered = parse_digest_map(src, "delivered")
    if sorted(digests) != list(range(1, rules_version + 1)):
        raise GenError("digests 的版本要從 1 連續編到 RULES_VERSION=%d，收到 %s" % (rules_version, sorted(digests)))
    for v, d in delivered.items():
        if digests.get(v) != d:
            raise GenError("版本 %d 已交出，digests 與 delivered 的摘要不同" % v)
    return digests, delivered


# ── 產生 C++ ────────────────────────────────────────────────────────────

GEN_NOTE = "由 native/tools/gen_params.py 從 nightread/src/main/kotlin/li/joye/yakuyomi/nightread/Params.kt 產生，不要手改"


def gen_header(classes, enums, order, leaves, used_enums, ni, nd, h64, rules_version, digests, delivered):
    L = []
    a = L.append
    a("// " + GEN_NOTE + "（改 Params.kt 後重跑 gen_params.py；CI 的 native-guard 重跑後 git diff 必須是空的）。")
    a("//")
    a("// 夜讀參數的 C++ 鏡像：結構、欄位名、型別、預設值與 Kotlin 的 NightReadParams 一一對應。C++ 不自己決定參數：")
    a("// 產品路徑一律由 Kotlin 攤平（ParamsFlat.toFlat）經 JNI 傳進來、在這裡 fromFlat 還原；預設值只給原生測試用。")
    a("// 攤平格式與 schema 雜湊的定義見 gen_params.py 檔頭。")
    a("#pragma once")
    a("")
    a("#include <cstdint>")
    a("#include <cstring>")
    a("#include <limits>")
    a("")
    a("namespace nr {")
    a("namespace params {")
    a("")
    a("/** 規則版本（＝NightRead.RULES_VERSION；nr.h 的 NR_RULES_VERSION 由同一支產生器同步）。 */")
    a("constexpr int32_t kRulesVersion = %d;" % rules_version)
    a("/** 葉欄位數（巢狀參數組展開後）。 */")
    a("constexpr int32_t kFieldCount = %d;" % len(leaves))
    a("/** 攤平後 ints／doubles 的長度。 */")
    a("constexpr int32_t kIntSlots = %d;" % ni)
    a("constexpr int32_t kDoubleSlots = %d;" % nd)
    a("/** 攤平格式的 schema 雜湊（＝ParamsFlat.SCHEMA_HASH）。 */")
    a("constexpr uint64_t kSchemaHash = 0x%016xULL;" % h64)
    a("")
    a("/** 規則版本 → 產品兩檔 8 頁摘要（RulesVersionGuardTest.digests）；delivered＝已交給使用者（RulesVersionGuardTest.delivered），摘要凍結。 */")
    a("struct RulesDigest {")
    a("    int32_t version;")
    a("    const char* sha256;")
    a("    bool delivered;")
    a("};")
    a("constexpr RulesDigest kRulesDigests[] = {")
    for v in sorted(digests):
        a('    {%d, "%s", %s},' % (v, digests[v], "true" if v in delivered else "false"))
    a("};")
    a("constexpr int32_t kRulesDigestCount = %d;" % len(digests))
    a("")
    for e in used_enums:
        a("enum class %s : int32_t {" % e)
        for k, item in enumerate(enums[e]):
            a("    %s = %d," % (item, k))
        a("};")
        a("constexpr int32_t k%sCount = %d;" % (e, len(enums[e])))
        a("")
    a("/** Kotlin 的 Int?：present＝false 時 value 一律 0。 */")
    a("struct OptI32 {")
    a("    bool present;")
    a("    int32_t value;")
    a("};")
    a("")
    for cls in order:
        a("struct %s {" % cls)
        for fname, ktype, default in classes[cls]:
            if ktype in classes:
                a("    %s %s{};" % (ktype, fname))
            elif ktype in enums:
                a("    %s %s = %s;" % (ktype, fname, cpp_default(cls, fname, ktype, default, enums)))
            else:
                kind = KIND_OF[ktype]
                dv = cpp_default(cls, fname, ktype, default, enums)
                if kind == "opt_int":
                    a("    OptI32 %s = %s;" % (fname, dv))
                else:
                    a("    %s %s = %s;" % (CPP_TYPE[kind], fname, dv))
        a("};")
        a("")

    # fromFlat
    a("namespace detail {")
    a("inline bool flag(int32_t v, bool* out) {")
    a("    if (v != 0 && v != 1) return false;")
    a("    *out = v == 1;")
    a("    return true;")
    a("}")
    a("/** Kotlin Float 經 toDouble 傳來：必須能精確轉回 float（有限值超出 float 範圍、或有捨入就拒收）。 */")
    a("inline bool narrow(double d, float* out) {")
    a("    if (d != d) {")
    a("        *out = std::numeric_limits<float>::quiet_NaN();")
    a("        return true;")
    a("    }")
    a("    const double fmax = static_cast<double>(std::numeric_limits<float>::max());")
    a("    if ((d > fmax || d < -fmax) && d != std::numeric_limits<double>::infinity() &&")
    a("        d != -std::numeric_limits<double>::infinity()) {")
    a("        return false;")
    a("    }")
    a("    const float f = static_cast<float>(d);")
    a("    if (static_cast<double>(f) != d) return false;")
    a("    *out = f;")
    a("    return true;")
    a("}")
    a("}  // namespace detail")
    a("")
    a("/**")
    a(" * 攤平 → 結構（ParamsFlat.toFlat 的反向）。長度不對、Boolean 不是 0／1、enum 超出範圍、Int? 沒有值卻帶非 0 值、")
    a(" * Float 欄位轉不回 float ⇒ 回 false（out 內容不定）。")
    a(" */")
    a("inline bool fromFlat(const int32_t* ints, int32_t nInts, const double* dbls, int32_t nDbls, %s* out) {" % ROOT_CLASS)
    a("    if (out == nullptr || nInts != kIntSlots || nDbls != kDoubleSlots) return false;")
    a("    if ((kIntSlots > 0 && ints == nullptr) || (kDoubleSlots > 0 && dbls == nullptr)) return false;")
    a("    %s& p = *out;" % ROOT_CLASS)
    for lf in leaves:
        acc = "p." + lf.path
        if lf.kind == "int":
            a("    %s = ints[%d];" % (acc, lf.islot))
        elif lf.kind == "bool":
            a("    if (!detail::flag(ints[%d], &%s)) return false;" % (lf.islot, acc))
        elif lf.kind == "enum":
            a("    if (ints[%d] < 0 || ints[%d] >= k%sCount) return false;" % (lf.islot, lf.islot, lf.enum))
            a("    %s = static_cast<%s>(ints[%d]);" % (acc, lf.enum, lf.islot))
        elif lf.kind == "opt_int":
            a("    if (!detail::flag(ints[%d], &%s.present)) return false;" % (lf.islot, acc))
            a("    if (!%s.present && ints[%d] != 0) return false;" % (acc, lf.islot + 1))
            a("    %s.value = ints[%d];" % (acc, lf.islot + 1))
        elif lf.kind == "double":
            a("    %s = dbls[%d];" % (acc, lf.dslot))
        elif lf.kind == "float":
            a("    if (!detail::narrow(dbls[%d], &%s)) return false;" % (lf.dslot, acc))
    a("    return true;")
    a("}")
    a("")
    a("/** 結構 → 攤平（ints 長 kIntSlots、dbls 長 kDoubleSlots）。 */")
    a("inline void toFlat(const %s& p, int32_t* ints, double* dbls) {" % ROOT_CLASS)
    a("    (void)ints;")
    a("    (void)dbls;")
    for lf in leaves:
        acc = "p." + lf.path
        if lf.kind == "int":
            a("    ints[%d] = %s;" % (lf.islot, acc))
        elif lf.kind == "bool":
            a("    ints[%d] = %s ? 1 : 0;" % (lf.islot, acc))
        elif lf.kind == "enum":
            a("    ints[%d] = static_cast<int32_t>(%s);" % (lf.islot, acc))
        elif lf.kind == "opt_int":
            a("    ints[%d] = %s.present ? 1 : 0;" % (lf.islot, acc))
            a("    ints[%d] = %s.present ? %s.value : 0;" % (lf.islot + 1, acc, acc))
        elif lf.kind == "double":
            a("    dbls[%d] = %s;" % (lf.dslot, acc))
        elif lf.kind == "float":
            a("    dbls[%d] = static_cast<double>(%s);" % (lf.dslot, acc))
    a("}")
    a("")

    # 逐欄存取（測試用）
    a("// ── 逐欄存取（測試用：直接讀寫結構成員、不經攤平槽位，對照 Kotlin 端以反射逐欄比）────────────────")
    a("")
    a("enum class Kind : int32_t { Int = 0, Double = 1, Float = 2, Bool = 3, OptInt = 4, Enum = 5 };")
    a("")
    a("struct Field {")
    a("    const char* name;   // Kotlin 的屬性路徑（巢狀以 . 連接）")
    a("    Kind kind;")
    a("    int32_t intSlot;    // ints 的槽（Double／Float 為 -1）")
    a("    int32_t doubleSlot; // dbls 的槽（其餘為 -1）")
    a("};")
    a("")
    kind_name = {"int": "Int", "double": "Double", "float": "Float", "bool": "Bool", "opt_int": "OptInt", "enum": "Enum"}
    a("constexpr Field kFields[kFieldCount] = {")
    for lf in leaves:
        a('    {"%s", Kind::%s, %d, %d},' % (lf.path, kind_name[lf.kind],
                                           -1 if lf.islot is None else lf.islot, -1 if lf.dslot is None else lf.dslot))
    a("};")
    a("")
    a("/** 一個欄位的值：Int／Bool／Enum／OptInt 的值在 i（OptInt 另有 present），Double／Float 在 d（Float 轉成 double）。 */")
    a("struct Value {")
    a("    int64_t i;")
    a("    double d;")
    a("    bool present;")
    a("};")
    a("")
    a("/** 第 idx 個欄位（kFields 的順序）的值；idx 超出範圍回 false。 */")
    a("inline bool getField(const %s& p, int32_t idx, Value* v) {" % ROOT_CLASS)
    a("    *v = Value{0, 0.0, true};")
    a("    switch (idx) {")
    for k, lf in enumerate(leaves):
        acc = "p." + lf.path
        if lf.kind == "int":
            a("        case %d: v->i = %s; return true;" % (k, acc))
        elif lf.kind == "bool":
            a("        case %d: v->i = %s ? 1 : 0; return true;" % (k, acc))
        elif lf.kind == "enum":
            a("        case %d: v->i = static_cast<int32_t>(%s); return true;" % (k, acc))
        elif lf.kind == "opt_int":
            a("        case %d: v->present = %s.present; v->i = %s.value; return true;" % (k, acc, acc))
        elif lf.kind == "double":
            a("        case %d: v->d = %s; return true;" % (k, acc))
        elif lf.kind == "float":
            a("        case %d: v->d = static_cast<double>(%s); return true;" % (k, acc))
    a("        default: return false;")
    a("    }")
    a("}")
    a("")
    a("/** 設第 idx 個欄位；值不合該型別（Bool 不是 0／1、enum 超出範圍、Int 超出 int32、Float 轉不回 float）回 false。 */")
    a("inline bool setField(%s* p, int32_t idx, const Value& v) {" % ROOT_CLASS)
    a("    const bool intOk = v.i >= INT32_MIN && v.i <= INT32_MAX;")
    a("    switch (idx) {")
    for k, lf in enumerate(leaves):
        acc = "p->" + lf.path
        if lf.kind == "int":
            a("        case %d: if (!intOk) return false; %s = static_cast<int32_t>(v.i); return true;" % (k, acc))
        elif lf.kind == "bool":
            a("        case %d: if (v.i != 0 && v.i != 1) return false; %s = v.i == 1; return true;" % (k, acc))
        elif lf.kind == "enum":
            a("        case %d: if (v.i < 0 || v.i >= k%sCount) return false; %s = static_cast<%s>(v.i); return true;"
              % (k, lf.enum, acc, lf.enum))
        elif lf.kind == "opt_int":
            a("        case %d: if (!intOk || (!v.present && v.i != 0)) return false; %s = OptI32{v.present, static_cast<int32_t>(v.i)}; return true;"
              % (k, acc))
        elif lf.kind == "double":
            a("        case %d: %s = v.d; return true;" % (k, acc))
        elif lf.kind == "float":
            a("        case %d: return detail::narrow(v.d, &%s);" % (k, acc))
    a("        default: return false;")
    a("    }")
    a("}")
    a("")
    a("}  // namespace params")
    a("}  // namespace nr")
    return "\n".join(L) + "\n"


# ── 產生 Kotlin ─────────────────────────────────────────────────────────

def kt_ctor(classes, enums, cls, prefix, leaves_by_path, indent):
    """巢狀建構子呼叫（具名參數）。"""
    pad = " " * indent
    lines = ["%s(" % cls]
    for fname, ktype, _ in classes[cls]:
        path = prefix + fname
        if ktype in classes:
            inner = kt_ctor(classes, enums, ktype, path + ".", leaves_by_path, indent + 4)
            lines.append("%s    %s = %s," % (pad, fname, inner))
            continue
        lf = leaves_by_path[path]
        if lf.kind == "int":
            expr = "i[%d]" % lf.islot
        elif lf.kind == "bool":
            expr = "flag(i[%d], \"%s\")" % (lf.islot, path)
        elif lf.kind == "enum":
            expr = "%s.entries.getOrNull(i[%d]) ?: bad(\"%s\")" % (lf.enum, lf.islot, path)
        elif lf.kind == "opt_int":
            expr = "opt(i[%d], i[%d], \"%s\")" % (lf.islot, lf.islot + 1, path)
        elif lf.kind == "double":
            expr = "d[%d]" % lf.dslot
        elif lf.kind == "float":
            expr = "narrow(d[%d], \"%s\")" % (lf.dslot, path)
        lines.append("%s    %s = %s," % (pad, fname, expr))
    lines.append("%s)" % pad)
    return "\n".join(lines)


def gen_kotlin(classes, enums, leaves, used_enums, ni, nd, h64):
    by_path = {lf.path: lf for lf in leaves}
    signed = h64 - (1 << 64) if h64 >= 1 << 63 else h64
    L = []
    a = L.append
    a("// " + GEN_NOTE + "（改 Params.kt 後重跑；CI 的 native-guard 重跑後 git diff 必須是空的）。")
    a("package li.joye.yakuyomi.nightread")
    a("")
    a("/**")
    a(" * [NightReadParams] 的攤平格式：JNI 只傳一個 IntArray 與一個 DoubleArray，C++ 端（native/core/params_gen.h 的")
    a(" * `nr::params::fromFlat`）照同一張欄位表還原成結構。欄位順序＝Params.kt 的宣告順序，巢狀參數組在宣告的位置展開。")
    a(" * - ints：Int、Boolean（0／1）、enum（ordinal）、Int?（兩格：有沒有值、值；沒有值時值那格是 0）")
    a(" * - doubles：Double、Float（轉成 double，精確）")
    a(" * [SCHEMA_HASH] 只看欄位的名稱、型別與順序（定義見 gen_params.py 檔頭），C++ 端同一個值；兩邊不同＝不同版的 .so，")
    a(" * [NightNative.available] 會回 false。")
    a(" */")
    a("internal object ParamsFlat {")
    a("    /** 葉欄位數。 */")
    a("    const val FIELD_COUNT: Int = %d" % len(leaves))
    a("")
    a("    /** ints 的長度。 */")
    a("    const val INT_SLOTS: Int = %d" % ni)
    a("")
    a("    /** doubles 的長度。 */")
    a("    const val DOUBLE_SLOTS: Int = %d" % nd)
    a("")
    a("    /** schema 雜湊（0x%016x）。 */" % h64)
    a("    const val SCHEMA_HASH: Long = %dL" % signed)
    a("")
    a("    /** 欄位型別代碼（[FIELD_KINDS]；與 C++ `nr::params::Kind` 相同）。 */")
    a("    const val KIND_INT: Int = 0")
    a("    const val KIND_DOUBLE: Int = 1")
    a("    const val KIND_FLOAT: Int = 2")
    a("    const val KIND_BOOL: Int = 3")
    a("    const val KIND_OPT_INT: Int = 4")
    a("    const val KIND_ENUM: Int = 5")
    a("")
    a("    /** schema 雜湊用的正規字串的開頭（版本標記）。 */")
    a("    const val SCHEMA_TAG: String = \"%s\"" % SCHEMA_TAG)
    a("")
    kind_code = {"int": 0, "double": 1, "float": 2, "bool": 3, "opt_int": 4, "enum": 5}
    a("    /** 欄位路徑（巢狀以 . 連接），依攤平順序。 */")
    a("    val FIELD_NAMES: List<String> = listOf(")
    for lf in leaves:
        a("        \"%s\"," % lf.path)
    a("    )")
    a("")
    a("    /** 欄位型別代碼，與 [FIELD_NAMES] 同序。 */")
    a("    val FIELD_KINDS: IntArray = intArrayOf(")
    row = []
    for lf in leaves:
        row.append(str(kind_code[lf.kind]))
        if len(row) == 32:
            a("        " + ", ".join(row) + ",")
            row = []
    if row:
        a("        " + ", ".join(row) + ",")
    a("    )")
    a("")
    a("    /** 欄位的槽：Double／Float 是 doubles 的索引，其餘是 ints 的索引（Int? 佔這格與下一格），與 [FIELD_NAMES] 同序。 */")
    a("    val FIELD_SLOTS: IntArray = intArrayOf(")
    row = []
    for lf in leaves:
        row.append(str(lf.dslot if lf.dslot is not None else lf.islot))
        if len(row) == 32:
            a("        " + ", ".join(row) + ",")
            row = []
    if row:
        a("        " + ", ".join(row) + ",")
    a("    )")
    a("")
    a("    /** enum 欄位的型別名（schema 雜湊用），與 [FIELD_NAMES] 同序；不是 enum 的是 null。 */")
    a("    val FIELD_ENUMS: List<String?> = listOf(")
    for lf in leaves:
        a("        %s," % ("\"%s\"" % lf.enum if lf.kind == "enum" else "null"))
    a("    )")
    a("")
    a("    /** 參數用到的 enum：名稱 → 項目（ordinal 順序）。 */")
    a("    val ENUMS: Map<String, List<String>> = mapOf(")
    for e in used_enums:
        a("        \"%s\" to %s.entries.map { it.name }," % (e, e))
    a("    )")
    a("")
    a("    /** [p] 攤平進 [i]（長 [INT_SLOTS]）與 [d]（長 [DOUBLE_SLOTS]）。 */")
    a("    fun toFlat(p: NightReadParams, i: IntArray, d: DoubleArray) {")
    a("        require(i.size == INT_SLOTS && d.size == DOUBLE_SLOTS) { \"ParamsFlat.toFlat：陣列長度要是 $INT_SLOTS／$DOUBLE_SLOTS\" }")
    for lf in leaves:
        acc = "p." + lf.path
        if lf.kind == "int":
            a("        i[%d] = %s" % (lf.islot, acc))
        elif lf.kind == "bool":
            a("        i[%d] = if (%s) 1 else 0" % (lf.islot, acc))
        elif lf.kind == "enum":
            a("        i[%d] = %s.ordinal" % (lf.islot, acc))
        elif lf.kind == "opt_int":
            a("        i[%d] = if (%s != null) 1 else 0" % (lf.islot, acc))
            a("        i[%d] = %s ?: 0" % (lf.islot + 1, acc))
        elif lf.kind == "double":
            a("        d[%d] = %s" % (lf.dslot, acc))
        elif lf.kind == "float":
            a("        d[%d] = %s.toDouble()" % (lf.dslot, acc))
    a("    }")
    a("")
    a("    /** [toFlat] 的新陣列版：first＝ints、second＝doubles。 */")
    a("    fun toFlat(p: NightReadParams): Pair<IntArray, DoubleArray> {")
    a("        val i = IntArray(INT_SLOTS)")
    a("        val d = DoubleArray(DOUBLE_SLOTS)")
    a("        toFlat(p, i, d)")
    a("        return i to d")
    a("    }")
    a("")
    a("    /**")
    a("     * 攤平 → [NightReadParams]（[toFlat] 的反向）。長度不對、Boolean 不是 0／1、enum 超出範圍、Int? 沒有值卻帶非 0 值、")
    a("     * Float 欄位轉不回 float ⇒ [IllegalArgumentException]。")
    a("     */")
    a("    fun fromFlat(i: IntArray, d: DoubleArray): NightReadParams {")
    a("        require(i.size == INT_SLOTS && d.size == DOUBLE_SLOTS) { \"ParamsFlat.fromFlat：陣列長度要是 $INT_SLOTS／$DOUBLE_SLOTS\" }")
    a("        return " + kt_ctor(classes, enums, ROOT_CLASS, "", by_path, 8))
    a("    }")
    a("")
    a("    private fun bad(field: String): Nothing = throw IllegalArgumentException(\"ParamsFlat：欄位 $field 的值不合法\")")
    a("")
    a("    private fun flag(v: Int, field: String): Boolean = when (v) {")
    a("        0 -> false")
    a("        1 -> true")
    a("        else -> bad(field)")
    a("    }")
    a("")
    a("    private fun opt(has: Int, v: Int, field: String): Int? = if (flag(has, field)) v else if (v == 0) null else bad(field)")
    a("")
    a("    private fun narrow(v: Double, field: String): Float {")
    a("        val f = v.toFloat()")
    a("        if (f.toDouble() != v && !v.isNaN()) bad(field)")
    a("        return f")
    a("    }")
    a("}")
    return "\n".join(L) + "\n"


def sync_nr_h(rules_version):
    with open(NR_H, encoding="utf-8") as f:
        src = f.read()
    pat = re.compile(r"^#define NR_RULES_VERSION \d+$", re.M)
    if len(pat.findall(src)) != 1:
        raise GenError("nr.h 要剛好有一行 `#define NR_RULES_VERSION <n>`")
    return pat.sub("#define NR_RULES_VERSION %d" % rules_version, src)


def main(argv):
    check = "--check" in argv
    with open(PARAMS_KT, encoding="utf-8") as f:
        src = strip_comments(f.read())
    classes = parse_data_classes(src)
    enums = parse_enums(src)
    order, leaves, used_enums, ni, nd = build_model(classes, enums)
    h64 = schema_hash(leaves, enums, used_enums)
    rules_version = parse_rules_version()
    digests, delivered = parse_digests(rules_version)

    outputs = {
        OUT_H: gen_header(classes, enums, order, leaves, used_enums, ni, nd, h64, rules_version, digests, delivered),
        OUT_KT: gen_kotlin(classes, enums, leaves, used_enums, ni, nd, h64),
        NR_H: sync_nr_h(rules_version),
    }
    # Kotlin 的區塊註解會巢狀：註解內文（含路徑萬用字元）出現 /* 會吞掉後面整個檔。只准行首的 KDoc 開頭。
    for line in outputs[OUT_KT].splitlines():
        if "/*" in line and not re.fullmatch(r"\s*/\*\*.*", line) or line.count("/*") > 1:
            raise GenError("產生的 Kotlin 有行內的 /*（Kotlin 註解會巢狀吞掉後文）：%r" % line)
    stale = []
    for path, text in outputs.items():
        old = None
        if os.path.exists(path):
            with open(path, encoding="utf-8", newline="") as f:
                old = f.read()
        if old != text:
            stale.append(os.path.relpath(path, ROOT))
            if not check:
                with open(path, "w", encoding="utf-8", newline="\n") as f:
                    f.write(text)
    print("gen_params：%d 個欄位（ints %d、doubles %d）、schema 0x%016x、規則版本 %d、摘要 %d 筆"
          % (len(leaves), ni, nd, h64, rules_version, len(digests)))
    if stale:
        print(("過期（未重產）：" if check else "已更新：") + "、".join(stale))
        return 1 if check else 0
    print("全部是最新的")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except GenError as e:
        print("gen_params：" + str(e), file=sys.stderr)
        sys.exit(2)
