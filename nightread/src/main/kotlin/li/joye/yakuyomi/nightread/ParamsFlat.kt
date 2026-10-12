// 由 native/tools/gen_params.py 從 nightread/src/main/kotlin/li/joye/yakuyomi/nightread/Params.kt 產生，不要手改（改 Params.kt 後重跑；CI 的 native-guard 重跑後 git diff 必須是空的）。
package li.joye.yakuyomi.nightread

/**
 * [NightReadParams] 的攤平格式：JNI 只傳一個 IntArray 與一個 DoubleArray，C++ 端（native/core/params_gen.h 的
 * `nr::params::fromFlat`）照同一張欄位表還原成結構。欄位順序＝Params.kt 的宣告順序，巢狀參數組在宣告的位置展開。
 * - ints：Int、Boolean（0／1）、enum（ordinal）、Int?（兩格：有沒有值、值；沒有值時值那格是 0）
 * - doubles：Double、Float（轉成 double，精確）
 * [SCHEMA_HASH] 只看欄位的名稱、型別與順序（定義見 gen_params.py 檔頭），C++ 端同一個值；兩邊不同＝不同版的 .so，
 * [NightNative.available] 會回 false。
 */
internal object ParamsFlat {
    /** 葉欄位數。 */
    const val FIELD_COUNT: Int = 379

    /** ints 的長度。 */
    const val INT_SLOTS: Int = 235

    /** doubles 的長度。 */
    const val DOUBLE_SLOTS: Int = 145

    /** schema 雜湊（0xf2f49f43294490f2）。 */
    const val SCHEMA_HASH: Long = -939951311409606414L

    /** 欄位型別代碼（[FIELD_KINDS]；與 C++ `nr::params::Kind` 相同）。 */
    const val KIND_INT: Int = 0
    const val KIND_DOUBLE: Int = 1
    const val KIND_FLOAT: Int = 2
    const val KIND_BOOL: Int = 3
    const val KIND_OPT_INT: Int = 4
    const val KIND_ENUM: Int = 5

    /** schema 雜湊用的正規字串的開頭（版本標記）。 */
    const val SCHEMA_TAG: String = "nightread-params/1"

