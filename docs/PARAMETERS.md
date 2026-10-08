# Parameters

English ｜ [中文](PARAMETERS_zh.md)

Every knob in the pipeline: what it controls, what it is set to, and what happens if you push it either
way. They live at the top of `research/nightread.py`, except the modules that keep their own block:
`nightread_sep.py` (any-angle separators), `nightread_bleed.py` (bleed-panel filter), `nightread_ring.py` (ring
thinning), `nightread_obj.py` and `nightread_fx.py` (the "More" background-object rule and effect lines) — there
are no magic numbers scattered through the code. On the Kotlin side they are `NightReadParams` and its nested
groups (`sep`, `bleed`, `bubbleSeal`, `more`, `ring`, `obj` with `obj.fx`).

One environment variable is required: `NIGHTREAD_CHARMASK`, pointing at the character-mask directory
produced by `charmask.py`. If it is missing the run fails outright rather than silently degrading. The three
fill levels (the product ships L2 and L3) are also selected through the environment (`NIGHTREAD_STICKER_MODE`,
`NIGHTREAD_STICKER_ROUGH`, `NIGHTREAD_STICKER_MINFRAC`, `NIGHTREAD_PB`, `NIGHTREAD_HM` — see *Fill tiers*), the
any-angle separator and the bleed-panel filter each have a switch (`NIGHTREAD_SEP`, `NIGHTREAD_BLEED`, on by
default), bubble-leak sealing has its radius there (`NIGHTREAD_BUBBLE_SEAL_R`, 1 by default, 0 = off), the two
bubble-strip fixes each have a switch (`NIGHTREAD_CLEAN_INK_HOLES`, `NIGHTREAD_GUARD_RAW`, on by default, 0 = old
behaviour), so does the bubble-leak test (`NIGHTREAD_ELEAK`, on by default; thresholds `NIGHTREAD_ELEAK_*`, see
`BUBBLE_LEAK`), so does the "More" rule A2 (`NIGHTREAD_MORE`, off by default = the old L3; thresholds `NIGHTREAD_MORE_*`, see
*"More" rule A2*), so does character ring thinning (`NIGHTREAD_RING`, `NIGHTREAD_RING_SEEDCONN`, both on by default;
constants at the top of `research/nightread_ring.py`, see *Character ring thinning*), so does the "More" background-object
rule (`NIGHTREAD_OBJ`, `NIGHTREAD_OBJ_VETO`, `NIGHTREAD_OBJ_LT`, on by default and only effective with `NIGHTREAD_MORE=1`;
constants at the top of `research/nightread_obj.py`, see *"More" background-object rule*), and a few research toggles read it too (`NIGHTREAD_EDGE_INK`, `NIGHTREAD_REQUIRE_CLEAN`,
`NIGHTREAD_TEXT_*`).
Everything else is edited in the file, so a run reproduces.

Where each value came from, and which alternatives were measured and rejected, is in
[DECISIONS.md](DECISIONS.md).

---

## Output levels

### `BG` = 16
Brightness of the dark ground. The gutter, bubble interiors, and filled backgrounds all use it. 0 shows
near-black crush on OLED; 16 is the compromise.

### `INK` = 240
Brightness of text strokes inside a bubble. The original ink density comes in as alpha, so stroke edges are
antialiased for free.

### `EDGE_INK` = `INK` (`NIGHTREAD_EDGE_INK`; Kotlin `edgeInk`)
Ceiling for the 1 px brightened edges: gutter borders, bubble outlines and the ring outside a bubble. It
defaults to `INK`, so output is unchanged; separating it from `INK` lets a reader tone those lines down without
dimming the text (on device they looked like pure-white lines). Yakuyomi's brightness presets set text / edges /
figure outline (`INK` / `EDGE_INK` / `STROKE_OBJ_V`) to 240/240/220 (Standard), 205/170/190 (Soft, the default)
and 190/150/175 (Softer); its advanced settings also expose `BG`, `DIM_CEIL` and `GLOW_CAP`.

### `STROKE` = 1
Radius of the boundary brightening. Once a bubble is filled dark its original black outline is invisible
against the dark ground, so the outline has to be brightened back in. **Don't raise it**: radius 3, plus the
2–3 px of the outline itself, leaves roughly a 6 px white ring outside the bubble.

### `STROKE_OBJ_V` = 220
Brightness of the white outline around foreground figures, a little below `INK` so the outline and the text
stay visually distinct.

### `SCENE_FLOOR` = 8 · `DIM_CEIL` = 140
The range of the scene curve: black maps to 8, paper white to 140. The curve is linear, so it is
order-preserving. The 140 ceiling is part of the red line — ink is always darker than the paper it sits on,
so the page never inverts.

### `GLOW_STRENGTH` = 55 · `GLOW_CAP` = 112
Adaptive ink brightening: strokes are lifted only where the local background is dark, and never above
`DIM_CEIL`. The flat, non-adaptive version was shown to wash out screentone.

---

## Input and white components

### `SEG_TH` = 0.12
Binarization threshold for the DBNet stroke mask; the same value as the engine's `segThreshold`.

### `WHITE_TH` = 235
Grey-level floor for "white". The entire zoning scheme is built on these white connected components, so
changing it moves every stage that follows.

### `INK_DARK_TH` = 128
Grey-level ceiling for "ink", used when judging how much line art a white component has swallowed.

### `BUBBLE_PAD` = 40
How far the text-region bbox is expanded when searching for a white component.

### `GUTTER_MIN_AREA_FRAC` = 0.0006
Minimum page-area fraction for a gutter component; filters out fragments.

### `WHITE_MEASURE_TH` = 200
Threshold for the white-area statistics. Reporting only — it never feeds the algorithm.

---

## Paper-white normalization

Scanned pages and toned paper do not have 255 paper white. demo05's watercolour paper peaks at 223: not one
pixel on the page reaches `WHITE_TH`, and every zoning stage downstream falls apart.

### `PAPER_PEAK_LO` = 200
Only highlights above this are considered when estimating the paper-white peak.

### `PAPER_NORM_MIN` = 245
Highlights are stretched to 255 only when the peak is below this. Clean white paper passes through
untouched — 10 of the 11 fixtures change by nothing.

### `PAPER_NORM_CHROMA_MAX` = 8.0
Chroma ceiling for the peak region. **This gate is the whole point**: real scanned paper white is neutral,
with chroma close to 0, whereas a pale watercolour ground can be just as bright but carries chroma — that is
paint, not paper. Without the gate, demo05's entire pale wash is stretched to white and the output comes out
8 percentage points brighter.

---

## Page type

Measures the density of long straight panel-frame lines, in pixels per thousand pixels. Below the threshold
the page is frameless: its background is only dimmed, never filled dark.

### `FRAME_LINE_L_DIV` = 5
A line counts as "long" at `min(W,H) // 5`, with a floor of 60 px.

### `FRAME_DARK_TH` = 100
Grey ceiling for a frame line to count as dark.

### `FRAME_MIN_EACH` = 1.0 · `FRAME_MIN_SUM` = 4.5
Floors for horizontal and vertical lines separately, plus a floor on the sum. Calibration: the weakest framed
page measures 1.19 and 5.77, the strongest frameless page 0.86 and 3.36 — the two groups separate cleanly.

### `FRAMELESS_MARGIN_DEPTH` = 0.12
On a frameless page, only a genuine border band — reaching no deeper into the page than 12% of the short
edge — is filled. An open background runs into the middle of the page, fails that test, and stays grey.

---

## Margins and in-panel white

White components touching the page edge are classified one at a time. A true gutter is filled dark; white
that belongs inside a panel is only dimmed.

### `CORE_R` = 26
Distance-transform threshold for a "thick core". A real gutter's half-width is far below this.

### `DEEP_EDGE_FRAC` = 0.07
Margin band width = `max(64, 0.07 × min(W,H))`. Anything past that distance counts as reaching into the page.

### `IN_PANEL_CORE_FRAC` = 0.15 · `IN_PANEL_CORE_DEEP` = 0.25
Rule A: if the thick core is at least 15% of the component, and at least 25% of that core reaches into the
page, the component is in-panel white. The sky in a bleed panel has exactly this shape.

### `DEEP_INK_DEEP` = 0.5 · `DEEP_INK_RATIO` = 0.02
Rule B: if more than half the thick core reaches into the page, and the ink inside its small holes is at
least 2% of the component, the white has wrapped artwork and is reclassified as artwork. Calibration: real
gutters run 0.00–0.08 on the deep fraction, the problem panels 0.40–0.91.

### `HOLE_MAX_FRAC` = 0.01
Page-area ceiling for a "small" hole. A large hole is a whole panel ringed by gutter, not line art wrapped
in white.

### `SAFE_GUTTER_DEPTH` = 0.12
On a framed page, the gutter is filled only where it reaches no deeper than 12% of the short edge. In a bleed
close-up the face and the white clothing share a component with the page white and contain nothing but thin
line art — there is no ink mass to trigger the protection — so anything deeper is left grey. The failure
direction is safe.

---

## Bubbles

### `BUBBLE_COMP_MAX_FRAC` = 0.07 · `BUBBLE_LOCAL_K` = 4.0
Two gates: a page-area ceiling on the bubble component, and a cap of four times the text search window on
its area. Together they stop a whole background from being taken for a bubble.

### `BUBBLE_CORE_MIN_FRAC` = 0.003
Bubbles above this page fraction are filled from text-seeded cores; smaller ones are filled whole. Setting it
to 0 means core-filling everywhere, and there is no downside to that: on a tight small bubble the core fill
has nothing to cut away, so the result is identical to filling it whole.

### `BUBBLE_NECK_R` = 8
Neck-cutting radius for bubbles. A gap in a bubble outline leaking into the background, and white behind
text written on the artwork reaching through a chin gap into a face, are both narrow necks. A real bubble is
wide open inside — the white between lines is at least 13 px — so it is unaffected.

A shape gate (solidity) was measured and is unusable: a real bubble's white is cut into concave shapes by its
own text, and after hole-filling demo01's face block lands right in the middle of the real-bubble
distribution. "Adjacent to thick ink" is unusable too — bold text strokes are themselves 6 px thick.
Geometric neck-cutting is the only thing that separates them.

### `SAFE_BUBBLE_RATIO` = 6.0
A bubble core may be no larger than 6× the square of the text box's long edge. A real bubble is packed with
its text; a component grown from text sitting on a cheek or a hand is a whole sheet of skin-white.

Raised from 2.5 on 2026-09-26 after device pages of translated chapters: short translations shrink the long
edge squared 10–20×, and the bubble was rejected; on the fixtures 2.5 → 6 changes no guard box.

**The denominator is the long edge squared, not the text box's area**: a single column of vertical text is
one column wide, so an area denominator blows up spuriously, the whole bubble is rejected, and it stays
white. The long edge squared equals the area for a square box and only loosens the test for long thin ones.

### `BUBBLE_CLEAN_WINS` = 0.005 · `BUBBLE_CLEAN_TEXT_MAX` = 0.8
The two tests for "clean bubble, fill it whole". A real bubble is an empty container: after hole-filling, the
non-text ink inside it is 0–0.3%. A face mistaken for a bubble has features and shadows, and runs above 1%.
Anything that passes is filled whole, with nothing subtracted for the character mask.

The second test is insurance: anything more than 80% text is not a bubble. The white hair and white hands
that get misclassified are almost entirely made of the text strokes themselves.

