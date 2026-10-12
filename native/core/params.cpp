// 夜讀 C++ 核心：參數（params_gen.h 由 native/tools/gen_params.py 從 Params.kt 產生）。
// 這裡只有 C API 的 schema 握手與幾道編譯期檢查；攤平／還原與欄位表都在產生的標頭裡（inline）。
#include "nightread/nr.h"
#include "params_gen.h"

static_assert(NR_RULES_VERSION == nr::params::kRulesVersion,
              "nr.h 的 NR_RULES_VERSION 與 params_gen.h 不同：重跑 native/tools/gen_params.py");
static_assert(nr::params::kRulesDigestCount == nr::params::kRulesVersion &&
                  nr::params::kRulesDigests[nr::params::kRulesDigestCount - 1].version == nr::params::kRulesVersion,
              "規則版本摘要表要從 1 連續編到 kRulesVersion（RulesVersionGuardTest.digests）");
static_assert(nr::params::kFieldCount > 0 && nr::params::kIntSlots > 0 && nr::params::kDoubleSlots > 0, "參數表是空的");

extern "C" int64_t nr_params_schema(void) { return static_cast<int64_t>(nr::params::kSchemaHash); }