    /** 欄位路徑（巢狀以 . 連接），依攤平順序。 */
    val FIELD_NAMES: List<String> = listOf(
        "bg",
        "ink",
        "edgeInk",
        "stroke",
        "strokeObjV",
        "sceneFloor",
        "dimCeil",
        "glowStrength",
        "glowCap",
        "whiteTh",
        "inkDarkTh",
        "bubblePad",
        "gutterMinAreaFrac",
        "paperPeakLo",
        "paperNormMin",
        "paperNormChromaMax",
        "frameLineLDiv",
        "frameDarkTh",
        "frameMinEach",
        "frameMinSum",
        "framelessMarginDepth",
        "coreR",
        "deepEdgeFrac",
        "inPanelCoreFrac",
        "inPanelCoreDeep",
        "deepInkDeep",
        "deepInkRatio",
        "holeMaxFrac",
        "safeGutterDepth",
        "gfcDilate",
        "gfcCloseFrac",
        "textureTh",
        "textureHi",
        "textureWin",
        "texturePad",
        "textureMinArea",
        "textureFrameDil",
        "textureSegDil",
        "textureBubbleNear",
        "textureBubbleOutline",
        "textureClose",
        "textureRegionMin",
        "bubbleCompMaxFrac",
        "bubbleLocalK",
        "bubbleCoreMinFrac",
        "bubbleNeckR",
        "safeBubbleRatio",
        "bubbleCleanWins",
        "bubbleCleanTextMax",
        "bubbleCleanInkHoles",
        "bubbleGuardRaw",
        "bubbleConfirmTextIn",
        "bubbleLeak",
        "bubbleLeakRing",
        "bubbleLeakRange",
        "bubbleLeakWin",
        "bubbleLeakEdgeMax",
        "bubbleLeakRingMin",
        "bubbleLeakMinArea",
        "bubbleRestNear",
        "bubbleOutlineMin",
        "bubbleOutlineDist",
        "bubbleOutlineMinPx",
        "bubbleOutlinePad",
        "bubbleSeal.r",
        "bubbleSeal.charMax",
        "bubbleSeal.textIn",
        "bubbleSeal.restMin",
        "bubbleSeal.maxGaps",
        "bubbleSeal.cutGeo",
        "pbCovMax",
        "pbNeckR",
        "pbGrowFrac",
        "pbAuraR",
        "pbAuraThick",
        "pbAuraMinArea",
        "strokeObjFrac",
        "strokeObjMin",
        "strokeObjMax",
        "stickerMinFrac",
        "figNoiseArea",
        "figNoiseClose",
        "stickerFigMin",
        "stickerFigMax",
        "stickerThinR",
        "stickerThinMax",
        "stickerChromaMax",
        "stickerEatenR",
        "stickerEatenDens",
        "stickerEatenMax",
        "stickerEatenHard",
        "stickerTextBgMin",
        "stickerTextMax",
        "stickerTextOnPad",
        "stickerSmallArea",
        "stickerNeckR",
        "stickerCoreMin",
        "stickerProtectEatenMin",
        "stickerProtectDilate",
        "faintOfFMax",
        "faintG",
        "frameHugDilate",
        "frameHugThick",
        "frameHugMin",
        "frameHugStrong",
        "hugSideMin",
        "promotedTextOnMax",
        "stickerMode",
        "stickerRoughMax",
        "stickerSimpleMinFrac",
        "stickerPlainRingR",
        "stickerPlainArtMax",
        "stickerPlainFrameDil",
        "stickerPlainFaintMax",
        "stickerPlainFaintHalo",
        "more.enabled",
        "more.tOut",
        "more.tIn",
        "more.faintMax",
        "more.roughCap",
        "more.charMax",
        "more.hyst",
        "more.holeMinArea",
        "more.holeDarkMax",
        "more.median",
        "more.explainDil",
        "more.nearDil",
        "more.ringR",
        "more.halo",
        "more.winPad",
        "more.keepDarkStroke",
        "more.edgeSeedFallback",
        "more.edgeBand",
        "more.edgeReach",
        "pseudoBubbles",
        "harmonize",
        "coreNeckR",
        "coreRecoverR",
        "geoRatioMax",
        "geoSlack",
        "coreReleasePad",
        "charSnap",
        "charSnapPad",
        "maskSmoothMedian",
        "edgeFeather",
        "textPad",
        "textGamma",
        "textKnee",
        "textTopPad",
        "textBackingR",
        "harmonizeZoneCell",
        "harmonizeZoneDark",
        "harmonizeInZone",
        "harmonizeAreaMax",
        "harmonizeCollarInk",
        "separators",
        "sep.lenFrac",
        "sep.nearWhiteR",
        "sep.thStep",
        "sep.walkWin0",
        "sep.walkWin",
        "sep.gap",
        "sep.fillMin",
        "sep.peakNmsT",
        "sep.peakNmsR",
        "sep.peakMax",
        "sep.peakVoteFrac",
        "sep.dedupeAng",
        "sep.dedupeOff",
        "sep.groupAng",
        "sep.groupOff",
        "sep.pairAng",
        "sep.gapMin",
        "sep.edgeSkip",
        "sep.profWhite",
        "sep.profWhiteBridge",
        "sep.shortBridge",
        "sep.profGapFill",
        "sep.passMin",
        "sep.stripExtFrac",
        "sep.bridgeFrac",
        "sep.edgeMadMax",
        "sep.textDil",
        "sep.textProfMax",
        "sep.familyAng",
        "sep.familyReach",
        "sep.wmaxFrac",
        "sep.ovlFrac",
        "sep.extDarkSkip",
        "sep.bubLineR",
        "sep.bubLineMax",
        "sep.netTouch",
        "sep.netThick",
        "sep.netExtFrac",
        "sep.marginRunFrac",
        "sep.marginLightFrac",
        "sep.marginLightMin",
        "sep.farProbe",
        "sep.marginOkTh",
        "sep.marginFrac",
        "sep.flR",
        "sep.flHitR",
        "sep.marginEdgeAng",
        "sep.marginAxisAng",
        "sep.marginFillMin",
        "sep.marginOcc",
        "sep.marginClose",
        "sep.occPieceFrac",
        "sep.occGapFrac",
        "sep.marginHaloR",
        "sep.bubDil",
        "sep.pairSubMax",
        "sep.minCc",
        "bleedFilter",
        "bleed.ringR",
        "bleed.ringEdge",
        "bleed.piecePad",
        "bleed.frDil",
        "bleed.spDil",
        "bleed.bbR",
        "bleed.txR",
        "bleed.chR",
        "bleed.textBoxPad",
        "bleed.touchPx",
        "bleed.netFrac",
        "bleed.txtKeep",
        "bleed.frameKeep",
        "bleed.islandBbMax",
        "bleed.enclosedMax",
        "bleed.artDrop",
        "bleed.marginOk",
        "bleed.marginFrac",
        "bleed.marginLightMaxFrac",
        "bleed.marginLightMin",
        "bleed.marginMinRows",
        "bleed.marginNeuLook",
        "bleed.marginMinDepth",
        "bleed.marginCover",
        "bleed.marginAng",
        "bleed.marginDark",
        "bleed.marginLineMax",
        "bleed.mlineMinRun",
        "ring.enabled",
        "ring.rOut",
        "ring.seedMin",
        "ring.gvGap",
        "ring.rawClose",
        "ring.steps",
        "ring.inkBox",
        "ring.inkSum",
        "ring.inkMaxGray",
        "ring.holeMax",
        "ring.evIn",
        "ring.evOut",
        "ring.rayFrom",
        "ring.rayTo",
        "ring.raySigma",
        "ring.gapClose",
        "ring.breakPad",
        "ring.faintGray",
        "ring.faintInkPad",
        "ring.faintWin",
        "ring.faintMin",
        "ring.open",
        "ring.seedConnected",
        "obj.enabled",
        "obj.veto",
        "obj.lightFill",
        "obj.fxLines",
        "obj.fxSparks",
        "obj.fx.segSd",
        "obj.fx.segMin",
        "obj.fx.cosJoin",
        "obj.fx.perp",
        "obj.fx.gap",
        "obj.fx.lineMin",
        "obj.fx.cosVp",
        "obj.fx.vpTop",
        "obj.fx.maxFamilies",
        "obj.fx.minMembers",
        "obj.fx.cosSpread",
        "obj.fx.taper",
        "obj.fx.taperFrac",
        "obj.fx.freeMin",
        "obj.fx.freeFrac",
        "obj.fx.ray0",
        "obj.fx.ray1",
        "obj.fx.rayG",
        "obj.fx.terr",
        "obj.fx.cosAtol2",
        "obj.fx.coh",
        "obj.fx.epsJ",
        "obj.fx.si",
        "obj.fx.vpNear",
        "obj.fx.mark",
        "obj.fx.lineR",
        "obj.fx.excl",
        "obj.fx.exclT",
        "obj.fx.agree",
        "obj.fx.resid",
        "obj.fx.nMem",
        "obj.fx.lineV",
        "obj.fx.grow",
        "obj.fx.halo",
        "obj.fx.erMin",
        "obj.fx.minPart",
        "obj.fx.sparkIso",
        "obj.fx.sparkMin",
        "obj.fx.sparkMax",
        "obj.fx.sparkDil",
        "obj.fx.sparkV",
        "obj.fx.longStraight",
        "obj.personFaint",
        "obj.pfTouch",
        "obj.pfBridge",
        "obj.pfReach",
        "obj.pfHalo",
        "obj.textNeedsRegion",
        "obj.pfShape",
        "obj.pfClose",
        "obj.pfMargin",
        "obj.pfHole",
        "obj.pfLinePct",
        "obj.pfMark",
        "obj.pfStub",
        "obj.pfPair",
        "obj.textStroke",
        "obj.tsInk",
        "obj.tsMin",
        "obj.tsG",
        "obj.tsRough",
        "obj.tsBandClean",
        "obj.seam",
        "obj.seamR",
        "obj.seamSpark",
        "obj.seamG",
        "obj.inpaintIslands",
        "obj.islandInpD",
        "obj.islandInpPct",
        "obj.vetoEpm",
        "obj.vetoRc",
        "obj.vetoMinArea",
        "obj.vetoEvDil",
        "obj.vetoBackPad",
        "obj.darkG",
        "obj.lightTh",
        "obj.areaMin",
        "obj.lineMax",
        "obj.can4Max",
        "obj.fitMax",
        "obj.toneMax",
        "obj.toneGrad",
        "obj.chromaMax",
        "obj.thickMin",
        "obj.marks",
        "obj.marksDen",
        "obj.rLoc",
        "obj.coreMin",
        "obj.seal",
        "obj.ctxEpm",
        "obj.ctxWhite",
        "obj.islandMax",
        "obj.islandTouch",
        "obj.islandTouchMin",
        "obj.hugMax",
        "obj.longLen",
        "obj.longR",
        "obj.bhTh",
        "obj.lineR",
        "obj.brightTh",
        "obj.brightLrNum",
        "obj.brightLrDen",
        "obj.dot",
        "obj.dotBright",
        "obj.evMinArea",
        "obj.cannyLo",
        "obj.cannyHi",
        "obj.canny4Lo",
        "obj.canny4Hi",
    )

