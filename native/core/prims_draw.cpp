// 夜讀 C++ 核心：第 3b 階的繪圖（cv2.line／cv2.fillPoly，LINE_8、shift 0）。移植自
// nightread/src/main/kotlin/li/joye/yakuyomi/nightread/Cv.kt @ d48c5d3：clipLine、hline、lineIter、line2、fillConvexPoly、fillCircle、
// line（ThickLine）、fillPoly（CollectPolyEdges＋FillEdgeCollection）。Kotlin 版本身逐行移植 OpenCV 4.11 drawing.cpp。
// Long 與 Int 的混算、環繞、位移量（Int 取 &31、Long 取 &63）、Long→Int 的截斷、Double→Long 的飽和全照 Kotlin；Double 乘加不合成 FMA。
// 寫遮罩的索引照 Kotlin 的 Int 算法算（y·w＋x 會環繞），越界丟 std::out_of_range（Kotlin 的 ArrayIndexOutOfBounds）。
#include <algorithm>
#include <stdexcept>
#include <vector>

#include "jmath.h"
#include "prims.h"

namespace nr {
namespace prims {

namespace {

constexpr int32_t XY_SHIFT = 16;
constexpr int64_t XY_ONE = int64_t(1) << XY_SHIFT;

/** m.data[i] = true，i 是 Kotlin 的 Int 索引（越界丟）。 */
inline void setPx(Mask& m, int32_t i) {
    if (i < 0 || static_cast<size_t>(i) >= m.data.size()) throw std::out_of_range("draw: index out of bounds");
    m.data[static_cast<size_t>(i)] = 1;
}

/** cv::clipLine(Size2l, Point2l&, Point2l&)：把線段裁進 [0,w)×[0,h)（p＝[x1, y1, x2, y2]，就地改）；回傳是否有落在影像內的部分。 */
bool clipLine(int64_t w, int64_t h, int64_t p[4]) {
    if (w <= 0 || h <= 0) return false;
    const int64_t right = w - 1;
    const int64_t bottom = h - 1;
    int64_t x1 = p[0], y1 = p[1], x2 = p[2], y2 = p[3];
    auto code = [&](int64_t x, int64_t y) {
        return (x < 0 ? 1 : 0) + (x > right ? 2 : 0) + (y < 0 ? 4 : 0) + (y > bottom ? 8 : 0);
    };
    auto step = [](int64_t a, int64_t b, int64_t c, int64_t d) {   // ((a − b).toDouble() * (c − d).toDouble() / … ).toLong() 的前半
        return jm::l2d(jm::wsub(a, b)) * jm::l2d(jm::wsub(c, d));
    };
    int c1 = code(x1, y1);
    int c2 = code(x2, y2);
    if ((c1 & c2) == 0 && (c1 | c2) != 0) {
        int64_t a;
        if ((c1 & 12) != 0) {
            a = c1 < 8 ? 0 : bottom;
            x1 = jm::wadd(x1, jm::d2l(step(a, y1, x2, x1) / jm::l2d(jm::wsub(y2, y1))));
            y1 = a;
            c1 = (x1 < 0 ? 1 : 0) + (x1 > right ? 2 : 0);
        }
        if ((c2 & 12) != 0) {
            a = c2 < 8 ? 0 : bottom;
            x2 = jm::wadd(x2, jm::d2l(step(a, y2, x2, x1) / jm::l2d(jm::wsub(y2, y1))));
            y2 = a;
            c2 = (x2 < 0 ? 1 : 0) + (x2 > right ? 2 : 0);
        }
        if ((c1 & c2) == 0 && (c1 | c2) != 0) {
            if (c1 != 0) {
                a = c1 == 1 ? 0 : right;
                y1 = jm::wadd(y1, jm::d2l(step(a, x1, y2, y1) / jm::l2d(jm::wsub(x2, x1))));
                x1 = a;
                c1 = 0;
            }
            if (c2 != 0) {
                a = c2 == 1 ? 0 : right;
                y2 = jm::wadd(y2, jm::d2l(step(a, x2, y2, y1) / jm::l2d(jm::wsub(x2, x1))));
                x2 = a;
                c2 = 0;
            }
        }
    }
    p[0] = x1; p[1] = y1; p[2] = x2; p[3] = y2;
    return (c1 | c2) == 0;
}

/** for (x in xl..xr) m.data[y·w + x] = true（Int 索引；Kotlin 的 IntRange 走到 xr 為止，xr＝INT32_MAX 也停得下來）。 */
void hline(Mask& m, int32_t y, int32_t xl, int32_t xr) {
    const int32_t base = y * m.w;
    for (int64_t x = xl; x <= xr; x++) setPx(m, base + static_cast<int32_t>(x));
}

/** cv2 Line()（thickness 1、LINE_8）：LineIterator(img, p1, p2, 8, leftToRight=true)，端點在外先 clipLine。 */
void lineIter(Mask& m, int32_t ax, int32_t ay, int32_t bx, int32_t by) {
    const int32_t w = m.w, h = m.h;
    int32_t x1 = ax, y1 = ay, x2 = bx, y2 = by;
    if (x1 < 0 || x1 >= w || x2 < 0 || x2 >= w || y1 < 0 || y1 >= h || y2 < 0 || y2 >= h) {
        int64_t p[4] = {x1, y1, x2, y2};
        if (!clipLine(w, h, p)) return;
        x1 = static_cast<int32_t>(p[0]); y1 = static_cast<int32_t>(p[1]); x2 = static_cast<int32_t>(p[2]); y2 = static_cast<int32_t>(p[3]);
    }
    int32_t deltaX = 1;
    int32_t deltaY = 1;
    int32_t dx = x2 - x1;
    int32_t dy = y2 - y1;
    if (dx < 0) {   // leftToRight：從左端開始
        dx = -dx; dy = -dy;
        x1 = x2; y1 = y2;
    }
    if (dy < 0) { dy = -dy; deltaY = -1; }
    const bool vert = dy > dx;
    if (vert) {
        const int32_t t = dx; dx = dy; dy = t;
        const int32_t td = deltaX; deltaX = deltaY; deltaY = td;
    }
    int32_t err = dx - (dy + dy);
    const int32_t plusDelta = dx + dx;
    const int32_t minusDelta = -(dy + dy);
    int32_t minusShift = deltaX;
    int32_t plusShift = 0;
    int32_t minusStep = 0;
    int32_t plusStep = deltaY;
    const int32_t count = dx + 1;
    if (vert) {
        int32_t t = plusStep; plusStep = plusShift; plusShift = t;
        t = minusStep; minusStep = minusShift; minusShift = t;
    }
    int32_t px = x1;
    int32_t py = y1;
    for (int32_t i = 0; i < count; i++) {
        setPx(m, py * w + px);
        const int32_t mask = err < 0 ? -1 : 0;
        err += minusDelta + (plusDelta & mask);
        px += minusShift + (plusShift & mask);
        py += minusStep + (plusStep & mask);
    }
}

/** cv2 Line2()：16 位定點端點的細線（FillConvexPoly 在 shift≠0 時畫邊用）。 */
void line2(Mask& m, int64_t p1x0, int64_t p1y0, int64_t p2x0, int64_t p2y0) {
    const int32_t w = m.w, h = m.h;
    int64_t p[4] = {p1x0, p1y0, p2x0, p2y0};
    if (!clipLine(jm::shl(int64_t(w), XY_SHIFT), jm::shl(int64_t(h), XY_SHIFT), p)) return;
    int64_t pt1x = p[0], pt1y = p[1], pt2x = p[2], pt2y = p[3];
    int64_t dx = jm::wsub(pt2x, pt1x);
    int64_t dy = jm::wsub(pt2y, pt1y);
    const int64_t j = dx < 0 ? -1 : 0;
    const int64_t ax = jm::wsub(dx ^ j, j);
    const int64_t i = dy < 0 ? -1 : 0;
    const int64_t ay = jm::wsub(dy ^ i, i);
    int64_t xStep, yStep;
    int32_t ecount;
    if (ax > ay) {
        dy = jm::wsub(dy ^ j, j);
        pt1x ^= pt2x & j; pt2x ^= pt1x & j; pt1x ^= pt2x & j;
        pt1y ^= pt2y & j; pt2y ^= pt1y & j; pt1y ^= pt2y & j;
        xStep = XY_ONE;
        yStep = jm::div(jm::wmul(dy, int64_t(1) << XY_SHIFT), ax | 1);
        ecount = static_cast<int32_t>(jm::shr(jm::wsub(pt2x, pt1x), XY_SHIFT));
    } else {
        dx = jm::wsub(dx ^ i, i);
        pt1x ^= pt2x & i; pt2x ^= pt1x & i; pt1x ^= pt2x & i;
        pt1y ^= pt2y & i; pt2y ^= pt1y & i; pt1y ^= pt2y & i;
        xStep = jm::div(jm::wmul(dx, int64_t(1) << XY_SHIFT), ay | 1);
        yStep = XY_ONE;
        ecount = static_cast<int32_t>(jm::shr(jm::wsub(pt2y, pt1y), XY_SHIFT));
    }
    pt1x = jm::wadd(pt1x, XY_ONE >> 1);
    pt1y = jm::wadd(pt1y, XY_ONE >> 1);
    auto put = [&](int64_t x, int64_t y) {
        if (x >= 0 && x < w && y >= 0 && y < h) m.data[static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)] = 1;
    };
    put(jm::shr(jm::wadd(pt2x, XY_ONE >> 1), XY_SHIFT), jm::shr(jm::wadd(pt2y, XY_ONE >> 1), XY_SHIFT));
    if (ax > ay) {
        pt1x = jm::shr(pt1x, XY_SHIFT);
        while (ecount >= 0) {
            put(pt1x, jm::shr(pt1y, XY_SHIFT));
            pt1x = jm::wadd(pt1x, int64_t(1));
            pt1y = jm::wadd(pt1y, yStep);
            ecount--;
        }
    } else {
        pt1y = jm::shr(pt1y, XY_SHIFT);
        while (ecount >= 0) {
            put(jm::shr(pt1x, XY_SHIFT), pt1y);
            pt1x = jm::wadd(pt1x, xStep);
            pt1y = jm::wadd(pt1y, int64_t(1));
            ecount--;
        }
    }
}

/** cv2 FillConvexPoly()（LINE_8）。vx／vy 是 shift 位定點座標（npts ≥ 1）。 */
void fillConvexPoly(Mask& m, const int64_t* vx, const int64_t* vy, int32_t npts, int32_t shift) {
    const int32_t w = m.w, h = m.h;
    const int32_t delta = jm::shl(int32_t(1), shift) >> 1;
    const int64_t delta1 = XY_ONE >> 1;
    const int64_t delta2 = XY_ONE >> 1;
    int32_t imin = 0;
    int32_t edges = npts;
    int64_t p0x = jm::shl(vx[npts - 1], XY_SHIFT - shift);
    int64_t p0y = jm::shl(vy[npts - 1], XY_SHIFT - shift);
    int64_t xmin = vx[0], xmax = vx[0];
    int64_t ymin = vy[0], ymax = vy[0];
    for (int32_t i = 0; i < npts; i++) {
        int64_t px = vx[i];
        int64_t py = vy[i];
        if (py < ymin) { ymin = py; imin = i; }
        ymax = jm::max(ymax, py);
        xmax = jm::max(xmax, px);
        xmin = jm::min(xmin, px);
        px = jm::shl(px, XY_SHIFT - shift);
        py = jm::shl(py, XY_SHIFT - shift);
        if (shift == 0) {
            lineIter(m, static_cast<int32_t>(jm::shr(p0x, XY_SHIFT)), static_cast<int32_t>(jm::shr(p0y, XY_SHIFT)),
                     static_cast<int32_t>(jm::shr(px, XY_SHIFT)), static_cast<int32_t>(jm::shr(py, XY_SHIFT)));
        } else {
            line2(m, p0x, p0y, px, py);
        }
        p0x = px;
        p0y = py;
    }
    xmin = jm::shr(jm::wadd(xmin, int64_t(delta)), shift);
    xmax = jm::shr(jm::wadd(xmax, int64_t(delta)), shift);
    ymin = jm::shr(jm::wadd(ymin, int64_t(delta)), shift);
    ymax = jm::shr(jm::wadd(ymax, int64_t(delta)), shift);
    if (npts < 3 || static_cast<int32_t>(xmax) < 0 || static_cast<int32_t>(ymax) < 0 || static_cast<int32_t>(xmin) >= w ||
        static_cast<int32_t>(ymin) >= h)
        return;
    ymax = jm::min(ymax, int64_t(h - 1));
    int32_t eIdx[2] = {imin, imin};
    const int32_t eDi[2] = {1, npts - 1};
    int64_t eX[2] = {-XY_ONE, -XY_ONE};
    int64_t eDx[2] = {0, 0};
    int32_t y = static_cast<int32_t>(ymin);
    int32_t eYe[2] = {y, y};
    do {
        for (int i = 0; i < 2; i++) {
            if (y >= eYe[i]) {
                int32_t idx0 = eIdx[i];
                const int32_t di = eDi[i];
                int32_t idx = idx0 + di;
                if (idx >= npts) idx -= npts;
                for (;;) {
                    const bool cont = edges > 0;
                    edges--;
                    if (!cont) break;
                    const int32_t ty = static_cast<int32_t>(jm::shr(jm::wadd(vy[idx], int64_t(delta)), shift));
                    if (ty > y) {
                        int64_t xs = vx[idx0];
                        int64_t xe = vx[idx];
                        if (shift != XY_SHIFT) {
                            xs = jm::shl(xs, XY_SHIFT - shift);
                            xe = jm::shl(xe, XY_SHIFT - shift);
                        }
                        eYe[i] = ty;
                        const int64_t dyy = int64_t(ty) - y;
                        eDx[i] = jm::div(jm::wadd(jm::wmul(jm::wsub(xe, xs), int64_t(2)), dyy), jm::wmul(int64_t(2), dyy));
                        eX[i] = xs;
                        eIdx[i] = idx;
                        break;
                    }
                    idx0 = idx;
                    idx += di;
                    if (idx >= npts) idx -= npts;
                }
            }
        }
        if (edges < 0) break;
        if (y >= 0) {
            int left = 0;
            int right = 1;
            if (eX[0] > eX[1]) { left = 1; right = 0; }
            int32_t xx1 = static_cast<int32_t>(jm::shr(jm::wadd(eX[left], delta1), XY_SHIFT));
            int32_t xx2 = static_cast<int32_t>(jm::shr(jm::wadd(eX[right], delta2), XY_SHIFT));
            if (xx2 >= 0 && xx1 < w) {
                if (xx1 < 0) xx1 = 0;
                if (xx2 >= w) xx2 = w - 1;
                hline(m, y, xx1, xx2);
            }
        }
        eX[0] = jm::wadd(eX[0], eDx[0]);
        eX[1] = jm::wadd(eX[1], eDx[1]);
    } while (++y <= static_cast<int32_t>(ymax));
}

/** cv2 Circle(img, center, radius, color, fill=1)：實心圓（Bresenham 式逐列水平線）。 */
void fillCircle(Mask& m, int32_t cx, int32_t cy, int32_t radius) {
    const int32_t w = m.w, h = m.h;
    int64_t err = 0;
    int64_t dx = radius;
    int64_t dy = 0;
    int64_t plus = 1;
    int64_t minus = jm::wsub(jm::shl(int64_t(radius), 1), int64_t(1));
    const bool inside = cx >= radius && cx < w - radius && cy >= radius && cy < h - radius;
    auto i32 = [](int64_t v) { return static_cast<int32_t>(v); };
    while (dx >= dy) {
        const int64_t y11 = jm::wsub(int64_t(cy), dy), y12 = jm::wadd(int64_t(cy), dy);
        const int64_t y21 = jm::wsub(int64_t(cy), dx), y22 = jm::wadd(int64_t(cy), dx);
        int64_t x11 = jm::wsub(int64_t(cx), dx), x12 = jm::wadd(int64_t(cx), dx);
        int64_t x21 = jm::wsub(int64_t(cx), dy), x22 = jm::wadd(int64_t(cx), dy);
        if (inside) {
            hline(m, i32(y11), i32(x11), i32(x12));
            hline(m, i32(y12), i32(x11), i32(x12));
            hline(m, i32(y21), i32(x21), i32(x22));
            hline(m, i32(y22), i32(x21), i32(x22));
        } else if (x11 < w && x12 >= 0 && y21 < h && y22 >= 0) {
            x11 = jm::max(x11, int64_t(0));
            x12 = jm::min(x12, int64_t(w - 1));
            if (y11 >= 0 && y11 < h) hline(m, i32(y11), i32(x11), i32(x12));
            if (y12 >= 0 && y12 < h) hline(m, i32(y12), i32(x11), i32(x12));
            if (x21 < w && x22 >= 0) {
                x21 = jm::max(x21, int64_t(0));
                x22 = jm::min(x22, int64_t(w - 1));
                if (y21 >= 0 && y21 < h) hline(m, i32(y21), i32(x21), i32(x22));
                if (y22 >= 0 && y22 < h) hline(m, i32(y22), i32(x21), i32(x22));
            }
        }
        dy = jm::wadd(dy, int64_t(1));
        err = jm::wadd(err, plus);
        plus = jm::wadd(plus, int64_t(2));
        const int64_t mask = err <= 0 ? 0 : -1;
        err = jm::wsub(err, minus & mask);
        dx = jm::wadd(dx, mask);
        minus = jm::wsub(minus, mask & 2);
    }
}

}  // namespace

void line(Mask& m, int32_t x0, int32_t y0, int32_t x1, int32_t y1, int32_t thickness) {
    int64_t p0x = jm::shl(int64_t(x0), XY_SHIFT);
    int64_t p0y = jm::shl(int64_t(y0), XY_SHIFT);
    const int64_t p1x = jm::shl(int64_t(x1), XY_SHIFT);
    const int64_t p1y = jm::shl(int64_t(y1), XY_SHIFT);
    if (thickness <= 1) {
        lineIter(m, x0, y0, x1, y1);
        return;
    }
    const double inv = 1.0 / jm::l2d(XY_ONE);
    const double dx = jm::l2d(jm::wsub(p0x, p1x)) * inv;
    const double dy = jm::l2d(jm::wsub(p1y, p0y)) * inv;
    double r = dx * dx + dy * dy;   // -ffp-contract=off：不合成 FMA（同 Kotlin 兩次捨入）
    const int32_t odd = thickness & 1;
    const int64_t th = jm::shl(int64_t(thickness), XY_SHIFT - 1);
    if (jm::abs(r) > 2.220446049250313e-16) {
        r = (jm::l2d(th) + jm::l2d(jm::wmul(int64_t(odd), XY_ONE)) * 0.5) / jm::sqrt(r);
        const int64_t dpx = jm::d2l(jm::rint(dy * r));   // cvRound：半數取偶
        const int64_t dpy = jm::d2l(jm::rint(dx * r));
        const int64_t vx[4] = {jm::wadd(p0x, dpx), jm::wsub(p0x, dpx), jm::wsub(p1x, dpx), jm::wadd(p1x, dpx)};
        const int64_t vy[4] = {jm::wadd(p0y, dpy), jm::wsub(p0y, dpy), jm::wsub(p1y, dpy), jm::wadd(p1y, dpy)};
        fillConvexPoly(m, vx, vy, 4, XY_SHIFT);
    }
    const int32_t rad = static_cast<int32_t>(jm::shr(jm::wadd(th, XY_ONE >> 1), XY_SHIFT));
    for (int i = 0; i < 2; i++) {
        const int32_t cx = static_cast<int32_t>(jm::shr(jm::wadd(p0x, XY_ONE >> 1), XY_SHIFT));
        const int32_t cy = static_cast<int32_t>(jm::shr(jm::wadd(p0y, XY_ONE >> 1), XY_SHIFT));
        fillCircle(m, cx, cy, rad);
        p0x = p1x;
        p0y = p1y;
    }
}

void fillPoly(Mask& m, const int32_t* xs, int32_t count, const int32_t* ys, int32_t ny, Budget* b) {
    // Kotlin 先讀 xs[count − 1]、ys[count − 1]（都在畫之前）
    if (count <= 0 || ny < count) throw std::out_of_range("fillPoly: xs/ys index out of bounds");
    const int32_t w = m.w, h = m.h;
    // ── CollectPolyEdges（offset 0、shift 0 ⇒ delta 0）──
    const size_t sc = static_cast<size_t>(count);
    Buf<int32_t> ey0(sc, b), ey1(sc, b);
    Buf<int64_t> ex(sc + 1, b), edx(sc + 1, b);
    int32_t total = 0;
    int64_t pt0x = jm::shl(int64_t(xs[count - 1]), XY_SHIFT);
    int64_t pt0y = ys[count - 1];
    for (int32_t i = 0; i < count; i++) {
        const int64_t pt1x = jm::shl(int64_t(xs[i]), XY_SHIFT);
        const int64_t pt1y = ys[i];
        int64_t pt0cy = pt0y;
        int64_t pt1cy = pt1y;
        int64_t t[4] = {jm::shr(jm::wadd(pt0x, XY_ONE >> 1), XY_SHIFT), pt0y, jm::shr(jm::wadd(pt1x, XY_ONE >> 1), XY_SHIFT), pt1y};
        lineIter(m, static_cast<int32_t>(t[0]), static_cast<int32_t>(t[1]), static_cast<int32_t>(t[2]), static_cast<int32_t>(t[3]));
        if (t[0] < 0 || t[0] >= w || t[2] < 0 || t[2] >= w || t[1] < 0 || t[1] >= h || t[3] < 0 || t[3] >= h) {
            clipLine(w, h, t);
            if (t[1] != t[3]) { pt0cy = t[1]; pt1cy = t[3]; }
        }
        const int64_t pt0cx = jm::shl(t[0], XY_SHIFT);
        const int64_t pt1cx = jm::shl(t[2], XY_SHIFT);
        if (pt0y != pt1y) {
            const int64_t den = jm::wsub(pt1cy, pt0cy);
            if (den == 0) throw std::domain_error("fillPoly: / by zero");   // Kotlin 的 ArithmeticException（不會發生：見 Kotlin 版）
            const int64_t dxe = jm::div(jm::wsub(pt1cx, pt0cx), den);
            if (pt0y < pt1y) {
                ey0[total] = static_cast<int32_t>(pt0y); ey1[total] = static_cast<int32_t>(pt1y);
                ex[total] = jm::wadd(pt0cx, jm::wmul(jm::wsub(pt0y, pt0cy), dxe));
            } else {
                ey0[total] = static_cast<int32_t>(pt1y); ey1[total] = static_cast<int32_t>(pt0y);
                ex[total] = jm::wadd(pt1cx, jm::wmul(jm::wsub(pt1y, pt1cy), dxe));
            }
            edx[total] = dxe;
            total++;
        }
        pt0x = pt1x;
        pt0y = pt1y;
    }
    // ── FillEdgeCollection ──
    if (total < 2) return;
    const int64_t delta = XY_ONE - 1;
    int32_t yMax = INT32_MIN;
    int32_t yMin = INT32_MAX;
    int64_t xMax = -1;
    int64_t xMin = INT64_MAX;
    for (int32_t i = 0; i < total; i++) {
        const int64_t x1 = jm::wadd(ex[i], jm::wmul(int64_t(ey1[i] - ey0[i]), edx[i]));
        yMin = jm::min(yMin, ey0[i]); yMax = jm::max(yMax, ey1[i]);
        xMin = jm::min(xMin, ex[i]); xMax = jm::max(xMax, ex[i]);
        xMin = jm::min(xMin, x1); xMax = jm::max(xMax, x1);
    }
    if (yMax < 0 || yMin >= h || xMax < 0 || xMin >= jm::shl(int64_t(w), XY_SHIFT)) return;
    // CmpEdges：y0、x、dx 遞增；Kotlin 用穩定排序（sortedWith）
    std::vector<int32_t> order(static_cast<size_t>(total));
    for (int32_t i = 0; i < total; i++) order[static_cast<size_t>(i)] = i;
    std::stable_sort(order.begin(), order.end(), [&](int32_t a, int32_t c) {
        if (ey0[a] != ey0[c]) return ey0[a] < ey0[c];
        if (ex[a] != ex[c]) return ex[a] < ex[c];
        return edx[a] < edx[c];
    });
    const size_t st = static_cast<size_t>(total);
    Buf<int32_t> sy0(st + 1, b), sy1(st + 1, b);
    Buf<int64_t> sx(st + 1, b), sdx(st + 1, b);
    for (size_t k = 0; k < st; k++) {
        const int32_t o = order[k];
        sy0[k] = ey0[o]; sy1[k] = ey1[o]; sx[k] = ex[o]; sdx[k] = edx[o];
    }
    sy0[st] = INT32_MAX;   // 哨兵（edges.push_back(tmp)）
    Buf<int32_t> next(st + 2, b);
    for (size_t k = 0; k < st + 2; k++) next[k] = -1;
    const int32_t head = total + 1;   // tmp（活動邊表的頭）
    // 索引檢查（照 Kotlin 陣列：sy0／sy1／sx／sdx 長 total+1、next 長 total+2）
    auto E = [&](int32_t i) -> size_t {
        if (i < 0 || i > total) throw std::out_of_range("fillPoly: edge index");
        return static_cast<size_t>(i);
    };
    auto N = [&](int32_t i) -> size_t {
        if (i < 0 || i > total + 1) throw std::out_of_range("fillPoly: next index");
        return static_cast<size_t>(i);
    };
    int32_t i = 0;
    int32_t e = 0;
    yMax = jm::min(yMax, h);
    int32_t y = sy0[E(e)];
    while (y < yMax) {
        int32_t draw = 0;
        const bool clipline = y < 0;
        int32_t prelast = head;
        int32_t last = next[N(head)];
        while (last >= 0 || sy0[E(e)] == y) {
            if (last >= 0 && sy1[E(last)] == y) {
                next[N(prelast)] = next[N(last)];
                last = next[N(last)];
                continue;
            }
            const int32_t keepPrelast = prelast;
            if (last >= 0 && (sy0[E(e)] > y || sx[E(last)] < sx[E(e)])) {
                prelast = last;
                last = next[N(last)];
            } else if (i < total) {
                next[N(prelast)] = e;
                next[N(e)] = last;
                prelast = e;
                e = ++i;
            } else {
                break;
            }
            if (draw != 0) {
                if (!clipline) {
                    int32_t x1, x2;
                    if (sx[E(keepPrelast)] > sx[E(prelast)]) {
                        x1 = static_cast<int32_t>(jm::shr(jm::wadd(sx[E(prelast)], delta), XY_SHIFT));
                        x2 = static_cast<int32_t>(jm::shr(sx[E(keepPrelast)], XY_SHIFT));
                    } else {
                        x1 = static_cast<int32_t>(jm::shr(jm::wadd(sx[E(keepPrelast)], delta), XY_SHIFT));
                        x2 = static_cast<int32_t>(jm::shr(sx[E(prelast)], XY_SHIFT));
                    }
                    if (x1 < w && x2 >= 0) {
                        if (x1 < 0) x1 = 0;
                        if (x2 >= w) x2 = w - 1;
                        hline(m, y, x1, x2);
                    }
                }
                sx[E(keepPrelast)] = jm::wadd(sx[E(keepPrelast)], sdx[E(keepPrelast)]);
                sx[E(prelast)] = jm::wadd(sx[E(prelast)], sdx[E(prelast)]);
            }
            draw ^= 1;
        }
        // 活動邊表依 x 泡沫排序
        int32_t keep = -1;
        do {
            prelast = head;
            last = next[N(head)];
            int32_t lastExchange = -1;
            while (last != keep && next[N(last)] != -1) {
                const int32_t te = next[N(last)];
                if (sx[E(last)] > sx[E(te)]) {
                    next[N(prelast)] = te;
                    next[N(last)] = next[N(te)];
                    next[N(te)] = last;
                    prelast = te;
                    lastExchange = prelast;
                } else {
                    prelast = last;
                    last = te;
                }
            }
            if (lastExchange == -1) break;
            keep = lastExchange;
        } while (keep != next[N(head)] && keep != head);
        y++;
    }
}

}  // namespace prims
}  // namespace nr