### `BUBBLE_CLEAN_INK_HOLES` = on (`NIGHTREAD_CLEAN_INK_HOLES`; Kotlin `bubbleCleanInkHoles`)
The clean-bubble test counts only non-paper-white pixels (original < `WHITE_TH`) as holes. The small paper-white
gaps between vertical text columns that the core fill does not take into the bubble no longer count as
"something else inside the bubble". Across the 47 pages, 33 of the 39 real bubbles judged not clean had
nothing but paper white in their holes, and the 0.5% threshold was a coin toss for them (scaling, JPEG, or the
phone's NCNN inputs flip them); a flipped bubble leaves the light strip described next. 0 restores the old
behaviour.

This entry used to list a cost: "when a frameless bubble leaks into white hair, the white gaps between the
strands no longer count as holes either (demo04)". That was wrong. All the paper-white holes of that demo04
bubble lie inside its text boxes; they are gaps between text columns. The hair was painted because the bubble
has no outline and its white runs straight into the white highlights of the hair. `BUBBLE_LEAK` below handles it.

### `BUBBLE_GUARD_RAW` = on (`NIGHTREAD_GUARD_RAW`; Kotlin `bubbleGuardRaw`) · `BUBBLE_CONFIRM_TEXT_IN` = 0.5 (Kotlin `bubbleConfirmTextIn`)
A bubble still judged not clean gives way to the character where the two touch. The old code subtracted the
character mask after snapping and smoothing; the snap grows along the paper white inside the bubble and leaves
a light strip 4–17 px wide. With this switch, a text-confirmed bubble gives way only to the character model's
**raw** output, and the strip grown by the snap is painted with the bubble's black. Text-confirmed means the
bubble component (before trimming) is at most `BUBBLE_CLEAN_TEXT_MAX` text, and at least one text box has
≥ 0.5 of its full bbox area inside the hole-filled component. Other bubbles are unchanged.

It is not "the bubble always wins": that paints a white shirt (demo04) and a hand (demo05) that were mistaken
for bubbles. 0 restores the old behaviour.

### `BUBBLE_LEAK` = on (`NIGHTREAD_ELEAK`; Kotlin `bubbleLeak`)
The bubble-leak test. A clean bubble is painted black whole and does not give way to the character, on the
assumption that the bubble is drawn on top of the character. A bubble without an outline breaks that: its white
runs straight into white on the character (demo04: white hair highlights, a white shirt), and painting the
whole bubble paints the character. The inside of a bubble always stops at its outline. When a character stands
behind a bubble there is an outline between them; where the bubble's white reaches the character with no
outline in between, that part is not bubble, it is white on the character.

How: take the 8-connected blocks of clean bubble ∩ the character model's **raw** output, one at a time. Dilate
the block by `BUBBLE_LEAK_RING` px and keep the part of that ring outside "the bubbles and the clean bubbles'
holes" (the bubble edge the block sits against). If that ring has no outline, the block goes back into the
mask the bubble gives way to; the trimming that follows is unchanged (only blocks touching the bubble's outer
edge are cut). 0 = fix e only. Independent of the fill level.

| Parameter | Value | Research env var | Kotlin |
|---|---|---|---|
| `BUBBLE_LEAK_MODE` | `lc` | `NIGHTREAD_ELEAK_MODE` | `lc` only |
| `BUBBLE_LEAK_RING` | 3 | `NIGHTREAD_ELEAK_RING` | `bubbleLeakRing` |
| `BUBBLE_LEAK_RANGE` | 60 | `NIGHTREAD_ELEAK_LC_RANGE` | `bubbleLeakRange` |
| `BUBBLE_LEAK_WIN` | 9 | — | `bubbleLeakWin` |
| `BUBBLE_LEAK_EDGE_MAX` | 0.85 | `NIGHTREAD_ELEAK_LC_MAX` | `bubbleLeakEdgeMax` |
| `BUBBLE_LEAK_RING_MIN` | 100 | `NIGHTREAD_ELEAK_RINGMIN` | `bubbleLeakRingMin` |
| `BUBBLE_LEAK_MIN_AREA` | 100 | `NIGHTREAD_ELEAK_MINAREA` | `bubbleLeakMinArea` |
| `BUBBLE_LEAK_INK_MAX` | 0.10 | `NIGHTREAD_ELEAK_INK` | none (research comparison) |

- **`lc` (local contrast, the product rule).** A ring pixel "has a line" when the brightest minus the darkest
  value in the 9×9 window around it is ≥ 60. A share of such pixels below 0.85 means this stretch of bubble
  edge has no outline. Measured on 1,458 blocks across 19 inputs (scaling 0.5–1.2×, JPEG q50–q85, Gaussian blur
  σ 1.0–2.0, raised black level, downscale-then-upscale): the demo04 blocks that should go back to the
  character reach at most 75.7% (one 106 px block at blur σ=2.0; 62.8% otherwise), every other page is at
  least 92.3%.
- **`ink` (research comparison, do not use).** Share of ink (< `INK_DARK_TH`) on the ring below 0.10. Same
  results as `lc` on sharp pages, but a blurred 2–3 px outline rises above 128 at its centre and no ink is
  seen: 3 blocks misfire at blur σ=1.5 and 65 at σ=2.0 (24 pages, whole bubbles left grey, 1.2 M px less
  black). `lc` looks at the step between the line and its surroundings, which survives blurring.
- A ring smaller than `BUBBLE_LEAK_RING_MIN` px is not judged: the block is almost enclosed by the bubble and
  does not reach its outer edge. Blocks smaller than `BUBBLE_LEAK_MIN_AREA` px are ignored.
- 60 and 0.85 were chosen looking at these 19 inputs, with no held-out data. Among the 47 pages only demo04
  triggers the rule (two places: the hanging hair and the man's white shirt).

### `BUBBLE_REST_NEAR` = 20
Whatever is left of the bubble component once the core is removed is filled only within 20 px of the bubble.
Drop the distance limit and fill the whole remainder, and the white-bearded old man loses his beard.

### `BUBBLE_OUTLINE_MIN` = 0.85 · `BUBBLE_OUTLINE_DIST` = 6 · `BUBBLE_OUTLINE_MIN_PX` = 100 · `BUBBLE_OUTLINE_PAD` = 8
The text-on-background gate. Take the core of a core-filled bubble, its 1 px inner boundary minus the dilated
text strokes (the "non-text boundary"), and measure the fraction of it lying within 6 px of ink (distance
transform of the non-ink pixels inside the component bbox padded by 8). A real bubble is enclosed by its own
outline, so the fraction is ~1 (105 real bubbles over 19 pages: ≥ 0.977 at d=6, ≥ 0.959 at d=4). Text written
straight onto a sky or a wall sits in white whose boundary is screentone, cloud lines or someone else's linework
(d=6): c362_010「我想想」0.076, c371_008 0.257, c362_013 0.314, ch34_015 0.614, demo01's face 0.748. 0.85 sits
between the two groups, the narrowest margin being demo01 at 0.10; guard 18/665 (default) and 12/12/16 (L1/L2/L3) unchanged. Two escapes: fewer than
100 non-text boundary pixels is not judged (too few samples, old behaviour kept), and a 1–3 px ring whose mean
chroma exceeds `STICKER_CHROMA_MAX` is not judged (a colour page's bubble outline or fill may be a tint, so the
ink criterion does not hold). 0 turns the gate off.

Hole-filled shape measures (roughness, solidity, ellipse IoU) were tried and rejected: text holes usually
connect to the outside through the strokes, so filling does not rescue them, and burst bubbles, cloud bubbles
and sky blocks overlap.

The distance is absolute pixels. It was first set to 4 on pages 1600–2000 px tall; a reviewer's resolution
probe (the same pages upscaled) showed d=4 breaking at 1.5–2× — at 2× 74–79 real bubbles fell below 0.85 —
while d=6 keeps real bubbles ≥ 0.94 all the way from 1× to 2×, with text-on-background at most 0.748 at 1× and
≤ 0.68 once upscaled. So d=6. At 1× it
changes nothing: the 11 fixture pages and the 8 device pages are pixel-identical to d=4.

### `BUBBLE_REQUIRE_CLEAN` = off (`NIGHTREAD_REQUIRE_CLEAN`)
Research toggle: demand a clean container (non-text ink below `BUBBLE_CLEAN_WINS`) before accepting a
component as a bubble at all, instead of only when deciding whether to fill it whole. It had no effect on the
guard in the tier ablation, so no product level uses it. (Not ported to Kotlin.)

---

## Bubble-leak sealing

A 1–2 px crack in a bubble outline puts the bubble's white and whatever lies outside the crack (panel sky, a
margin) into one white component. That component is then too large (`BUBBLE_COMP_MAX_FRAC`, `BUBBLE_LOCAL_K`)
or classified as margin / in-panel white, the whole bubble is rejected, and in L1–L3 (no pseudo-bubbles) it
stays scene grey — c362_005:3, reported from the phone. Sealing only revisits the components that were rejected
for those two reasons, and does not grow anything:

1. Erode the component's white with a (2R+1) ellipse (R=1: a 3×3 cross) → "deep white", white more than R from
   ink. The page edge does not count as ink.
2. Each deep-white block the text touches (dilated strokes inside that region's `BUBBLE_PAD` window), largest
   first, is restored into a unit: the block plus component white within R of it, minus the pixels that another
   block's R-neighbourhood also reaches (the arc that seals the crack); keep the part 8-connected to the block;
   fill holes (text, pockets between characters). The restore only lands inside ink-free discs of radius R, so a
   unit never crosses an ink line.
3. A crack is component white outside the filled unit and 8-adjacent to it; its 8-connected groups are counted.
4. The unit goes down the unchanged bubble path as if it were a component (page share, locality, ratio, core
   fill for large units, the ink-outline gate), plus the gates below. Each (component, block) is judged once, by
   the first region that touches it; blocks inside an accepted unit are pockets and are skipped; a rejected unit
   does not hide the blocks inside it.

Accepted units go only into the bubble layer (bubble repaint, pseudo-bubble coverage, bright-island skip, and
bubbles winning over the character restore). They are kept out of the structural layers — any-angle separators,
texture_veto2, the bleed filter, stickers and `BUBBLE_REST_NEAR` — because a bubble is a partition or evidence
there and a new one would change pixels outside it. Over 47 pages × 4 levels only c362_004, c362_005 and
c362_011 change; the guard stays 18 / 12 / 12 / 16 (/664). Kotlin: `NightReadParams.bubbleSeal`
(`BubbleSealParams`). Numbers and the rejected v1/v2 designs are in [DECISIONS.md](DECISIONS.md) (Bubble-leak
sealing).

Extra gates, in order: **edge** (the unit's bbox within 2 px of the page edge — such a unit would be a margin
candidate itself), **no-split** (`BUBBLE_SEAL_REST_MIN`), **porous** (`BUBBLE_SEAL_MAX_GAPS`), **text-in**
(`BUBBLE_SEAL_TEXT_IN`), then the usual page-share and locality gates, **cut-far** (`BUBBLE_SEAL_CUT_GEO`),
the usual ratio / core fill, **chromatic** (a unit whose 1–3 px ring has mean chroma above
`STICKER_CHROMA_MAX` is rejected, not skipped; a Kotlin caller that passes no chroma is treated as a grey page,
chroma 0), the **ink-outline gate for small units too**, and **character** (`BUBBLE_SEAL_CHAR_MAX`). Fractions
are rounded like python's `round()` (text-in, outline and character to 4 decimals, ring chroma to 2) before they
are compared, and the outline distance is the 3×3 chamfer distance (`cv2.DIST_L2, 3`). In python both outline gates
use that distance. In Kotlin only this gate does: the HEAD outline gate in `buildBubbleMask` has always used the exact
Euclidean distance (noted in the code; the bubble masks match python pixel for pixel on every parity case), while
the sealing gate needs the chamfer distance because the exact one crosses the threshold 6 at offsets like (5,3).

### `BUBBLE_SEAL_R` = 1 (`NIGHTREAD_BUBBLE_SEAL_R`; Kotlin `r`)
Erosion radius. Erosion seals a crack whose actual white width is ≤ 2R, independent of how thick the line is
(synthetic lines w = 2/3/4 at 0/30/45/90° and an ellipse, gaps 1–10 px, hard and 4× anti-aliased: R=1 seals
d ≤ 2 — only d = 1 for binary-drawn 45° lines of every width and the w = 2 line at 30°, up to 3 when
anti-aliased — and never d ≥ 4). Closing (dilate → erode) was rejected: on a 2 px line it sealed 0 of 91 cracked
cases, because the erode wears the bridge away again. Every leaking bubble on the 47 pages has cracks ≤ 2 px.
R=2 was rejected: it seals the 4 px channel between the two lines of a double-line frame (the verifier's
W_dbl_i12_o5b turns 43 px black between the frames in L1), and it pushes c362_004:5 past the porous limit.
0 turns sealing off (pixel-identical to the output before it existed).

### `BUBBLE_SEAL_REST_MIN` = 50 (Kotlin `restMin`)
After sealing, the component must still have ≥ 50 px of deep white outside the unit — the seal really cut a
piece off rather than shaving a ring off the edge of a large component.

### `BUBBLE_SEAL_MAX_GAPS` = 8 (Kotlin `maxGaps`)
At most 8 cracks. A bubble with one or two hairline cracks qualifies; a rough frame leaking everywhere does
not. Measured at 1× and across JPEG q60–95 / 0.9× / 1.1×: c362_005:3 has 2–5, c362_011:5 3–5, c362_004:5 6–12
(8 at 1×; three variants exceed 8 and stay as before), the demo02 caption box c2236 13–23, the smeared-hand
attack H1 27. The margin is narrow on both sides (c362_004:5 at 12 against c2236 at 13), so raising it is not
free.

### `BUBBLE_SEAL_TEXT_IN` = 0.5 (Kotlin `textIn`)
At least half of the stroke pixels inside the region's box must fall inside the filled unit: the bubble has to
hold the text. A unit that is only a small pocket beside the text scores 0; every accepted real bubble scores
1.0.

### `BUBBLE_SEAL_CUT_GEO` = 3 × `BUBBLE_NECK_R` = 24 (Kotlin `cutGeo`; `null` = 3 × `bubbleNeckR`)
Every crack must lie within 24 px (geodesic, 8-connected, 1 px per step, inside the unit plus the cracks) of the
bubble body — the unit opened with `BUBBLE_NECK_R`, keeping the pieces that contain region-box pixels. The crack
has to be on the bubble's own outline. Real bubbles: 2–3 px (c362_005:3), 8–9 (c362_004:5), 14–16 (c362_011:5,
at the tail tip). The double-line frame W_dbl_i12_o5b is sealed by R=1 at a diagonal narrow point of the channel
between its frames, 36 px from the body; before this gate that page turned 11,560 px black in L1. It is only
half a guarantee: if the narrow point of such a channel were within 24 px of the inner frame's gap, that short
stretch of channel would be sealed into the unit (no synthetic case covers it). The limit follows the neck radius
on both sides: python defines it as `3 * BUBBLE_NECK_R`, and Kotlin's default `null` means 3 × `bubbleNeckR`; set
an integer to pin it.

### `BUBBLE_SEAL_CHAR_MAX` = 0.25 (Kotlin `charMax`)
Share of the area actually painted that lies on the raw character mask (model output, before snapping and
smoothing). Real bubbles: ≤ 0.062 (c362_011:5, its tail covers hair); units accepted in the loose virtual-box
scan ≤ 0.0798; units this gate stopped in that scan 0.30 (c362_004 c125) and 1.0 (three); character whites in
the earlier audits 0.9977–1.0. The smoothed mask cannot be used: c362_011:5 already scores 0.26 on it.

---

## Pseudo-bubbles

Open bubbles, gaps where the tail meets the body, and text written straight onto the artwork leave no closed
white component to work with. Instead a snug backing is grown outward from the white beneath the text.

### `PB_COV_MAX` = 0.85
Pseudo-bubbles only engage for text regions whose bubble-mask coverage is below this.

### `PB_NECK_R` = 10
Neck-cutting radius for pseudo-bubbles; blocks chin gaps and bubble-tail gaps.

### `PB_GROW_FRAC` = 0.6 · `PB_GROW_REF` = `"max"`
Growth limit = text box edge × 0.6. **The edge is the long one**: with the short edge, horizontal title and
effect lettering grows only a ring around each individual character and the gaps between characters stay
white.

The long edge has its price: a long vertical run can grow 175 px, far enough to pass through a chin gap and
flood into the white of a face. A reviewer caught that one — three rounds of my own visual inspection missed
it. Neck-cutting and the character mask are the two layers holding it back now.

### `PB_AURA_R` = 12 · `PB_AURA_THICK` = 6 · `PB_AURA_MIN_AREA` = 800
Thick-ink aura: nothing is filled within 12 px of a thick ink mass. "Thick" means a distance transform of at
least 6 px over an area of at least 800 px, so thin strokes — lettering, bubble outlines — do not qualify.
This is the second layer of insurance around a mass of hair beside a face.

---

## Sticker backgrounds

Once a plain white background is filled black, the foreground needs a white outline to lift it off the page.

### `STROKE_OBJ_FRAC` = 0.0035 · `STROKE_OBJ_MIN` = 4 · `STROKE_OBJ_MAX` = 7
Foreground outline radius = `min(W,H) × 0.0035`, clamped to 4–7.

**This is the real width of "that white ring around the figure".** Refining the mask boundary does nothing
for it; the outline radius is the knob.

### `STICKER_MIN_FRAC` = 0.01
Minimum page fraction for a background white component on a frameless page; only large areas count as
background.

### `FIG_NOISE_AREA` = 40 · `FIG_NOISE_CLOSE` = 11
Small foreground specks get no outline and are merged into the background fill. The second value is the
closing kernel that decides whether a speck is stranded inside the background.

### `STICKER_FIG_MIN` = 0.1 · `STICKER_FIG_MAX` = 0.85
Bounds on the foreground's share of the bbox. Too low means the whole panel is being read as background; too
high means no background was separated out at all.

### `STICKER_THIN_R` = 4 · `STICKER_THIN_MAX` = 0.45
Radius for calling white wispy, and the ceiling on the wispy fraction. Wispy throughout means the background
has already broken up and cannot be trusted.

### `STICKER_CHROMA_MAX` = 6.0
Ceiling on a component's mean chroma; blocks pale watercolour grounds. Calibration: pure black-and-white
pages all sit below 2.0, pale-colour pages run 2.5–18.3. This gate is what keeps colour pages from being
wrecked.

### `STICKER_EATEN_R` = 5 · `STICKER_EATEN_DENS` = 0.35
"Foreground white that got eaten": thin white (distance transform no more than 5) with a local ink density of
at least 0.35. That is what a white beard or dense hair looks like when it connects into the background
through the gaps between strokes.

### `STICKER_EATEN_MAX` = 0.06 · `STICKER_EATEN_HARD` = 0.3
A soft ceiling and a hard one. Above the soft ceiling there may be a white foreground object connected into
the background, so semantic evidence is required; above the hard ceiling it is refused regardless.

### `STICKER_TEXT_BG_MIN` = 0.1 · `STICKER_TEXTON_PAD` = 8
The semantic evidence: at least 0.1 of the text really sits on this white, which means the author is treating
it as a background to write on.

**Measured on the tight bbox plus 8 px, not the 40 px window**: the 40 px window sweeps in handwriting from
the neighbouring panel as false evidence, which is exactly how ch34_010's white shawl got eaten.

### `STICKER_TEXT_MAX` = 0.55 · `STICKER_SMALL_AREA` = 0.02
The gate for a bubble that failed to merge (a ceiling on text coverage), and the area threshold below which a
component needs semantic evidence before it may be filled black.

### `STICKER_NECK_R` = 8 · `STICKER_CORE_MIN` = 0.03
Region-level protection: the erosion radius, and how much of the component the surviving core has to be
before the component counts as an open background. The core then geodesically reconstructs the component;
white the reconstruction cannot reach is white reachable only through a narrow gap — white that belongs to an
object.

### `STICKER_PROTECT_EATEN_MIN` = 0.002 · `STICKER_PROTECT_DILATE` = 6
How much eaten white an object-attached region has to contain before the whole of it is protected, and how
far the protected region is expanded. A clean panel's narrow frame gaps hold no eaten white and get filled as
usual.

### `FAINT_OF_F_MAX` = 0.62 · `FAINT_G` = 160
Ceiling on the fraction of faint pixels inside the foreground; `FAINT_G` is the grey floor for counting a
pixel as faint. Faintly sketched crowds and buildings exceed the ceiling and are left unfilled.

---

## Frame-hug promotion

Panel background enclosed by the frame does not connect to the page gutter, so the classification stage never
reaches it. This layer picks it back up by asking whether the component runs along the frame.

### `FRAME_HUG_DILATE` = 5 · `FRAME_HUG_THICK` = 3.0
The component is dilated by 5 px and intersected with the frame-line mask; the intersecting pixels are
divided by a nominal line thickness of 3.0 to give a hug length.

### `FRAME_HUG_MIN` = 0.25 · `FRAME_HUG_STRONG` = 0.4
Floor on hug length over bbox perimeter, and the threshold for a strong hug. A background runs along the
frame; white foreground clothing only touches it at points. A strong hug means the background evidence is
good enough to use the relaxed gates.

### `PROMOTED_TEXTON_MAX` = 0.3
The text-coverage ceiling that still rejects a strong hug. A lot of text genuinely sitting on the component
means it is a caption box, and filling that black turns the text into a smeared outline.

---

## Fill tiers

How much white should go black is a product setting. The library defines three levels (user definition,
2026-09-27); since 2026-10-01 the product ships two of them, Standard = L2 and More = L3 with rule A2, and L1 is
kept for research. The full
pipeline — every accepted sticker, pseudo-bubbles and floating-head harmonization — stays the library default
so the fixtures and guard baselines hold, but it is no longer one of the product levels. Each level is a set of
environment variables (listed in the header of `research/nightread.py`).

### `STICKER_MODE` = `"all"` (`NIGHTREAD_STICKER_MODE`)
Which accepted sticker components are actually filled. `all` fills everything the safety net accepted (the
default). `plain` (L1) keeps only "no-artwork background": components that do not touch the raw character
mask and whose outer ring meets nothing but frame lines or the page edge. `simple` (L2/L3) keeps the plain
ones plus any accepted component with `rough` ≤ `STICKER_ROUGH_MAX` and page fraction ≥
`STICKER_SIMPLE_MIN_FRAC`. A promoted component that is dropped is not core-filled either. Whatever is dropped
gets the treatment it would have had without the sticker layer (scene curve on a framed page, untouched on a
frameless one), so a level can only be brighter, never wrong.

The ablation behind it: the tearing on device pages comes from large white components that touch a character
and contain linework. They pass the safety net, and the core fill then stops at the character-mask boundary,
leaving a black ring around the figure. Roughness (perimeter² / 4π·area) separates them: legitimate large
backgrounds measure 1.8–8.3, the tearing components 17.8–173.

### `STICKER_ROUGH_MAX` = 10 (`NIGHTREAD_STICKER_ROUGH`) · `STICKER_SIMPLE_MIN_FRAC` = 0.005 (`NIGHTREAD_STICKER_MINFRAC`)
The `simple` thresholds. L2 uses 10 and 0.5%; L3 uses 20 and no area floor. Guard: L1 12, L2 12, L3 16 of
665, against 18 for the default. Mean bright fraction (pixels ≥ 110) over the 8 tearing device pages: L1
40.7%, L2 40.2%, L3 40.0%, default 35.7% (40.3 / 39.8 / 39.6 / 35.6 before the outline gate existed).
These were measured before the any-angle separator and the bleed-panel filter (next two sections); with them
the guard reads L1 13, L2 13, L3 17, default 19 (each +1 is the same ch34_006 annotation drawn over a gutter)
and the bright fraction L1 38.8%, L2 38.3%, L3 38.1%, default 36.3%. Current guard (688 scored boxes, after the
2026-10-03 redraw): full pipeline 11, L1 10, L2 10, old L3 10, More 10 (Kotlin 11 / 9 / 9 / 9 / 9).

### `STICKER_PLAIN_RING_R` = 7 · `STICKER_PLAIN_ART_MAX` = 0.05 · `STICKER_PLAIN_FRAME_DIL` = 7
The `plain` test. The ring is the component dilated with a 15×15 ellipse minus the component; "artwork" is
any pixel darker than `INK_DARK_TH` that is not within a 7×7 dilation of the frame-line mask. The component is
plain when less than 5% of its ring is artwork, and less than `STICKER_PLAIN_FAINT_MAX` of it is faint
linework (next entry).

### `STICKER_PLAIN_FAINT_MAX` = 0.10 · `STICKER_PLAIN_FAINT_HALO` = 5
The second half of the `plain` test. "Faint linework" is a ring pixel with `INK_DARK_TH` ≤ g < `WHITE_TH`
that is neither within the 7×7 frame-line dilation nor within a 5×5 dilation of the dark ink (the anti-aliased
halo of a dark line belongs to that line). Clouds, speed lines and light screentone are linework too, just
lighter than `INK_DARK_TH`; counting only dark ink let a cloudy sky through. On device page c362_009 the sky
under the bunting measured 0.041 dark ink (under the 0.05 bar) and 0.638 faint linework, and all three levels
painted it black along the cloud lines.

Over the 47 pages (11 fixtures, 8 device tearing pages, 28 extra), 139 components pass the sticker safety net
and only 2 of them reach this test (do not touch the character mask, dark ink < 0.05): that sky (0.638) and a
small triangle bounded by faint ceiling lines on c371_010 (0.167). Both were wrong fills, and with this test
`plain` fires on none of the 47 pages, so L1 today is gutters plus bubbles and paints no stickers at all. Any
threshold between 0 and 0.166 gives the same result (the triangle is 0.16676 and the test is strict); a 3×3 halo gives 0.688 / 0.176. The test applies only to
`plain`: the `simple` rule (roughness and area) does not look at it. A wider "plain background" test (ring minus
bubble outlines and text) is open research.

### `PSEUDO_BUBBLES` = 1 (`NIGHTREAD_PB`) · `HARMONIZE` = 1 (`NIGHTREAD_HM`)
Switches for the pseudo-bubble and floating-head layers. All three levels turn both off:
pseudo-bubbles grow from the text into the background and cost 2 guard boxes; harmonization has no effect on
the guard but digs black holes into panel backgrounds. The default keeps both on.

### Kotlin: `NightTier` and `renderTiers`
`NightTier.L1/L2/L3.apply(base)` is the single source of the three parameter sets: it sets `stickerMode`,
`stickerRoughMax` and `stickerSimpleMinFrac` as above, turns the "More" rule on for L3 (`more.enabled = true`, the
other `more` fields come from `base`; off for L1/L2), turns `pseudoBubbles` and `harmonize` off, and keeps
everything else (brightness and so on) from `base`. The two product levels are "Standard" = L2 and "More" = L3; the
old L3 without A2 is `L3.apply(base).copy(more = base.more.copy(enabled = false))`, kept for research comparisons.
`NightRead.renderTiers` renders several levels from one analysis and requires exactly this shape: the levels may
differ only in those three fields and `more`, and every level must have pseudo-bubbles and harmonization off,
because both make the character-restore mask or the gutter depend on the level. A level is skipped (the sink gets
null) only when its composition key matches the previous level's: keep, promoted components, the keep before A2,
and More's drawing settings (the A2 switches `keepDarkStroke`, `edgeSeedFallback`, `edgeBand`, `edgeReach`, plus
the background-object parameters; with that rule on, a level is composed even when its keep is empty). An A2
level whose keep equals the previous one but whose drawing switches differ is still composed; pixel-level dedup
is left to the caller. `key` (`l1`/`l2`/`l3`) is what Yakuyomi stores in the `nightread_fill_level` preference
(only `l2` and `l3` now); night pages are saved as `<page>.night.std.webp` and `<page>.night.more.webp`, and older
`l1`/`l2`/`l3` files are still read.

