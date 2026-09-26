# `:nightread` — night-reading rebuild

English ｜ [中文](README_zh.md)

A library that rebuilds a manga page for reading in the dark. You give it a page in greyscale plus three
pieces of analysis (a text mask, the text-region boxes, and a character mask); you get back a dark page:
bubbles filled dark with their text drawn light, empty backgrounds filled black with the figures lifted out
by a white outline, and the rest tone-mapped so black lines stay black.

The module stands alone. It contains no reader, no text detector, and no inference framework: its only job
is `NightRead.render(input) -> NightReadResult`, and where the inputs come from is the caller's choice.
Yakuyomi produces them with the DBNet detector in
[yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine), which is one way of wiring it up, not a
requirement — see [Text detection is not in this repo](#text-detection-is-not-in-this-repo).

> **This page is the integration guide.** What the algorithm decides in each zone is in
> [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md), every knob is listed in
> [`docs/PARAMETERS.md`](../docs/PARAMETERS.md), and where each value came from — with the alternatives that
> were measured and rejected — is in [`docs/DECISIONS.md`](../docs/DECISIONS.md).

## Module

| Module | Coordinates | Depends on | What it does |
|---|---|---|---|
| `:nightread` | `li.joye.yakuyomi:nightread:0.1.0` | nothing but `testImplementation junit` | the zoned rebuild pipeline |

It is an Android library, minSdk 26, compileSdk 37, Java 17, with a group and version set, so a consumer can
wire it in through a Gradle composite build (`includeBuild`), which is what the Yakuyomi fork does.

Keeping it to one module is deliberate. `:nightread` imports nothing but `kotlin.math`, not even
`android.graphics`: that keeps the pipeline runnable under plain JVM unit tests (which is how it is checked
against the Python fixtures) and lets any JVM project take it as is. The character mask is computed
elsewhere: Yakuyomi runs the two segmentation models with NCNN inside
[yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine) (`CsegSegmenter` and `YoloSegSegmenter`, see
[Character-mask models](#character-mask-models)), and this repo only consumes the resulting mask. There used
to be a `:nightread-ort` module here running the same two models through ONNX Runtime; it went away with the
move to NCNN.

## Quick start

```kotlin
dependencies {
    implementation("li.joye.yakuyomi:nightread:0.1.0")
}
```

```kotlin
// 1. Page pixels, the ARGB int array that Bitmap.getPixels fills.
val argb = IntArray(w * h)
pageBitmap.getPixels(argb, 0, w, 0, 0, w, h)

// 2. Greyscale and chroma. The greyscale formula is fixed (see Input format, rule 1).
val gray = Gray(w, h)
val chroma = Gray(w, h)                 // optional; pass null for a pure-greyscale page
for (i in argb.indices) {
    val px = argb[i]
    val r = (px shr 16) and 0xFF
    val g = (px shr 8) and 0xFF
    val b = px and 0xFF
    gray.data[i] = (r * 299 + g * 587 + b * 114 + 500) / 1000
    chroma.data[i] = maxOf(r, g, b) - minOf(r, g, b)
}

// 3. Text mask and text-region boxes from your detector (see Input format, rules 2 and 3).
val seg: Mask = yourTextMask(w, h)                      // Mask(w, h), true = inside a text region
val regions: List<TextRegion> = yourTextBoxes()         // TextRegion(x0, y0, x1, y1), page pixels

// 4. Character mask from your segmenter, the model's raw output (see Input format, rule 4). With
//    yakuyomi-engine it is Mask(w, h, yolo.segment(pageBitmap)) OR-ed with the cseg one; see below.
val charMask: Mask = yourCharacterMask(w, h)            // Mask(w, h), true = character

// 5. Rebuild.
val result: NightReadResult = NightRead.render(NightReadInput(gray, seg, regions, charMask, chroma))

// 6. The dark page, 0..255 per pixel, same size as the input.
val dark = result.out
val outPixels = IntArray(w * h) {
    val v = dark.data[it]
    (0xFF shl 24) or (v shl 16) or (v shl 8) or v
}
val nightBitmap = Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
```

There is one entry point:

```kotlin
fun render(
    input: NightReadInput,
    p: NightReadParams = NightReadParams(),
    debug: NightReadDebug? = null,
): NightReadResult
```

`NightReadDebug` is a typealias for `(stage: String, value: Int) -> Unit`. All the types are in
`li.joye.yakuyomi.nightread`.

## Inputs

`NightReadInput` carries four required fields plus one optional one:

| Field | Type | What |
|---|---|---|
| `gray` | `Gray(w, h)` | page greyscale, 0..255 |
| `seg` | `Mask(w, h)` | text mask (read rule 2: regions, not exact strokes) |
| `regions` | `List<TextRegion>` | text-region boxes, `x0, y0, x1, y1` in original-image pixels |
| `charMask` | `Mask(w, h)` | character mask, the model's raw output. Required |
| `chroma` | `Gray(w, h)?` | per-pixel chroma (max channel minus min channel), used for colour-page judgements. A pure-greyscale page can pass `null` |

### Text detection is not in this repo

Night reading ships no detector. `seg` and `regions` are yours to produce. Yakuyomi uses the DBNet detector
from manga-image-translator through yakuyomi-engine, but nothing in the pipeline depends on that: any source
that can emit a text-region mask and text-region boxes works, as long as it meets the format rules below.

## Input format

Four rules. Break one and the output degrades quietly rather than failing, so check them first.

1. **Greyscale must be BT.601**: `(R*299 + G*587 + B*114 + 500) / 1000`, the same formula as
   `cv2.imread(IMREAD_GRAYSCALE)`. Every threshold in the pipeline (white at 235, ink at 128, and the rest)
   was measured in that greyscale space. A different formula shifts all of them at once.

2. **`seg` must be text *regions*, not exact strokes.** This is the easiest rule to get wrong, and it was
   found by measurement: feeding the typesetter's exact strokes (only the glyphs themselves, none of the white
   around them) leaves bubbles unfilled, because the bubble core fill uses `seg` as its seed. On one bubble
   the region mask covered 90% of the area while the exact strokes were only 32% black, and that seed is too
   small to fill from. DBNet's second output is a region mask, which is why it fits. If you bring your own
   detector, check which of the two it emits. Binarising it is the caller's job as well; the research
   pipeline thresholds DBNet's region map at 0.12.

3. **`seg` must cover all the text, including decorative text you have no intention of processing.** In the
   same measurement, one bubble of decorative hand-lettering was missed entirely, because it fell inside no
   text region at all and so nothing filled it.

4. **`charMask` must be the model's raw output.** Do not tighten or smooth it first. The pipeline does its
   own ink-snapping and median smoothing, and the pseudo-bubble stage needs the unprocessed version to decide
   which side of a boundary a pixel belongs to.

## Character-mask models

The character mask is a required input, and this repo does not compute it. Yakuyomi computes it in
yakuyomi-engine, where the two models run on NCNN, the same backend as the engine's detector and inpainter:

```kotlin
// yakuyomi-engine; both implement CharSegmenter { fun segment(page: Bitmap): BooleanArray }
val yolo = YoloSegSegmenter(yoloParamPath, yoloBinPath)
val cseg = CsegSegmenter(csegParamPath, csegBinPath)
val a = yolo.segment(pageBitmap)
val b = cseg.segment(pageBitmap)
val charMask = Mask(w, h, BooleanArray(w * h) { a[it] || b[it] })
```

`segment` takes the page `Bitmap` and returns a page-sized boolean array, true for character pixels; the
settled recipe is the union of the two. Each segmenter holds one NCNN net and is `AutoCloseable`.

| Model | Files | Size (fp16) | Role | Licence |
|---|---|---|---|---|
| YOLO11-seg | `manga_seg_s.ncnn.param` + `.bin` | 20.4 MB | the settled recipe's base, and the cheapest standalone option | **AGPL-3.0** (Ultralytics) |
| CartoonSegmentation (RTMDet-Ins) | `cartoonseg.ncnn.param` + `.bin` | 126 MB | optional, more accurate with it | MIT |

Three things measurement settled:

- **Running YOLO11-seg alone works.** The cost is guard-box violations rising from 18 to 27.
- **All fp16, no int8.** CartoonSegmentation comes out of `ncnn2int8` producing zero instances (a toolchain
  failure on its graph, not a calibration problem), and YOLO11-seg int8 is 7% faster on a mask that already
  takes 0.4 s. Both were measured on device. The earlier finding against the ONNX int8 build of
  CartoonSegmentation (it over-covered and ate bubbles) is moot now that nothing runs ONNX on the device.
- **The union costs 1.2–1.4 s per page** on the test device (Snapdragon 8 Gen 3), for 146 MB of weights.

The `.onnx` exports of the same two models are still what `research/charmask.py` runs on the desktop through
Python `onnxruntime`. That is the reference the NCNN ports are checked against (union IoU ≥ 0.996), not
something the device uses.

Anyone redistributing this should note the AGPL-3.0 on the YOLO11-seg weights: that licence is contagious.
It is compatible with this project's GPL-3.0 (GPLv3 §13), which is not the same as being free of obligations.

## Result

`NightReadResult` carries the page plus the intermediates, so a caller can debug or run the acceptance test
without re-deriving them:

| Field | Type | What |
|---|---|---|
| `out` | `Gray` | the dark page |
| `gutter`, `bubble`, `charMask` | `Mask` | the intermediate masks, for debugging and acceptance |
| `frameless` | `Boolean` | the page-type verdict |
| `stickerAccept`, `stickerPromoted` | `Set<Int>` | the sticker plan, used by the parity checks |

## Lifecycle and threading

- `NightRead` is an `object` and holds no state. The debug hook is a parameter (`NightReadDebug`), not a
  global field, so `render` can be called concurrently.
- The segmenters that produce `charMask` are not this module's concern. In yakuyomi-engine each holds one
  NCNN net and is `AutoCloseable`: build once, reuse across pages, close when done.

## Parameters

`NightReadParams` is a `data class` holding the module's 91 parameters, and its defaults are the settled
values. Per-item documentation is in [`docs/PARAMETERS.md`](../docs/PARAMETERS.md), which covers all 94 knobs
of the pipeline; the three that are not in `NightReadParams` sit outside the module — the detector's
binarisation threshold, a report-only white-area threshold, and the pseudo-bubble growth reference, which the
Kotlin port fixes to the long edge.

Changing them changes the algorithm, not a style setting. The thresholds are interlocked: the zoning scheme
is built on the white-component decisions, so a value moved in one stage propagates through every stage after
it.

## The red line

**Never paint over a face, a hand, a white sleeve or white hair.** The only acceptable failure is "not dark
enough". Acceptance is 704 hand-annotated foreground boxes; the pipeline currently sits at 18 violations.

Meeting that line requires a semantic character mask. Pure geometry tops out at 37 violations, and pays 14
percentage points of light area to get there.

## The Python reference

`research/nightread.py` is the same pipeline in Python and is the spec the Kotlin port is written against.
Its entry point is `run_page(page_path, outdir=OUT_DEFAULT, col_w=1000, regions=None, seg=None)`: pass
`regions` and `seg` and detection is skipped, which is the same shape as `NightReadInput`.

The research scripts import yakuyomi-engine's parity tools to do detection, and find them through the
`YAKU_ENGINE_CLONE` environment variable. That is a convenience of the scripts, not a requirement of the
pipeline.

## License

GPL-3.0, the same as the rest of Yakuyomi. The rebuild algorithm itself is original. The research pipeline
uses m-i-t's DBNet detector (GPL-3.0) through yakuyomi-engine, and the character-mask models carry their own
licences (see above).
