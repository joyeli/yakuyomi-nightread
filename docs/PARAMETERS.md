# Parameters

English ｜ [中文](PARAMETERS_zh.md)

Every knob in the pipeline: what it controls, what it is set to, and what happens if you push it either
way. They all live in a single block at the top of `research/nightread.py` — there are no magic numbers
scattered through the code.

There is exactly one environment variable: `NIGHTREAD_CHARMASK`, pointing at the character-mask directory
produced by `charmask.py`. It is a **required input** — if it is missing the run fails outright rather than
silently degrading. Everything else is edited in the file, so a run reproduces.

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