## "More" rule A2

The product "More" level is L3 plus this rule set (user decision, 2026-10-02; research in
`research/out/more_and_strip/more/HARDEN.md`, gitignored). It only **adds** to L3's keep, never removes, so
More ⊇ L3 ⊇ Standard holds for keep by construction. Research side `MORE_RULE` (`NIGHTREAD_MORE`, default 0 = the
old L3); Kotlin `NightReadParams.more: MoreRuleParams` (`enabled` defaults to false, `NightTier.L3` turns it on).
The thresholds default to the product values, and each has an environment variable on the research side.

**Candidates**:
- C1: components the safety net accepted but the level dropped.
- C2: components the safety net rejected only at the "too little foreground `figlo` / too much text on it `textOnP` /
  text window `textCov`" gates. This reads the gates the safety net **actually** failed (`gates` in the
  `sticker_plan` audit; Kotlin `Sticker.Metrics.gates`), not gates reconstructed from the audit numbers.
- C3: margin-white components on a framed page that count as in-panel white once every in-panel test is relaxed
  by `MORE_HYST` (thick-core radius 26 → 19.5, core fraction 0.15 → 0.1125, depth 0.25 → 0.1875; or depth ≥ 0.375
  and enclosed ink ≥ 0.015). The thick core uses the 5×5 chamfer (the same cv2 call as
  `classify_white_components`). These components are measured with the safety net's metrics first (the gates of the
  weak-hug path).