    /** 欄位型別代碼，與 [FIELD_NAMES] 同序。 */
    val FIELD_KINDS: IntArray = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 0, 1, 1,
        1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 1, 1, 1, 3, 3, 1, 3, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0,
        0, 1, 1, 0, 0, 4, 1, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 0, 1, 1, 1, 1, 1, 0, 1, 0,
        1, 1, 0, 1, 0, 0, 1, 1, 1, 1, 1, 5, 1, 1, 0, 1, 0, 1, 0, 3, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0,
        0, 0, 3, 3, 0, 0, 3, 3, 0, 0, 1, 1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 0, 0, 1, 1, 1, 1, 3, 1, 0, 1, 0,
        0, 0, 1, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 0, 0, 1, 0,
        0, 1, 1, 1, 0, 0, 0, 1, 0, 0, 1, 1, 1, 3, 3, 1, 1, 0, 0, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 1, 1, 0, 1, 1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 0, 0, 0, 0, 0, 0, 0, 3, 3, 3, 3, 3, 3, 1, 0, 1, 1, 1, 1, 1, 0, 0, 0, 1, 1, 1, 0, 1, 0, 0, 0,
        0, 1, 1, 1, 1, 0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 1, 3, 0, 0, 0, 0, 3, 3, 0,
        0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 0, 3, 3, 0, 0, 0, 3, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1,
        1, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    )

    /** 欄位的槽：Double／Float 是 doubles 的索引，其餘是 ints 的索引（Int? 佔這格與下一格），與 [FIELD_NAMES] 同序。 */
    val FIELD_SLOTS: IntArray = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 0, 7, 8, 9, 10, 1, 11, 12, 2, 13, 14, 3, 4, 5, 15, 6, 7, 8, 9, 10, 11, 12, 16, 13, 14,
        15, 17, 18, 19, 20, 21, 22, 23, 24, 16, 17, 18, 19, 25, 20, 21, 22, 26, 27, 23, 28, 29, 30, 31, 24, 32, 33, 34, 25, 35, 36, 37,
        38, 26, 27, 39, 40, 41, 28, 43, 29, 44, 45, 46, 30, 47, 48, 31, 49, 50, 32, 33, 51, 34, 35, 52, 36, 37, 38, 39, 40, 53, 41, 54,
        42, 43, 55, 44, 56, 57, 45, 46, 47, 48, 49, 58, 50, 51, 59, 52, 60, 53, 61, 62, 54, 55, 56, 57, 58, 59, 63, 64, 65, 66, 67, 68,
        69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 60, 61, 79, 80, 81, 82, 62, 83, 63, 64, 84, 85, 86, 65, 66, 67, 68, 87, 69, 88, 70, 89,
        90, 91, 71, 92, 93, 94, 72, 73, 74, 75, 76, 77, 95, 96, 78, 79, 97, 98, 80, 81, 82, 83, 99, 84, 85, 86, 87, 88, 100, 101, 89, 102,
        103, 90, 91, 92, 104, 105, 106, 93, 107, 108, 94, 95, 96, 109, 110, 97, 98, 111, 112, 99, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124,
        100, 101, 102, 103, 104, 105, 125, 106, 107, 126, 127, 128, 129, 108, 109, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146,
        110, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 111, 160, 112, 113, 114, 115, 116, 161, 162, 163, 117, 118, 119, 164, 120, 165, 166, 167,
        168, 121, 122, 123, 124, 169, 125, 170, 171, 172, 126, 127, 173, 174, 175, 176, 177, 178, 179, 128, 129, 180, 181, 130, 182, 183, 184, 185, 186, 187, 188, 189,
        190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 131, 209, 210, 211, 212, 213, 214, 132, 133, 134, 135, 136, 137,
        138, 139, 215, 140, 216, 141, 217, 142, 218, 143, 219, 220, 144, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234,
    )

    /** enum 欄位的型別名（schema 雜湊用），與 [FIELD_NAMES] 同序；不是 enum 的是 null。 */
    val FIELD_ENUMS: List<String?> = listOf(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "StickerMode",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
    )

    /** 參數用到的 enum：名稱 → 項目（ordinal 順序）。 */
    val ENUMS: Map<String, List<String>> = mapOf(
        "StickerMode" to StickerMode.entries.map { it.name },
    )

    /** [p] 攤平進 [i]（長 [INT_SLOTS]）與 [d]（長 [DOUBLE_SLOTS]）。 */
    fun toFlat(p: NightReadParams, i: IntArray, d: DoubleArray) {
        require(i.size == INT_SLOTS && d.size == DOUBLE_SLOTS) { "ParamsFlat.toFlat：陣列長度要是 $INT_SLOTS／$DOUBLE_SLOTS" }
        i[0] = p.bg
        i[1] = p.ink
        i[2] = p.edgeInk
        i[3] = p.stroke
        i[4] = p.strokeObjV
        i[5] = p.sceneFloor
        i[6] = p.dimCeil
        d[0] = p.glowStrength.toDouble()
        i[7] = p.glowCap
        i[8] = p.whiteTh
        i[9] = p.inkDarkTh
        i[10] = p.bubblePad
        d[1] = p.gutterMinAreaFrac
        i[11] = p.paperPeakLo
        i[12] = p.paperNormMin
        d[2] = p.paperNormChromaMax
        i[13] = p.frameLineLDiv
        i[14] = p.frameDarkTh
        d[3] = p.frameMinEach
        d[4] = p.frameMinSum
        d[5] = p.framelessMarginDepth
        i[15] = p.coreR
        d[6] = p.deepEdgeFrac
        d[7] = p.inPanelCoreFrac
        d[8] = p.inPanelCoreDeep
        d[9] = p.deepInkDeep
        d[10] = p.deepInkRatio
        d[11] = p.holeMaxFrac
        d[12] = p.safeGutterDepth
        i[16] = p.gfcDilate
        d[13] = p.gfcCloseFrac
        d[14] = p.textureTh
        d[15] = p.textureHi
        i[17] = p.textureWin
        i[18] = p.texturePad
        i[19] = p.textureMinArea
        i[20] = p.textureFrameDil
        i[21] = p.textureSegDil
        i[22] = p.textureBubbleNear
        i[23] = p.textureBubbleOutline
        i[24] = p.textureClose
        d[16] = p.textureRegionMin
        d[17] = p.bubbleCompMaxFrac
        d[18] = p.bubbleLocalK
        d[19] = p.bubbleCoreMinFrac
        i[25] = p.bubbleNeckR
        d[20] = p.safeBubbleRatio
        d[21] = p.bubbleCleanWins
        d[22] = p.bubbleCleanTextMax
        i[26] = if (p.bubbleCleanInkHoles) 1 else 0
        i[27] = if (p.bubbleGuardRaw) 1 else 0
        d[23] = p.bubbleConfirmTextIn
        i[28] = if (p.bubbleLeak) 1 else 0
        i[29] = p.bubbleLeakRing
        i[30] = p.bubbleLeakRange
        i[31] = p.bubbleLeakWin
        d[24] = p.bubbleLeakEdgeMax
        i[32] = p.bubbleLeakRingMin
        i[33] = p.bubbleLeakMinArea
        i[34] = p.bubbleRestNear
        d[25] = p.bubbleOutlineMin
        i[35] = p.bubbleOutlineDist
        i[36] = p.bubbleOutlineMinPx
        i[37] = p.bubbleOutlinePad
        i[38] = p.bubbleSeal.r
        d[26] = p.bubbleSeal.charMax
        d[27] = p.bubbleSeal.textIn
        i[39] = p.bubbleSeal.restMin
        i[40] = p.bubbleSeal.maxGaps
        i[41] = if (p.bubbleSeal.cutGeo != null) 1 else 0
        i[42] = p.bubbleSeal.cutGeo ?: 0
        d[28] = p.pbCovMax
        i[43] = p.pbNeckR
        d[29] = p.pbGrowFrac
        i[44] = p.pbAuraR
        i[45] = p.pbAuraThick
        i[46] = p.pbAuraMinArea
        d[30] = p.strokeObjFrac
        i[47] = p.strokeObjMin
        i[48] = p.strokeObjMax
        d[31] = p.stickerMinFrac
        i[49] = p.figNoiseArea
        i[50] = p.figNoiseClose
        d[32] = p.stickerFigMin
        d[33] = p.stickerFigMax
        i[51] = p.stickerThinR
        d[34] = p.stickerThinMax
        d[35] = p.stickerChromaMax
        i[52] = p.stickerEatenR
        d[36] = p.stickerEatenDens
        d[37] = p.stickerEatenMax
        d[38] = p.stickerEatenHard
        d[39] = p.stickerTextBgMin
        d[40] = p.stickerTextMax
        i[53] = p.stickerTextOnPad
        d[41] = p.stickerSmallArea
        i[54] = p.stickerNeckR
        d[42] = p.stickerCoreMin
        d[43] = p.stickerProtectEatenMin
        i[55] = p.stickerProtectDilate
        d[44] = p.faintOfFMax
        i[56] = p.faintG
        i[57] = p.frameHugDilate
        d[45] = p.frameHugThick
        d[46] = p.frameHugMin
        d[47] = p.frameHugStrong
        d[48] = p.hugSideMin
        d[49] = p.promotedTextOnMax
        i[58] = p.stickerMode.ordinal
        d[50] = p.stickerRoughMax
        d[51] = p.stickerSimpleMinFrac
        i[59] = p.stickerPlainRingR
        d[52] = p.stickerPlainArtMax
        i[60] = p.stickerPlainFrameDil
        d[53] = p.stickerPlainFaintMax
        i[61] = p.stickerPlainFaintHalo
        i[62] = if (p.more.enabled) 1 else 0
        d[54] = p.more.tOut
        d[55] = p.more.tIn
        d[56] = p.more.faintMax
        d[57] = p.more.roughCap
        d[58] = p.more.charMax
        d[59] = p.more.hyst
        i[63] = p.more.holeMinArea
        i[64] = p.more.holeDarkMax
        i[65] = p.more.median
        i[66] = p.more.explainDil
        i[67] = p.more.nearDil
        i[68] = p.more.ringR
        i[69] = p.more.halo
        i[70] = p.more.winPad
        i[71] = if (p.more.keepDarkStroke) 1 else 0
        i[72] = if (p.more.edgeSeedFallback) 1 else 0
        i[73] = p.more.edgeBand
        i[74] = p.more.edgeReach
        i[75] = if (p.pseudoBubbles) 1 else 0
        i[76] = if (p.harmonize) 1 else 0
        i[77] = p.coreNeckR
        i[78] = p.coreRecoverR
        d[60] = p.geoRatioMax
        d[61] = p.geoSlack
        i[79] = p.coreReleasePad
        i[80] = p.charSnap
        i[81] = p.charSnapPad
        i[82] = p.maskSmoothMedian
        d[62] = p.edgeFeather
        i[83] = p.textPad
        d[63] = p.textGamma
        d[64] = p.textKnee
        i[84] = p.textTopPad
        i[85] = p.textBackingR
        i[86] = p.harmonizeZoneCell
        d[65] = p.harmonizeZoneDark
        d[66] = p.harmonizeInZone
        d[67] = p.harmonizeAreaMax
        d[68] = p.harmonizeCollarInk
        i[87] = if (p.separators) 1 else 0
        d[69] = p.sep.lenFrac
        i[88] = p.sep.nearWhiteR
        d[70] = p.sep.thStep
        i[89] = p.sep.walkWin0
        i[90] = p.sep.walkWin
        i[91] = p.sep.gap
        d[71] = p.sep.fillMin
        i[92] = p.sep.peakNmsT
        i[93] = p.sep.peakNmsR
        i[94] = p.sep.peakMax
        d[72] = p.sep.peakVoteFrac
        d[73] = p.sep.dedupeAng
        d[74] = p.sep.dedupeOff
        d[75] = p.sep.groupAng
        d[76] = p.sep.groupOff
        d[77] = p.sep.pairAng
        i[95] = p.sep.gapMin
        i[96] = p.sep.edgeSkip
        d[78] = p.sep.profWhite
        d[79] = p.sep.profWhiteBridge
        i[97] = p.sep.shortBridge
        i[98] = p.sep.profGapFill
        d[80] = p.sep.passMin
        d[81] = p.sep.stripExtFrac
        d[82] = p.sep.bridgeFrac
        d[83] = p.sep.edgeMadMax
        i[99] = p.sep.textDil
        d[84] = p.sep.textProfMax
        d[85] = p.sep.familyAng
        d[86] = p.sep.familyReach
        d[87] = p.sep.wmaxFrac
        d[88] = p.sep.ovlFrac
        i[100] = p.sep.extDarkSkip
        i[101] = p.sep.bubLineR
        d[89] = p.sep.bubLineMax
        i[102] = p.sep.netTouch
        i[103] = p.sep.netThick
        d[90] = p.sep.netExtFrac
        d[91] = p.sep.marginRunFrac
        d[92] = p.sep.marginLightFrac
        i[104] = p.sep.marginLightMin
        i[105] = p.sep.farProbe
        i[106] = p.sep.marginOkTh
        d[93] = p.sep.marginFrac
        i[107] = p.sep.flR
        i[108] = p.sep.flHitR
        d[94] = p.sep.marginEdgeAng
        d[95] = p.sep.marginAxisAng
        d[96] = p.sep.marginFillMin
        i[109] = if (p.sep.marginOcc) 1 else 0
        i[110] = if (p.sep.marginClose) 1 else 0
        d[97] = p.sep.occPieceFrac
        d[98] = p.sep.occGapFrac
        i[111] = p.sep.marginHaloR
        i[112] = p.sep.bubDil
        d[99] = p.sep.pairSubMax
        i[113] = p.sep.minCc
        i[114] = if (p.bleedFilter) 1 else 0
        i[115] = p.bleed.ringR
        i[116] = p.bleed.ringEdge
        i[117] = p.bleed.piecePad
        i[118] = p.bleed.frDil
        i[119] = p.bleed.spDil
        i[120] = p.bleed.bbR
        i[121] = p.bleed.txR
        i[122] = p.bleed.chR
        i[123] = p.bleed.textBoxPad
        i[124] = p.bleed.touchPx
        d[100] = p.bleed.netFrac
        d[101] = p.bleed.txtKeep
        d[102] = p.bleed.frameKeep
        d[103] = p.bleed.islandBbMax
        d[104] = p.bleed.enclosedMax
        d[105] = p.bleed.artDrop
        i[125] = p.bleed.marginOk
        d[106] = p.bleed.marginFrac
        d[107] = p.bleed.marginLightMaxFrac
        i[126] = p.bleed.marginLightMin
        i[127] = p.bleed.marginMinRows
        i[128] = p.bleed.marginNeuLook
        i[129] = p.bleed.marginMinDepth
        d[108] = p.bleed.marginCover
        d[109] = p.bleed.marginAng
        i[130] = p.bleed.marginDark
        i[131] = p.bleed.marginLineMax
        i[132] = p.bleed.mlineMinRun
        i[133] = if (p.ring.enabled) 1 else 0
        i[134] = p.ring.rOut
        i[135] = p.ring.seedMin
        i[136] = p.ring.gvGap
        i[137] = p.ring.rawClose
        i[138] = p.ring.steps
        i[139] = p.ring.inkBox
        i[140] = p.ring.inkSum
        i[141] = p.ring.inkMaxGray
        i[142] = p.ring.holeMax
        i[143] = p.ring.evIn
        i[144] = p.ring.evOut
        i[145] = p.ring.rayFrom
        i[146] = p.ring.rayTo
        d[110] = p.ring.raySigma
        i[147] = p.ring.gapClose
        i[148] = p.ring.breakPad
        i[149] = p.ring.faintGray
        i[150] = p.ring.faintInkPad
        i[151] = p.ring.faintWin
        i[152] = p.ring.faintMin
        i[153] = p.ring.open
        i[154] = if (p.ring.seedConnected) 1 else 0
        i[155] = if (p.obj.enabled) 1 else 0
        i[156] = if (p.obj.veto) 1 else 0
        i[157] = if (p.obj.lightFill) 1 else 0
        i[158] = if (p.obj.fxLines) 1 else 0
        i[159] = if (p.obj.fxSparks) 1 else 0
        d[111] = p.obj.fx.segSd
        i[160] = p.obj.fx.segMin
        d[112] = p.obj.fx.cosJoin
        d[113] = p.obj.fx.perp
        d[114] = p.obj.fx.gap
        d[115] = p.obj.fx.lineMin
        d[116] = p.obj.fx.cosVp
        i[161] = p.obj.fx.vpTop
        i[162] = p.obj.fx.maxFamilies
        i[163] = p.obj.fx.minMembers
        d[117] = p.obj.fx.cosSpread
        d[118] = p.obj.fx.taper
        d[119] = p.obj.fx.taperFrac
        i[164] = p.obj.fx.freeMin
        d[120] = p.obj.fx.freeFrac
        i[165] = p.obj.fx.ray0
        i[166] = p.obj.fx.ray1
        i[167] = p.obj.fx.rayG
        i[168] = p.obj.fx.terr
        d[121] = p.obj.fx.cosAtol2
        d[122] = p.obj.fx.coh
        d[123] = p.obj.fx.epsJ
        d[124] = p.obj.fx.si
        i[169] = p.obj.fx.vpNear
        d[125] = p.obj.fx.mark
        i[170] = p.obj.fx.lineR
        i[171] = p.obj.fx.excl
        i[172] = p.obj.fx.exclT
        d[126] = p.obj.fx.agree
        d[127] = p.obj.fx.resid
        i[173] = p.obj.fx.nMem
        i[174] = p.obj.fx.lineV
        i[175] = p.obj.fx.grow
        i[176] = p.obj.fx.halo
        i[177] = p.obj.fx.erMin
        i[178] = p.obj.fx.minPart
        i[179] = p.obj.fx.sparkIso
        d[128] = p.obj.fx.sparkMin
        d[129] = p.obj.fx.sparkMax
        i[180] = p.obj.fx.sparkDil
        i[181] = p.obj.fx.sparkV
        d[130] = p.obj.fx.longStraight
        i[182] = if (p.obj.personFaint) 1 else 0
        i[183] = p.obj.pfTouch
        i[184] = p.obj.pfBridge
        i[185] = p.obj.pfReach
        i[186] = p.obj.pfHalo
        i[187] = if (p.obj.textNeedsRegion) 1 else 0
        i[188] = if (p.obj.pfShape) 1 else 0
        i[189] = p.obj.pfClose
        i[190] = p.obj.pfMargin
        i[191] = p.obj.pfHole
        i[192] = p.obj.pfLinePct
        i[193] = p.obj.pfMark
        i[194] = p.obj.pfStub
        i[195] = p.obj.pfPair
        i[196] = if (p.obj.textStroke) 1 else 0
        i[197] = p.obj.tsInk
        i[198] = p.obj.tsMin
        i[199] = p.obj.tsG
        i[200] = p.obj.tsRough
        i[201] = if (p.obj.tsBandClean) 1 else 0
        i[202] = if (p.obj.seam) 1 else 0
        i[203] = p.obj.seamR
        i[204] = p.obj.seamSpark
        i[205] = p.obj.seamG
        i[206] = if (p.obj.inpaintIslands) 1 else 0
        i[207] = p.obj.islandInpD
        i[208] = p.obj.islandInpPct
        d[131] = p.obj.vetoEpm
        i[209] = p.obj.vetoRc
        i[210] = p.obj.vetoMinArea
        i[211] = p.obj.vetoEvDil
        i[212] = p.obj.vetoBackPad
        i[213] = p.obj.darkG
        i[214] = p.obj.lightTh
        d[132] = p.obj.areaMin
        d[133] = p.obj.lineMax
        d[134] = p.obj.can4Max
        d[135] = p.obj.fitMax
        d[136] = p.obj.toneMax
        d[137] = p.obj.toneGrad
        d[138] = p.obj.chromaMax
        d[139] = p.obj.thickMin
        i[215] = p.obj.marks
        d[140] = p.obj.marksDen
        i[216] = p.obj.rLoc
        d[141] = p.obj.coreMin
        i[217] = p.obj.seal
        d[142] = p.obj.ctxEpm
        i[218] = p.obj.ctxWhite
        d[143] = p.obj.islandMax
        i[219] = p.obj.islandTouch
        i[220] = p.obj.islandTouchMin
        d[144] = p.obj.hugMax
        i[221] = p.obj.longLen
        i[222] = p.obj.longR
        i[223] = p.obj.bhTh
        i[224] = p.obj.lineR
        i[225] = p.obj.brightTh
        i[226] = p.obj.brightLrNum
        i[227] = p.obj.brightLrDen
        i[228] = p.obj.dot
        i[229] = p.obj.dotBright
        i[230] = p.obj.evMinArea
        i[231] = p.obj.cannyLo
        i[232] = p.obj.cannyHi
        i[233] = p.obj.canny4Lo
        i[234] = p.obj.canny4Hi
    }

    /** [toFlat] 的新陣列版：first＝ints、second＝doubles。 */
    fun toFlat(p: NightReadParams): Pair<IntArray, DoubleArray> {
        val i = IntArray(INT_SLOTS)
        val d = DoubleArray(DOUBLE_SLOTS)
        toFlat(p, i, d)
        return i to d
    }

    /**
     * 攤平 → [NightReadParams]（[toFlat] 的反向）。長度不對、Boolean 不是 0／1、enum 超出範圍、Int? 沒有值卻帶非 0 值、
     * Float 欄位轉不回 float ⇒ [IllegalArgumentException]。
     */
    fun fromFlat(i: IntArray, d: DoubleArray): NightReadParams {
        require(i.size == INT_SLOTS && d.size == DOUBLE_SLOTS) { "ParamsFlat.fromFlat：陣列長度要是 $INT_SLOTS／$DOUBLE_SLOTS" }
        return NightReadParams(
            bg = i[0],
            ink = i[1],
            edgeInk = i[2],
            stroke = i[3],
            strokeObjV = i[4],
            sceneFloor = i[5],
            dimCeil = i[6],
            glowStrength = narrow(d[0], "glowStrength"),
            glowCap = i[7],
            whiteTh = i[8],
            inkDarkTh = i[9],
            bubblePad = i[10],
            gutterMinAreaFrac = d[1],
            paperPeakLo = i[11],
            paperNormMin = i[12],
            paperNormChromaMax = d[2],
            frameLineLDiv = i[13],
            frameDarkTh = i[14],
            frameMinEach = d[3],
            frameMinSum = d[4],
            framelessMarginDepth = d[5],
            coreR = i[15],
            deepEdgeFrac = d[6],
            inPanelCoreFrac = d[7],
            inPanelCoreDeep = d[8],
            deepInkDeep = d[9],
            deepInkRatio = d[10],
            holeMaxFrac = d[11],
            safeGutterDepth = d[12],
            gfcDilate = i[16],
            gfcCloseFrac = d[13],
            textureTh = d[14],
            textureHi = d[15],
            textureWin = i[17],
            texturePad = i[18],
            textureMinArea = i[19],
            textureFrameDil = i[20],
            textureSegDil = i[21],
            textureBubbleNear = i[22],
            textureBubbleOutline = i[23],
            textureClose = i[24],
            textureRegionMin = d[16],
            bubbleCompMaxFrac = d[17],
            bubbleLocalK = d[18],
            bubbleCoreMinFrac = d[19],
            bubbleNeckR = i[25],
            safeBubbleRatio = d[20],
            bubbleCleanWins = d[21],
            bubbleCleanTextMax = d[22],
            bubbleCleanInkHoles = flag(i[26], "bubbleCleanInkHoles"),
            bubbleGuardRaw = flag(i[27], "bubbleGuardRaw"),
            bubbleConfirmTextIn = d[23],
            bubbleLeak = flag(i[28], "bubbleLeak"),
            bubbleLeakRing = i[29],
            bubbleLeakRange = i[30],
            bubbleLeakWin = i[31],
            bubbleLeakEdgeMax = d[24],
            bubbleLeakRingMin = i[32],
            bubbleLeakMinArea = i[33],
            bubbleRestNear = i[34],
            bubbleOutlineMin = d[25],
            bubbleOutlineDist = i[35],
            bubbleOutlineMinPx = i[36],
            bubbleOutlinePad = i[37],
            bubbleSeal = BubbleSealParams(
                r = i[38],
                charMax = d[26],
                textIn = d[27],
                restMin = i[39],
                maxGaps = i[40],
                cutGeo = opt(i[41], i[42], "bubbleSeal.cutGeo"),
            ),
            pbCovMax = d[28],
            pbNeckR = i[43],
            pbGrowFrac = d[29],
            pbAuraR = i[44],
            pbAuraThick = i[45],
            pbAuraMinArea = i[46],
            strokeObjFrac = d[30],
            strokeObjMin = i[47],
            strokeObjMax = i[48],
            stickerMinFrac = d[31],
            figNoiseArea = i[49],
            figNoiseClose = i[50],
            stickerFigMin = d[32],
            stickerFigMax = d[33],
            stickerThinR = i[51],
            stickerThinMax = d[34],
            stickerChromaMax = d[35],
            stickerEatenR = i[52],
            stickerEatenDens = d[36],
            stickerEatenMax = d[37],
            stickerEatenHard = d[38],
            stickerTextBgMin = d[39],
            stickerTextMax = d[40],
            stickerTextOnPad = i[53],
            stickerSmallArea = d[41],
            stickerNeckR = i[54],
            stickerCoreMin = d[42],
            stickerProtectEatenMin = d[43],
            stickerProtectDilate = i[55],
            faintOfFMax = d[44],
            faintG = i[56],
            frameHugDilate = i[57],
            frameHugThick = d[45],
            frameHugMin = d[46],
            frameHugStrong = d[47],
            hugSideMin = d[48],
            promotedTextOnMax = d[49],
            stickerMode = StickerMode.entries.getOrNull(i[58]) ?: bad("stickerMode"),
            stickerRoughMax = d[50],
            stickerSimpleMinFrac = d[51],
            stickerPlainRingR = i[59],
            stickerPlainArtMax = d[52],
            stickerPlainFrameDil = i[60],
            stickerPlainFaintMax = d[53],
            stickerPlainFaintHalo = i[61],
            more = MoreRuleParams(
                enabled = flag(i[62], "more.enabled"),
                tOut = d[54],
                tIn = d[55],
                faintMax = d[56],
                roughCap = d[57],
                charMax = d[58],
                hyst = d[59],
                holeMinArea = i[63],
                holeDarkMax = i[64],
                median = i[65],
                explainDil = i[66],
                nearDil = i[67],
                ringR = i[68],
                halo = i[69],
                winPad = i[70],
                keepDarkStroke = flag(i[71], "more.keepDarkStroke"),
                edgeSeedFallback = flag(i[72], "more.edgeSeedFallback"),
                edgeBand = i[73],
                edgeReach = i[74],
            ),
            pseudoBubbles = flag(i[75], "pseudoBubbles"),
            harmonize = flag(i[76], "harmonize"),
            coreNeckR = i[77],
            coreRecoverR = i[78],
            geoRatioMax = d[60],
            geoSlack = d[61],
            coreReleasePad = i[79],
            charSnap = i[80],
            charSnapPad = i[81],
            maskSmoothMedian = i[82],
            edgeFeather = d[62],
            textPad = i[83],
            textGamma = d[63],
            textKnee = d[64],
            textTopPad = i[84],
            textBackingR = i[85],
            harmonizeZoneCell = i[86],
            harmonizeZoneDark = d[65],
            harmonizeInZone = d[66],
            harmonizeAreaMax = d[67],
            harmonizeCollarInk = d[68],
            separators = flag(i[87], "separators"),
            sep = SeparatorParams(
                lenFrac = d[69],
                nearWhiteR = i[88],
                thStep = d[70],
                walkWin0 = i[89],
                walkWin = i[90],
                gap = i[91],
                fillMin = d[71],
                peakNmsT = i[92],
                peakNmsR = i[93],
                peakMax = i[94],
                peakVoteFrac = d[72],
                dedupeAng = d[73],
                dedupeOff = d[74],
                groupAng = d[75],
                groupOff = d[76],
                pairAng = d[77],
                gapMin = i[95],
                edgeSkip = i[96],
                profWhite = d[78],
                profWhiteBridge = d[79],
                shortBridge = i[97],
                profGapFill = i[98],
                passMin = d[80],
                stripExtFrac = d[81],
                bridgeFrac = d[82],
                edgeMadMax = d[83],
                textDil = i[99],
                textProfMax = d[84],
                familyAng = d[85],
                familyReach = d[86],
                wmaxFrac = d[87],
                ovlFrac = d[88],
                extDarkSkip = i[100],
                bubLineR = i[101],
                bubLineMax = d[89],
                netTouch = i[102],
                netThick = i[103],
                netExtFrac = d[90],
                marginRunFrac = d[91],
                marginLightFrac = d[92],
                marginLightMin = i[104],
                farProbe = i[105],
                marginOkTh = i[106],
                marginFrac = d[93],
                flR = i[107],
                flHitR = i[108],
                marginEdgeAng = d[94],
                marginAxisAng = d[95],
                marginFillMin = d[96],
                marginOcc = flag(i[109], "sep.marginOcc"),
                marginClose = flag(i[110], "sep.marginClose"),
                occPieceFrac = d[97],
                occGapFrac = d[98],
                marginHaloR = i[111],
                bubDil = i[112],
                pairSubMax = d[99],
                minCc = i[113],
            ),
            bleedFilter = flag(i[114], "bleedFilter"),
            bleed = BleedParams(
                ringR = i[115],
                ringEdge = i[116],
                piecePad = i[117],
                frDil = i[118],
                spDil = i[119],
                bbR = i[120],
                txR = i[121],
                chR = i[122],
                textBoxPad = i[123],
                touchPx = i[124],
                netFrac = d[100],
                txtKeep = d[101],
                frameKeep = d[102],
                islandBbMax = d[103],
                enclosedMax = d[104],
                artDrop = d[105],
                marginOk = i[125],
                marginFrac = d[106],
                marginLightMaxFrac = d[107],
                marginLightMin = i[126],
                marginMinRows = i[127],
                marginNeuLook = i[128],
                marginMinDepth = i[129],
                marginCover = d[108],
                marginAng = d[109],
                marginDark = i[130],
                marginLineMax = i[131],
                mlineMinRun = i[132],
            ),
            ring = RingParams(
                enabled = flag(i[133], "ring.enabled"),
                rOut = i[134],
                seedMin = i[135],
                gvGap = i[136],
                rawClose = i[137],
                steps = i[138],
                inkBox = i[139],
                inkSum = i[140],
                inkMaxGray = i[141],
                holeMax = i[142],
                evIn = i[143],
                evOut = i[144],
                rayFrom = i[145],
                rayTo = i[146],
                raySigma = d[110],
                gapClose = i[147],
                breakPad = i[148],
                faintGray = i[149],
                faintInkPad = i[150],
                faintWin = i[151],
                faintMin = i[152],
                open = i[153],
                seedConnected = flag(i[154], "ring.seedConnected"),
            ),
            obj = ObjectRuleParams(
                enabled = flag(i[155], "obj.enabled"),
                veto = flag(i[156], "obj.veto"),
                lightFill = flag(i[157], "obj.lightFill"),
                fxLines = flag(i[158], "obj.fxLines"),
                fxSparks = flag(i[159], "obj.fxSparks"),
                fx = EffectLineParams(
                    segSd = d[111],
                    segMin = i[160],
                    cosJoin = d[112],
                    perp = d[113],
                    gap = d[114],
                    lineMin = d[115],
                    cosVp = d[116],
                    vpTop = i[161],
                    maxFamilies = i[162],
                    minMembers = i[163],
                    cosSpread = d[117],
                    taper = d[118],
                    taperFrac = d[119],
                    freeMin = i[164],
                    freeFrac = d[120],
                    ray0 = i[165],
                    ray1 = i[166],
                    rayG = i[167],
                    terr = i[168],
                    cosAtol2 = d[121],
                    coh = d[122],
                    epsJ = d[123],
                    si = d[124],
                    vpNear = i[169],
                    mark = d[125],
                    lineR = i[170],
                    excl = i[171],
                    exclT = i[172],
                    agree = d[126],
                    resid = d[127],
                    nMem = i[173],
                    lineV = i[174],
                    grow = i[175],
                    halo = i[176],
                    erMin = i[177],
                    minPart = i[178],
                    sparkIso = i[179],
                    sparkMin = d[128],
                    sparkMax = d[129],
                    sparkDil = i[180],
                    sparkV = i[181],
                    longStraight = d[130],
                ),
                personFaint = flag(i[182], "obj.personFaint"),
                pfTouch = i[183],
                pfBridge = i[184],
                pfReach = i[185],
                pfHalo = i[186],
                textNeedsRegion = flag(i[187], "obj.textNeedsRegion"),
                pfShape = flag(i[188], "obj.pfShape"),
                pfClose = i[189],
                pfMargin = i[190],
                pfHole = i[191],
                pfLinePct = i[192],
                pfMark = i[193],
                pfStub = i[194],
                pfPair = i[195],
                textStroke = flag(i[196], "obj.textStroke"),
                tsInk = i[197],
                tsMin = i[198],
                tsG = i[199],
                tsRough = i[200],
                tsBandClean = flag(i[201], "obj.tsBandClean"),
                seam = flag(i[202], "obj.seam"),
                seamR = i[203],
                seamSpark = i[204],
                seamG = i[205],
                inpaintIslands = flag(i[206], "obj.inpaintIslands"),
                islandInpD = i[207],
                islandInpPct = i[208],
                vetoEpm = d[131],
                vetoRc = i[209],
                vetoMinArea = i[210],
                vetoEvDil = i[211],
                vetoBackPad = i[212],
                darkG = i[213],
                lightTh = i[214],
                areaMin = d[132],
                lineMax = d[133],
                can4Max = d[134],
                fitMax = d[135],
                toneMax = d[136],
                toneGrad = d[137],
                chromaMax = d[138],
                thickMin = d[139],
                marks = i[215],
                marksDen = d[140],
                rLoc = i[216],
                coreMin = d[141],
                seal = i[217],
                ctxEpm = d[142],
                ctxWhite = i[218],
                islandMax = d[143],
                islandTouch = i[219],
                islandTouchMin = i[220],
                hugMax = d[144],
                longLen = i[221],
                longR = i[222],
                bhTh = i[223],
                lineR = i[224],
                brightTh = i[225],
                brightLrNum = i[226],
                brightLrDen = i[227],
                dot = i[228],
                dotBright = i[229],
                evMinArea = i[230],
                cannyLo = i[231],
                cannyHi = i[232],
                canny4Lo = i[233],
                canny4Hi = i[234],
            ),
        )
    }

    private fun bad(field: String): Nothing = throw IllegalArgumentException("ParamsFlat：欄位 $field 的值不合法")

    private fun flag(v: Int, field: String): Boolean = when (v) {
        0 -> false
        1 -> true
        else -> bad(field)
    }

    private fun opt(has: Int, v: Int, field: String): Int? = if (flag(has, field)) v else if (v == 0) null else bad(field)

    private fun narrow(v: Double, field: String): Float {
        val f = v.toFloat()
        if (f.toDouble() != v && !v.isNaN()) bad(field)
        return f
    }
}
