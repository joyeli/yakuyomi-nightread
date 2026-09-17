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

## Modules

| Module | Coordinates | Depends on | What it does |
|---|---|---|---|
| `:nightread` | `li.joye.yakuyomi:nightread:0.1.0` | nothing but `testImplementation junit` | the zoned rebuild pipeline |
| `:nightread-ort` | `li.joye.yakuyomi:nightread-ort:0.1.0` | `api(project(":nightread"))` + `api(onnxruntime-android 1.20.0)` | ONNX Runtime inference for the character mask |

Both are Android libraries, minSdk 26, compileSdk 37, Java 17, and both set a group and version, so a
consumer can wire them in through a Gradle composite build (`includeBuild`), which is what the Yakuyomi fork
does.

The split is deliberate. `:nightread` imports nothing but `kotlin.math`, not even `android.graphics`: that
keeps the pipeline runnable under plain JVM unit tests (which is how it is checked against the Python
fixtures) and lets any JVM project take it as is. `:nightread-ort` is the only module that pulls in ONNX
Runtime, and it exists to compute the character mask.

## Quick start

```kotlin
dependencies {
    implementation("li.joye.yakuyomi:nightread:0.1.0")
    implementation("li.joye.yakuyomi:nightread-ort:0.1.0")
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

// 4. Character mask. Build the ORT sessions once, reuse them, close them when done.
val charMask: Mask = CharMaskOrt(yolosegPath, csegPath).use { it.detect(argb, w, h) }

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

`NightReadDebug` is a typealias for `(stage: String, value: Int) -> Unit`. The types are in
`li.joye.yakuyomi.nightread`, and `CharMaskOrt` in `li.joye.yakuyomi.nightread.ort`.

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

The character mask is a required input, and `:nightread-ort` is the module that computes it:

```kotlin
CharMaskOrt(yolosegPath, csegPath).use { it.detect(argb, w, h) }
```

`detect` takes the `Bitmap.getPixels` style ARGB int array and returns a page-sized `Mask`. `CharMaskOrt`
needs at least one of the two models — either path may be `null` — and takes the union when both are given.

| Model | File | Size | Role | Licence |
|---|---|---|---|---|
| YOLO11-seg | `manga_seg_s.onnx` | 38.9 MB (int8: 10.5 MB) | the settled recipe's base, and the cheapest standalone option | **AGPL-3.0** (Ultralytics) |
| CartoonSegmentation (RTMDet-Ins) | `cartoonseg.onnx` | 227.6 MB | optional, more accurate with it | MIT |

Two things measurement settled:

- **Running YOLO11-seg alone works.** The cost is guard-box violations rising from 18 to 27.
- **Do not use CartoonSegmentation's int8 build.** int8 needs the score threshold dropped far enough that the
  mask over-covers, and bubbles then get eaten as characters.

Anyone redistributing this should note the AGPL-3.0 on the YOLO11-seg weights: that licence is contagious.
It is compatible with this project's GPL-3.0 (GPLv3 §13), which is not the same as being free of obligations.

⚠️ Always build the ORT session from a **file path**. Do not `readBytes()` the weights into the JVM heap: an
app's heap is capped around 512 MB regardless of physical RAM, and a 228 MB model OOMs on the way in.

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
- `CharMaskOrt` holds ORT sessions and is `AutoCloseable`. Build it once, reuse it across pages, close it
  when you are done; the `use { }` above is the single-page shorthand.

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