- Excluded: a component that is a candidate only because it hugs a frame (not in-panel white, not margin white,
  not on a frameless page) needs a hug score ≥ `FRAME_HUG_MIN` × (1 + `MORE_HYST`) = 0.3125.

**Every candidate must pass** (S1–S5), measured in the component's bbox padded by `MORE_WIN_PAD`:

### `MORE_T_OUT` = 5 (`NIGHTREAD_MORE_TOUT`; Kotlin `tOut`)
S1, free outer boundary. Fill the component's holes, smooth it with a `MORE_MEDIAN` (5×5, binary majority,
BORDER_REPLICATE) median, take the 1 px inner boundary, and drop what is "explained": the character (snapped), the
bubbles (before trimming, sealed ones included), bubble outlines (non-white pixels within the
`BUBBLE_OUTLINE_DIST` ellipse outside a bubble), text (seg, square-dilated by `MORE_EXPLAIN_DIL` = 7) and frame
lines (same), all square-dilated again by `MORE_NEAR_DIL` (9). With L the remaining length, the score is
L²/(4π·area), rounded to 2 decimals before the comparison. Across the 7 inputs the accepted set is the same for any
threshold in 3.69–7.7.

### `MORE_T_IN` = 2 (`NIGHTREAD_MORE_TIN`; Kotlin `tIn`) · `MORE_HOLE_MIN_AREA` = 8 · `MORE_HOLE_DARK_MAX` = 200
S2, inner marks. Only the component's holes (8-connected) that really contain drawing count: area ≥ 8 px or darkest
pixel < 200 (compression specks and faint noise do not). Their 1 px inner boundary minus the explained pixels, same
formula. Stable range 0.52–3.79. Splitting S1 and S2 keeps JPEG jaggies along cloud edges and gradients from
inflating the boundary (prototype A's single free boundary ranged 9.4–23.5 on the top panel of c362_009 across the 7
inputs).

### `MORE_FAINT_MAX` = 0.3 (`NIGHTREAD_MORE_FAINT`; Kotlin `faintMax`) · `MORE_RING_R` = 7 · `MORE_HALO` = 5
S3, faint outer ring. The ring is the component dilated by a 15×15 ellipse minus the component, minus the
character (snapped); faint pixels are those not in a bubble (outline excluded), text or frame line, not in the 5×5
halo of dark ink, with `INK_DARK_TH` ≤ g < `WHITE_TH`; the fraction is rounded to 3 decimals. This is what mostly
keeps cloudy skies grey. The stable range is only 0.276–0.324 (speed lines on c362_008 vs a cloudy sky on
c362_012) and should be re-swept on new chapters.

### `MORE_ROUGH_CAP` = 40 (`NIGHTREAD_MORE_ROUGH`; Kotlin `roughCap`)
S4, soft-gate components need rough ≤ 40. A soft-gate component is a C2/C3 component that failed any gate, or a C1
component in the 0.3–0.5 hug band (`FRAME_HUG_STRONG` × (1 ± `MORE_HYST`)) that trips the text gate of either the
strong or the weak hug path (hug ≥ 0.3 and text-on > 0.3; or hug < 0.5 and text window > 0.55 and page fraction
< 2%). Stable range 34.9–40.4.

### `MORE_CHAR_MAX` = 0.67 (`NIGHTREAD_MORE_CHAR`; Kotlin `charMax`)
S5, a component with this much or more of its area under the raw character mask is not taken: it is white on or
enclosed by a character, not background behind one. Stable range 0.578–0.739 (the white-bearded old man's panel on
ch34_006 vs white inside a tornado on c362_013); the phone's character mask is the NCNN build, so print it on a
device.

### `MORE_HYST` = 0.25 (`NIGHTREAD_MORE_HYST`; Kotlin `hyst`)
Hysteresis: C3's in-panel tests × (1 − this), the hug-only candidate floor × (1 + this), and S4's strong/weak hug
band × (1 ± this).

### `MORE_KEEP_DARK` = 1 (`NIGHTREAD_MORE_KEEPDARK`; Kotlin `keepDarkStroke`)
P1: the sticker's foreground highlight (220) does not overwrite pixels that are already ≤ `BG` at that point
(gutters, separators, an earlier sticker). A2 levels only.

### `MORE_EDGE_FB` = 1 (`NIGHTREAD_MORE_EDGEFB`; Kotlin `edgeSeedFallback`) · `MORE_EDGE_BAND` = 3 · `MORE_EDGE_REACH` = 4
P2: for a core-fill block (8-connected after the `CORE_NECK_R` opening) that has no frame seed, the page edge
(within 3 px) inside that block dilated by `CORE_NECK_R` + 4 also seeds it — bleed panels have no frame line to grow
from (the speed-line background on c362_008). Blocks that do have a frame seed get nothing extra: adding it moves the
geodesic-ratio cut and leaves a small black wedge (c362_017, beside a shoulder).

