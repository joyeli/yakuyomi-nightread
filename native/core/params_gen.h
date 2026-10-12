// 由 native/tools/gen_params.py 從 nightread/src/main/kotlin/li/joye/yakuyomi/nightread/Params.kt 產生，不要手改（改 Params.kt 後重跑 gen_params.py；CI 的 native-guard 重跑後 git diff 必須是空的）。
//
// 夜讀參數的 C++ 鏡像：結構、欄位名、型別、預設值與 Kotlin 的 NightReadParams 一一對應。C++ 不自己決定參數：
// 產品路徑一律由 Kotlin 攤平（ParamsFlat.toFlat）經 JNI 傳進來、在這裡 fromFlat 還原；預設值只給原生測試用。
// 攤平格式與 schema 雜湊的定義見 gen_params.py 檔頭。
#pragma once

#include <cstdint>
#include <cstring>
#include <limits>

namespace nr {
namespace params {

/** 規則版本（＝NightRead.RULES_VERSION；nr.h 的 NR_RULES_VERSION 由同一支產生器同步）。 */
constexpr int32_t kRulesVersion = 4;
/** 葉欄位數（巢狀參數組展開後）。 */
constexpr int32_t kFieldCount = 379;
/** 攤平後 ints／doubles 的長度。 */
constexpr int32_t kIntSlots = 235;
constexpr int32_t kDoubleSlots = 145;
/** 攤平格式的 schema 雜湊（＝ParamsFlat.SCHEMA_HASH）。 */
constexpr uint64_t kSchemaHash = 0xf2f49f43294490f2ULL;

/** 規則版本 → 產品兩檔 8 頁摘要（RulesVersionGuardTest.digests）；delivered＝已交給使用者（RulesVersionGuardTest.delivered），摘要凍結。 */
struct RulesDigest {
    int32_t version;
    const char* sha256;
    bool delivered;
};
constexpr RulesDigest kRulesDigests[] = {
    {1, "852996dcb2d3dc5b0cf674feee343bfce9e3c115e6d3d2a8db014b5f2294bab0", true},
    {2, "78a821623962844b53f7ff4c5f395c5dcc514df659a7ed3ef2293bce6dabbec7", true},
    {3, "4aa60a1bb49a7c0a46380e1ca4140ed801a66a06fe3f701e1c433eccc3c446a7", true},
    {4, "31f2ed4d88e7d85fc8314e958ff2c6483d85d884427115f24c12efa196b32e85", true},
};
constexpr int32_t kRulesDigestCount = 4;

enum class StickerMode : int32_t {
    ALL = 0,
    SIMPLE = 1,
    PLAIN = 2,
};
constexpr int32_t kStickerModeCount = 3;

/** Kotlin 的 Int?：present＝false 時 value 一律 0。 */
struct OptI32 {
    bool present;
    int32_t value;
};

struct BubbleSealParams {
    int32_t r = 1;
    double charMax = 0.25;
    double textIn = 0.5;
    int32_t restMin = 50;
    int32_t maxGaps = 8;
    OptI32 cutGeo = {false, 0};
};

struct MoreRuleParams {
    bool enabled = false;
    double tOut = 5.0;
    double tIn = 2.0;
    double faintMax = 0.3;
    double roughCap = 40.0;
    double charMax = 0.67;
    double hyst = 0.25;
    int32_t holeMinArea = 8;
    int32_t holeDarkMax = 200;
    int32_t median = 5;
    int32_t explainDil = 7;
    int32_t nearDil = 9;
    int32_t ringR = 7;
    int32_t halo = 5;
    int32_t winPad = 16;
    bool keepDarkStroke = true;
    bool edgeSeedFallback = true;
    int32_t edgeBand = 3;
    int32_t edgeReach = 4;
};

struct SeparatorParams {
    double lenFrac = 0.15;
    int32_t nearWhiteR = 3;
    double thStep = 0.5;
    int32_t walkWin0 = 4;
    int32_t walkWin = 2;
    int32_t gap = 8;
    double fillMin = 0.85;
    int32_t peakNmsT = 3;
    int32_t peakNmsR = 6;
    int32_t peakMax = 800;
    double peakVoteFrac = 0.8;
    double dedupeAng = 2.0;
    double dedupeOff = 6.0;
    double groupAng = 1.0;
    double groupOff = 6.0;
    double pairAng = 5.0;
    int32_t gapMin = 6;
    int32_t edgeSkip = 4;
    double profWhite = 0.9;
    double profWhiteBridge = 0.98;
    int32_t shortBridge = 24;
    int32_t profGapFill = 2;
    double passMin = 0.6;
    double stripExtFrac = 0.15;
    double bridgeFrac = 0.30;
    double edgeMadMax = 1.0;
    int32_t textDil = 3;
    double textProfMax = 0.2;
    double familyAng = 10.0;
    double familyReach = 1.5;
    double wmaxFrac = 0.09;
    double ovlFrac = 0.5;
    int32_t extDarkSkip = 8;
    int32_t bubLineR = 7;
    double bubLineMax = 0.5;
    int32_t netTouch = 2;
    int32_t netThick = 5;
    double netExtFrac = 0.15;
    double marginRunFrac = 0.015;
    double marginLightFrac = 0.02;
    int32_t marginLightMin = 3;
    int32_t farProbe = 14;
    int32_t marginOkTh = 200;
    double marginFrac = 0.12;
    int32_t flR = 3;
    int32_t flHitR = 8;
    double marginEdgeAng = 30.0;
    double marginAxisAng = 5.0;
    double marginFillMin = 0.95;
    bool marginOcc = true;
    bool marginClose = true;
    double occPieceFrac = 0.5;
    double occGapFrac = 0.05;
    int32_t marginHaloR = 2;
    int32_t bubDil = 7;
    double pairSubMax = 0.5;
    int32_t minCc = 150;
};

struct BleedParams {
    int32_t ringR = 5;
    int32_t ringEdge = 4;
    int32_t piecePad = 8;
    int32_t frDil = 9;
    int32_t spDil = 5;
    int32_t bbR = 6;
    int32_t txR = 6;
    int32_t chR = 8;
    int32_t textBoxPad = 10;
    int32_t touchPx = 2;
    double netFrac = 0.03;
    double txtKeep = 0.15;
    double frameKeep = 0.6;
    double islandBbMax = 0.3;
    double enclosedMax = 0.15;
    double artDrop = 0.45;
    int32_t marginOk = 200;
    double marginFrac = 0.12;
    double marginLightMaxFrac = 0.02;
    int32_t marginLightMin = 3;
    int32_t marginMinRows = 30;
    int32_t marginNeuLook = 8;
    int32_t marginMinDepth = 4;
    double marginCover = 0.7;
    double marginAng = 5.0;
    int32_t marginDark = 190;
    int32_t marginLineMax = 12;
    int32_t mlineMinRun = 30;
};

struct RingParams {
    bool enabled = true;
    int32_t rOut = 18;
    int32_t seedMin = 200;
    int32_t gvGap = 24;
    int32_t rawClose = 6;
    int32_t steps = 64;
    int32_t inkBox = 5;
    int32_t inkSum = 957;
    int32_t inkMaxGray = 200;
    int32_t holeMax = 256;
    int32_t evIn = 8;
    int32_t evOut = 3;
    int32_t rayFrom = 3;
    int32_t rayTo = 24;
    double raySigma = 2.0;
    int32_t gapClose = 6;
    int32_t breakPad = 12;
    int32_t faintGray = 225;
    int32_t faintInkPad = 2;
    int32_t faintWin = 21;
    int32_t faintMin = 7;
    int32_t open = 3;
    bool seedConnected = true;
};

struct EffectLineParams {
    double segSd = 1.2;
    int32_t segMin = 8;
    double cosJoin = 0.9945218953682733;
    double perp = 2.5;
    double gap = 20.0;
    double lineMin = 30.0;
    double cosVp = 0.9986295347545738;
    int32_t vpTop = 120;
    int32_t maxFamilies = 4;
    int32_t minMembers = 8;
    double cosSpread = 0.8660254037844387;
    double taper = 1.3;
    double taperFrac = 0.45;
    int32_t freeMin = 2;
    double freeFrac = 0.1;
    int32_t ray0 = 4;
    int32_t ray1 = 16;
    int32_t rayG = 215;
    int32_t terr = 48;
    double cosAtol2 = 0.9396926207859084;
    double coh = 0.5;
    double epsJ = 0.065536;
    double si = 4.0;
    int32_t vpNear = 24;
    double mark = 16.0;
    int32_t lineR = 3;
    int32_t excl = 4;
    int32_t exclT = 14;
    double agree = 0.85;
    double resid = 25.0;
    int32_t nMem = 4;
    int32_t lineV = 170;
    int32_t grow = 120;
    int32_t halo = 8;
    int32_t erMin = 30;
    int32_t minPart = 400;
    int32_t sparkIso = 4;
    double sparkMin = 5.0;
    double sparkMax = 60.0;
    int32_t sparkDil = 3;
    int32_t sparkV = 170;
    double longStraight = 2.0;
};

struct ObjectRuleParams {
    bool enabled = true;
    bool veto = true;
    bool lightFill = true;
    bool fxLines = true;
    bool fxSparks = true;
    EffectLineParams fx{};
    bool personFaint = true;
    int32_t pfTouch = 5;
    int32_t pfBridge = 2;
    int32_t pfReach = 64;
    int32_t pfHalo = 10;
    bool textNeedsRegion = true;
    bool pfShape = true;
    int32_t pfClose = 10;
    int32_t pfMargin = 3;
    int32_t pfHole = 2500;
    int32_t pfLinePct = 104;
    int32_t pfMark = 3;
    int32_t pfStub = 16;
    int32_t pfPair = 20;
    bool textStroke = true;
    int32_t tsInk = 128;
    int32_t tsMin = 5;
    int32_t tsG = 30;
    int32_t tsRough = 12;
    bool tsBandClean = true;
    bool seam = true;
    int32_t seamR = 3;
    int32_t seamSpark = 4;
    int32_t seamG = 200;
    bool inpaintIslands = true;
    int32_t islandInpD = 6;
    int32_t islandInpPct = 30;
    double vetoEpm = 25.0;
    int32_t vetoRc = 5;
    int32_t vetoMinArea = 300;
    int32_t vetoEvDil = 2;
    int32_t vetoBackPad = 2;
    int32_t darkG = 63;
    int32_t lightTh = 150;
    double areaMin = 0.002;
    double lineMax = 20.0;
    double can4Max = 12.0;
    double fitMax = 9.0;
    double toneMax = 215.0;
    double toneGrad = 0.8;
    double chromaMax = 6.0;
    double thickMin = 24.0;
    int32_t marks = 4;
    double marksDen = 4.0;
    int32_t rLoc = 24;
    double coreMin = 0.0008;
    int32_t seal = 4;
    double ctxEpm = 25.0;
    int32_t ctxWhite = 230;
    double islandMax = 0.015;
    int32_t islandTouch = 12;
    int32_t islandTouchMin = 20;
    double hugMax = 0.6;
    int32_t longLen = 80;
    int32_t longR = 40;
    int32_t bhTh = 20;
    int32_t lineR = 12;
    int32_t brightTh = 12;
    int32_t brightLrNum = 36;
    int32_t brightLrDen = 5;
    int32_t dot = 10;
    int32_t dotBright = 8;
    int32_t evMinArea = 15;
    int32_t cannyLo = 15;
    int32_t cannyHi = 40;
    int32_t canny4Lo = 10;
    int32_t canny4Hi = 25;
};

struct NightReadParams {
    int32_t bg = 16;
    int32_t ink = 240;
    int32_t edgeInk = 240;
    int32_t stroke = 1;
    int32_t strokeObjV = 220;
    int32_t sceneFloor = 8;
    int32_t dimCeil = 140;
    float glowStrength = 55.0f;
    int32_t glowCap = 112;
    int32_t whiteTh = 235;
    int32_t inkDarkTh = 128;
    int32_t bubblePad = 40;
    double gutterMinAreaFrac = 0.0006;
    int32_t paperPeakLo = 200;
    int32_t paperNormMin = 245;
    double paperNormChromaMax = 8.0;
    int32_t frameLineLDiv = 5;
    int32_t frameDarkTh = 100;
    double frameMinEach = 1.0;
    double frameMinSum = 4.5;
    double framelessMarginDepth = 0.12;
    int32_t coreR = 26;
    double deepEdgeFrac = 0.07;
    double inPanelCoreFrac = 0.15;
    double inPanelCoreDeep = 0.25;
    double deepInkDeep = 0.5;
    double deepInkRatio = 0.02;
    double holeMaxFrac = 0.01;
    double safeGutterDepth = 0.12;
    int32_t gfcDilate = 4;
    double gfcCloseFrac = 0.30;
    double textureTh = 0.10;
    double textureHi = 0.15;
    int32_t textureWin = 31;
    int32_t texturePad = 4;
    int32_t textureMinArea = 1500;
    int32_t textureFrameDil = 9;
    int32_t textureSegDil = 21;
    int32_t textureBubbleNear = 25;
    int32_t textureBubbleOutline = 8;
    int32_t textureClose = 15;
    double textureRegionMin = 0.002;
    double bubbleCompMaxFrac = 0.07;
    double bubbleLocalK = 4.0;
    double bubbleCoreMinFrac = 0.003;
    int32_t bubbleNeckR = 8;
    double safeBubbleRatio = 6.0;
    double bubbleCleanWins = 0.005;
    double bubbleCleanTextMax = 0.8;
    bool bubbleCleanInkHoles = true;
    bool bubbleGuardRaw = true;
    double bubbleConfirmTextIn = 0.5;
    bool bubbleLeak = true;
    int32_t bubbleLeakRing = 3;
    int32_t bubbleLeakRange = 60;
    int32_t bubbleLeakWin = 9;
    double bubbleLeakEdgeMax = 0.85;
    int32_t bubbleLeakRingMin = 100;
    int32_t bubbleLeakMinArea = 100;
    int32_t bubbleRestNear = 20;
    double bubbleOutlineMin = 0.85;
    int32_t bubbleOutlineDist = 6;
    int32_t bubbleOutlineMinPx = 100;
    int32_t bubbleOutlinePad = 8;
    BubbleSealParams bubbleSeal{};
    double pbCovMax = 0.85;
    int32_t pbNeckR = 10;
    double pbGrowFrac = 0.6;
    int32_t pbAuraR = 12;
    int32_t pbAuraThick = 6;
    int32_t pbAuraMinArea = 800;
    double strokeObjFrac = 0.0035;
    int32_t strokeObjMin = 4;
    int32_t strokeObjMax = 7;
    double stickerMinFrac = 0.01;
    int32_t figNoiseArea = 40;
    int32_t figNoiseClose = 11;
    double stickerFigMin = 0.1;
    double stickerFigMax = 0.85;
    int32_t stickerThinR = 4;
    double stickerThinMax = 0.45;
    double stickerChromaMax = 6.0;
    int32_t stickerEatenR = 5;
    double stickerEatenDens = 0.35;
    double stickerEatenMax = 0.06;
    double stickerEatenHard = 0.3;
    double stickerTextBgMin = 0.1;
    double stickerTextMax = 0.55;
    int32_t stickerTextOnPad = 8;
    double stickerSmallArea = 0.02;
    int32_t stickerNeckR = 8;
    double stickerCoreMin = 0.03;
    double stickerProtectEatenMin = 0.002;
    int32_t stickerProtectDilate = 6;
    double faintOfFMax = 0.62;
    int32_t faintG = 160;
    int32_t frameHugDilate = 5;
    double frameHugThick = 3.0;
    double frameHugMin = 0.25;
    double frameHugStrong = 0.4;
    double hugSideMin = 0.5;
    double promotedTextOnMax = 0.3;
    StickerMode stickerMode = StickerMode::ALL;
    double stickerRoughMax = 10.0;
    double stickerSimpleMinFrac = 0.005;
    int32_t stickerPlainRingR = 7;
    double stickerPlainArtMax = 0.05;
    int32_t stickerPlainFrameDil = 7;
    double stickerPlainFaintMax = 0.10;
    int32_t stickerPlainFaintHalo = 5;
    MoreRuleParams more{};
    bool pseudoBubbles = true;
    bool harmonize = true;
    int32_t coreNeckR = 12;
    int32_t coreRecoverR = 9;
    double geoRatioMax = 1.6;
    double geoSlack = 40.0;
    int32_t coreReleasePad = 16;
    int32_t charSnap = 10;
    int32_t charSnapPad = 1;
    int32_t maskSmoothMedian = 15;
    double edgeFeather = 0.7;
    int32_t textPad = 2;
    double textGamma = 1.4;
    double textKnee = 0.35;
    int32_t textTopPad = 3;
    int32_t textBackingR = 5;
    int32_t harmonizeZoneCell = 16;
    double harmonizeZoneDark = 0.45;
    double harmonizeInZone = 0.6;
    double harmonizeAreaMax = 0.004;
    double harmonizeCollarInk = 0.3;
    bool separators = true;
    SeparatorParams sep{};
    bool bleedFilter = true;
    BleedParams bleed{};
    RingParams ring{};
    ObjectRuleParams obj{};
};

namespace detail {
inline bool flag(int32_t v, bool* out) {
    if (v != 0 && v != 1) return false;
    *out = v == 1;
    return true;
}
/** Kotlin Float 經 toDouble 傳來：必須能精確轉回 float（有限值超出 float 範圍、或有捨入就拒收）。 */
inline bool narrow(double d, float* out) {
    if (d != d) {
        *out = std::numeric_limits<float>::quiet_NaN();
        return true;
    }
    const double fmax = static_cast<double>(std::numeric_limits<float>::max());
    if ((d > fmax || d < -fmax) && d != std::numeric_limits<double>::infinity() &&
        d != -std::numeric_limits<double>::infinity()) {
        return false;
    }
    const float f = static_cast<float>(d);
    if (static_cast<double>(f) != d) return false;
    *out = f;
    return true;
}
}  // namespace detail

/**
 * 攤平 → 結構（ParamsFlat.toFlat 的反向）。長度不對、Boolean 不是 0／1、enum 超出範圍、Int? 沒有值卻帶非 0 值、
 * Float 欄位轉不回 float ⇒ 回 false（out 內容不定）。
 */
inline bool fromFlat(const int32_t* ints, int32_t nInts, const double* dbls, int32_t nDbls, NightReadParams* out) {
    if (out == nullptr || nInts != kIntSlots || nDbls != kDoubleSlots) return false;
    if ((kIntSlots > 0 && ints == nullptr) || (kDoubleSlots > 0 && dbls == nullptr)) return false;
    NightReadParams& p = *out;
    p.bg = ints[0];
    p.ink = ints[1];
    p.edgeInk = ints[2];
    p.stroke = ints[3];
    p.strokeObjV = ints[4];
    p.sceneFloor = ints[5];
    p.dimCeil = ints[6];
    if (!detail::narrow(dbls[0], &p.glowStrength)) return false;
    p.glowCap = ints[7];
    p.whiteTh = ints[8];
    p.inkDarkTh = ints[9];
    p.bubblePad = ints[10];
    p.gutterMinAreaFrac = dbls[1];
    p.paperPeakLo = ints[11];
    p.paperNormMin = ints[12];
    p.paperNormChromaMax = dbls[2];
    p.frameLineLDiv = ints[13];
    p.frameDarkTh = ints[14];
    p.frameMinEach = dbls[3];
    p.frameMinSum = dbls[4];
    p.framelessMarginDepth = dbls[5];
    p.coreR = ints[15];
    p.deepEdgeFrac = dbls[6];
    p.inPanelCoreFrac = dbls[7];
    p.inPanelCoreDeep = dbls[8];
    p.deepInkDeep = dbls[9];
    p.deepInkRatio = dbls[10];
    p.holeMaxFrac = dbls[11];
    p.safeGutterDepth = dbls[12];
    p.gfcDilate = ints[16];
    p.gfcCloseFrac = dbls[13];
    p.textureTh = dbls[14];
    p.textureHi = dbls[15];
    p.textureWin = ints[17];
    p.texturePad = ints[18];
    p.textureMinArea = ints[19];
    p.textureFrameDil = ints[20];
    p.textureSegDil = ints[21];
    p.textureBubbleNear = ints[22];
    p.textureBubbleOutline = ints[23];
    p.textureClose = ints[24];
    p.textureRegionMin = dbls[16];
    p.bubbleCompMaxFrac = dbls[17];
    p.bubbleLocalK = dbls[18];
    p.bubbleCoreMinFrac = dbls[19];
    p.bubbleNeckR = ints[25];
    p.safeBubbleRatio = dbls[20];
    p.bubbleCleanWins = dbls[21];
    p.bubbleCleanTextMax = dbls[22];
    if (!detail::flag(ints[26], &p.bubbleCleanInkHoles)) return false;
    if (!detail::flag(ints[27], &p.bubbleGuardRaw)) return false;
    p.bubbleConfirmTextIn = dbls[23];
    if (!detail::flag(ints[28], &p.bubbleLeak)) return false;
    p.bubbleLeakRing = ints[29];
    p.bubbleLeakRange = ints[30];
    p.bubbleLeakWin = ints[31];
    p.bubbleLeakEdgeMax = dbls[24];
    p.bubbleLeakRingMin = ints[32];
    p.bubbleLeakMinArea = ints[33];
    p.bubbleRestNear = ints[34];
    p.bubbleOutlineMin = dbls[25];
    p.bubbleOutlineDist = ints[35];
    p.bubbleOutlineMinPx = ints[36];
    p.bubbleOutlinePad = ints[37];
    p.bubbleSeal.r = ints[38];
    p.bubbleSeal.charMax = dbls[26];
    p.bubbleSeal.textIn = dbls[27];
    p.bubbleSeal.restMin = ints[39];
    p.bubbleSeal.maxGaps = ints[40];
    if (!detail::flag(ints[41], &p.bubbleSeal.cutGeo.present)) return false;
    if (!p.bubbleSeal.cutGeo.present && ints[42] != 0) return false;
    p.bubbleSeal.cutGeo.value = ints[42];
    p.pbCovMax = dbls[28];
    p.pbNeckR = ints[43];
    p.pbGrowFrac = dbls[29];
    p.pbAuraR = ints[44];
    p.pbAuraThick = ints[45];
    p.pbAuraMinArea = ints[46];
    p.strokeObjFrac = dbls[30];
    p.strokeObjMin = ints[47];
    p.strokeObjMax = ints[48];
    p.stickerMinFrac = dbls[31];
    p.figNoiseArea = ints[49];
    p.figNoiseClose = ints[50];
    p.stickerFigMin = dbls[32];
    p.stickerFigMax = dbls[33];
    p.stickerThinR = ints[51];
    p.stickerThinMax = dbls[34];
    p.stickerChromaMax = dbls[35];
    p.stickerEatenR = ints[52];
    p.stickerEatenDens = dbls[36];
    p.stickerEatenMax = dbls[37];
    p.stickerEatenHard = dbls[38];
    p.stickerTextBgMin = dbls[39];
    p.stickerTextMax = dbls[40];
    p.stickerTextOnPad = ints[53];
    p.stickerSmallArea = dbls[41];
    p.stickerNeckR = ints[54];
    p.stickerCoreMin = dbls[42];
    p.stickerProtectEatenMin = dbls[43];
    p.stickerProtectDilate = ints[55];
    p.faintOfFMax = dbls[44];
    p.faintG = ints[56];
    p.frameHugDilate = ints[57];
    p.frameHugThick = dbls[45];
    p.frameHugMin = dbls[46];
    p.frameHugStrong = dbls[47];
    p.hugSideMin = dbls[48];
    p.promotedTextOnMax = dbls[49];
    if (ints[58] < 0 || ints[58] >= kStickerModeCount) return false;
    p.stickerMode = static_cast<StickerMode>(ints[58]);
    p.stickerRoughMax = dbls[50];
    p.stickerSimpleMinFrac = dbls[51];
    p.stickerPlainRingR = ints[59];
    p.stickerPlainArtMax = dbls[52];
    p.stickerPlainFrameDil = ints[60];
    p.stickerPlainFaintMax = dbls[53];
    p.stickerPlainFaintHalo = ints[61];
    if (!detail::flag(ints[62], &p.more.enabled)) return false;
    p.more.tOut = dbls[54];
    p.more.tIn = dbls[55];
    p.more.faintMax = dbls[56];
    p.more.roughCap = dbls[57];
    p.more.charMax = dbls[58];
    p.more.hyst = dbls[59];
    p.more.holeMinArea = ints[63];
    p.more.holeDarkMax = ints[64];
    p.more.median = ints[65];
    p.more.explainDil = ints[66];
    p.more.nearDil = ints[67];
    p.more.ringR = ints[68];
    p.more.halo = ints[69];
    p.more.winPad = ints[70];
    if (!detail::flag(ints[71], &p.more.keepDarkStroke)) return false;
    if (!detail::flag(ints[72], &p.more.edgeSeedFallback)) return false;
    p.more.edgeBand = ints[73];
    p.more.edgeReach = ints[74];
    if (!detail::flag(ints[75], &p.pseudoBubbles)) return false;
    if (!detail::flag(ints[76], &p.harmonize)) return false;
    p.coreNeckR = ints[77];
    p.coreRecoverR = ints[78];
    p.geoRatioMax = dbls[60];
    p.geoSlack = dbls[61];
    p.coreReleasePad = ints[79];
    p.charSnap = ints[80];
    p.charSnapPad = ints[81];
    p.maskSmoothMedian = ints[82];
    p.edgeFeather = dbls[62];
    p.textPad = ints[83];
    p.textGamma = dbls[63];
    p.textKnee = dbls[64];
    p.textTopPad = ints[84];
    p.textBackingR = ints[85];
    p.harmonizeZoneCell = ints[86];
    p.harmonizeZoneDark = dbls[65];
    p.harmonizeInZone = dbls[66];
    p.harmonizeAreaMax = dbls[67];
    p.harmonizeCollarInk = dbls[68];
    if (!detail::flag(ints[87], &p.separators)) return false;
    p.sep.lenFrac = dbls[69];
    p.sep.nearWhiteR = ints[88];
    p.sep.thStep = dbls[70];
    p.sep.walkWin0 = ints[89];
    p.sep.walkWin = ints[90];
    p.sep.gap = ints[91];
    p.sep.fillMin = dbls[71];
    p.sep.peakNmsT = ints[92];
    p.sep.peakNmsR = ints[93];
    p.sep.peakMax = ints[94];
    p.sep.peakVoteFrac = dbls[72];
    p.sep.dedupeAng = dbls[73];
    p.sep.dedupeOff = dbls[74];
    p.sep.groupAng = dbls[75];
    p.sep.groupOff = dbls[76];
    p.sep.pairAng = dbls[77];
    p.sep.gapMin = ints[95];
    p.sep.edgeSkip = ints[96];
    p.sep.profWhite = dbls[78];
    p.sep.profWhiteBridge = dbls[79];
    p.sep.shortBridge = ints[97];
    p.sep.profGapFill = ints[98];
    p.sep.passMin = dbls[80];
    p.sep.stripExtFrac = dbls[81];
    p.sep.bridgeFrac = dbls[82];
    p.sep.edgeMadMax = dbls[83];
    p.sep.textDil = ints[99];
    p.sep.textProfMax = dbls[84];
    p.sep.familyAng = dbls[85];
    p.sep.familyReach = dbls[86];
    p.sep.wmaxFrac = dbls[87];
    p.sep.ovlFrac = dbls[88];
    p.sep.extDarkSkip = ints[100];
    p.sep.bubLineR = ints[101];
    p.sep.bubLineMax = dbls[89];
    p.sep.netTouch = ints[102];
    p.sep.netThick = ints[103];
    p.sep.netExtFrac = dbls[90];
    p.sep.marginRunFrac = dbls[91];
    p.sep.marginLightFrac = dbls[92];
    p.sep.marginLightMin = ints[104];
    p.sep.farProbe = ints[105];
    p.sep.marginOkTh = ints[106];
    p.sep.marginFrac = dbls[93];
    p.sep.flR = ints[107];
    p.sep.flHitR = ints[108];
    p.sep.marginEdgeAng = dbls[94];
    p.sep.marginAxisAng = dbls[95];
    p.sep.marginFillMin = dbls[96];
    if (!detail::flag(ints[109], &p.sep.marginOcc)) return false;
    if (!detail::flag(ints[110], &p.sep.marginClose)) return false;
    p.sep.occPieceFrac = dbls[97];
    p.sep.occGapFrac = dbls[98];
    p.sep.marginHaloR = ints[111];
    p.sep.bubDil = ints[112];
    p.sep.pairSubMax = dbls[99];
    p.sep.minCc = ints[113];
    if (!detail::flag(ints[114], &p.bleedFilter)) return false;
    p.bleed.ringR = ints[115];
    p.bleed.ringEdge = ints[116];
    p.bleed.piecePad = ints[117];
    p.bleed.frDil = ints[118];
    p.bleed.spDil = ints[119];
    p.bleed.bbR = ints[120];
    p.bleed.txR = ints[121];
    p.bleed.chR = ints[122];
    p.bleed.textBoxPad = ints[123];
    p.bleed.touchPx = ints[124];
    p.bleed.netFrac = dbls[100];
    p.bleed.txtKeep = dbls[101];
    p.bleed.frameKeep = dbls[102];
    p.bleed.islandBbMax = dbls[103];
    p.bleed.enclosedMax = dbls[104];
    p.bleed.artDrop = dbls[105];
    p.bleed.marginOk = ints[125];
    p.bleed.marginFrac = dbls[106];
    p.bleed.marginLightMaxFrac = dbls[107];
    p.bleed.marginLightMin = ints[126];
    p.bleed.marginMinRows = ints[127];
    p.bleed.marginNeuLook = ints[128];
    p.bleed.marginMinDepth = ints[129];
    p.bleed.marginCover = dbls[108];
    p.bleed.marginAng = dbls[109];
    p.bleed.marginDark = ints[130];
    p.bleed.marginLineMax = ints[131];
    p.bleed.mlineMinRun = ints[132];
    if (!detail::flag(ints[133], &p.ring.enabled)) return false;
    p.ring.rOut = ints[134];
    p.ring.seedMin = ints[135];
    p.ring.gvGap = ints[136];
    p.ring.rawClose = ints[137];
    p.ring.steps = ints[138];
    p.ring.inkBox = ints[139];
    p.ring.inkSum = ints[140];
    p.ring.inkMaxGray = ints[141];
    p.ring.holeMax = ints[142];
    p.ring.evIn = ints[143];
    p.ring.evOut = ints[144];
    p.ring.rayFrom = ints[145];
    p.ring.rayTo = ints[146];
    p.ring.raySigma = dbls[110];
    p.ring.gapClose = ints[147];
    p.ring.breakPad = ints[148];
    p.ring.faintGray = ints[149];
    p.ring.faintInkPad = ints[150];
    p.ring.faintWin = ints[151];
    p.ring.faintMin = ints[152];
    p.ring.open = ints[153];
    if (!detail::flag(ints[154], &p.ring.seedConnected)) return false;
    if (!detail::flag(ints[155], &p.obj.enabled)) return false;
    if (!detail::flag(ints[156], &p.obj.veto)) return false;
    if (!detail::flag(ints[157], &p.obj.lightFill)) return false;
    if (!detail::flag(ints[158], &p.obj.fxLines)) return false;
    if (!detail::flag(ints[159], &p.obj.fxSparks)) return false;
    p.obj.fx.segSd = dbls[111];
    p.obj.fx.segMin = ints[160];
    p.obj.fx.cosJoin = dbls[112];
    p.obj.fx.perp = dbls[113];
    p.obj.fx.gap = dbls[114];
    p.obj.fx.lineMin = dbls[115];
    p.obj.fx.cosVp = dbls[116];
    p.obj.fx.vpTop = ints[161];
    p.obj.fx.maxFamilies = ints[162];
    p.obj.fx.minMembers = ints[163];
    p.obj.fx.cosSpread = dbls[117];
    p.obj.fx.taper = dbls[118];
    p.obj.fx.taperFrac = dbls[119];
    p.obj.fx.freeMin = ints[164];
    p.obj.fx.freeFrac = dbls[120];
    p.obj.fx.ray0 = ints[165];
    p.obj.fx.ray1 = ints[166];
    p.obj.fx.rayG = ints[167];
    p.obj.fx.terr = ints[168];
    p.obj.fx.cosAtol2 = dbls[121];
    p.obj.fx.coh = dbls[122];
    p.obj.fx.epsJ = dbls[123];
    p.obj.fx.si = dbls[124];
    p.obj.fx.vpNear = ints[169];
    p.obj.fx.mark = dbls[125];
    p.obj.fx.lineR = ints[170];
    p.obj.fx.excl = ints[171];
    p.obj.fx.exclT = ints[172];
    p.obj.fx.agree = dbls[126];
    p.obj.fx.resid = dbls[127];
    p.obj.fx.nMem = ints[173];
    p.obj.fx.lineV = ints[174];
    p.obj.fx.grow = ints[175];
    p.obj.fx.halo = ints[176];
    p.obj.fx.erMin = ints[177];
    p.obj.fx.minPart = ints[178];
    p.obj.fx.sparkIso = ints[179];
    p.obj.fx.sparkMin = dbls[128];
    p.obj.fx.sparkMax = dbls[129];
    p.obj.fx.sparkDil = ints[180];
    p.obj.fx.sparkV = ints[181];
    p.obj.fx.longStraight = dbls[130];
    if (!detail::flag(ints[182], &p.obj.personFaint)) return false;
    p.obj.pfTouch = ints[183];
    p.obj.pfBridge = ints[184];
    p.obj.pfReach = ints[185];
    p.obj.pfHalo = ints[186];
    if (!detail::flag(ints[187], &p.obj.textNeedsRegion)) return false;
    if (!detail::flag(ints[188], &p.obj.pfShape)) return false;
    p.obj.pfClose = ints[189];
    p.obj.pfMargin = ints[190];
    p.obj.pfHole = ints[191];
    p.obj.pfLinePct = ints[192];
    p.obj.pfMark = ints[193];
    p.obj.pfStub = ints[194];
    p.obj.pfPair = ints[195];
    if (!detail::flag(ints[196], &p.obj.textStroke)) return false;
    p.obj.tsInk = ints[197];
    p.obj.tsMin = ints[198];
    p.obj.tsG = ints[199];
    p.obj.tsRough = ints[200];
    if (!detail::flag(ints[201], &p.obj.tsBandClean)) return false;
    if (!detail::flag(ints[202], &p.obj.seam)) return false;
    p.obj.seamR = ints[203];
    p.obj.seamSpark = ints[204];
    p.obj.seamG = ints[205];
    if (!detail::flag(ints[206], &p.obj.inpaintIslands)) return false;
    p.obj.islandInpD = ints[207];
    p.obj.islandInpPct = ints[208];
    p.obj.vetoEpm = dbls[131];
    p.obj.vetoRc = ints[209];
    p.obj.vetoMinArea = ints[210];
    p.obj.vetoEvDil = ints[211];
    p.obj.vetoBackPad = ints[212];
    p.obj.darkG = ints[213];
    p.obj.lightTh = ints[214];
    p.obj.areaMin = dbls[132];
    p.obj.lineMax = dbls[133];
    p.obj.can4Max = dbls[134];
    p.obj.fitMax = dbls[135];
    p.obj.toneMax = dbls[136];
    p.obj.toneGrad = dbls[137];
    p.obj.chromaMax = dbls[138];
    p.obj.thickMin = dbls[139];
    p.obj.marks = ints[215];
    p.obj.marksDen = dbls[140];
    p.obj.rLoc = ints[216];
    p.obj.coreMin = dbls[141];
    p.obj.seal = ints[217];
    p.obj.ctxEpm = dbls[142];
    p.obj.ctxWhite = ints[218];
    p.obj.islandMax = dbls[143];
    p.obj.islandTouch = ints[219];
    p.obj.islandTouchMin = ints[220];
    p.obj.hugMax = dbls[144];
    p.obj.longLen = ints[221];
    p.obj.longR = ints[222];
    p.obj.bhTh = ints[223];
    p.obj.lineR = ints[224];
    p.obj.brightTh = ints[225];
    p.obj.brightLrNum = ints[226];
    p.obj.brightLrDen = ints[227];
    p.obj.dot = ints[228];
    p.obj.dotBright = ints[229];
    p.obj.evMinArea = ints[230];
    p.obj.cannyLo = ints[231];
    p.obj.cannyHi = ints[232];
    p.obj.canny4Lo = ints[233];
    p.obj.canny4Hi = ints[234];
    return true;
}

/** 結構 → 攤平（ints 長 kIntSlots、dbls 長 kDoubleSlots）。 */
inline void toFlat(const NightReadParams& p, int32_t* ints, double* dbls) {
    (void)ints;
    (void)dbls;
    ints[0] = p.bg;
    ints[1] = p.ink;
    ints[2] = p.edgeInk;
    ints[3] = p.stroke;
    ints[4] = p.strokeObjV;
    ints[5] = p.sceneFloor;
    ints[6] = p.dimCeil;
    dbls[0] = static_cast<double>(p.glowStrength);
    ints[7] = p.glowCap;
    ints[8] = p.whiteTh;
    ints[9] = p.inkDarkTh;
    ints[10] = p.bubblePad;
    dbls[1] = p.gutterMinAreaFrac;
    ints[11] = p.paperPeakLo;
    ints[12] = p.paperNormMin;
    dbls[2] = p.paperNormChromaMax;
    ints[13] = p.frameLineLDiv;
    ints[14] = p.frameDarkTh;
    dbls[3] = p.frameMinEach;
    dbls[4] = p.frameMinSum;
    dbls[5] = p.framelessMarginDepth;
    ints[15] = p.coreR;
    dbls[6] = p.deepEdgeFrac;
    dbls[7] = p.inPanelCoreFrac;
    dbls[8] = p.inPanelCoreDeep;
    dbls[9] = p.deepInkDeep;
    dbls[10] = p.deepInkRatio;
    dbls[11] = p.holeMaxFrac;
    dbls[12] = p.safeGutterDepth;
    ints[16] = p.gfcDilate;
    dbls[13] = p.gfcCloseFrac;
    dbls[14] = p.textureTh;
    dbls[15] = p.textureHi;
    ints[17] = p.textureWin;
    ints[18] = p.texturePad;
    ints[19] = p.textureMinArea;
    ints[20] = p.textureFrameDil;
    ints[21] = p.textureSegDil;
    ints[22] = p.textureBubbleNear;
    ints[23] = p.textureBubbleOutline;
    ints[24] = p.textureClose;
    dbls[16] = p.textureRegionMin;
    dbls[17] = p.bubbleCompMaxFrac;
    dbls[18] = p.bubbleLocalK;
    dbls[19] = p.bubbleCoreMinFrac;
    ints[25] = p.bubbleNeckR;
    dbls[20] = p.safeBubbleRatio;
    dbls[21] = p.bubbleCleanWins;
    dbls[22] = p.bubbleCleanTextMax;
    ints[26] = p.bubbleCleanInkHoles ? 1 : 0;
    ints[27] = p.bubbleGuardRaw ? 1 : 0;
    dbls[23] = p.bubbleConfirmTextIn;
    ints[28] = p.bubbleLeak ? 1 : 0;
    ints[29] = p.bubbleLeakRing;
    ints[30] = p.bubbleLeakRange;
    ints[31] = p.bubbleLeakWin;
    dbls[24] = p.bubbleLeakEdgeMax;
    ints[32] = p.bubbleLeakRingMin;
    ints[33] = p.bubbleLeakMinArea;
    ints[34] = p.bubbleRestNear;
    dbls[25] = p.bubbleOutlineMin;
    ints[35] = p.bubbleOutlineDist;
    ints[36] = p.bubbleOutlineMinPx;
    ints[37] = p.bubbleOutlinePad;
    ints[38] = p.bubbleSeal.r;
    dbls[26] = p.bubbleSeal.charMax;
    dbls[27] = p.bubbleSeal.textIn;
    ints[39] = p.bubbleSeal.restMin;
    ints[40] = p.bubbleSeal.maxGaps;
    ints[41] = p.bubbleSeal.cutGeo.present ? 1 : 0;
    ints[42] = p.bubbleSeal.cutGeo.present ? p.bubbleSeal.cutGeo.value : 0;
    dbls[28] = p.pbCovMax;
    ints[43] = p.pbNeckR;
    dbls[29] = p.pbGrowFrac;
    ints[44] = p.pbAuraR;
    ints[45] = p.pbAuraThick;
    ints[46] = p.pbAuraMinArea;
    dbls[30] = p.strokeObjFrac;
    ints[47] = p.strokeObjMin;
    ints[48] = p.strokeObjMax;
    dbls[31] = p.stickerMinFrac;
    ints[49] = p.figNoiseArea;
    ints[50] = p.figNoiseClose;
    dbls[32] = p.stickerFigMin;
    dbls[33] = p.stickerFigMax;
    ints[51] = p.stickerThinR;
    dbls[34] = p.stickerThinMax;
    dbls[35] = p.stickerChromaMax;
    ints[52] = p.stickerEatenR;
    dbls[36] = p.stickerEatenDens;
    dbls[37] = p.stickerEatenMax;
    dbls[38] = p.stickerEatenHard;
    dbls[39] = p.stickerTextBgMin;
    dbls[40] = p.stickerTextMax;
    ints[53] = p.stickerTextOnPad;
    dbls[41] = p.stickerSmallArea;
    ints[54] = p.stickerNeckR;
    dbls[42] = p.stickerCoreMin;
    dbls[43] = p.stickerProtectEatenMin;
    ints[55] = p.stickerProtectDilate;
    dbls[44] = p.faintOfFMax;
    ints[56] = p.faintG;
    ints[57] = p.frameHugDilate;
    dbls[45] = p.frameHugThick;
    dbls[46] = p.frameHugMin;
    dbls[47] = p.frameHugStrong;
    dbls[48] = p.hugSideMin;
    dbls[49] = p.promotedTextOnMax;
    ints[58] = static_cast<int32_t>(p.stickerMode);
    dbls[50] = p.stickerRoughMax;
    dbls[51] = p.stickerSimpleMinFrac;
    ints[59] = p.stickerPlainRingR;
    dbls[52] = p.stickerPlainArtMax;
    ints[60] = p.stickerPlainFrameDil;
    dbls[53] = p.stickerPlainFaintMax;
    ints[61] = p.stickerPlainFaintHalo;
    ints[62] = p.more.enabled ? 1 : 0;
    dbls[54] = p.more.tOut;
    dbls[55] = p.more.tIn;
    dbls[56] = p.more.faintMax;
    dbls[57] = p.more.roughCap;
    dbls[58] = p.more.charMax;
    dbls[59] = p.more.hyst;
    ints[63] = p.more.holeMinArea;
    ints[64] = p.more.holeDarkMax;
    ints[65] = p.more.median;
    ints[66] = p.more.explainDil;
    ints[67] = p.more.nearDil;
    ints[68] = p.more.ringR;
    ints[69] = p.more.halo;
    ints[70] = p.more.winPad;
    ints[71] = p.more.keepDarkStroke ? 1 : 0;
    ints[72] = p.more.edgeSeedFallback ? 1 : 0;
    ints[73] = p.more.edgeBand;
    ints[74] = p.more.edgeReach;
    ints[75] = p.pseudoBubbles ? 1 : 0;
    ints[76] = p.harmonize ? 1 : 0;
    ints[77] = p.coreNeckR;
    ints[78] = p.coreRecoverR;
    dbls[60] = p.geoRatioMax;
    dbls[61] = p.geoSlack;
    ints[79] = p.coreReleasePad;
    ints[80] = p.charSnap;
    ints[81] = p.charSnapPad;
    ints[82] = p.maskSmoothMedian;
    dbls[62] = p.edgeFeather;
    ints[83] = p.textPad;
    dbls[63] = p.textGamma;
    dbls[64] = p.textKnee;
    ints[84] = p.textTopPad;
    ints[85] = p.textBackingR;
    ints[86] = p.harmonizeZoneCell;
    dbls[65] = p.harmonizeZoneDark;
    dbls[66] = p.harmonizeInZone;
    dbls[67] = p.harmonizeAreaMax;
    dbls[68] = p.harmonizeCollarInk;
    ints[87] = p.separators ? 1 : 0;
    dbls[69] = p.sep.lenFrac;
    ints[88] = p.sep.nearWhiteR;
    dbls[70] = p.sep.thStep;
    ints[89] = p.sep.walkWin0;
    ints[90] = p.sep.walkWin;
    ints[91] = p.sep.gap;
    dbls[71] = p.sep.fillMin;
    ints[92] = p.sep.peakNmsT;
    ints[93] = p.sep.peakNmsR;
    ints[94] = p.sep.peakMax;
    dbls[72] = p.sep.peakVoteFrac;
    dbls[73] = p.sep.dedupeAng;
    dbls[74] = p.sep.dedupeOff;
    dbls[75] = p.sep.groupAng;
    dbls[76] = p.sep.groupOff;
    dbls[77] = p.sep.pairAng;
    ints[95] = p.sep.gapMin;
    ints[96] = p.sep.edgeSkip;
    dbls[78] = p.sep.profWhite;
    dbls[79] = p.sep.profWhiteBridge;
    ints[97] = p.sep.shortBridge;
    ints[98] = p.sep.profGapFill;
    dbls[80] = p.sep.passMin;
    dbls[81] = p.sep.stripExtFrac;
    dbls[82] = p.sep.bridgeFrac;
    dbls[83] = p.sep.edgeMadMax;
    ints[99] = p.sep.textDil;
    dbls[84] = p.sep.textProfMax;
    dbls[85] = p.sep.familyAng;
    dbls[86] = p.sep.familyReach;
    dbls[87] = p.sep.wmaxFrac;
    dbls[88] = p.sep.ovlFrac;
    ints[100] = p.sep.extDarkSkip;
    ints[101] = p.sep.bubLineR;
    dbls[89] = p.sep.bubLineMax;
    ints[102] = p.sep.netTouch;
    ints[103] = p.sep.netThick;
    dbls[90] = p.sep.netExtFrac;
    dbls[91] = p.sep.marginRunFrac;
    dbls[92] = p.sep.marginLightFrac;
    ints[104] = p.sep.marginLightMin;
    ints[105] = p.sep.farProbe;
    ints[106] = p.sep.marginOkTh;
    dbls[93] = p.sep.marginFrac;
    ints[107] = p.sep.flR;
    ints[108] = p.sep.flHitR;
    dbls[94] = p.sep.marginEdgeAng;
    dbls[95] = p.sep.marginAxisAng;
    dbls[96] = p.sep.marginFillMin;
    ints[109] = p.sep.marginOcc ? 1 : 0;
    ints[110] = p.sep.marginClose ? 1 : 0;
    dbls[97] = p.sep.occPieceFrac;
    dbls[98] = p.sep.occGapFrac;
    ints[111] = p.sep.marginHaloR;
    ints[112] = p.sep.bubDil;
    dbls[99] = p.sep.pairSubMax;
    ints[113] = p.sep.minCc;
    ints[114] = p.bleedFilter ? 1 : 0;
    ints[115] = p.bleed.ringR;
    ints[116] = p.bleed.ringEdge;
    ints[117] = p.bleed.piecePad;
    ints[118] = p.bleed.frDil;
    ints[119] = p.bleed.spDil;
    ints[120] = p.bleed.bbR;
    ints[121] = p.bleed.txR;
    ints[122] = p.bleed.chR;
    ints[123] = p.bleed.textBoxPad;
    ints[124] = p.bleed.touchPx;
    dbls[100] = p.bleed.netFrac;
    dbls[101] = p.bleed.txtKeep;
    dbls[102] = p.bleed.frameKeep;
    dbls[103] = p.bleed.islandBbMax;
    dbls[104] = p.bleed.enclosedMax;
    dbls[105] = p.bleed.artDrop;
    ints[125] = p.bleed.marginOk;
    dbls[106] = p.bleed.marginFrac;
    dbls[107] = p.bleed.marginLightMaxFrac;
    ints[126] = p.bleed.marginLightMin;
    ints[127] = p.bleed.marginMinRows;
    ints[128] = p.bleed.marginNeuLook;
    ints[129] = p.bleed.marginMinDepth;
    dbls[108] = p.bleed.marginCover;
    dbls[109] = p.bleed.marginAng;
    ints[130] = p.bleed.marginDark;
    ints[131] = p.bleed.marginLineMax;
    ints[132] = p.bleed.mlineMinRun;
    ints[133] = p.ring.enabled ? 1 : 0;
    ints[134] = p.ring.rOut;
    ints[135] = p.ring.seedMin;
    ints[136] = p.ring.gvGap;
    ints[137] = p.ring.rawClose;
    ints[138] = p.ring.steps;
    ints[139] = p.ring.inkBox;
    ints[140] = p.ring.inkSum;
    ints[141] = p.ring.inkMaxGray;
    ints[142] = p.ring.holeMax;
    ints[143] = p.ring.evIn;
    ints[144] = p.ring.evOut;
    ints[145] = p.ring.rayFrom;
    ints[146] = p.ring.rayTo;
    dbls[110] = p.ring.raySigma;
    ints[147] = p.ring.gapClose;
    ints[148] = p.ring.breakPad;
    ints[149] = p.ring.faintGray;
    ints[150] = p.ring.faintInkPad;
    ints[151] = p.ring.faintWin;
    ints[152] = p.ring.faintMin;
    ints[153] = p.ring.open;
    ints[154] = p.ring.seedConnected ? 1 : 0;
    ints[155] = p.obj.enabled ? 1 : 0;
    ints[156] = p.obj.veto ? 1 : 0;
    ints[157] = p.obj.lightFill ? 1 : 0;
    ints[158] = p.obj.fxLines ? 1 : 0;
    ints[159] = p.obj.fxSparks ? 1 : 0;
    dbls[111] = p.obj.fx.segSd;
    ints[160] = p.obj.fx.segMin;
    dbls[112] = p.obj.fx.cosJoin;
    dbls[113] = p.obj.fx.perp;
    dbls[114] = p.obj.fx.gap;
    dbls[115] = p.obj.fx.lineMin;
    dbls[116] = p.obj.fx.cosVp;
    ints[161] = p.obj.fx.vpTop;
    ints[162] = p.obj.fx.maxFamilies;
    ints[163] = p.obj.fx.minMembers;
    dbls[117] = p.obj.fx.cosSpread;
    dbls[118] = p.obj.fx.taper;
    dbls[119] = p.obj.fx.taperFrac;
    ints[164] = p.obj.fx.freeMin;
    dbls[120] = p.obj.fx.freeFrac;
    ints[165] = p.obj.fx.ray0;
    ints[166] = p.obj.fx.ray1;
    ints[167] = p.obj.fx.rayG;
    ints[168] = p.obj.fx.terr;
    dbls[121] = p.obj.fx.cosAtol2;
    dbls[122] = p.obj.fx.coh;
    dbls[123] = p.obj.fx.epsJ;
    dbls[124] = p.obj.fx.si;
    ints[169] = p.obj.fx.vpNear;
    dbls[125] = p.obj.fx.mark;
    ints[170] = p.obj.fx.lineR;
    ints[171] = p.obj.fx.excl;
    ints[172] = p.obj.fx.exclT;
    dbls[126] = p.obj.fx.agree;
    dbls[127] = p.obj.fx.resid;
    ints[173] = p.obj.fx.nMem;
    ints[174] = p.obj.fx.lineV;
    ints[175] = p.obj.fx.grow;
    ints[176] = p.obj.fx.halo;
    ints[177] = p.obj.fx.erMin;
    ints[178] = p.obj.fx.minPart;
    ints[179] = p.obj.fx.sparkIso;
    dbls[128] = p.obj.fx.sparkMin;
    dbls[129] = p.obj.fx.sparkMax;
    ints[180] = p.obj.fx.sparkDil;
    ints[181] = p.obj.fx.sparkV;
    dbls[130] = p.obj.fx.longStraight;
    ints[182] = p.obj.personFaint ? 1 : 0;
    ints[183] = p.obj.pfTouch;
    ints[184] = p.obj.pfBridge;
    ints[185] = p.obj.pfReach;
    ints[186] = p.obj.pfHalo;
    ints[187] = p.obj.textNeedsRegion ? 1 : 0;
    ints[188] = p.obj.pfShape ? 1 : 0;
    ints[189] = p.obj.pfClose;
    ints[190] = p.obj.pfMargin;
    ints[191] = p.obj.pfHole;
    ints[192] = p.obj.pfLinePct;
    ints[193] = p.obj.pfMark;
    ints[194] = p.obj.pfStub;
    ints[195] = p.obj.pfPair;
    ints[196] = p.obj.textStroke ? 1 : 0;
    ints[197] = p.obj.tsInk;
    ints[198] = p.obj.tsMin;
    ints[199] = p.obj.tsG;
    ints[200] = p.obj.tsRough;
    ints[201] = p.obj.tsBandClean ? 1 : 0;
    ints[202] = p.obj.seam ? 1 : 0;
    ints[203] = p.obj.seamR;
    ints[204] = p.obj.seamSpark;
    ints[205] = p.obj.seamG;
    ints[206] = p.obj.inpaintIslands ? 1 : 0;
    ints[207] = p.obj.islandInpD;
    ints[208] = p.obj.islandInpPct;
    dbls[131] = p.obj.vetoEpm;
    ints[209] = p.obj.vetoRc;
    ints[210] = p.obj.vetoMinArea;
    ints[211] = p.obj.vetoEvDil;
    ints[212] = p.obj.vetoBackPad;
    ints[213] = p.obj.darkG;
    ints[214] = p.obj.lightTh;
    dbls[132] = p.obj.areaMin;
    dbls[133] = p.obj.lineMax;
    dbls[134] = p.obj.can4Max;
    dbls[135] = p.obj.fitMax;
    dbls[136] = p.obj.toneMax;
    dbls[137] = p.obj.toneGrad;
    dbls[138] = p.obj.chromaMax;
    dbls[139] = p.obj.thickMin;
    ints[215] = p.obj.marks;
    dbls[140] = p.obj.marksDen;
    ints[216] = p.obj.rLoc;
    dbls[141] = p.obj.coreMin;
    ints[217] = p.obj.seal;
    dbls[142] = p.obj.ctxEpm;
    ints[218] = p.obj.ctxWhite;
    dbls[143] = p.obj.islandMax;
    ints[219] = p.obj.islandTouch;
    ints[220] = p.obj.islandTouchMin;
    dbls[144] = p.obj.hugMax;
    ints[221] = p.obj.longLen;
    ints[222] = p.obj.longR;
    ints[223] = p.obj.bhTh;
    ints[224] = p.obj.lineR;
    ints[225] = p.obj.brightTh;
    ints[226] = p.obj.brightLrNum;
    ints[227] = p.obj.brightLrDen;
    ints[228] = p.obj.dot;
    ints[229] = p.obj.dotBright;
    ints[230] = p.obj.evMinArea;
    ints[231] = p.obj.cannyLo;
    ints[232] = p.obj.cannyHi;
    ints[233] = p.obj.canny4Lo;
    ints[234] = p.obj.canny4Hi;
}

// ── 逐欄存取（測試用：直接讀寫結構成員、不經攤平槽位，對照 Kotlin 端以反射逐欄比）────────────────

enum class Kind : int32_t { Int = 0, Double = 1, Float = 2, Bool = 3, OptInt = 4, Enum = 5 };

struct Field {
    const char* name;   // Kotlin 的屬性路徑（巢狀以 . 連接）
    Kind kind;
    int32_t intSlot;    // ints 的槽（Double／Float 為 -1）
    int32_t doubleSlot; // dbls 的槽（其餘為 -1）
};

constexpr Field kFields[kFieldCount] = {
    {"bg", Kind::Int, 0, -1},
    {"ink", Kind::Int, 1, -1},
    {"edgeInk", Kind::Int, 2, -1},
    {"stroke", Kind::Int, 3, -1},
    {"strokeObjV", Kind::Int, 4, -1},
    {"sceneFloor", Kind::Int, 5, -1},
    {"dimCeil", Kind::Int, 6, -1},
    {"glowStrength", Kind::Float, -1, 0},
    {"glowCap", Kind::Int, 7, -1},
    {"whiteTh", Kind::Int, 8, -1},
    {"inkDarkTh", Kind::Int, 9, -1},
    {"bubblePad", Kind::Int, 10, -1},
    {"gutterMinAreaFrac", Kind::Double, -1, 1},
    {"paperPeakLo", Kind::Int, 11, -1},
    {"paperNormMin", Kind::Int, 12, -1},
    {"paperNormChromaMax", Kind::Double, -1, 2},
    {"frameLineLDiv", Kind::Int, 13, -1},
    {"frameDarkTh", Kind::Int, 14, -1},
    {"frameMinEach", Kind::Double, -1, 3},
    {"frameMinSum", Kind::Double, -1, 4},
    {"framelessMarginDepth", Kind::Double, -1, 5},
    {"coreR", Kind::Int, 15, -1},
    {"deepEdgeFrac", Kind::Double, -1, 6},
    {"inPanelCoreFrac", Kind::Double, -1, 7},
    {"inPanelCoreDeep", Kind::Double, -1, 8},
    {"deepInkDeep", Kind::Double, -1, 9},
    {"deepInkRatio", Kind::Double, -1, 10},
    {"holeMaxFrac", Kind::Double, -1, 11},
    {"safeGutterDepth", Kind::Double, -1, 12},
    {"gfcDilate", Kind::Int, 16, -1},
    {"gfcCloseFrac", Kind::Double, -1, 13},
    {"textureTh", Kind::Double, -1, 14},
    {"textureHi", Kind::Double, -1, 15},
    {"textureWin", Kind::Int, 17, -1},
    {"texturePad", Kind::Int, 18, -1},
    {"textureMinArea", Kind::Int, 19, -1},
    {"textureFrameDil", Kind::Int, 20, -1},
    {"textureSegDil", Kind::Int, 21, -1},
    {"textureBubbleNear", Kind::Int, 22, -1},
    {"textureBubbleOutline", Kind::Int, 23, -1},
    {"textureClose", Kind::Int, 24, -1},
    {"textureRegionMin", Kind::Double, -1, 16},
    {"bubbleCompMaxFrac", Kind::Double, -1, 17},
    {"bubbleLocalK", Kind::Double, -1, 18},
    {"bubbleCoreMinFrac", Kind::Double, -1, 19},
    {"bubbleNeckR", Kind::Int, 25, -1},
    {"safeBubbleRatio", Kind::Double, -1, 20},
    {"bubbleCleanWins", Kind::Double, -1, 21},
    {"bubbleCleanTextMax", Kind::Double, -1, 22},
    {"bubbleCleanInkHoles", Kind::Bool, 26, -1},
    {"bubbleGuardRaw", Kind::Bool, 27, -1},
    {"bubbleConfirmTextIn", Kind::Double, -1, 23},
    {"bubbleLeak", Kind::Bool, 28, -1},
    {"bubbleLeakRing", Kind::Int, 29, -1},
    {"bubbleLeakRange", Kind::Int, 30, -1},
    {"bubbleLeakWin", Kind::Int, 31, -1},
    {"bubbleLeakEdgeMax", Kind::Double, -1, 24},
    {"bubbleLeakRingMin", Kind::Int, 32, -1},
    {"bubbleLeakMinArea", Kind::Int, 33, -1},
    {"bubbleRestNear", Kind::Int, 34, -1},
    {"bubbleOutlineMin", Kind::Double, -1, 25},
    {"bubbleOutlineDist", Kind::Int, 35, -1},
    {"bubbleOutlineMinPx", Kind::Int, 36, -1},
    {"bubbleOutlinePad", Kind::Int, 37, -1},
    {"bubbleSeal.r", Kind::Int, 38, -1},
    {"bubbleSeal.charMax", Kind::Double, -1, 26},
    {"bubbleSeal.textIn", Kind::Double, -1, 27},
    {"bubbleSeal.restMin", Kind::Int, 39, -1},
    {"bubbleSeal.maxGaps", Kind::Int, 40, -1},
    {"bubbleSeal.cutGeo", Kind::OptInt, 41, -1},
    {"pbCovMax", Kind::Double, -1, 28},
    {"pbNeckR", Kind::Int, 43, -1},
    {"pbGrowFrac", Kind::Double, -1, 29},
    {"pbAuraR", Kind::Int, 44, -1},
    {"pbAuraThick", Kind::Int, 45, -1},
    {"pbAuraMinArea", Kind::Int, 46, -1},
    {"strokeObjFrac", Kind::Double, -1, 30},
    {"strokeObjMin", Kind::Int, 47, -1},
    {"strokeObjMax", Kind::Int, 48, -1},
    {"stickerMinFrac", Kind::Double, -1, 31},
    {"figNoiseArea", Kind::Int, 49, -1},
    {"figNoiseClose", Kind::Int, 50, -1},
    {"stickerFigMin", Kind::Double, -1, 32},
    {"stickerFigMax", Kind::Double, -1, 33},
    {"stickerThinR", Kind::Int, 51, -1},
    {"stickerThinMax", Kind::Double, -1, 34},
    {"stickerChromaMax", Kind::Double, -1, 35},
    {"stickerEatenR", Kind::Int, 52, -1},
    {"stickerEatenDens", Kind::Double, -1, 36},
    {"stickerEatenMax", Kind::Double, -1, 37},
    {"stickerEatenHard", Kind::Double, -1, 38},
    {"stickerTextBgMin", Kind::Double, -1, 39},
    {"stickerTextMax", Kind::Double, -1, 40},
    {"stickerTextOnPad", Kind::Int, 53, -1},
    {"stickerSmallArea", Kind::Double, -1, 41},
    {"stickerNeckR", Kind::Int, 54, -1},
    {"stickerCoreMin", Kind::Double, -1, 42},
    {"stickerProtectEatenMin", Kind::Double, -1, 43},
    {"stickerProtectDilate", Kind::Int, 55, -1},
    {"faintOfFMax", Kind::Double, -1, 44},
    {"faintG", Kind::Int, 56, -1},
    {"frameHugDilate", Kind::Int, 57, -1},
    {"frameHugThick", Kind::Double, -1, 45},
    {"frameHugMin", Kind::Double, -1, 46},
    {"frameHugStrong", Kind::Double, -1, 47},
    {"hugSideMin", Kind::Double, -1, 48},
    {"promotedTextOnMax", Kind::Double, -1, 49},
    {"stickerMode", Kind::Enum, 58, -1},
    {"stickerRoughMax", Kind::Double, -1, 50},
    {"stickerSimpleMinFrac", Kind::Double, -1, 51},
    {"stickerPlainRingR", Kind::Int, 59, -1},
    {"stickerPlainArtMax", Kind::Double, -1, 52},
    {"stickerPlainFrameDil", Kind::Int, 60, -1},
    {"stickerPlainFaintMax", Kind::Double, -1, 53},
    {"stickerPlainFaintHalo", Kind::Int, 61, -1},
    {"more.enabled", Kind::Bool, 62, -1},
    {"more.tOut", Kind::Double, -1, 54},
    {"more.tIn", Kind::Double, -1, 55},
    {"more.faintMax", Kind::Double, -1, 56},
    {"more.roughCap", Kind::Double, -1, 57},
    {"more.charMax", Kind::Double, -1, 58},
    {"more.hyst", Kind::Double, -1, 59},
    {"more.holeMinArea", Kind::Int, 63, -1},
    {"more.holeDarkMax", Kind::Int, 64, -1},
    {"more.median", Kind::Int, 65, -1},
    {"more.explainDil", Kind::Int, 66, -1},
    {"more.nearDil", Kind::Int, 67, -1},
    {"more.ringR", Kind::Int, 68, -1},
    {"more.halo", Kind::Int, 69, -1},
    {"more.winPad", Kind::Int, 70, -1},
    {"more.keepDarkStroke", Kind::Bool, 71, -1},
    {"more.edgeSeedFallback", Kind::Bool, 72, -1},
    {"more.edgeBand", Kind::Int, 73, -1},
    {"more.edgeReach", Kind::Int, 74, -1},
    {"pseudoBubbles", Kind::Bool, 75, -1},
    {"harmonize", Kind::Bool, 76, -1},
    {"coreNeckR", Kind::Int, 77, -1},
    {"coreRecoverR", Kind::Int, 78, -1},
    {"geoRatioMax", Kind::Double, -1, 60},
    {"geoSlack", Kind::Double, -1, 61},
    {"coreReleasePad", Kind::Int, 79, -1},
    {"charSnap", Kind::Int, 80, -1},
    {"charSnapPad", Kind::Int, 81, -1},
    {"maskSmoothMedian", Kind::Int, 82, -1},
    {"edgeFeather", Kind::Double, -1, 62},
    {"textPad", Kind::Int, 83, -1},
    {"textGamma", Kind::Double, -1, 63},
    {"textKnee", Kind::Double, -1, 64},
    {"textTopPad", Kind::Int, 84, -1},
    {"textBackingR", Kind::Int, 85, -1},
    {"harmonizeZoneCell", Kind::Int, 86, -1},
    {"harmonizeZoneDark", Kind::Double, -1, 65},
    {"harmonizeInZone", Kind::Double, -1, 66},
    {"harmonizeAreaMax", Kind::Double, -1, 67},
    {"harmonizeCollarInk", Kind::Double, -1, 68},
    {"separators", Kind::Bool, 87, -1},
    {"sep.lenFrac", Kind::Double, -1, 69},
    {"sep.nearWhiteR", Kind::Int, 88, -1},
    {"sep.thStep", Kind::Double, -1, 70},
    {"sep.walkWin0", Kind::Int, 89, -1},
    {"sep.walkWin", Kind::Int, 90, -1},
    {"sep.gap", Kind::Int, 91, -1},
    {"sep.fillMin", Kind::Double, -1, 71},
    {"sep.peakNmsT", Kind::Int, 92, -1},
    {"sep.peakNmsR", Kind::Int, 93, -1},
    {"sep.peakMax", Kind::Int, 94, -1},
    {"sep.peakVoteFrac", Kind::Double, -1, 72},
    {"sep.dedupeAng", Kind::Double, -1, 73},
    {"sep.dedupeOff", Kind::Double, -1, 74},
    {"sep.groupAng", Kind::Double, -1, 75},
    {"sep.groupOff", Kind::Double, -1, 76},
    {"sep.pairAng", Kind::Double, -1, 77},
    {"sep.gapMin", Kind::Int, 95, -1},
    {"sep.edgeSkip", Kind::Int, 96, -1},
    {"sep.profWhite", Kind::Double, -1, 78},
    {"sep.profWhiteBridge", Kind::Double, -1, 79},
    {"sep.shortBridge", Kind::Int, 97, -1},
    {"sep.profGapFill", Kind::Int, 98, -1},
    {"sep.passMin", Kind::Double, -1, 80},
    {"sep.stripExtFrac", Kind::Double, -1, 81},
    {"sep.bridgeFrac", Kind::Double, -1, 82},
    {"sep.edgeMadMax", Kind::Double, -1, 83},
    {"sep.textDil", Kind::Int, 99, -1},
    {"sep.textProfMax", Kind::Double, -1, 84},
    {"sep.familyAng", Kind::Double, -1, 85},
    {"sep.familyReach", Kind::Double, -1, 86},
    {"sep.wmaxFrac", Kind::Double, -1, 87},
    {"sep.ovlFrac", Kind::Double, -1, 88},
    {"sep.extDarkSkip", Kind::Int, 100, -1},
    {"sep.bubLineR", Kind::Int, 101, -1},
    {"sep.bubLineMax", Kind::Double, -1, 89},
    {"sep.netTouch", Kind::Int, 102, -1},
    {"sep.netThick", Kind::Int, 103, -1},
    {"sep.netExtFrac", Kind::Double, -1, 90},
    {"sep.marginRunFrac", Kind::Double, -1, 91},
    {"sep.marginLightFrac", Kind::Double, -1, 92},
    {"sep.marginLightMin", Kind::Int, 104, -1},
    {"sep.farProbe", Kind::Int, 105, -1},
    {"sep.marginOkTh", Kind::Int, 106, -1},
    {"sep.marginFrac", Kind::Double, -1, 93},
    {"sep.flR", Kind::Int, 107, -1},
    {"sep.flHitR", Kind::Int, 108, -1},
    {"sep.marginEdgeAng", Kind::Double, -1, 94},
    {"sep.marginAxisAng", Kind::Double, -1, 95},
    {"sep.marginFillMin", Kind::Double, -1, 96},
    {"sep.marginOcc", Kind::Bool, 109, -1},
    {"sep.marginClose", Kind::Bool, 110, -1},
    {"sep.occPieceFrac", Kind::Double, -1, 97},
    {"sep.occGapFrac", Kind::Double, -1, 98},
    {"sep.marginHaloR", Kind::Int, 111, -1},
    {"sep.bubDil", Kind::Int, 112, -1},
    {"sep.pairSubMax", Kind::Double, -1, 99},
    {"sep.minCc", Kind::Int, 113, -1},
    {"bleedFilter", Kind::Bool, 114, -1},
    {"bleed.ringR", Kind::Int, 115, -1},
    {"bleed.ringEdge", Kind::Int, 116, -1},
    {"bleed.piecePad", Kind::Int, 117, -1},
    {"bleed.frDil", Kind::Int, 118, -1},
    {"bleed.spDil", Kind::Int, 119, -1},
    {"bleed.bbR", Kind::Int, 120, -1},
    {"bleed.txR", Kind::Int, 121, -1},
    {"bleed.chR", Kind::Int, 122, -1},
    {"bleed.textBoxPad", Kind::Int, 123, -1},
    {"bleed.touchPx", Kind::Int, 124, -1},
    {"bleed.netFrac", Kind::Double, -1, 100},
    {"bleed.txtKeep", Kind::Double, -1, 101},
    {"bleed.frameKeep", Kind::Double, -1, 102},
    {"bleed.islandBbMax", Kind::Double, -1, 103},
    {"bleed.enclosedMax", Kind::Double, -1, 104},
    {"bleed.artDrop", Kind::Double, -1, 105},
    {"bleed.marginOk", Kind::Int, 125, -1},
    {"bleed.marginFrac", Kind::Double, -1, 106},
    {"bleed.marginLightMaxFrac", Kind::Double, -1, 107},
    {"bleed.marginLightMin", Kind::Int, 126, -1},
    {"bleed.marginMinRows", Kind::Int, 127, -1},
    {"bleed.marginNeuLook", Kind::Int, 128, -1},
    {"bleed.marginMinDepth", Kind::Int, 129, -1},
    {"bleed.marginCover", Kind::Double, -1, 108},
    {"bleed.marginAng", Kind::Double, -1, 109},
    {"bleed.marginDark", Kind::Int, 130, -1},
    {"bleed.marginLineMax", Kind::Int, 131, -1},
    {"bleed.mlineMinRun", Kind::Int, 132, -1},
    {"ring.enabled", Kind::Bool, 133, -1},
    {"ring.rOut", Kind::Int, 134, -1},
    {"ring.seedMin", Kind::Int, 135, -1},
    {"ring.gvGap", Kind::Int, 136, -1},
    {"ring.rawClose", Kind::Int, 137, -1},
    {"ring.steps", Kind::Int, 138, -1},
    {"ring.inkBox", Kind::Int, 139, -1},
    {"ring.inkSum", Kind::Int, 140, -1},
    {"ring.inkMaxGray", Kind::Int, 141, -1},
    {"ring.holeMax", Kind::Int, 142, -1},
    {"ring.evIn", Kind::Int, 143, -1},
    {"ring.evOut", Kind::Int, 144, -1},
    {"ring.rayFrom", Kind::Int, 145, -1},
    {"ring.rayTo", Kind::Int, 146, -1},
    {"ring.raySigma", Kind::Double, -1, 110},
    {"ring.gapClose", Kind::Int, 147, -1},
    {"ring.breakPad", Kind::Int, 148, -1},
    {"ring.faintGray", Kind::Int, 149, -1},
    {"ring.faintInkPad", Kind::Int, 150, -1},
    {"ring.faintWin", Kind::Int, 151, -1},
    {"ring.faintMin", Kind::Int, 152, -1},
    {"ring.open", Kind::Int, 153, -1},
    {"ring.seedConnected", Kind::Bool, 154, -1},
    {"obj.enabled", Kind::Bool, 155, -1},
    {"obj.veto", Kind::Bool, 156, -1},
    {"obj.lightFill", Kind::Bool, 157, -1},
    {"obj.fxLines", Kind::Bool, 158, -1},
    {"obj.fxSparks", Kind::Bool, 159, -1},
    {"obj.fx.segSd", Kind::Double, -1, 111},
    {"obj.fx.segMin", Kind::Int, 160, -1},
    {"obj.fx.cosJoin", Kind::Double, -1, 112},
    {"obj.fx.perp", Kind::Double, -1, 113},
    {"obj.fx.gap", Kind::Double, -1, 114},
    {"obj.fx.lineMin", Kind::Double, -1, 115},
    {"obj.fx.cosVp", Kind::Double, -1, 116},
    {"obj.fx.vpTop", Kind::Int, 161, -1},
    {"obj.fx.maxFamilies", Kind::Int, 162, -1},
    {"obj.fx.minMembers", Kind::Int, 163, -1},
    {"obj.fx.cosSpread", Kind::Double, -1, 117},
    {"obj.fx.taper", Kind::Double, -1, 118},
    {"obj.fx.taperFrac", Kind::Double, -1, 119},
    {"obj.fx.freeMin", Kind::Int, 164, -1},
    {"obj.fx.freeFrac", Kind::Double, -1, 120},
    {"obj.fx.ray0", Kind::Int, 165, -1},
    {"obj.fx.ray1", Kind::Int, 166, -1},
    {"obj.fx.rayG", Kind::Int, 167, -1},
    {"obj.fx.terr", Kind::Int, 168, -1},
    {"obj.fx.cosAtol2", Kind::Double, -1, 121},
    {"obj.fx.coh", Kind::Double, -1, 122},
    {"obj.fx.epsJ", Kind::Double, -1, 123},
    {"obj.fx.si", Kind::Double, -1, 124},
    {"obj.fx.vpNear", Kind::Int, 169, -1},
    {"obj.fx.mark", Kind::Double, -1, 125},
    {"obj.fx.lineR", Kind::Int, 170, -1},
    {"obj.fx.excl", Kind::Int, 171, -1},
    {"obj.fx.exclT", Kind::Int, 172, -1},
    {"obj.fx.agree", Kind::Double, -1, 126},
    {"obj.fx.resid", Kind::Double, -1, 127},
    {"obj.fx.nMem", Kind::Int, 173, -1},
    {"obj.fx.lineV", Kind::Int, 174, -1},
    {"obj.fx.grow", Kind::Int, 175, -1},
    {"obj.fx.halo", Kind::Int, 176, -1},
    {"obj.fx.erMin", Kind::Int, 177, -1},
    {"obj.fx.minPart", Kind::Int, 178, -1},
    {"obj.fx.sparkIso", Kind::Int, 179, -1},
    {"obj.fx.sparkMin", Kind::Double, -1, 128},
    {"obj.fx.sparkMax", Kind::Double, -1, 129},
    {"obj.fx.sparkDil", Kind::Int, 180, -1},
    {"obj.fx.sparkV", Kind::Int, 181, -1},
    {"obj.fx.longStraight", Kind::Double, -1, 130},
    {"obj.personFaint", Kind::Bool, 182, -1},
    {"obj.pfTouch", Kind::Int, 183, -1},
    {"obj.pfBridge", Kind::Int, 184, -1},
    {"obj.pfReach", Kind::Int, 185, -1},
    {"obj.pfHalo", Kind::Int, 186, -1},
    {"obj.textNeedsRegion", Kind::Bool, 187, -1},
    {"obj.pfShape", Kind::Bool, 188, -1},
    {"obj.pfClose", Kind::Int, 189, -1},
    {"obj.pfMargin", Kind::Int, 190, -1},
    {"obj.pfHole", Kind::Int, 191, -1},
    {"obj.pfLinePct", Kind::Int, 192, -1},
    {"obj.pfMark", Kind::Int, 193, -1},
    {"obj.pfStub", Kind::Int, 194, -1},
    {"obj.pfPair", Kind::Int, 195, -1},
    {"obj.textStroke", Kind::Bool, 196, -1},
    {"obj.tsInk", Kind::Int, 197, -1},
    {"obj.tsMin", Kind::Int, 198, -1},
    {"obj.tsG", Kind::Int, 199, -1},
    {"obj.tsRough", Kind::Int, 200, -1},
    {"obj.tsBandClean", Kind::Bool, 201, -1},
    {"obj.seam", Kind::Bool, 202, -1},
    {"obj.seamR", Kind::Int, 203, -1},
    {"obj.seamSpark", Kind::Int, 204, -1},
    {"obj.seamG", Kind::Int, 205, -1},
    {"obj.inpaintIslands", Kind::Bool, 206, -1},
    {"obj.islandInpD", Kind::Int, 207, -1},
    {"obj.islandInpPct", Kind::Int, 208, -1},
    {"obj.vetoEpm", Kind::Double, -1, 131},
    {"obj.vetoRc", Kind::Int, 209, -1},
    {"obj.vetoMinArea", Kind::Int, 210, -1},
    {"obj.vetoEvDil", Kind::Int, 211, -1},
    {"obj.vetoBackPad", Kind::Int, 212, -1},
    {"obj.darkG", Kind::Int, 213, -1},
    {"obj.lightTh", Kind::Int, 214, -1},
    {"obj.areaMin", Kind::Double, -1, 132},
    {"obj.lineMax", Kind::Double, -1, 133},
    {"obj.can4Max", Kind::Double, -1, 134},
    {"obj.fitMax", Kind::Double, -1, 135},
    {"obj.toneMax", Kind::Double, -1, 136},
    {"obj.toneGrad", Kind::Double, -1, 137},
    {"obj.chromaMax", Kind::Double, -1, 138},
    {"obj.thickMin", Kind::Double, -1, 139},
    {"obj.marks", Kind::Int, 215, -1},
    {"obj.marksDen", Kind::Double, -1, 140},
    {"obj.rLoc", Kind::Int, 216, -1},
    {"obj.coreMin", Kind::Double, -1, 141},
    {"obj.seal", Kind::Int, 217, -1},
    {"obj.ctxEpm", Kind::Double, -1, 142},
    {"obj.ctxWhite", Kind::Int, 218, -1},
    {"obj.islandMax", Kind::Double, -1, 143},
    {"obj.islandTouch", Kind::Int, 219, -1},
    {"obj.islandTouchMin", Kind::Int, 220, -1},
    {"obj.hugMax", Kind::Double, -1, 144},
    {"obj.longLen", Kind::Int, 221, -1},
    {"obj.longR", Kind::Int, 222, -1},
    {"obj.bhTh", Kind::Int, 223, -1},
    {"obj.lineR", Kind::Int, 224, -1},
    {"obj.brightTh", Kind::Int, 225, -1},
    {"obj.brightLrNum", Kind::Int, 226, -1},
    {"obj.brightLrDen", Kind::Int, 227, -1},
    {"obj.dot", Kind::Int, 228, -1},
    {"obj.dotBright", Kind::Int, 229, -1},
    {"obj.evMinArea", Kind::Int, 230, -1},
    {"obj.cannyLo", Kind::Int, 231, -1},
    {"obj.cannyHi", Kind::Int, 232, -1},
    {"obj.canny4Lo", Kind::Int, 233, -1},
    {"obj.canny4Hi", Kind::Int, 234, -1},
};

/** 一個欄位的值：Int／Bool／Enum／OptInt 的值在 i（OptInt 另有 present），Double／Float 在 d（Float 轉成 double）。 */
struct Value {
    int64_t i;
    double d;
    bool present;
};

/** 第 idx 個欄位（kFields 的順序）的值；idx 超出範圍回 false。 */
inline bool getField(const NightReadParams& p, int32_t idx, Value* v) {
    *v = Value{0, 0.0, true};
    switch (idx) {
        case 0: v->i = p.bg; return true;
        case 1: v->i = p.ink; return true;
        case 2: v->i = p.edgeInk; return true;
        case 3: v->i = p.stroke; return true;
        case 4: v->i = p.strokeObjV; return true;
        case 5: v->i = p.sceneFloor; return true;
        case 6: v->i = p.dimCeil; return true;
        case 7: v->d = static_cast<double>(p.glowStrength); return true;
        case 8: v->i = p.glowCap; return true;
        case 9: v->i = p.whiteTh; return true;
        case 10: v->i = p.inkDarkTh; return true;
        case 11: v->i = p.bubblePad; return true;
        case 12: v->d = p.gutterMinAreaFrac; return true;
        case 13: v->i = p.paperPeakLo; return true;
        case 14: v->i = p.paperNormMin; return true;
        case 15: v->d = p.paperNormChromaMax; return true;
        case 16: v->i = p.frameLineLDiv; return true;
        case 17: v->i = p.frameDarkTh; return true;
        case 18: v->d = p.frameMinEach; return true;
        case 19: v->d = p.frameMinSum; return true;
        case 20: v->d = p.framelessMarginDepth; return true;
        case 21: v->i = p.coreR; return true;
        case 22: v->d = p.deepEdgeFrac; return true;
        case 23: v->d = p.inPanelCoreFrac; return true;
        case 24: v->d = p.inPanelCoreDeep; return true;
        case 25: v->d = p.deepInkDeep; return true;
        case 26: v->d = p.deepInkRatio; return true;
        case 27: v->d = p.holeMaxFrac; return true;
        case 28: v->d = p.safeGutterDepth; return true;
        case 29: v->i = p.gfcDilate; return true;
        case 30: v->d = p.gfcCloseFrac; return true;
        case 31: v->d = p.textureTh; return true;
        case 32: v->d = p.textureHi; return true;
        case 33: v->i = p.textureWin; return true;
        case 34: v->i = p.texturePad; return true;
        case 35: v->i = p.textureMinArea; return true;
        case 36: v->i = p.textureFrameDil; return true;
        case 37: v->i = p.textureSegDil; return true;
        case 38: v->i = p.textureBubbleNear; return true;
        case 39: v->i = p.textureBubbleOutline; return true;
        case 40: v->i = p.textureClose; return true;
        case 41: v->d = p.textureRegionMin; return true;
        case 42: v->d = p.bubbleCompMaxFrac; return true;
        case 43: v->d = p.bubbleLocalK; return true;
        case 44: v->d = p.bubbleCoreMinFrac; return true;
        case 45: v->i = p.bubbleNeckR; return true;
        case 46: v->d = p.safeBubbleRatio; return true;
        case 47: v->d = p.bubbleCleanWins; return true;
        case 48: v->d = p.bubbleCleanTextMax; return true;
        case 49: v->i = p.bubbleCleanInkHoles ? 1 : 0; return true;
        case 50: v->i = p.bubbleGuardRaw ? 1 : 0; return true;
        case 51: v->d = p.bubbleConfirmTextIn; return true;
        case 52: v->i = p.bubbleLeak ? 1 : 0; return true;
        case 53: v->i = p.bubbleLeakRing; return true;
        case 54: v->i = p.bubbleLeakRange; return true;
        case 55: v->i = p.bubbleLeakWin; return true;
        case 56: v->d = p.bubbleLeakEdgeMax; return true;
        case 57: v->i = p.bubbleLeakRingMin; return true;
        case 58: v->i = p.bubbleLeakMinArea; return true;
        case 59: v->i = p.bubbleRestNear; return true;
        case 60: v->d = p.bubbleOutlineMin; return true;
        case 61: v->i = p.bubbleOutlineDist; return true;
        case 62: v->i = p.bubbleOutlineMinPx; return true;
        case 63: v->i = p.bubbleOutlinePad; return true;
        case 64: v->i = p.bubbleSeal.r; return true;
        case 65: v->d = p.bubbleSeal.charMax; return true;
        case 66: v->d = p.bubbleSeal.textIn; return true;
        case 67: v->i = p.bubbleSeal.restMin; return true;
        case 68: v->i = p.bubbleSeal.maxGaps; return true;
        case 69: v->present = p.bubbleSeal.cutGeo.present; v->i = p.bubbleSeal.cutGeo.value; return true;
        case 70: v->d = p.pbCovMax; return true;
        case 71: v->i = p.pbNeckR; return true;
        case 72: v->d = p.pbGrowFrac; return true;
        case 73: v->i = p.pbAuraR; return true;
        case 74: v->i = p.pbAuraThick; return true;
        case 75: v->i = p.pbAuraMinArea; return true;
        case 76: v->d = p.strokeObjFrac; return true;
        case 77: v->i = p.strokeObjMin; return true;
        case 78: v->i = p.strokeObjMax; return true;
        case 79: v->d = p.stickerMinFrac; return true;
        case 80: v->i = p.figNoiseArea; return true;
        case 81: v->i = p.figNoiseClose; return true;
        case 82: v->d = p.stickerFigMin; return true;
        case 83: v->d = p.stickerFigMax; return true;
        case 84: v->i = p.stickerThinR; return true;
        case 85: v->d = p.stickerThinMax; return true;
        case 86: v->d = p.stickerChromaMax; return true;
        case 87: v->i = p.stickerEatenR; return true;
        case 88: v->d = p.stickerEatenDens; return true;
        case 89: v->d = p.stickerEatenMax; return true;
        case 90: v->d = p.stickerEatenHard; return true;
        case 91: v->d = p.stickerTextBgMin; return true;
        case 92: v->d = p.stickerTextMax; return true;
        case 93: v->i = p.stickerTextOnPad; return true;
        case 94: v->d = p.stickerSmallArea; return true;
        case 95: v->i = p.stickerNeckR; return true;
        case 96: v->d = p.stickerCoreMin; return true;
        case 97: v->d = p.stickerProtectEatenMin; return true;
        case 98: v->i = p.stickerProtectDilate; return true;
        case 99: v->d = p.faintOfFMax; return true;
        case 100: v->i = p.faintG; return true;
        case 101: v->i = p.frameHugDilate; return true;
        case 102: v->d = p.frameHugThick; return true;
        case 103: v->d = p.frameHugMin; return true;
        case 104: v->d = p.frameHugStrong; return true;
        case 105: v->d = p.hugSideMin; return true;
        case 106: v->d = p.promotedTextOnMax; return true;
        case 107: v->i = static_cast<int32_t>(p.stickerMode); return true;
        case 108: v->d = p.stickerRoughMax; return true;
        case 109: v->d = p.stickerSimpleMinFrac; return true;
        case 110: v->i = p.stickerPlainRingR; return true;
        case 111: v->d = p.stickerPlainArtMax; return true;
        case 112: v->i = p.stickerPlainFrameDil; return true;
        case 113: v->d = p.stickerPlainFaintMax; return true;
        case 114: v->i = p.stickerPlainFaintHalo; return true;
        case 115: v->i = p.more.enabled ? 1 : 0; return true;
        case 116: v->d = p.more.tOut; return true;
        case 117: v->d = p.more.tIn; return true;
        case 118: v->d = p.more.faintMax; return true;
        case 119: v->d = p.more.roughCap; return true;
        case 120: v->d = p.more.charMax; return true;
        case 121: v->d = p.more.hyst; return true;
        case 122: v->i = p.more.holeMinArea; return true;
        case 123: v->i = p.more.holeDarkMax; return true;
        case 124: v->i = p.more.median; return true;
        case 125: v->i = p.more.explainDil; return true;
        case 126: v->i = p.more.nearDil; return true;
        case 127: v->i = p.more.ringR; return true;
        case 128: v->i = p.more.halo; return true;
        case 129: v->i = p.more.winPad; return true;
        case 130: v->i = p.more.keepDarkStroke ? 1 : 0; return true;
        case 131: v->i = p.more.edgeSeedFallback ? 1 : 0; return true;
        case 132: v->i = p.more.edgeBand; return true;
        case 133: v->i = p.more.edgeReach; return true;
        case 134: v->i = p.pseudoBubbles ? 1 : 0; return true;
        case 135: v->i = p.harmonize ? 1 : 0; return true;
        case 136: v->i = p.coreNeckR; return true;
        case 137: v->i = p.coreRecoverR; return true;
        case 138: v->d = p.geoRatioMax; return true;
        case 139: v->d = p.geoSlack; return true;
        case 140: v->i = p.coreReleasePad; return true;
        case 141: v->i = p.charSnap; return true;
        case 142: v->i = p.charSnapPad; return true;
        case 143: v->i = p.maskSmoothMedian; return true;
        case 144: v->d = p.edgeFeather; return true;
        case 145: v->i = p.textPad; return true;
        case 146: v->d = p.textGamma; return true;
        case 147: v->d = p.textKnee; return true;
        case 148: v->i = p.textTopPad; return true;
        case 149: v->i = p.textBackingR; return true;
        case 150: v->i = p.harmonizeZoneCell; return true;
        case 151: v->d = p.harmonizeZoneDark; return true;
        case 152: v->d = p.harmonizeInZone; return true;
        case 153: v->d = p.harmonizeAreaMax; return true;
        case 154: v->d = p.harmonizeCollarInk; return true;
        case 155: v->i = p.separators ? 1 : 0; return true;
        case 156: v->d = p.sep.lenFrac; return true;
        case 157: v->i = p.sep.nearWhiteR; return true;
        case 158: v->d = p.sep.thStep; return true;
        case 159: v->i = p.sep.walkWin0; return true;
        case 160: v->i = p.sep.walkWin; return true;
        case 161: v->i = p.sep.gap; return true;
        case 162: v->d = p.sep.fillMin; return true;
        case 163: v->i = p.sep.peakNmsT; return true;
        case 164: v->i = p.sep.peakNmsR; return true;
        case 165: v->i = p.sep.peakMax; return true;
        case 166: v->d = p.sep.peakVoteFrac; return true;
        case 167: v->d = p.sep.dedupeAng; return true;
        case 168: v->d = p.sep.dedupeOff; return true;
        case 169: v->d = p.sep.groupAng; return true;
        case 170: v->d = p.sep.groupOff; return true;
        case 171: v->d = p.sep.pairAng; return true;
        case 172: v->i = p.sep.gapMin; return true;
        case 173: v->i = p.sep.edgeSkip; return true;
        case 174: v->d = p.sep.profWhite; return true;
        case 175: v->d = p.sep.profWhiteBridge; return true;
        case 176: v->i = p.sep.shortBridge; return true;
        case 177: v->i = p.sep.profGapFill; return true;
        case 178: v->d = p.sep.passMin; return true;
        case 179: v->d = p.sep.stripExtFrac; return true;
        case 180: v->d = p.sep.bridgeFrac; return true;
        case 181: v->d = p.sep.edgeMadMax; return true;
        case 182: v->i = p.sep.textDil; return true;
        case 183: v->d = p.sep.textProfMax; return true;
        case 184: v->d = p.sep.familyAng; return true;
        case 185: v->d = p.sep.familyReach; return true;
        case 186: v->d = p.sep.wmaxFrac; return true;
        case 187: v->d = p.sep.ovlFrac; return true;
        case 188: v->i = p.sep.extDarkSkip; return true;
        case 189: v->i = p.sep.bubLineR; return true;
        case 190: v->d = p.sep.bubLineMax; return true;
        case 191: v->i = p.sep.netTouch; return true;
        case 192: v->i = p.sep.netThick; return true;
        case 193: v->d = p.sep.netExtFrac; return true;
        case 194: v->d = p.sep.marginRunFrac; return true;
        case 195: v->d = p.sep.marginLightFrac; return true;
        case 196: v->i = p.sep.marginLightMin; return true;
        case 197: v->i = p.sep.farProbe; return true;
        case 198: v->i = p.sep.marginOkTh; return true;
        case 199: v->d = p.sep.marginFrac; return true;
        case 200: v->i = p.sep.flR; return true;
        case 201: v->i = p.sep.flHitR; return true;
        case 202: v->d = p.sep.marginEdgeAng; return true;
        case 203: v->d = p.sep.marginAxisAng; return true;
        case 204: v->d = p.sep.marginFillMin; return true;
        case 205: v->i = p.sep.marginOcc ? 1 : 0; return true;
        case 206: v->i = p.sep.marginClose ? 1 : 0; return true;
        case 207: v->d = p.sep.occPieceFrac; return true;
        case 208: v->d = p.sep.occGapFrac; return true;
        case 209: v->i = p.sep.marginHaloR; return true;
        case 210: v->i = p.sep.bubDil; return true;
        case 211: v->d = p.sep.pairSubMax; return true;
        case 212: v->i = p.sep.minCc; return true;
        case 213: v->i = p.bleedFilter ? 1 : 0; return true;
        case 214: v->i = p.bleed.ringR; return true;
        case 215: v->i = p.bleed.ringEdge; return true;
        case 216: v->i = p.bleed.piecePad; return true;
        case 217: v->i = p.bleed.frDil; return true;
        case 218: v->i = p.bleed.spDil; return true;
        case 219: v->i = p.bleed.bbR; return true;
        case 220: v->i = p.bleed.txR; return true;
        case 221: v->i = p.bleed.chR; return true;
        case 222: v->i = p.bleed.textBoxPad; return true;
        case 223: v->i = p.bleed.touchPx; return true;
        case 224: v->d = p.bleed.netFrac; return true;
        case 225: v->d = p.bleed.txtKeep; return true;
        case 226: v->d = p.bleed.frameKeep; return true;
        case 227: v->d = p.bleed.islandBbMax; return true;
        case 228: v->d = p.bleed.enclosedMax; return true;
        case 229: v->d = p.bleed.artDrop; return true;
        case 230: v->i = p.bleed.marginOk; return true;
        case 231: v->d = p.bleed.marginFrac; return true;
        case 232: v->d = p.bleed.marginLightMaxFrac; return true;
        case 233: v->i = p.bleed.marginLightMin; return true;
        case 234: v->i = p.bleed.marginMinRows; return true;
        case 235: v->i = p.bleed.marginNeuLook; return true;
        case 236: v->i = p.bleed.marginMinDepth; return true;
        case 237: v->d = p.bleed.marginCover; return true;
        case 238: v->d = p.bleed.marginAng; return true;
        case 239: v->i = p.bleed.marginDark; return true;
        case 240: v->i = p.bleed.marginLineMax; return true;
        case 241: v->i = p.bleed.mlineMinRun; return true;
        case 242: v->i = p.ring.enabled ? 1 : 0; return true;
        case 243: v->i = p.ring.rOut; return true;
        case 244: v->i = p.ring.seedMin; return true;
        case 245: v->i = p.ring.gvGap; return true;
        case 246: v->i = p.ring.rawClose; return true;
        case 247: v->i = p.ring.steps; return true;
        case 248: v->i = p.ring.inkBox; return true;
        case 249: v->i = p.ring.inkSum; return true;
        case 250: v->i = p.ring.inkMaxGray; return true;
        case 251: v->i = p.ring.holeMax; return true;
        case 252: v->i = p.ring.evIn; return true;
        case 253: v->i = p.ring.evOut; return true;
        case 254: v->i = p.ring.rayFrom; return true;
        case 255: v->i = p.ring.rayTo; return true;
        case 256: v->d = p.ring.raySigma; return true;
        case 257: v->i = p.ring.gapClose; return true;
        case 258: v->i = p.ring.breakPad; return true;
        case 259: v->i = p.ring.faintGray; return true;
        case 260: v->i = p.ring.faintInkPad; return true;
        case 261: v->i = p.ring.faintWin; return true;
        case 262: v->i = p.ring.faintMin; return true;
        case 263: v->i = p.ring.open; return true;
        case 264: v->i = p.ring.seedConnected ? 1 : 0; return true;
        case 265: v->i = p.obj.enabled ? 1 : 0; return true;
        case 266: v->i = p.obj.veto ? 1 : 0; return true;
        case 267: v->i = p.obj.lightFill ? 1 : 0; return true;
        case 268: v->i = p.obj.fxLines ? 1 : 0; return true;
        case 269: v->i = p.obj.fxSparks ? 1 : 0; return true;
        case 270: v->d = p.obj.fx.segSd; return true;
        case 271: v->i = p.obj.fx.segMin; return true;
        case 272: v->d = p.obj.fx.cosJoin; return true;
        case 273: v->d = p.obj.fx.perp; return true;
        case 274: v->d = p.obj.fx.gap; return true;
        case 275: v->d = p.obj.fx.lineMin; return true;
        case 276: v->d = p.obj.fx.cosVp; return true;
        case 277: v->i = p.obj.fx.vpTop; return true;
        case 278: v->i = p.obj.fx.maxFamilies; return true;
        case 279: v->i = p.obj.fx.minMembers; return true;
        case 280: v->d = p.obj.fx.cosSpread; return true;
        case 281: v->d = p.obj.fx.taper; return true;
        case 282: v->d = p.obj.fx.taperFrac; return true;
        case 283: v->i = p.obj.fx.freeMin; return true;
        case 284: v->d = p.obj.fx.freeFrac; return true;
        case 285: v->i = p.obj.fx.ray0; return true;
        case 286: v->i = p.obj.fx.ray1; return true;
        case 287: v->i = p.obj.fx.rayG; return true;
        case 288: v->i = p.obj.fx.terr; return true;
        case 289: v->d = p.obj.fx.cosAtol2; return true;
        case 290: v->d = p.obj.fx.coh; return true;
        case 291: v->d = p.obj.fx.epsJ; return true;
        case 292: v->d = p.obj.fx.si; return true;
        case 293: v->i = p.obj.fx.vpNear; return true;
        case 294: v->d = p.obj.fx.mark; return true;
        case 295: v->i = p.obj.fx.lineR; return true;
        case 296: v->i = p.obj.fx.excl; return true;
        case 297: v->i = p.obj.fx.exclT; return true;
        case 298: v->d = p.obj.fx.agree; return true;
        case 299: v->d = p.obj.fx.resid; return true;
        case 300: v->i = p.obj.fx.nMem; return true;
        case 301: v->i = p.obj.fx.lineV; return true;
        case 302: v->i = p.obj.fx.grow; return true;
        case 303: v->i = p.obj.fx.halo; return true;
        case 304: v->i = p.obj.fx.erMin; return true;
        case 305: v->i = p.obj.fx.minPart; return true;
        case 306: v->i = p.obj.fx.sparkIso; return true;
        case 307: v->d = p.obj.fx.sparkMin; return true;
        case 308: v->d = p.obj.fx.sparkMax; return true;
        case 309: v->i = p.obj.fx.sparkDil; return true;
        case 310: v->i = p.obj.fx.sparkV; return true;
        case 311: v->d = p.obj.fx.longStraight; return true;
        case 312: v->i = p.obj.personFaint ? 1 : 0; return true;
        case 313: v->i = p.obj.pfTouch; return true;
        case 314: v->i = p.obj.pfBridge; return true;
        case 315: v->i = p.obj.pfReach; return true;
        case 316: v->i = p.obj.pfHalo; return true;
        case 317: v->i = p.obj.textNeedsRegion ? 1 : 0; return true;
        case 318: v->i = p.obj.pfShape ? 1 : 0; return true;
        case 319: v->i = p.obj.pfClose; return true;
        case 320: v->i = p.obj.pfMargin; return true;
        case 321: v->i = p.obj.pfHole; return true;
        case 322: v->i = p.obj.pfLinePct; return true;
        case 323: v->i = p.obj.pfMark; return true;
        case 324: v->i = p.obj.pfStub; return true;
        case 325: v->i = p.obj.pfPair; return true;
        case 326: v->i = p.obj.textStroke ? 1 : 0; return true;
        case 327: v->i = p.obj.tsInk; return true;
        case 328: v->i = p.obj.tsMin; return true;
        case 329: v->i = p.obj.tsG; return true;
        case 330: v->i = p.obj.tsRough; return true;
        case 331: v->i = p.obj.tsBandClean ? 1 : 0; return true;
        case 332: v->i = p.obj.seam ? 1 : 0; return true;
        case 333: v->i = p.obj.seamR; return true;
        case 334: v->i = p.obj.seamSpark; return true;
        case 335: v->i = p.obj.seamG; return true;
        case 336: v->i = p.obj.inpaintIslands ? 1 : 0; return true;
        case 337: v->i = p.obj.islandInpD; return true;
        case 338: v->i = p.obj.islandInpPct; return true;
        case 339: v->d = p.obj.vetoEpm; return true;
        case 340: v->i = p.obj.vetoRc; return true;
        case 341: v->i = p.obj.vetoMinArea; return true;
        case 342: v->i = p.obj.vetoEvDil; return true;
        case 343: v->i = p.obj.vetoBackPad; return true;
        case 344: v->i = p.obj.darkG; return true;
        case 345: v->i = p.obj.lightTh; return true;
        case 346: v->d = p.obj.areaMin; return true;
        case 347: v->d = p.obj.lineMax; return true;
        case 348: v->d = p.obj.can4Max; return true;
        case 349: v->d = p.obj.fitMax; return true;
        case 350: v->d = p.obj.toneMax; return true;
        case 351: v->d = p.obj.toneGrad; return true;
        case 352: v->d = p.obj.chromaMax; return true;
        case 353: v->d = p.obj.thickMin; return true;
        case 354: v->i = p.obj.marks; return true;
        case 355: v->d = p.obj.marksDen; return true;
        case 356: v->i = p.obj.rLoc; return true;
        case 357: v->d = p.obj.coreMin; return true;
        case 358: v->i = p.obj.seal; return true;
        case 359: v->d = p.obj.ctxEpm; return true;
        case 360: v->i = p.obj.ctxWhite; return true;
        case 361: v->d = p.obj.islandMax; return true;
        case 362: v->i = p.obj.islandTouch; return true;
        case 363: v->i = p.obj.islandTouchMin; return true;
        case 364: v->d = p.obj.hugMax; return true;
        case 365: v->i = p.obj.longLen; return true;
        case 366: v->i = p.obj.longR; return true;
        case 367: v->i = p.obj.bhTh; return true;
        case 368: v->i = p.obj.lineR; return true;
        case 369: v->i = p.obj.brightTh; return true;
        case 370: v->i = p.obj.brightLrNum; return true;
        case 371: v->i = p.obj.brightLrDen; return true;
        case 372: v->i = p.obj.dot; return true;
        case 373: v->i = p.obj.dotBright; return true;
        case 374: v->i = p.obj.evMinArea; return true;
        case 375: v->i = p.obj.cannyLo; return true;
        case 376: v->i = p.obj.cannyHi; return true;
        case 377: v->i = p.obj.canny4Lo; return true;
        case 378: v->i = p.obj.canny4Hi; return true;
        default: return false;
    }
}

/** 設第 idx 個欄位；值不合該型別（Bool 不是 0／1、enum 超出範圍、Int 超出 int32、Float 轉不回 float）回 false。 */
inline bool setField(NightReadParams* p, int32_t idx, const Value& v) {
    const bool intOk = v.i >= INT32_MIN && v.i <= INT32_MAX;
    switch (idx) {
        case 0: if (!intOk) return false; p->bg = static_cast<int32_t>(v.i); return true;
        case 1: if (!intOk) return false; p->ink = static_cast<int32_t>(v.i); return true;
        case 2: if (!intOk) return false; p->edgeInk = static_cast<int32_t>(v.i); return true;
        case 3: if (!intOk) return false; p->stroke = static_cast<int32_t>(v.i); return true;
        case 4: if (!intOk) return false; p->strokeObjV = static_cast<int32_t>(v.i); return true;
        case 5: if (!intOk) return false; p->sceneFloor = static_cast<int32_t>(v.i); return true;
        case 6: if (!intOk) return false; p->dimCeil = static_cast<int32_t>(v.i); return true;
        case 7: return detail::narrow(v.d, &p->glowStrength);
        case 8: if (!intOk) return false; p->glowCap = static_cast<int32_t>(v.i); return true;
        case 9: if (!intOk) return false; p->whiteTh = static_cast<int32_t>(v.i); return true;
        case 10: if (!intOk) return false; p->inkDarkTh = static_cast<int32_t>(v.i); return true;
        case 11: if (!intOk) return false; p->bubblePad = static_cast<int32_t>(v.i); return true;
        case 12: p->gutterMinAreaFrac = v.d; return true;
        case 13: if (!intOk) return false; p->paperPeakLo = static_cast<int32_t>(v.i); return true;
        case 14: if (!intOk) return false; p->paperNormMin = static_cast<int32_t>(v.i); return true;
        case 15: p->paperNormChromaMax = v.d; return true;
        case 16: if (!intOk) return false; p->frameLineLDiv = static_cast<int32_t>(v.i); return true;
        case 17: if (!intOk) return false; p->frameDarkTh = static_cast<int32_t>(v.i); return true;
        case 18: p->frameMinEach = v.d; return true;
        case 19: p->frameMinSum = v.d; return true;
        case 20: p->framelessMarginDepth = v.d; return true;
        case 21: if (!intOk) return false; p->coreR = static_cast<int32_t>(v.i); return true;
        case 22: p->deepEdgeFrac = v.d; return true;
        case 23: p->inPanelCoreFrac = v.d; return true;
        case 24: p->inPanelCoreDeep = v.d; return true;
        case 25: p->deepInkDeep = v.d; return true;
        case 26: p->deepInkRatio = v.d; return true;
        case 27: p->holeMaxFrac = v.d; return true;
        case 28: p->safeGutterDepth = v.d; return true;
        case 29: if (!intOk) return false; p->gfcDilate = static_cast<int32_t>(v.i); return true;
        case 30: p->gfcCloseFrac = v.d; return true;
        case 31: p->textureTh = v.d; return true;
        case 32: p->textureHi = v.d; return true;
        case 33: if (!intOk) return false; p->textureWin = static_cast<int32_t>(v.i); return true;
        case 34: if (!intOk) return false; p->texturePad = static_cast<int32_t>(v.i); return true;
        case 35: if (!intOk) return false; p->textureMinArea = static_cast<int32_t>(v.i); return true;
        case 36: if (!intOk) return false; p->textureFrameDil = static_cast<int32_t>(v.i); return true;
        case 37: if (!intOk) return false; p->textureSegDil = static_cast<int32_t>(v.i); return true;
        case 38: if (!intOk) return false; p->textureBubbleNear = static_cast<int32_t>(v.i); return true;
        case 39: if (!intOk) return false; p->textureBubbleOutline = static_cast<int32_t>(v.i); return true;
        case 40: if (!intOk) return false; p->textureClose = static_cast<int32_t>(v.i); return true;
        case 41: p->textureRegionMin = v.d; return true;
        case 42: p->bubbleCompMaxFrac = v.d; return true;
        case 43: p->bubbleLocalK = v.d; return true;
        case 44: p->bubbleCoreMinFrac = v.d; return true;
        case 45: if (!intOk) return false; p->bubbleNeckR = static_cast<int32_t>(v.i); return true;
        case 46: p->safeBubbleRatio = v.d; return true;
        case 47: p->bubbleCleanWins = v.d; return true;
        case 48: p->bubbleCleanTextMax = v.d; return true;
        case 49: if (v.i != 0 && v.i != 1) return false; p->bubbleCleanInkHoles = v.i == 1; return true;
        case 50: if (v.i != 0 && v.i != 1) return false; p->bubbleGuardRaw = v.i == 1; return true;
        case 51: p->bubbleConfirmTextIn = v.d; return true;
        case 52: if (v.i != 0 && v.i != 1) return false; p->bubbleLeak = v.i == 1; return true;
        case 53: if (!intOk) return false; p->bubbleLeakRing = static_cast<int32_t>(v.i); return true;
        case 54: if (!intOk) return false; p->bubbleLeakRange = static_cast<int32_t>(v.i); return true;
        case 55: if (!intOk) return false; p->bubbleLeakWin = static_cast<int32_t>(v.i); return true;
        case 56: p->bubbleLeakEdgeMax = v.d; return true;
        case 57: if (!intOk) return false; p->bubbleLeakRingMin = static_cast<int32_t>(v.i); return true;
        case 58: if (!intOk) return false; p->bubbleLeakMinArea = static_cast<int32_t>(v.i); return true;
        case 59: if (!intOk) return false; p->bubbleRestNear = static_cast<int32_t>(v.i); return true;
        case 60: p->bubbleOutlineMin = v.d; return true;
        case 61: if (!intOk) return false; p->bubbleOutlineDist = static_cast<int32_t>(v.i); return true;
        case 62: if (!intOk) return false; p->bubbleOutlineMinPx = static_cast<int32_t>(v.i); return true;
        case 63: if (!intOk) return false; p->bubbleOutlinePad = static_cast<int32_t>(v.i); return true;
        case 64: if (!intOk) return false; p->bubbleSeal.r = static_cast<int32_t>(v.i); return true;
        case 65: p->bubbleSeal.charMax = v.d; return true;
        case 66: p->bubbleSeal.textIn = v.d; return true;
        case 67: if (!intOk) return false; p->bubbleSeal.restMin = static_cast<int32_t>(v.i); return true;
        case 68: if (!intOk) return false; p->bubbleSeal.maxGaps = static_cast<int32_t>(v.i); return true;
        case 69: if (!intOk || (!v.present && v.i != 0)) return false; p->bubbleSeal.cutGeo = OptI32{v.present, static_cast<int32_t>(v.i)}; return true;
        case 70: p->pbCovMax = v.d; return true;
        case 71: if (!intOk) return false; p->pbNeckR = static_cast<int32_t>(v.i); return true;
        case 72: p->pbGrowFrac = v.d; return true;
        case 73: if (!intOk) return false; p->pbAuraR = static_cast<int32_t>(v.i); return true;
        case 74: if (!intOk) return false; p->pbAuraThick = static_cast<int32_t>(v.i); return true;
        case 75: if (!intOk) return false; p->pbAuraMinArea = static_cast<int32_t>(v.i); return true;
        case 76: p->strokeObjFrac = v.d; return true;
        case 77: if (!intOk) return false; p->strokeObjMin = static_cast<int32_t>(v.i); return true;
        case 78: if (!intOk) return false; p->strokeObjMax = static_cast<int32_t>(v.i); return true;
        case 79: p->stickerMinFrac = v.d; return true;
        case 80: if (!intOk) return false; p->figNoiseArea = static_cast<int32_t>(v.i); return true;
        case 81: if (!intOk) return false; p->figNoiseClose = static_cast<int32_t>(v.i); return true;
        case 82: p->stickerFigMin = v.d; return true;
        case 83: p->stickerFigMax = v.d; return true;
        case 84: if (!intOk) return false; p->stickerThinR = static_cast<int32_t>(v.i); return true;
        case 85: p->stickerThinMax = v.d; return true;
        case 86: p->stickerChromaMax = v.d; return true;
        case 87: if (!intOk) return false; p->stickerEatenR = static_cast<int32_t>(v.i); return true;
        case 88: p->stickerEatenDens = v.d; return true;
        case 89: p->stickerEatenMax = v.d; return true;
        case 90: p->stickerEatenHard = v.d; return true;
        case 91: p->stickerTextBgMin = v.d; return true;
        case 92: p->stickerTextMax = v.d; return true;
        case 93: if (!intOk) return false; p->stickerTextOnPad = static_cast<int32_t>(v.i); return true;
        case 94: p->stickerSmallArea = v.d; return true;
        case 95: if (!intOk) return false; p->stickerNeckR = static_cast<int32_t>(v.i); return true;
        case 96: p->stickerCoreMin = v.d; return true;
        case 97: p->stickerProtectEatenMin = v.d; return true;
        case 98: if (!intOk) return false; p->stickerProtectDilate = static_cast<int32_t>(v.i); return true;
        case 99: p->faintOfFMax = v.d; return true;
        case 100: if (!intOk) return false; p->faintG = static_cast<int32_t>(v.i); return true;
        case 101: if (!intOk) return false; p->frameHugDilate = static_cast<int32_t>(v.i); return true;
        case 102: p->frameHugThick = v.d; return true;
        case 103: p->frameHugMin = v.d; return true;
        case 104: p->frameHugStrong = v.d; return true;
        case 105: p->hugSideMin = v.d; return true;
        case 106: p->promotedTextOnMax = v.d; return true;
        case 107: if (v.i < 0 || v.i >= kStickerModeCount) return false; p->stickerMode = static_cast<StickerMode>(v.i); return true;
        case 108: p->stickerRoughMax = v.d; return true;
        case 109: p->stickerSimpleMinFrac = v.d; return true;
        case 110: if (!intOk) return false; p->stickerPlainRingR = static_cast<int32_t>(v.i); return true;
        case 111: p->stickerPlainArtMax = v.d; return true;
        case 112: if (!intOk) return false; p->stickerPlainFrameDil = static_cast<int32_t>(v.i); return true;
        case 113: p->stickerPlainFaintMax = v.d; return true;
        case 114: if (!intOk) return false; p->stickerPlainFaintHalo = static_cast<int32_t>(v.i); return true;
        case 115: if (v.i != 0 && v.i != 1) return false; p->more.enabled = v.i == 1; return true;
        case 116: p->more.tOut = v.d; return true;
        case 117: p->more.tIn = v.d; return true;
        case 118: p->more.faintMax = v.d; return true;
        case 119: p->more.roughCap = v.d; return true;
        case 120: p->more.charMax = v.d; return true;
        case 121: p->more.hyst = v.d; return true;
        case 122: if (!intOk) return false; p->more.holeMinArea = static_cast<int32_t>(v.i); return true;
        case 123: if (!intOk) return false; p->more.holeDarkMax = static_cast<int32_t>(v.i); return true;
        case 124: if (!intOk) return false; p->more.median = static_cast<int32_t>(v.i); return true;
        case 125: if (!intOk) return false; p->more.explainDil = static_cast<int32_t>(v.i); return true;
        case 126: if (!intOk) return false; p->more.nearDil = static_cast<int32_t>(v.i); return true;
        case 127: if (!intOk) return false; p->more.ringR = static_cast<int32_t>(v.i); return true;
        case 128: if (!intOk) return false; p->more.halo = static_cast<int32_t>(v.i); return true;
        case 129: if (!intOk) return false; p->more.winPad = static_cast<int32_t>(v.i); return true;
        case 130: if (v.i != 0 && v.i != 1) return false; p->more.keepDarkStroke = v.i == 1; return true;
        case 131: if (v.i != 0 && v.i != 1) return false; p->more.edgeSeedFallback = v.i == 1; return true;
        case 132: if (!intOk) return false; p->more.edgeBand = static_cast<int32_t>(v.i); return true;
        case 133: if (!intOk) return false; p->more.edgeReach = static_cast<int32_t>(v.i); return true;
        case 134: if (v.i != 0 && v.i != 1) return false; p->pseudoBubbles = v.i == 1; return true;
        case 135: if (v.i != 0 && v.i != 1) return false; p->harmonize = v.i == 1; return true;
        case 136: if (!intOk) return false; p->coreNeckR = static_cast<int32_t>(v.i); return true;
        case 137: if (!intOk) return false; p->coreRecoverR = static_cast<int32_t>(v.i); return true;
        case 138: p->geoRatioMax = v.d; return true;
        case 139: p->geoSlack = v.d; return true;
        case 140: if (!intOk) return false; p->coreReleasePad = static_cast<int32_t>(v.i); return true;
        case 141: if (!intOk) return false; p->charSnap = static_cast<int32_t>(v.i); return true;
        case 142: if (!intOk) return false; p->charSnapPad = static_cast<int32_t>(v.i); return true;
        case 143: if (!intOk) return false; p->maskSmoothMedian = static_cast<int32_t>(v.i); return true;
        case 144: p->edgeFeather = v.d; return true;
        case 145: if (!intOk) return false; p->textPad = static_cast<int32_t>(v.i); return true;
        case 146: p->textGamma = v.d; return true;
        case 147: p->textKnee = v.d; return true;
        case 148: if (!intOk) return false; p->textTopPad = static_cast<int32_t>(v.i); return true;
        case 149: if (!intOk) return false; p->textBackingR = static_cast<int32_t>(v.i); return true;
        case 150: if (!intOk) return false; p->harmonizeZoneCell = static_cast<int32_t>(v.i); return true;
        case 151: p->harmonizeZoneDark = v.d; return true;
        case 152: p->harmonizeInZone = v.d; return true;
        case 153: p->harmonizeAreaMax = v.d; return true;
        case 154: p->harmonizeCollarInk = v.d; return true;
        case 155: if (v.i != 0 && v.i != 1) return false; p->separators = v.i == 1; return true;
        case 156: p->sep.lenFrac = v.d; return true;
        case 157: if (!intOk) return false; p->sep.nearWhiteR = static_cast<int32_t>(v.i); return true;
        case 158: p->sep.thStep = v.d; return true;
        case 159: if (!intOk) return false; p->sep.walkWin0 = static_cast<int32_t>(v.i); return true;
        case 160: if (!intOk) return false; p->sep.walkWin = static_cast<int32_t>(v.i); return true;
        case 161: if (!intOk) return false; p->sep.gap = static_cast<int32_t>(v.i); return true;
        case 162: p->sep.fillMin = v.d; return true;
        case 163: if (!intOk) return false; p->sep.peakNmsT = static_cast<int32_t>(v.i); return true;
        case 164: if (!intOk) return false; p->sep.peakNmsR = static_cast<int32_t>(v.i); return true;
        case 165: if (!intOk) return false; p->sep.peakMax = static_cast<int32_t>(v.i); return true;
        case 166: p->sep.peakVoteFrac = v.d; return true;
        case 167: p->sep.dedupeAng = v.d; return true;
        case 168: p->sep.dedupeOff = v.d; return true;
        case 169: p->sep.groupAng = v.d; return true;
        case 170: p->sep.groupOff = v.d; return true;
        case 171: p->sep.pairAng = v.d; return true;
        case 172: if (!intOk) return false; p->sep.gapMin = static_cast<int32_t>(v.i); return true;
        case 173: if (!intOk) return false; p->sep.edgeSkip = static_cast<int32_t>(v.i); return true;
        case 174: p->sep.profWhite = v.d; return true;
        case 175: p->sep.profWhiteBridge = v.d; return true;
        case 176: if (!intOk) return false; p->sep.shortBridge = static_cast<int32_t>(v.i); return true;
        case 177: if (!intOk) return false; p->sep.profGapFill = static_cast<int32_t>(v.i); return true;
        case 178: p->sep.passMin = v.d; return true;
        case 179: p->sep.stripExtFrac = v.d; return true;
        case 180: p->sep.bridgeFrac = v.d; return true;
        case 181: p->sep.edgeMadMax = v.d; return true;
        case 182: if (!intOk) return false; p->sep.textDil = static_cast<int32_t>(v.i); return true;
        case 183: p->sep.textProfMax = v.d; return true;
        case 184: p->sep.familyAng = v.d; return true;
        case 185: p->sep.familyReach = v.d; return true;
        case 186: p->sep.wmaxFrac = v.d; return true;
        case 187: p->sep.ovlFrac = v.d; return true;
        case 188: if (!intOk) return false; p->sep.extDarkSkip = static_cast<int32_t>(v.i); return true;
        case 189: if (!intOk) return false; p->sep.bubLineR = static_cast<int32_t>(v.i); return true;
        case 190: p->sep.bubLineMax = v.d; return true;
        case 191: if (!intOk) return false; p->sep.netTouch = static_cast<int32_t>(v.i); return true;
        case 192: if (!intOk) return false; p->sep.netThick = static_cast<int32_t>(v.i); return true;
        case 193: p->sep.netExtFrac = v.d; return true;
        case 194: p->sep.marginRunFrac = v.d; return true;
        case 195: p->sep.marginLightFrac = v.d; return true;
        case 196: if (!intOk) return false; p->sep.marginLightMin = static_cast<int32_t>(v.i); return true;
        case 197: if (!intOk) return false; p->sep.farProbe = static_cast<int32_t>(v.i); return true;
        case 198: if (!intOk) return false; p->sep.marginOkTh = static_cast<int32_t>(v.i); return true;
        case 199: p->sep.marginFrac = v.d; return true;
        case 200: if (!intOk) return false; p->sep.flR = static_cast<int32_t>(v.i); return true;
        case 201: if (!intOk) return false; p->sep.flHitR = static_cast<int32_t>(v.i); return true;
        case 202: p->sep.marginEdgeAng = v.d; return true;
        case 203: p->sep.marginAxisAng = v.d; return true;
        case 204: p->sep.marginFillMin = v.d; return true;
        case 205: if (v.i != 0 && v.i != 1) return false; p->sep.marginOcc = v.i == 1; return true;
        case 206: if (v.i != 0 && v.i != 1) return false; p->sep.marginClose = v.i == 1; return true;
        case 207: p->sep.occPieceFrac = v.d; return true;
        case 208: p->sep.occGapFrac = v.d; return true;
        case 209: if (!intOk) return false; p->sep.marginHaloR = static_cast<int32_t>(v.i); return true;
        case 210: if (!intOk) return false; p->sep.bubDil = static_cast<int32_t>(v.i); return true;
        case 211: p->sep.pairSubMax = v.d; return true;
        case 212: if (!intOk) return false; p->sep.minCc = static_cast<int32_t>(v.i); return true;
        case 213: if (v.i != 0 && v.i != 1) return false; p->bleedFilter = v.i == 1; return true;
        case 214: if (!intOk) return false; p->bleed.ringR = static_cast<int32_t>(v.i); return true;
        case 215: if (!intOk) return false; p->bleed.ringEdge = static_cast<int32_t>(v.i); return true;
        case 216: if (!intOk) return false; p->bleed.piecePad = static_cast<int32_t>(v.i); return true;
        case 217: if (!intOk) return false; p->bleed.frDil = static_cast<int32_t>(v.i); return true;
        case 218: if (!intOk) return false; p->bleed.spDil = static_cast<int32_t>(v.i); return true;
        case 219: if (!intOk) return false; p->bleed.bbR = static_cast<int32_t>(v.i); return true;
        case 220: if (!intOk) return false; p->bleed.txR = static_cast<int32_t>(v.i); return true;
        case 221: if (!intOk) return false; p->bleed.chR = static_cast<int32_t>(v.i); return true;
        case 222: if (!intOk) return false; p->bleed.textBoxPad = static_cast<int32_t>(v.i); return true;
        case 223: if (!intOk) return false; p->bleed.touchPx = static_cast<int32_t>(v.i); return true;
        case 224: p->bleed.netFrac = v.d; return true;
        case 225: p->bleed.txtKeep = v.d; return true;
        case 226: p->bleed.frameKeep = v.d; return true;
        case 227: p->bleed.islandBbMax = v.d; return true;
        case 228: p->bleed.enclosedMax = v.d; return true;
        case 229: p->bleed.artDrop = v.d; return true;
        case 230: if (!intOk) return false; p->bleed.marginOk = static_cast<int32_t>(v.i); return true;
        case 231: p->bleed.marginFrac = v.d; return true;
        case 232: p->bleed.marginLightMaxFrac = v.d; return true;
        case 233: if (!intOk) return false; p->bleed.marginLightMin = static_cast<int32_t>(v.i); return true;
        case 234: if (!intOk) return false; p->bleed.marginMinRows = static_cast<int32_t>(v.i); return true;
        case 235: if (!intOk) return false; p->bleed.marginNeuLook = static_cast<int32_t>(v.i); return true;
        case 236: if (!intOk) return false; p->bleed.marginMinDepth = static_cast<int32_t>(v.i); return true;
        case 237: p->bleed.marginCover = v.d; return true;
        case 238: p->bleed.marginAng = v.d; return true;
        case 239: if (!intOk) return false; p->bleed.marginDark = static_cast<int32_t>(v.i); return true;
        case 240: if (!intOk) return false; p->bleed.marginLineMax = static_cast<int32_t>(v.i); return true;
        case 241: if (!intOk) return false; p->bleed.mlineMinRun = static_cast<int32_t>(v.i); return true;
        case 242: if (v.i != 0 && v.i != 1) return false; p->ring.enabled = v.i == 1; return true;
        case 243: if (!intOk) return false; p->ring.rOut = static_cast<int32_t>(v.i); return true;
        case 244: if (!intOk) return false; p->ring.seedMin = static_cast<int32_t>(v.i); return true;
        case 245: if (!intOk) return false; p->ring.gvGap = static_cast<int32_t>(v.i); return true;
        case 246: if (!intOk) return false; p->ring.rawClose = static_cast<int32_t>(v.i); return true;
        case 247: if (!intOk) return false; p->ring.steps = static_cast<int32_t>(v.i); return true;
        case 248: if (!intOk) return false; p->ring.inkBox = static_cast<int32_t>(v.i); return true;
        case 249: if (!intOk) return false; p->ring.inkSum = static_cast<int32_t>(v.i); return true;
        case 250: if (!intOk) return false; p->ring.inkMaxGray = static_cast<int32_t>(v.i); return true;
        case 251: if (!intOk) return false; p->ring.holeMax = static_cast<int32_t>(v.i); return true;
        case 252: if (!intOk) return false; p->ring.evIn = static_cast<int32_t>(v.i); return true;
        case 253: if (!intOk) return false; p->ring.evOut = static_cast<int32_t>(v.i); return true;
        case 254: if (!intOk) return false; p->ring.rayFrom = static_cast<int32_t>(v.i); return true;
        case 255: if (!intOk) return false; p->ring.rayTo = static_cast<int32_t>(v.i); return true;
        case 256: p->ring.raySigma = v.d; return true;
        case 257: if (!intOk) return false; p->ring.gapClose = static_cast<int32_t>(v.i); return true;
        case 258: if (!intOk) return false; p->ring.breakPad = static_cast<int32_t>(v.i); return true;
        case 259: if (!intOk) return false; p->ring.faintGray = static_cast<int32_t>(v.i); return true;
        case 260: if (!intOk) return false; p->ring.faintInkPad = static_cast<int32_t>(v.i); return true;
        case 261: if (!intOk) return false; p->ring.faintWin = static_cast<int32_t>(v.i); return true;
        case 262: if (!intOk) return false; p->ring.faintMin = static_cast<int32_t>(v.i); return true;
        case 263: if (!intOk) return false; p->ring.open = static_cast<int32_t>(v.i); return true;
        case 264: if (v.i != 0 && v.i != 1) return false; p->ring.seedConnected = v.i == 1; return true;
        case 265: if (v.i != 0 && v.i != 1) return false; p->obj.enabled = v.i == 1; return true;
        case 266: if (v.i != 0 && v.i != 1) return false; p->obj.veto = v.i == 1; return true;
        case 267: if (v.i != 0 && v.i != 1) return false; p->obj.lightFill = v.i == 1; return true;
        case 268: if (v.i != 0 && v.i != 1) return false; p->obj.fxLines = v.i == 1; return true;
        case 269: if (v.i != 0 && v.i != 1) return false; p->obj.fxSparks = v.i == 1; return true;
        case 270: p->obj.fx.segSd = v.d; return true;
        case 271: if (!intOk) return false; p->obj.fx.segMin = static_cast<int32_t>(v.i); return true;
        case 272: p->obj.fx.cosJoin = v.d; return true;
        case 273: p->obj.fx.perp = v.d; return true;
        case 274: p->obj.fx.gap = v.d; return true;
        case 275: p->obj.fx.lineMin = v.d; return true;
        case 276: p->obj.fx.cosVp = v.d; return true;
        case 277: if (!intOk) return false; p->obj.fx.vpTop = static_cast<int32_t>(v.i); return true;
        case 278: if (!intOk) return false; p->obj.fx.maxFamilies = static_cast<int32_t>(v.i); return true;
        case 279: if (!intOk) return false; p->obj.fx.minMembers = static_cast<int32_t>(v.i); return true;
        case 280: p->obj.fx.cosSpread = v.d; return true;
        case 281: p->obj.fx.taper = v.d; return true;
        case 282: p->obj.fx.taperFrac = v.d; return true;
        case 283: if (!intOk) return false; p->obj.fx.freeMin = static_cast<int32_t>(v.i); return true;
        case 284: p->obj.fx.freeFrac = v.d; return true;
        case 285: if (!intOk) return false; p->obj.fx.ray0 = static_cast<int32_t>(v.i); return true;
        case 286: if (!intOk) return false; p->obj.fx.ray1 = static_cast<int32_t>(v.i); return true;
        case 287: if (!intOk) return false; p->obj.fx.rayG = static_cast<int32_t>(v.i); return true;
        case 288: if (!intOk) return false; p->obj.fx.terr = static_cast<int32_t>(v.i); return true;
        case 289: p->obj.fx.cosAtol2 = v.d; return true;
        case 290: p->obj.fx.coh = v.d; return true;
        case 291: p->obj.fx.epsJ = v.d; return true;
        case 292: p->obj.fx.si = v.d; return true;
        case 293: if (!intOk) return false; p->obj.fx.vpNear = static_cast<int32_t>(v.i); return true;
        case 294: p->obj.fx.mark = v.d; return true;
        case 295: if (!intOk) return false; p->obj.fx.lineR = static_cast<int32_t>(v.i); return true;
        case 296: if (!intOk) return false; p->obj.fx.excl = static_cast<int32_t>(v.i); return true;
        case 297: if (!intOk) return false; p->obj.fx.exclT = static_cast<int32_t>(v.i); return true;
        case 298: p->obj.fx.agree = v.d; return true;
        case 299: p->obj.fx.resid = v.d; return true;
        case 300: if (!intOk) return false; p->obj.fx.nMem = static_cast<int32_t>(v.i); return true;
        case 301: if (!intOk) return false; p->obj.fx.lineV = static_cast<int32_t>(v.i); return true;
        case 302: if (!intOk) return false; p->obj.fx.grow = static_cast<int32_t>(v.i); return true;
        case 303: if (!intOk) return false; p->obj.fx.halo = static_cast<int32_t>(v.i); return true;
        case 304: if (!intOk) return false; p->obj.fx.erMin = static_cast<int32_t>(v.i); return true;
        case 305: if (!intOk) return false; p->obj.fx.minPart = static_cast<int32_t>(v.i); return true;
        case 306: if (!intOk) return false; p->obj.fx.sparkIso = static_cast<int32_t>(v.i); return true;
        case 307: p->obj.fx.sparkMin = v.d; return true;
        case 308: p->obj.fx.sparkMax = v.d; return true;
        case 309: if (!intOk) return false; p->obj.fx.sparkDil = static_cast<int32_t>(v.i); return true;
        case 310: if (!intOk) return false; p->obj.fx.sparkV = static_cast<int32_t>(v.i); return true;
        case 311: p->obj.fx.longStraight = v.d; return true;
        case 312: if (v.i != 0 && v.i != 1) return false; p->obj.personFaint = v.i == 1; return true;
        case 313: if (!intOk) return false; p->obj.pfTouch = static_cast<int32_t>(v.i); return true;
        case 314: if (!intOk) return false; p->obj.pfBridge = static_cast<int32_t>(v.i); return true;
        case 315: if (!intOk) return false; p->obj.pfReach = static_cast<int32_t>(v.i); return true;
        case 316: if (!intOk) return false; p->obj.pfHalo = static_cast<int32_t>(v.i); return true;
        case 317: if (v.i != 0 && v.i != 1) return false; p->obj.textNeedsRegion = v.i == 1; return true;
        case 318: if (v.i != 0 && v.i != 1) return false; p->obj.pfShape = v.i == 1; return true;
        case 319: if (!intOk) return false; p->obj.pfClose = static_cast<int32_t>(v.i); return true;
        case 320: if (!intOk) return false; p->obj.pfMargin = static_cast<int32_t>(v.i); return true;
        case 321: if (!intOk) return false; p->obj.pfHole = static_cast<int32_t>(v.i); return true;
        case 322: if (!intOk) return false; p->obj.pfLinePct = static_cast<int32_t>(v.i); return true;
        case 323: if (!intOk) return false; p->obj.pfMark = static_cast<int32_t>(v.i); return true;
        case 324: if (!intOk) return false; p->obj.pfStub = static_cast<int32_t>(v.i); return true;
        case 325: if (!intOk) return false; p->obj.pfPair = static_cast<int32_t>(v.i); return true;
        case 326: if (v.i != 0 && v.i != 1) return false; p->obj.textStroke = v.i == 1; return true;
        case 327: if (!intOk) return false; p->obj.tsInk = static_cast<int32_t>(v.i); return true;
        case 328: if (!intOk) return false; p->obj.tsMin = static_cast<int32_t>(v.i); return true;
        case 329: if (!intOk) return false; p->obj.tsG = static_cast<int32_t>(v.i); return true;
        case 330: if (!intOk) return false; p->obj.tsRough = static_cast<int32_t>(v.i); return true;
        case 331: if (v.i != 0 && v.i != 1) return false; p->obj.tsBandClean = v.i == 1; return true;
        case 332: if (v.i != 0 && v.i != 1) return false; p->obj.seam = v.i == 1; return true;
        case 333: if (!intOk) return false; p->obj.seamR = static_cast<int32_t>(v.i); return true;
        case 334: if (!intOk) return false; p->obj.seamSpark = static_cast<int32_t>(v.i); return true;
        case 335: if (!intOk) return false; p->obj.seamG = static_cast<int32_t>(v.i); return true;
        case 336: if (v.i != 0 && v.i != 1) return false; p->obj.inpaintIslands = v.i == 1; return true;
        case 337: if (!intOk) return false; p->obj.islandInpD = static_cast<int32_t>(v.i); return true;
        case 338: if (!intOk) return false; p->obj.islandInpPct = static_cast<int32_t>(v.i); return true;
        case 339: p->obj.vetoEpm = v.d; return true;
        case 340: if (!intOk) return false; p->obj.vetoRc = static_cast<int32_t>(v.i); return true;
        case 341: if (!intOk) return false; p->obj.vetoMinArea = static_cast<int32_t>(v.i); return true;
        case 342: if (!intOk) return false; p->obj.vetoEvDil = static_cast<int32_t>(v.i); return true;
        case 343: if (!intOk) return false; p->obj.vetoBackPad = static_cast<int32_t>(v.i); return true;
        case 344: if (!intOk) return false; p->obj.darkG = static_cast<int32_t>(v.i); return true;
        case 345: if (!intOk) return false; p->obj.lightTh = static_cast<int32_t>(v.i); return true;
        case 346: p->obj.areaMin = v.d; return true;
        case 347: p->obj.lineMax = v.d; return true;
        case 348: p->obj.can4Max = v.d; return true;
        case 349: p->obj.fitMax = v.d; return true;
        case 350: p->obj.toneMax = v.d; return true;
        case 351: p->obj.toneGrad = v.d; return true;
        case 352: p->obj.chromaMax = v.d; return true;
        case 353: p->obj.thickMin = v.d; return true;
        case 354: if (!intOk) return false; p->obj.marks = static_cast<int32_t>(v.i); return true;
        case 355: p->obj.marksDen = v.d; return true;
        case 356: if (!intOk) return false; p->obj.rLoc = static_cast<int32_t>(v.i); return true;
        case 357: p->obj.coreMin = v.d; return true;
        case 358: if (!intOk) return false; p->obj.seal = static_cast<int32_t>(v.i); return true;
        case 359: p->obj.ctxEpm = v.d; return true;
        case 360: if (!intOk) return false; p->obj.ctxWhite = static_cast<int32_t>(v.i); return true;
        case 361: p->obj.islandMax = v.d; return true;
        case 362: if (!intOk) return false; p->obj.islandTouch = static_cast<int32_t>(v.i); return true;
        case 363: if (!intOk) return false; p->obj.islandTouchMin = static_cast<int32_t>(v.i); return true;
        case 364: p->obj.hugMax = v.d; return true;
        case 365: if (!intOk) return false; p->obj.longLen = static_cast<int32_t>(v.i); return true;
        case 366: if (!intOk) return false; p->obj.longR = static_cast<int32_t>(v.i); return true;
        case 367: if (!intOk) return false; p->obj.bhTh = static_cast<int32_t>(v.i); return true;
        case 368: if (!intOk) return false; p->obj.lineR = static_cast<int32_t>(v.i); return true;
        case 369: if (!intOk) return false; p->obj.brightTh = static_cast<int32_t>(v.i); return true;
        case 370: if (!intOk) return false; p->obj.brightLrNum = static_cast<int32_t>(v.i); return true;
        case 371: if (!intOk) return false; p->obj.brightLrDen = static_cast<int32_t>(v.i); return true;
        case 372: if (!intOk) return false; p->obj.dot = static_cast<int32_t>(v.i); return true;
        case 373: if (!intOk) return false; p->obj.dotBright = static_cast<int32_t>(v.i); return true;
        case 374: if (!intOk) return false; p->obj.evMinArea = static_cast<int32_t>(v.i); return true;
        case 375: if (!intOk) return false; p->obj.cannyLo = static_cast<int32_t>(v.i); return true;
        case 376: if (!intOk) return false; p->obj.cannyHi = static_cast<int32_t>(v.i); return true;
        case 377: if (!intOk) return false; p->obj.canny4Lo = static_cast<int32_t>(v.i); return true;
        case 378: if (!intOk) return false; p->obj.canny4Hi = static_cast<int32_t>(v.i); return true;
        default: return false;
    }
}

}  // namespace params
}  // namespace nr
