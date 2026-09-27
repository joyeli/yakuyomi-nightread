# Parameters

English ｜ [中文](PARAMETERS_zh.md)

Every knob in the pipeline: what it controls, what it is set to, and what happens if you push it either
way. They all live in a single block at the top of `research/nightread.py` (the any-angle separator and
bleed-panel filter keep theirs at the top of their own modules, `research/nightread_sep.py` and
`research/nightread_bleed.py`) — there are no magic numbers scattered through the code.

One environment variable is required: `NIGHTREAD_CHARMASK`, pointing at the character-mask directory
produced by `charmask.py`. If it is missing the run fails outright rather than silently degrading. The three
product fill levels are also selected through the environment (`NIGHTREAD_STICKER_MODE`,
`NIGHTREAD_STICKER_ROUGH`, `NIGHTREAD_STICKER_MINFRAC`, `NIGHTREAD_PB`, `NIGHTREAD_HM` — see *Fill tiers*), the
any-angle separator and the bleed-panel filter each have a switch (`NIGHTREAD_SEP`, `NIGHTREAD_BLEED`, on by
default), and a few research toggles read it too (`NIGHTREAD_EDGE_INK`, `NIGHTREAD_REQUIRE_CLEAN`, `NIGHTREAD_TEXT_*`).
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

### `SAFE_BUBBLE_RATIO` = 2.5
A bubble core may be no larger than 2.5× the square of the text box's long edge. A real bubble is packed with
its text; a component grown from text sitting on a cheek or a hand is a whole sheet of skin-white.

**The denominator is the long edge squared, not the text box's area**: a single column of vertical text is
one column wide, so an area denominator blows up spuriously, the whole bubble is rejected, and it stays
white. The long edge squared equals the area for a square box and only loosens the test for long thin ones.

### `BUBBLE_CLEAN_WINS` = 0.005 · `BUBBLE_CLEAN_TEXT_MAX` = 0.8
The two tests for "clean bubble, fill it whole". A real bubble is an empty container: after hole-filling, the
non-text ink inside it is 0–0.3%. A face mistaken for a bubble has features and shadows, and runs above 1%.
Anything that passes is filled whole, with nothing subtracted for the character mask.

The second test is insurance: anything more than 80% text is not a bubble. The white hair and white hands
that get misclassified are almost entirely made of the text strokes themselves.

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
guard in the tier ablation, so no product level uses it.

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

How much white should go black is a product setting with three levels (user definition, 2026-09-27). The full
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
and the bright fraction L1 38.8%, L2 38.3%, L3 38.1%, default 36.3%.

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
Switches for the pseudo-bubble and floating-head layers. All three product levels turn both off:
pseudo-bubbles grow from the text into the background and cost 2 guard boxes; harmonization has no effect on
the guard but digs black holes into panel backgrounds. The default keeps both on.

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

### `TH_STEP` = 0.5 · `PEAK_NMS_T` = 3 · `PEAK_NMS_R` = 6 · `PEAK_MAX` = 400 · `PEAK_VOTE_FRAC` = 0.8
Hough. 0.5° per angle bin, 1 px per ρ bin, summed over 3 ρ bins (a 3 px wide white-adjacent band); a peak is a
local maximum over θ ±1.5° and ρ ±6 px with at least 0.8 × the minimum line length in votes; the 400 strongest
are kept. θ wraps around (0° and 179.5° are neighbours).

### `WALK_WIN0` = 4 · `WALK_WIN` = 2 · `GAP` = 8 · `FILL_MIN` = 0.85
Walking along the line. The first walk and the PCA refinement use a ±4 px normal window (absorbing the Hough
angle error); the final walk uses ±2 px; gaps up to 8 px are allowed. Initial runs need a hit rate of 0.6 and a
final segment 0.85 (ruled lines ≈1.0). Each segment is refined twice on its own,
so collinear pieces on the same ρ are not tilted by the longest one.

### `DEDUPE_ANG` = 2.0 · `DEDUPE_OFF` = 6.0 · `GROUP_ANG` = 1.0 · `GROUP_OFF` = 6.0
De-duplication and collinear grouping. Two overlapping segments within 2° and 6 px are the same line found
twice; segments within 1° and 6 px join one interrupted frame line (a list of intervals), so a frame cut by a
bubble, a sound effect or a bleeding figure stays one line.

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
Known gap: under upscaling (≥ 1.0675× in the c371_001 sweeps, mostly ≥ 1.09×) the two short pieces' own PCA angles
can differ by 1–1.8° > `GROUP_ANG`, they never form a group and that margin stays grey (54 of 483 sweep cells;
every cell ≤ 1.00× passes).

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