**P3**: on a frameless page the gutter layer uses the keep from before A2 (Kotlin `Sticker.Plan.baseAccept`): newly
taken components are painted whole as stickers and the margin band stays as it was.

Numbers (47 pages × 7 inputs, guard boxes, nesting, cost) are in [DECISIONS.md](DECISIONS.md),
"「更多」＝L3＋新規則 A2".

---

## Any-angle separators (`nightread_sep.py`)

`frame_line_mask` only sees long horizontal and vertical lines, so slanted gutters, gutters broken by a bubble
or a sound effect, and page margins drawn to the edge never reach the margin path. This layer finds frame
lines at any angle straight from the pixels: the white strip between two near-parallel frame lines is a gutter,
and the artwork-free white between the page edge and a frame line is a margin. The result (`sep`) is painted
with the margin treatment (BG fill, edge stroke) before the sticker layer, and it **sits above the character
mask**: only bubbles are subtracted, never the character, and compose's final character restore skips it
(layer order: text > bubble > gutter/margin > character > background). Over 47 pages the white still left grey
inside gutters drops from 314,591 to 554 px (against a first round that subtracted the character mask).

### `SEP_ON` = 1 (`NIGHTREAD_SEP`)
Switch for the whole layer. Off (together with `BLEED_ON`) reproduces the output from before it existed,
pixel for pixel.

### `LEN_FRAC` = 0.15 · `NEAR_WHITE_R` = 3 · `DARK_TH` = 100
Frame-line candidates and minimum length. A candidate pixel is darker than `DARK_TH` with white inside a 7×7
square (a frame line borders a white gutter or margin on at least one side; dark pixels inside linework never
enter the Hough). The shortest frame line is 0.15 × the short side (at least 60 px): panel frames are ruled
long lines, speed lines and linework are mostly shorter.

### `TH_STEP` = 0.5 · `PEAK_NMS_T` = 3 · `PEAK_NMS_R` = 6 · `PEAK_MAX` = 800 · `PEAK_VOTE_FRAC` = 0.8
Hough. 0.5° per angle bin, 1 px per ρ bin, summed over 3 ρ bins (a 3 px wide white-adjacent band); a peak is a
local maximum over θ ±1.5° and ρ ±6 px with at least 0.8 × the minimum line length in votes; the 800 strongest
are kept. θ wraps around (0° and 179.5° are neighbours).
The cap is still binding: the 47-page set has 3,239–5,695 qualifying local maxima per page (median 4,447). At 400
(until 2026-10-01) the frame lines of real gutters on c362_017, c371_006 and c371_013 ranked below the cut and those
gutters stayed grey; 800 recovers them for about +28 ms per page on the desktop JVM (the walk over the extra peaks).
Raising it changes which tied peaks survive the cut, so the Kotlin port has to keep numpy's unstable sort order.

### `WALK_WIN0` = 4 · `WALK_WIN` = 2 · `GAP` = 8 · `FILL_MIN` = 0.85
Walking along the line. The first walk and the PCA refinement use a ±4 px normal window (absorbing the Hough
angle error); the final walk uses ±2 px; gaps up to 8 px are allowed. Initial runs need a hit rate of 0.6 and a
final segment 0.85 (ruled lines ≈1.0). Each segment is refined twice on its own,
so collinear pieces on the same ρ are not tilted by the longest one.

### `DEDUPE_ANG` = 2.0 · `DEDUPE_OFF` = 6.0 · `GROUP_ANG` = 1.0 · `GROUP_OFF` = 6.0
De-duplication and collinear grouping. Two overlapping segments within 2° and 6 px are the same line found
twice; segments within 1° and 6 px join one interrupted frame line (a list of intervals), so a frame cut by a
bubble, a sound effect or a bleeding figure stays one line. The margin's occluded-frame pass (`MARGIN_OCC`) does
not use `GROUP_ANG`: its short pieces are grouped by a joint fit (`occ_group`) that reuses `GROUP_OFF`, `GAP`,
`FILL_MIN` and `WALK_WIN0`.

### `BUB_LINE_R` = 7 · `BUB_LINE_MAX` = 0.5
A line hugging a bubble or caption box is not a panel frame: sampled every 2 px, if half the samples fall in
the bubble mask ⊕7 it is discarded (the left edge of a square caption in c371_009 was once taken for a margin
frame and the speed lines beside it came out striped).

### `PAIR_ANG` = 5.0 · `GAP_MIN` = 6 · `WMAX_FRAC` = 0.09 · `OVL_FRAC` = 0.5
Which two lines can bound a gutter: at most 5° apart, 6 px to 0.09 × short side apart along the normal, and
overlapping by at least 0.5 × the minimum line length. Measured gutters are 1.0–1.3% wide (vertical), 2.5–3.2%
(horizontal), 6.3% / 7.9% for the wide slanted bands; the next widest candidate (demo01, 10.7%) is a pair of
parallel lines inside the artwork, which the profile test rejects anyway.

### `EDGE_SKIP` = 4 · `PROF_WHITE` = 0.9 · `PROF_WHITE_BRIDGE` = 0.98 · `SHORT_BRIDGE` = 24 · `PROF_GAP_FILL` = 2 · `PASS_MIN` = 0.6
Profile whiteness, station by station along the gutter with a 3-station window. Only the interior more than 4 px
from both lines counts (a quarter of the width in narrow gutters). A station with frame evidence on both sides
needs 0.9 white (or at most 3 non-white pixels in the window — one anti-aliased pixel drops a narrow gutter to
0.89); a bridged station inside the core needs 0.98 and the whole bridge must pass, so a bleeding figure or white
clothing crossing the gutter blocks the bridge entirely; bridges of up to 24 stations are treated as small
detection gaps and use 0.9. Isolated failures of up to 2 stations are filled. A strip whose evidence stations
pass less than 60% of the time is rejected.

### `STRIP_EXT_FRAC` = 0.15 · `BRIDGE_FRAC` = 0.30 · `EXT_DARK_SKIP` = 8
Extension. Collinear gaps up to 0.30 × the short side may be bridged (as `GFC_CLOSE_FRAC`); where only one side's
line continues, a strip extends outward by up to 0.15 × the short side (the tip of a slanted gutter meeting the
page edge, a gutter mouth), first stepping over up to 8 px of dark panel corner and stopping at the first
non-white station.

### `EDGE_MAD_MAX` = 1.0 · `TEXT_DIL` = 3 · `TEXT_PROF_MAX` = 0.2
Shape rejects. The normal position of the white edge on each side, detrended per segment, with MAD × 1.4826 above
1 px is curved, not a ruled gutter. More than 20% of stations touching the text mask ⊕7 means a caption box.

### `FAMILY_ANG` = 10.0 · `FAMILY_REACH` = 1.5
Hatching reject: a third long line within 10° lying outside either line within 1.5 × the gutter width and
overlapping more than half the span marks a family of speed lines or hatching (neighbouring radial speed lines
can differ by several degrees).

### `NET_TOUCH` = 2 · `NET_THICK` = 5 · `NET_EXT_FRAC` = 0.15
Network connectivity. A strip must connect, along its own corridor (between its two lines, extended by 0.15 ×
the short side at each end) and through passable pixels, to a margin, the page-edge band or an accepted strip.
Passable is white, or a solid dark blob with stroke width ≥ 10 px (sound effects, solid black). Parallel-line
white isolated inside a panel (title double rules, window frames) cannot connect and is dropped.

### `MARGIN_FRAC` = 0.12 · `MARGIN_OK_TH` = 200 · `MARGIN_RUN_FRAC` = 0.015 · `MARGIN_LIGHT_FRAC` = 0.02 · `MARGIN_LIGHT_MIN` = 3
Margins. Walking inward from the page edge along the axis, through pixels ≥ 200, text or bubble only, the white
up to a margin frame line (or its sealing extension) within 0.12 × the short side is margin. Runs must form
segments of at least 0.015 × the short side (thin white slits between speed lines are artwork), and "light but
not white" pixels (200–234) along the way must stay within max(3, 2% × depth). The result passes texture_veto2.

### `FAR_PROBE` = 14 · `FL_R` = 3 · `FL_HIT_R` = 8 · `MARGIN_EDGE_ANG` = 30.0 · `MARGIN_AXIS_ANG` = 5.0 · `MARGIN_FILL_MIN` = 0.95
Margin frame lines. A line that already bounds a gutter qualifies within 30° of the page edge; any other line
must be nearly parallel to it (≤ 5°) with a hit rate ≥ 0.95. A run "hits" the line within ±8 px (the fitted line
sits on the white side of the frame, and the far side of a thick frame must count too); after hitting a gutter's
line the walk probes 14 px further, and a gutter on the other side means this white belongs inside a bleed
panel, not the margin. The ±3 px frame raster doubles as texture_veto2's frame mask and as frame evidence for the
bleed filter.

### `MARGIN_OCC` = 1 (`NIGHTREAD_MARGIN_OCC`) · `MARGIN_CLOSE` = 1 (`NIGHTREAD_MARGIN_CLOSE`)
Switches for the two margin repairs (Kotlin `marginOcc` / `marginClose`); both off reproduces the output from before
they existed, pixel for pixel. **Occluded frames** (`occluded_frames`): a margin frame cut by a sound effect, a bubble
or a bleeding object into pieces that are each shorter than the minimum line length (c371_001: the left frame of
row 2 is cut by the stroke of 「ザ」 and its white outline into 150 and 159 px, minimum 203) is never found by the
main detection, so the margin walk has nothing to hit. A second pass over the same Hough peaks (only those within
`MARGIN_AXIS_ANG` + 1° of horizontal/vertical) finds the short pieces and chains collinear ones; the chain feeds only
the margin's "hit a frame" band and texture_veto2's frame mask — no thin line through white, no sealing extension,
no gutter, no bleed-filter evidence. It still has to pass `MARGIN_AXIS_ANG` and `MARGIN_FILL_MIN`, and lines hugging
a bubble are dropped. **Closed rows** (`_close_rows`): rows (or columns) where an object reaching into the margin
stops the walk short of the frame, with margin runs on both sides, also count as margin, and the white behind the
object up to the (interpolated) frame position that is 4-connected to the margin is taken too. Over 47 pages × 4
levels 13 pages change, every newly black pixel was white in the original; guard 18 / 12 / 12 / 16 (/664) unchanged.
Occluded pieces are grouped by a **joint fit** instead of comparing their own angles (a short piece of a thick frame
tilts 1–2° when its evidence switches from one edge to the other): the union of the pieces' hit pixels is refit by
PCA, every piece must have a hit rate ≥ `FILL_MIN` along the joint line (walked ±`WALK_WIN0`) and both ends within `GROUP_OFF` of it, and a
piece overlapping an accepted member by more than `GAP` along the line is not merged; a member's hit rate is the
larger of the one along the joint line and the one along its own line. The c371_001 sweeps (0.80–1.25×, JPEG, flips,
rotations) pass 248 / 248 and the half-step set 235 / 235.

