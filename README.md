# yakuyomi-nightread

Night-reading mode for manga — the **page itself** gets dark, not just the UI.

Every reader on the market does one of two things at night: paint the chrome black and leave the page
glaring white, or invert the whole image and wreck the art. Neither touches the page content. This project
does: it **rebuilds** the page — speech bubbles become dark-with-light-text, empty backgrounds are filled
black with the figures lifted out by a light outline, and everything else is tone-mapped so black lines stay
black. It is the sibling of [yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine) (the translation
engine) and consumes only its text-detection output.

**Status: desktop research.** Nothing here ships yet. The hard part — never painting over a face, a hand or
a white sleeve — is measured, not eyeballed (see below), and is not solved without a character-segmentation
model. Decisions and the current numbers live in [`docs/DECISIONS.md`](docs/DECISIONS.md).

## Layout

| Path | What |
|---|---|
| `research/nightread.py` | The whole pipeline, one page at a time. Every tunable and every design constraint is documented in the file header. |
| `research/nightread_batch.py` | Run the 11 fixture pages, print the light-area table. |
| `research/nightread_guard.py` + `nightread_guard.json` | **The red-line test.** 704 hand-annotated foreground boxes (faces, hands, skin, white clothes, white hair). Any output that paints >15 % of a box's originally-white pixels dark is a violation. |
| `research/nightread_curves_cmp.py` | Side-by-side sheets for the tone-curve A/B. |
| `fixtures/pages/` | The 11 test pages. `fixtures/baseline/` holds reference outputs for regression. |
| `nightread/` | Future Kotlin library (Gradle, Android library, **no `android.graphics` dependency** so JVM tests can compare against Python fixtures bit-for-bit). Only the primitive API contract (`Cv.kt`) exists so far. |

## Running

The research scripts need the engine's parity tools for text detection (DBNet checkpoint loader and the
m-i-t grouping spec). Point `YAKU_ENGINE_CLONE` at a checkout of yakuyomi-engine (default
`/mnt/d/Gits/Yakuyomi`); the same Python environment as its `parity/` works here.

```
cd research
python3 nightread_batch.py                 # all 11 pages → out/nightread/
python3 nightread_guard.py out/nightread   # red-line violations per box
```

Experiment switches (env vars; defaults are the chosen values): `NIGHTREAD_CURVE`, `NIGHTREAD_AURA`,
and one on/off flag per mechanism (`NIGHTREAD_HUG`, `_PSEUDO`, `_HARMONIZE`, `_GUTTER`, `_BUBBLE`,
`_STICKER`) for ablation. See the constants block at the top of `nightread.py`.

## License

GPL-3.0, same as the rest of Yakuyomi. The research pipeline uses the m-i-t DBNet detector (GPL-3.0)
through yakuyomi-engine; the rebuild algorithm itself is original.