### `OCC_PIECE_FRAC` = 0.5 · `OCC_GAP_FRAC` = 0.05 · `MARGIN_HALO_R` = 2
Each occluded-frame piece must be at least 0.5 × the minimum line length, and the chain at least two pieces whose
lengths sum to the minimum line length — the same evidence as one ordinary frame line, only covered in the middle.
The final walk of the second pass uses ±`WALK_WIN0` instead of ±`WALK_WIN` (a frame ≥ 5 px thick has candidate
pixels only on its two edges, and the ±2 window loses one edge under scaling or anti-aliasing, dropping the hit
rate to 0.93). The gap between neighbouring pieces and the length of a closed-row gap are both at most 0.05 × the
short side. In closed rows the "light but not white" budget ignores the anti-aliased halo within 2 px (square
kernel) of dark pixels (< `DARK_TH`): that halo belongs to the object in the way.

### `SEP_BUB_DIL` = 7 · `PAIR_SUB_MAX` = 0.5 · `SEP_MIN_CC` = 150
Layering. SEP subtracts the bubbles ⊕7 (square); a strip that loses more than half to that is dropped whole
(what remained would be a ladder of fragments) — **bubbles only, never the character mask**; fragments under
150 px after the subtraction are not painted. The character mask is never subtracted.

---

## Bleed-panel filter (`nightread_bleed.py`)

In a bleed panel (the artwork runs to the page edge with no frame) the sky, the ground or a tablecloth is the
same white component as the margin. The margin path's only defence is texture_veto2, sparse linework passes its
density gate, and the panel gets cut into jagged black blocks near the frame lines. This layer runs after
texture_veto2 on both margin paths and looks at what each piece's **outer ring** touches: frame lines or
gutters (keep) or artwork (drop). It only removes; SEP is painted afterwards regardless. Over 47 pages the
tears went from 215 blocks to 83.

The ring (ellipse r5, minus 4 px at the page edge) is classified pixel by pixel, highest priority first: FR frame
line (horizontal/vertical ∪ any angle, ⊕9; for pieces touching the page edge also margin-strip frame lines, see
`MARGIN_LINE_MAX`) > SP gutter/margin (before bubble subtraction, ⊕5) > BB bubble (⊕r6)
> TX text (⊕r6) > CH character (⊕r8) > VT white removed by the veto > DC white beyond the depth band > WO other
white > AR non-white. `inf` is the fraction left after BB/TX/CH; `frame_inf` = (FR + SP) / inf;
`art_inf` = (VT + DC + AR) / inf. Every fraction is rounded to 3 decimals before it is compared.

### `BLEED_ON` = 1 (`NIGHTREAD_BLEED`)
Switch for the whole layer. With SEP off the filter still runs, with empty gutter, margin and any-angle frame
evidence.

### `RING_R` = 5 · `RING_EDGE` = 4 · `FR_DIL` = 9 · `SP_DIL` = 5 · `BB_R` = 6 · `TX_R` = 6 · `CH_R` = 8 · `TEXT_BOX_PAD` = 10
Ring and classification geometry. There is no evidence beyond the page edge, so 4 px there are left out, lest a
piece touching the margin look like it touches no frame.

### `NET_FRAC` = 0.03 · `TXT_KEEP` = 0.15 · `FRAME_KEEP` = 0.6
Kept first: pieces of at least 3% of the page (the gutter network itself), pieces overlapping text-region boxes
⊕10 by 15% or more (white beside text), and `frame_inf` ≥ 0.6 (bounded mostly by frames or gutters). A piece
lying entirely inside SEP is also kept (SEP paints it anyway).

### `ISLAND_BB_MAX` = 0.3
Island in the artwork: not touching the page edge, a ring with no frame or gutter at all (FR + SP = 0), bubbles
under 30% ⇒ dropped.

### `ENCLOSED_MAX` = 0.15 · `ART_DROP` = 0.45
`inf` < 0.15 means the piece is wrapped in bubbles, text or character and the evidence is too thin: keep.
Otherwise `art_inf` ≥ 0.45 (nearly half the boundary is artwork) is dropped, and the rest is kept.

### `MARGIN_OK` = 200 · `MARGIN_COVER` = 0.7 · `MARGIN_ANG` = 5.0 · `MARGIN_DARK` = 190 · `MARGIN_MIN_ROWS` = 30 · `MARGIN_MIN_DEPTH` = 4 · `MARGIN_NEU_LOOK` = 8
Margin-strip test (pieces touching the page edge; any side passing keeps the piece): walk inward along the edge
to the first pixel below 200 or the first neutral pixel; rows that stop on a neutral (including one within 8 px
past the stop) do not count. For the rest, 70% of the stops must lie within ±2 px of a line at most 5° off the
page edge, the median stop brightness must be ≤ 190 (a line, not a gradient), informative rows and the line's
span must reach 30, the depth must be at least 4 px, and "light but not white" pixels on the way must stay within
max(3, 2% × depth). Maximum depth is `SAFE_GUTTER_DEPTH` (0.12 × the short side).

### `MARGIN_LINE_MAX` = 12 · `MLINE_MIN_RUN` = 30
Margin-strip frame lines as frame evidence (2026-09-28, c362_002). On a side that passes the margin-strip test, the
straight dark line the stops sit on is a panel frame — even when a bubble or a character cuts it so short that
`frame_line_mask` never sees it (c362_002's top-right frame shows only ~96 px). From each stop on the fitted line,
walk inward over consecutive non-white pixels (< 235); only if white comes back within 12 px is that run recorded
as frame line (a thin line with paper on both sides; a run that never returns to white is a frame fused with
artwork and is not recorded). Top/bottom sides go into one map and left/right into another, each dilated ⊕9 like
FR. For a piece that **touches the page edge**, ring pixels on those maps that are not already FR count as FR,
but only in a direction where the contact length — distinct x along a top/bottom line, distinct y along a
left/right line — reaches 30 (the same row count the margin-strip test itself demands). All edge-touching pieces
run the margin-strip test first, on all four sides, before any ring is classified. Each piece reports the contact
lengths as `mrun` (Kotlin `mrunH`/`mrunV`). Kotlin: `BleedParams.marginLineMax` / `mlineMinRun`.

---

## Core fill

Only wide-open regions reachable from a frame seed without squeezing through a narrow neck count as
background. A face or white clothing that shares a component with the background — because of a gap in the
line art — is severed at the neck. That is geometric protection, not a threshold.

### `CORE_NECK_R` = 12
Opening radius; severs the line-art gaps that connect a face or white clothing into the background.

### `CORE_RECOVER_R` = 9
Geodesic radius by which the settled core is recovered back out to the ink lines, so the fill hugs the line
art and leaves no white ring.

### `GEO_RATIO_MAX` = 1.6 · `GEO_SLACK` = 40
A region is background only if its geodesic distance is no more than 1.6× the straight-line distance plus
40 px. Background comes straight in from the frame, giving a ratio near 1; cloth and skin have to detour
around the figure's ink lines, giving a high one.

### `CORE_RELEASE_PAD` = 16
Safety dilation for a semantic release. Both geometric protections above are crude stand-ins from before the
character mask existed and they misfire on background wedges, so anything inside the core that the mask says
is definitely not a figure is released to be filled black.

The mask is dilated by 16 px before the release: the model's mask boundary is upscaled from 640, and
releasing right up against it bites into a white shirt cuff (18.6%) and a hand (16.9%), taking violations
from 18 to 20. Anything from 8 px up is back at 18 boxes.

---

## Character semantic mask

**A required input.** The guard boxes prove the red line is unreachable without it: the best pure-geometry
recipe still leaves 37 violating boxes, and pays 14 percentage points of light area for the privilege.

### `NIGHTREAD_CHARMASK` (environment variable)
The output directory from `charmask.py`, holding one `<page>_char.png` per page. Missing it is a hard error.

### `CHAR_SNAP` = 10
Radius for snapping the boundary to the ink: the mask grows geodesically inside non-ink area and stops when
it hits line art.

**A larger radius does not thicken the boundary.** That is a property of geodesic growth, and it refutes the
intuition that a thin boundary and the red line are necessarily in conflict. What conflicts is uniform
dilation, not geodesic snapping. Going from 1 to 10 took violations from 31 to 21 and widened the boundary by
0.15 px.

### `CHAR_SNAP_PAD` = 1
How many rounds of dilation fold the contour line itself into the mask after snapping. **This is the real
knob for edge thickness.**

### `MASK_SMOOTH_MEDIAN` = 15
Kernel radius for the median smoothing. The problem is not 1 px jaggies, it is blocky stepping: the mask is
produced at 640 and upscaled, leaving right-angled steps of 10–20 px along the boundary. A median filter
keeps straight edges, shaves the protruding corners, and does not shrink the mask; a Gaussian cannot do all
three.

### `EDGE_FEATHER` = 0.7
Antialiasing radius at the figure-restoration boundary, a transition of only 1–2 px. Any wider and you
introduce a gradient the original artwork never had.

---

## Character ring thinning (`nightread_ring.py`)

Background fill used to stop at the figure's safety margin, leaving a 15–20 px grey ring between the figure and
the black. The compromise (user decision 2026-10-03, both product tiers): **only where a drawn outline separates
the background from the figure** does the already-black background grow up to the outline; elsewhere the ring
keeps its old width. Over 47 pages the median ring width goes from 16.8 px to 3.6 px (standard) and 4.0 px
(more). Rules and numbers: docs/DECISIONS.md, section on character ring thinning; Kotlin `RingParams`
(`NightReadParams.ring`) and `Ring`.

### `RING_ON` = 1 (`NIGHTREAD_RING`; Kotlin `enabled`)
Master switch. 0 = the output before this rule, pixel-identical.

### `RING_R_OUT` = 18 · `RING_GV_GAP` = 24 · `RING_RAW_CLOSE` = 6 · `RING_SEED_MIN` = 200 · `RING_STEPS` = 64
What may be claimed (research code name F). Margin-band pixels vetoed as line art only because of the figure's own
ink count only within 18 px of the figure mask, and only when they sit between the black and the figure: 5×5
chamfer distance to the seed plus to the raw figure output ≤ 24. Narrow notches of the raw output (what a radius-6
closing adds) are never claimed, so the black does not creep between the fingers of a blob the model painted onto
blank paper. Seeds are background-black components of at least 200 px (8-connected, see below); growth from them
is 4-connected and at most 64 steps.

### `RING_INK_SUM` = 957 · `RING_INK_BOX` = 5 · `RING_INK_MAX_GRAY` = 200
Deep ink: the sum of (255 − grey) over a 5×5 window (border replicated) is at least 957 (mean darkness 0.15) and
the centre is darker than 200. This measures the amount of ink, which blur and JPEG spread out but do not reduce;
the 2 px, grey 186–216 faint stroke at the right edge of demo01's shoulder plate stays below it.

### `RING_HOLE_MAX` = 256 · `RING_EV_IN` = 8 · `RING_EV_OUT` = 3
Holes in the raw figure output smaller than 256 px that do not touch the page edge are filled first (for the
evidence only). A boundary point has an outline when deep ink lying within 8 px inside or 3 px outside the raw
output is within radius 8 of it. Holes and seeds are **8-connected components**: the research code calls
`connectedComponentsWithStats(m, 4)`, but cv2's second positional argument is the labels output, not the
connectivity, so it actually ran with the default 8; the product follows what the research code actually did.

### `RING_RAY_FROM` = 3 · `RING_RAY_TO` = 24 · `RING_RAY_SIGMA` = 2.0
Open background in front: an outline point whose outward normal (negative gradient of the raw output after a σ2
Gaussian) runs into the figure again between 3 and 24 px does not count. This is what keeps the white under the
chin in c371_015, figure above and below, untouched. The normal is computed per point in float64 with a fixed
summation order, bit-identical between research and Kotlin.

### `RING_GAP_CLOSE` = 6 · `RING_BREAK_PAD` = 12
Continuity: a radius-6 closing of the outline points fills short gaps; the remaining outline-less boundary points
are dilated by 12 px and outline points inside that also count as outline-less.

### `RING_FAINT_GRAY` = 225 · `RING_FAINT_INK_PAD` = 2 · `RING_FAINT_WIN` = 21 · `RING_FAINT_MIN` = 7
Blank paper: a faint stroke is grey below 225, not deep ink, and more than 2 px from deep ink. Where a 21×21
window (border replicated) holds 7 or more faint pixels nothing is claimed; the right edge of demo01's shoulder
plate has only faint strokes and this is the rule that stops it. The background-side rule (the nearest figure edge
must be an outlined one, an equality test between two exact Euclidean distance maps) has no parameter.

### `RING_OPEN` = 3 · `RING_SEED_CONN` = 1 (`NIGHTREAD_RING_SEEDCONN`; Kotlin `seedConnected`)
Finish: a radius-3 opening of the black (seed ∪ claim) drops thin fingers, and after the opening only claims
4-connected to a seed through claimed pixels are kept. The second part was added at integration time: over 47
pages and five tiers it removes 14 distinct small isolated black specks (standard 5 specks, 1,065 px; more 7
specks, 1,201 px) and leaves the guard boxes unchanged. 0 = the research prototype zhe.py.

---

## "More" background-object rule (`nightread_obj.py`)

Decided by the user on 2026-10-03 (rules version 3): background blackening in "More" no longer asks *is it white*, it asks
*is there an object*. Active only in "More" (`NIGHTREAD_MORE=1` on the research side; Kotlin `MoreRuleParams.enabled`);
"Standard" is unchanged pixel for pixel. Two mechanisms: **V** vetoes A2 whites that sit between objects; **L** blackens
light background (white or light tone alike) that holds no object. The thresholds were tuned by the prototype against 385
localized regions (`research/out/more_v3`); rules and numbers are in docs/DECISIONS.md, *「更多」背景物件規則*; Kotlin
`ObjectRuleParams` (`NightReadParams.obj`), `BgObjects`. Every brightness measurement runs on an integer Gaussian (Q16), so the
research side and Kotlin agree bit for bit.

### `OBJ_ON` = 1 · `OBJ_VETO` = 1 · `OBJ_LT` = 1 (`NIGHTREAD_OBJ` / `_VETO` / `_LT`; Kotlin `enabled` / `veto` / `lightFill`)
Master switch and one switch per mechanism (for ablation). `NIGHTREAD_OBJ=0` gives rules-version-2 "More", pixel for pixel.

### `VETO_EPM` = 25 · `VETO_RC` = 5 · `VETO_MIN_AREA` = 300 · `VETO_EV_DIL` = 2 (Kotlin also `vetoBackPad` = 2)
V: each connected block that "More" paints black beyond the standard-only stickers (≥ 300 px) closes its paper white
(≥ 235) across thin lines (ellipse radius 5) into a super-region; if the veto evidence (thin dark lines without the line
kernel, or σ2 Canny) within 2 px of the super-region exceeds 25 ‰, the block goes back to its standard-only state (inside a
square of stroke radius + 2 around it). The white itself shows no object (A2 only picks clean white); the objects are on its
rim: wall-panel lines, table edges, floor-tile lines. Blocks with objects bottom out at 26.7 ‰ (c371_017 white table),
object-free ones top out at 22.7 ‰ (behind the boy on c362_009) — a 4 ‰ margin. The prototype's 15 ‰ also vetoed five
object-free whites and two caption boxes. Blocks whose (hole-filled) area contains a text-region centre are never vetoed
(caption and narration boxes).

### `DARK_G` = 63
"Already black" = pixels already painted `BG` ∪ source gray ≤ 63 (the top of scene-curve ≤ 40 at the default levels). It does
not read the output values, which carry the floating-point scene curve and ink glow and move with the brightness settings.
Used by V's "painted beyond standard" and by L's candidates, context, islands and light edge.

### `BH_TH` = 20 · `LINE_R` = 12 · `BRIGHT_TH` = 12 · `BRIGHT_LR` = 36/5 · `DOT` = 10 (bright marks 8) · `EV_MIN_AREA` = 15
Object evidence. Thin dark lines: σ2 blackhat (ellipse radius 5) > 20 and the mean along one of 8 directional 17 px line
kernels > 12 — a line stays dark along its length, halftone dots only here and there. Bright marks (sparkles, white
highlight lines): σ1.5 tophat > 12 with an 11 px line-kernel mean > 7.2. Components whose bounding box is shorter than 10 px
(8 for bright marks) are halftone and do not count; evidence components under 15 px do not count either.

### `CANNY_LO` = 15 · `CANNY_HI` = 40 · `CANNY4_LO` = 10 · `CANNY4_HI` = 25
σ2 Canny feeds the veto evidence; σ4 Canny (tone boundaries: the edges of clouds and light/shadow patches, with coarse
halftone already smoothed away at σ4) feeds the object evidence.

### `T` = 150 · `AMIN` = 0.002
L's candidates: connected components (after an opening of radius 2) with σ5 brightness ≥ 150 that are not accounted for
(characters, bubbles dilated 6, text dilated 3, frame lines dilated 2) and not yet black, at least 0.2% of the page.

### `LINE` = 20 · `CAN4` = 12 · `FIT` = 9 · `TONE` = 215 · `TONE_GRAD` = 0.8 · `CHROMA` = 6 · `THICK` = 24 · `MARKS` = 4 · `MARKS_DEN` = 4
The whole region must pass every test: thin lines + bright marks ≤ 20 ‰ and σ4 Canny ≤ 12 ‰ inside it; residual of σ2.5
brightness against a quadratic surface ≤ 9 gray levels; tone edges (median 7 → σ3 → Sobel, gradient > 0.8 / page scale)
≤ 215 ‰; mean chroma ≤ 6 (colour pages untouched); largest inscribed radius ≥ 24 px (a strip pinched between two lines is
usually a table edge or wall panel); fewer than 4 isolated bright marks, or fewer than 4 per 100,000 px (sparkles count as
objects). The c362_006 bottom cell #6, which the user decided stays grey, is held by the residual: 10.6, a margin of about 1.5.

### `RLOC` = 24 · `CORE_MIN` = 0.0008 · `SEAL` = 4 · `LONG_LEN` = 80 · `LONG_R` = 40
A passing region is painted from its core only: more than 24 px (5×5 chamfer) from any evidence and not within 40 px of a
long line (bounding box ≥ 80 px); only blocks (after sealing evidence gaps with a closing of radius 4) whose core covers
≥ 0.08% of the page are painted. The core grows out at most 30 px, then back to the line edges by 7 px without crossing
evidence, so narrow pockets enclosed by lines (under a table, between wall panels) stay out of reach.

### `CTX_EPM` = 25 · `CTX_WHITE` = 230
A white block (σ2.5 median brightness ≥ 230) together with the light area it closes into across thin lines forms a
super-region; if its evidence exceeds 25 ‰ the block is not painted (white wedged between objects).

### `ISLAND_MAX` = 0.015 · `ISLAND_TOUCH` = 12 · `ISLAND_TOUCH_MIN` = 20 · `HUG_MAX` = 0.6
For blocks under 1.5% of the page: fewer than 20 painted-black pixels (originally light, outside bubbles) within 12 px makes
it a black hole in a grey sea, not painted; more than 60% of its rim hugging the character mask (dilated 4) is not painted
either (hair and clothing the mask missed tend to sit there).

### `OBJ_PF` = 1 · `PF_TOUCH` = 5 · `PF_BRIDGE` = 2 · `PF_REACH` = 64 · `PF_HALO` = 10 (`NIGHTREAD_OBJ_PF`; Kotlin `personFaint` / `pfTouch` / `pfBridge` / `pfReach` / `pfHalo`)
No paint around faint lines next to a character (2026-10-04 review). Hands and pencils the character model misses are often
drawn in faint dotted lines: the dots are disconnected, so they fail the 17 px line kernel, and the short ones are dropped by
the screentone-dot gate. They are not object evidence, so the light-background fill used to paint over them (the raised hand in
the c371_005 top cell, the hand reaching behind the bubble on c371_003). Faint ink = σ2 blackhat > 20 ∪ σ2 Canny (each without
screentone dots, minus the explained mask dilated 3 and the 6 px page margin). The faint ink is dilated by an ellipse of 2 px to
join the dots; starting from faint ink within 5 px of the character (trimmed mask ∪ raw output) it grows geodesically along that
for 64 px (3×3 dilations, intersected every 4), and what it reaches, dilated by an ellipse of 10 px, is a ring that is never
painted: the light-background fill drops it after its two growth steps and before the context and island checks. The effect-line
painting is not affected. Cost: a rounded grey patch next to lines that touch a character (hair strands, clothing edges, table
edges).

### `OBJ_TXTREG` = 1 (`NIGHTREAD_OBJ_TXTREG`; Kotlin `textNeedsRegion`)
"Brighten text strokes whose surroundings are mostly painted" only applies to text components that touch a text box (the
bounding box of a text region after DBNet line grouping; on translated pages the product also merges the translation boxes).
Components that DBNet only marked in its text mask, without a box, are not treated as text and keep their pixels: the tree clumps
on c371_013, the star mark on c371_014 and the sweat-drop marks used to be inverted to white. Cost: handwritten text without a box
(the 「44…」 on c362_001) is no longer brightened either; it stays grey like an object.

### Rules version 4: four additions to "More" (2026-10-05, the user's decisions q1–q4)
Only "More" changes; the standard tier is pixel-identical to version 3. Each addition has its own switch, all four off =
version 3 pixel for pixel. Not done, by the user's decision: extending screentone gradients to their dark end (q5) and the
stippled concentration lines on c362_016 (q6); the structural veto is closed (q7). After version 4 is delivered, "More" is
frozen except for red-line fixes (see DECISIONS, *「更多」規則版本 4*).

### `OBJ_SEAM` = 1 · `SEAM_R` = 3 · `SEAM_SPARK` = 4 · `SEAM_G` = 200 (`NIGHTREAD_OBJ_SEAM`; Kotlin `seam` / `seamR` / `seamSpark` / `seamG`)
q1, grey dashed seams in painted areas become black. Where two light tones meet, σ4 Canny marks the boundary as a tone edge and
the fill stops on both sides, leaving a grey dashed line inside the black. After the island check, the fill is closed with an
ellipse of radius 3; the pixels the closing adds (not explained, not already dark, not in the faint-line ring) are labelled
8-connected, and a component is painted only if none of its pixels is thin dark ink, a bright mark, a valley (σ2 blackhat > 20),
darker than 200 in the page, or within 4 px of a sparkle.

### `OBJ_TXTSTROKE` = 1 · `TS_INK` = 128 · `TS_MIN` = 5 · `TS_G` = 30 · `TS_ROUGH` = 12 · `TS_BANDCLEAN` = 1 (`NIGHTREAD_OBJ_TXTSTROKE`; Kotlin `textStroke` / `tsInk` / `tsMin` / `tsG` / `tsRough` / `tsBandClean`)
q2, bright handwritten text without a text box. A text component whose surroundings are mostly painted but which touches no
text box is brightened anyway when it looks like thick ink strokes: its ink (page grey < 128, at least 5 px) is mostly very dark
(more than half ≤ 30) and its outline is smooth and not thin (ink padded with 2 px of zeros, 3×3 open then 3×3 close; changed
pixels × 100 ≤ boundary pixels × 12). Tree clumps (texture, ragged outline), stars and sweat drops (thin, grey) do not qualify
and keep their pixels. The highlight band that only these newly brightened strokes cause is painted back to BG; the band next to
text that has a box stays as in version 3 (integration fix b). The「…」on c362_001 sits only 1–8 percentage points above the
50% very-dark share — known and accepted.

### `OBJ_PFSHAPE` = 1 · `PF_CLOSE` = 10 · `PF_MARGIN` = 3 · `PF_HOLE` = 2500 · `PF_LINE_PCT` = 104 · `PF_MARK` = 3 · `PF_STUB` = 16 · `PF_PAIR` = 20 (`NIGHTREAD_OBJ_PFSHAPE`; Kotlin `pfShape` / `pfClose` / `pfMargin` / `pfHole` / `pfLinePct` / `pfMark` / `pfStub` / `pfPair`)
q3, the faint-line ring next to a character follows the line. Each 8-connected component of the grown faint ink is closed with an
ellipse of radius 10 inside its bounding box padded by 11; if the closed area × 100 ≤ the area × 104 it is a single line and keeps
only a 3 px margin, otherwise (hands, screentone, several strokes bunched together) it keeps the version-3 ring of 10 px. Blocks
of the complement of (faint ink closed with radius 10 ∪ explained mask), 8-connected, that touch the closed ink and are small
(area × 1920² ≤ 2500 × clamp(page height, 960, 3840)²) or do not touch the explained mask are also kept inside the version-3
ring (palms, the gaps between fingers). Faint small marks — thin dark ink before the line kernel or σ2 Canny, before the dot
filter, minus X⊕3, bounding-box long side < 10 — that touch the version-3 ring keep a 3 px margin too, so emotion marks and short
strokes stay grey (integration fix a). Pre-delivery review fix c (the forearm under the waving girl's cuff on c362_001): a
faint-ink component whose bounding-box long side is under 16 px (a hair tip, a short stub sticking out of a character — too short
to tell whether it is a line) keeps the version-3 ring of 10 px, and inside one component's version-3 ring, pixels within 20 px
(ellipse dilation) of another faint-ink component are not painted either (the space between two faint lines). The result is
always inside the version-3 ring.

### `OBJ_INPAINT` = 1 · `ISLAND_INP_D` = 6 · `ISLAND_INP_PCT` = 30 (`NIGHTREAD_OBJ_INPAINT`; Kotlin `inpaintIslands` / `islandInpD` / `islandInpPct`)
q4, translated pages: a small painted block (under 1.5% of the page) with at least 30% of its pixels within 6 px of the inpaint
mask is not painted. Those blocks are clean white that only appeared after the Japanese text was inpainted (the black wedge
between the bubble and the translation on c362_014). The mask is the translation material `.yakuyomi/<page>.mask.png`, same
size as the page, passed as `NightReadInput.inpaintMask` (research: `run_page(..., inpaint=)`); without it (Japanese pages) the
rule does nothing.

### Effect lines (A) and sparkles (C) (`nightread_fx.py`, `nightread_obj.py`; Kotlin `EffectLines`, `ObjectRuleParams.fx` = `EffectLineParams`)
The user's ruling 2 of 2026-10-03: concentration lines / speed lines / "井" cross-hatching are not objects (black between the
lines, the lines stay light), sparkles / star dots are not objects (the region is painted, sparkles stay light grey), magic
swirls / wind / splashes are objects. This round only accepts radial concentration lines whose whole field is clean;
cross-hatching and parallel speed lines still count as objects (see DECISIONS, *「更多」效果線與閃光*). Angle thresholds are
written as cosine literals on both sides; no trigonometry runs at decision time.

### `OBJ_FXA` = 1 · `OBJ_FXC` = 1 (`NIGHTREAD_OBJ_FXA` / `_FXC`; Kotlin `fxLines` / `fxSparks`)
One switch each for A and C, effective only while the background-object rule is on. Both off = rules version 3 before the
effect lines, pixel for pixel.

### Finding lines: `SEG_SD` = 1.2 · `SEG_MIN` = 8 · join 6° · `PERP` = 2.5 · `GAP` = 20 · `LMIN` = 30
Thin dark lines (minus accounted-for ⊕ 3) are thinned to a skeleton (Zhang–Suen); junctions (crossing number ≥ 3) are removed
with a 3×3 dilation and the rest split into 8-connected branches. A branch of at least 8 points whose RMS distance to its
principal axis is ≤ 1.2 px is straight; straight branches within 6° of each other, ≤ 2.5 px apart and with an along-line gap
≤ 20 px are chained into one line, and only lines ≥ 30 px (× page scale) count.

### Families: `VP_TOP` = 120 · `NFAM` = 4 · converge 3° · `NMIN` = 8 · spread 30°
Pairwise intersections of the 120 longest lines are candidate vanishing points; the one with the largest total length of
lines whose direction is within 3° of (midpoint → point) wins, its members are removed, and the search repeats, up to 4
families. An effect-line family needs at least 8 members and an angular spread of ≥ 30° around the point (parallel families
are rejected this round: wall-panel lines, railings and door panels are parallel too).

### `TAPER` = 1.3 · `TAPER_FRAC` = 0.45 · `FREE_MIN` = 2 · `FREE_FRAC` = 0.1 · free ends 4–16 px, gray < 215
Thick outside, thin inside: sampling along each member and summing (250 − gray) over ±4 px across it, the median ratio of the
outer 40% (far from the point) to the inner 40% is ≥ 1.3, with ≥ 45% of members above 1.3. Free ends: 4–16 px beyond each
end along the line, offset ±1 px, σ1 gray stays ≥ 215 (ink within 2 px of a small mark does not count) — the line fades into
paper; at least 2 such ends and ≥ 10% of (free + blocked). The taper is the least stable measure: c371_008 is 1.36 at
original size, 1.28 at 1.1× and 1.21 after a σ1.5 blur.

### Effect ink: `TERR` = 48 · direction 10° · `COH` = 0.5 · `SI` = 4 · `VP_NEAR` = 24 · `MARK` = 16 · `LINE_R` = 3
A family's territory = its member lines (1 px) dilated by an ellipse of 48 px (× page scale). Thin dark ink in the territory
(small marks excluded) whose structure-tensor line direction (blackhat → σ1 → Sobel → products σ4, all integer Q16) is within
10° of the vanishing point, with coherence ≥ 0.5 and ≥ 24 px from the point, plus ink within 3 px of a member line, is effect
ink; ink specks in the territory whose bounding box is ≤ 16 px (× page scale) — dot patterns, short ticks — count too.

### Veto exception: `FX_EXCL` = 4 · `FX_NMEM` = 4
An A2 white block that would be vetoed is kept when its super-region evidence minus effect ink dilated by 4 px is ≤ 25 ‰ and
the super-region dilated by 2 px touches ≥ 4 member lines (c362_008 behind the duke, 43.0 ‰ before; c371_008 A1, 127.6 ‰).

### Effect-line regions: `FX_AGREE` = 0.85 · `FX_RESID` = 25 · `FX_EXCL_T` = 14
A region that failed the whole-region test, with chroma ≤ 6 and not a thin strip: ≥ 85% of its ink is effect ink, non-effect
ink ≤ 25 ‰, and its hole-filled area holds at least 4 member lines; after removing effect ink dilated by 14 px, σ4 Canny
≤ 12 ‰ and fit residual ≤ 9. If any region (with ≥ 4 member lines) in the same field — a connected piece of territory minus
already-black and frame lines ⊕ 2 — fails, no region of that field is painted (the top cell of c362_011 stays grey because
its cross-hatched part fails).

### Painting: `FX_GROW` = 120 · `FX_HALO` = 8 · `FX_ER_MIN` = 30 · `FX_MIN_PART` = 400 · `FX_LINE_V` = 170
From the (hole-filled) effect-line regions, grow up to 120 px through "effect ink dilated by 14, or light area" (into the
dense line ends, so no seam is left along the brightness threshold), avoiding accounted-for pixels, already-black pixels and
residual evidence (non-effect lines and tone edges, components ≥ 30 px) dilated by 8 px; drop pieces under 400 px. The rest
is painted from the source ink: BG + ((255 − gray)/255)^1.4 × (170 − BG), floored (paper → BG, solid line → 170: black between
the lines, lines light). Afterwards "already black" gains the pixels painted to ≤ 40.

### Sparkles: `FXC_ISO` = 4 · `FXC_MIN` = 5 · `FXC_MAX` = 60 · `FXC_DIL` = 3 · `SPARK_V` = 170 · `LONG_STRAIGHT` = 2
A bright-mark component whose 4 px elliptical dilation touches no dark (< 128) and no thin dark line, with a bounding box of
5–60 px (× page scale), is a sparkle (halftone gaps, white outlines and clothing highlights all have ink next to them). Sparkles
are not evidence or thin lines, σ4 Canny within 3 px of them is not a tone edge, and the "many isolated bright marks keep the
whole region grey" test is off. Inside painted areas each sparkle (dilated 1) is drawn as max(current, BG + (gray/255)^1.4 ×
(170 − BG)), floored. Also, a long line (bounding box ≥ 80) that is ruler-straight (square root of the minor eigenvalue of its
pixel coordinates ≤ 2 px) gets no long-line zone: that zone (40 px) is wider than the core growth (30 + 7 px) and leaves a
square grey notch beside straight lines.

---

## Text

Layer priority is **text > bubble > figure > background**.

### `TEXT_PAD` = 2
Dilation of the stroke mask when bright text is drawn inside a bubble.

### `TEXT_GAMMA` = 1.4 · `TEXT_KNEE` = 0.35
The gamma from ink density to brightness, and the knee below which low ink density is crushed to black. The
crush is there to kill the grey residue around the edge of a glyph.

### `TEXT_TOP_PAD` = 3
Width of the snug band that keeps text on top. Where the bubble mask has been subtracted by the figure, text
in that area loses its bright-in-bubble treatment: the measured contrast is −6, meaning the text is darker
than what sits behind it. The fix is not to let the whole bubble win — that breaks 17 guard boxes — but to
leave only the text strokes and their snug band unrestored.

### `TEXT_BACKING_R` = 5
Radius of the snug dark backing where text sits on a figure. The figure wins, but the text still has to be
legible.

---

## Floating-head harmonization

A blank head floating in a dark region comes out half black, half white, and it is glaring.

### `HARMONIZE_ZONE_CELL` = 16 · `HARMONIZE_ZONE_DARK` = 0.45
Downsampling scale for the dark-region map, and the dark fraction at which a coarse cell counts as dark.

### `HARMONIZE_IN_ZONE` = 0.6
A bright island is only processed if 60% of it falls inside a dark region.

### `HARMONIZE_AREA_MAX` = 0.004
Page-area ceiling for a bright island. A main character's face is larger than this, so it is excluded.

### `HARMONIZE_COLLAR_INK` = 0.3
Ceiling on the thin-ink density in the ring around the island. A beard or dense hair gives that ring a high
ink density and is excluded; a blank head has only a single contour line and passes.
