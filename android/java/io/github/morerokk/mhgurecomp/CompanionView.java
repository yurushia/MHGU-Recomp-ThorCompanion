// What the companion panel draws (Companion.java), in the manner of the 3DS games' bottom screen: a green title bar (the
// quest's area), stone panels, the hunter (a clock with the time left, health, stamina and sharpness, a ribbon with the name
// and weapon), the map of the area with the hunter and the large monsters on it, a panel for each large monster, and the item
// pouch as the item bar's black and gold frames. In a town the hunter and the town. Laid out on a 1240 x 1080 sheet (the AYN
// Thor's lower screen) scaled to fit whatever the display is.
//
// The map is the area's ground seen from above (AreaMap.java: read from the player's own game files; nothing of it is kept in
// the app), with the hunter and the monsters of the hunter's area where runtime/hle/companion.c says they are. Of the pieces of
// ground the area is made of, the ones something has stood on are drawn light and the rest dark. A touch on the map zooms in on
// the hunter and back. Without the game's files in a place it can read, a plain radar around the hunter is drawn instead.
//
// The item pouch is a fixed grid of two rows of four buttons, eight to a page, every button the same size on every page.
// A tap on a button uses that item at once (Companion.useItem: the reader puts a drawn weapon away first where the
// weapon needs it, runtime/hle/companion.c). A swipe sideways anywhere in the pouch area, or a tap on one of its arrows,
// turns the page and never uses anything: a touch is a tap only if the finger stays on its button, and a swipe once it has
// moved sideways past a small distance.
//
// The lettering is Barlow Condensed (android/assets/fonts; the system's condensed face without it).
package io.github.morerokk.mhgurecomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

final class CompanionView extends View {
    private static final float W = 1240, H = 1080, M = 28;  // (M: the margin round the sheet)
    // The item pouch: two rows of four buttons, eight to a page
    static final int COLS = 4, ROWS = 2, PER_PAGE = COLS * ROWS;
    private static final float POUCH_X = M, POUCH_Y = 582, POUCH_W = W - 2 * M, POUCH_H = 470;
    private static final float ARROW_W = 70, GRID_X = M + ARROW_W, GRID_W = W - 2 * M - 2 * ARROW_W, GRID_TOP = POUCH_Y + 16,
            GRID_BOTTOM = POUCH_Y + POUCH_H - 14, CELL_GAP = 12, POUCH_TOP = POUCH_Y;
    private static final float CELL_W = (GRID_W - CELL_GAP * (COLS - 1)) / COLS;
    private static final float CELL_H = (GRID_BOTTOM - GRID_TOP - CELL_GAP * (ROWS - 1)) / ROWS;
    // The upper part: the hunter and the map on the left, the monsters on the right
    private static final float HUNTER_X = M, HUNTER_Y = 118, HUNTER_W = 620, HUNTER_H = 212;
    private static final float MAP_X = M, MAP_Y = 344, MAP_W = 620, MAP_H = 224;
    private static final float MON_X = 662, MON_Y = 118, MON_W = W - M - 662, MON_H = 450, MON_GAP = 14;
    // Touch: how far a finger may wander and still be a tap, from where a swipe starts, how far (or how fast) it has to go
    private static final float TAP_SLOP = 30, SWIPE_SLOP = 36, SWIPE_COMMIT = 110, FLING_MIN = 55;
    private static final long FLING_MS = 350, ANIM_MS = 170;
    // The map when zoomed: how much of the area (world units across) the panel shows round the hunter
    private static final float ZOOM_SPAN = 3200;

    private static final int GROUND = 0xff26231d, STONE_TOP = 0xffb9b29f, STONE_BOT = 0xffa59d89, LIGHT = 0xffece6d2, EDGE = 0xff7d7665,
            INK = 0xff2b261c, INK_SOFT = 0xff5a5342, CREAM = 0xfff1e8cc, GOLD = 0xfff2c22c, PLATE = 0xff101010, GREEN_TOP = 0xffb8e25c,
            GREEN_MID = 0xffa4d94c, GREEN_BOT = 0xff6fa81f, GREEN_EDGE = 0xff4f7d10, GREEN_SHADOW = 0xff3d5f0b, RED = 0xffe0402c,
            ORANGE = 0xffe8672e, FRAME = 0xffe8dfc0, TROUGH = 0xff14120e;
    // By quest stage, as runtime/hle/presence.c
    private static final String[] STAGES = {
        null, "Jurassic Frontier", "Verdant Hills", "Arctic Ridge", "Misty Peaks", "Dunes", "Deserted Island",
        "Marshlands", "Volcano", "Arena", "Slayground", "Ancestral Steppe", "Volcanic Hollow", "Primal Forest",
        "Frozen Seaway", null, "Sanctuary", "Forlorn Arena", "Sacred Pinnacle", "Ingle Isle", "Polar Field",
        "Wyvern's End", "Desert", "Jungle", "Ruined Pinnacle", "Castle Schrade", "Fortress", "Forlorn Citadel"};
    private static final String[] TOWNS = {"Hub", "Bherna Village", "Kokoto Village", "Pokke Village", "Yukumo Village",
        null, "Wycademy", "Hunters' Pub"};
    private static final String[] WEAPONS = {
        "Great Sword", "Sword & Shield", "Hammer", "Lance", "Heavy Bowgun", null, "Light Bowgun", "Long Sword",
        "Switch Axe", "Gunlance", "Bow", "Dual Blades", "Hunting Horn", "Insect Glaive", "Charge Blade"};
    // Sharpness, drawn as the game's band: the part of the gauge each colour has (the game's own steps vary by weapon: this is
    // only the look, the gauge's fill is the real value over the real maximum)
    private static final int[] SHARP_COLORS = {0xffc8372d, 0xffe8833a, 0xffe8d44a, 0xff6fbf3c, 0xff3f7fd8, 0xfff2f2ee};
    private static final float[] SHARP_PARTS = {40, 50, 60, 70, 100, 130};
    // Item kinds, for the colour of an item's icon: recovery, boost, tool, bomb, trap
    private static final int[] KIND_COLORS = {0xff5fe08a, 0xffffc94d, 0xff7fc4e8, 0xffff8a4d, 0xffc79bff};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mapFill = new Paint(0), mapLine = new Paint(0);  // (no anti-aliasing: the map's triangles meet without seams)
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Typeface light, medium, semi;
    private String last = "";
    private JSONObject state;

    // The map: the area being shown (loaded in the background), the ground's pieces something has stood on, the paths drawn
    private volatile int areaKey = -1;
    private volatile AreaMap area;
    private volatile int areaState;  // 0 loading, 1 ready, 2 none (no files, or not understood)
    private java.io.File areaRoot;  // the game folder the area was asked for in (a miss is tried again if that changes)
    private boolean[] seen;
    private boolean pathsDirty = true;
    private final Path hills = new Path(), floor = new Path(), pond = new Path();
    private boolean mapZoom;

    // The pouch's page, and a swipe or the slide to a page: drag is how far the pages are shifted, in sheet pixels
    private int page, pageCount = 1;
    private float drag;
    private boolean animating;
    private long animStart;
    private float animFrom, animTo;
    private int animDir;
    // The buttons of the page shown, as last drawn (sheet coordinates), and their pouch slots
    private final RectF[] cells = new RectF[PER_PAGE];
    private final int[] cellSlots = new int[PER_PAGE];
    private int cellCount;
    // The touch in progress
    private boolean tracking, swiping, mapTouch;
    private float downX, downY;
    private long downTime;
    private int pressed = -1;
    private float sheetScale = 1, sheetX, sheetY;

    CompanionView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        mapLine.setStyle(Paint.Style.STROKE);
        light = face(context, "Light", "sans-serif-condensed-light", Typeface.NORMAL);
        medium = face(context, "Medium", "sans-serif-condensed-medium", Typeface.NORMAL);
        semi = face(context, "SemiBold", "sans-serif-condensed", Typeface.BOLD);
        for (int i = 0; i < PER_PAGE; i++) cells[i] = new RectF();
    }

    private static Typeface face(Context context, String weight, String fallback, int style) {
        try {
            return Typeface.createFromAsset(context.getAssets(), "fonts/BarlowCondensed-" + weight + ".ttf");
        } catch (RuntimeException e) {  // (an APK without the fonts)
            return Typeface.create(fallback, style);
        }
    }

    // The state as runtime/hle/companion.c writes it; drawn again only when it changed.
    void update(String json) {
        if (json.equals(last)) return;
        last = json;
        try {
            state = new JSONObject(json);
        } catch (JSONException e) {
            state = null;
        }
        invalidate();
    }

    /** The page of the pouch shown (0 based), for the tests. */
    int page() {
        return page;
    }

    /** Whether the map is zoomed in on the hunter, for the tests. */
    boolean zoomed() {
        return mapZoom;
    }

    private static String at(String[] names, int i) {
        return i >= 0 && i < names.length ? names[i] : null;
    }

    private static int alpha(int color, float a) {
        return (Math.round(255 * a) << 24) | (color & 0xffffff);
    }

    private static double part(double value, double max) {
        return max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
    }

    // Text at a baseline; returns its width. spacing: between the letters, in ems; width > 0: cut to it with an ellipsis.
    private float label(Canvas c, String s, float x, float y, float size, int color, Typeface face, Paint.Align align,
                        float spacing, float width) {
        if (s == null || s.isEmpty()) return 0;
        text.clearShadowLayer();
        text.setTextSize(size);
        text.setColor(color);
        text.setTypeface(face);
        text.setTextAlign(align);
        text.setLetterSpacing(spacing);
        if (width > 0) s = TextUtils.ellipsize(s, text, width, TextUtils.TruncateAt.END).toString();
        c.drawText(s, x, y, text);
        return text.measureText(s);
    }

    private float measure(String s, float size, Typeface face, float spacing) {
        text.setTextSize(size);
        text.setTypeface(face);
        text.setLetterSpacing(spacing);
        return text.measureText(s);
    }

    // ---- the drawing kit: stone, ribbons, bars, icons

    private void shader(float y0, float y1, int top, int bottom) {
        fill.setShader(new LinearGradient(0, y0, 0, y1, top, bottom, Shader.TileMode.CLAMP));
    }

    // A stone panel with the notched corners
    private void stone(Canvas c, float x, float y, float w, float h) {
        rect.set(x, y, x + w, y + h);
        shader(y, y + h, STONE_TOP, STONE_BOT);
        c.drawRoundRect(rect, 10, 10, fill);
        fill.setShader(null);
        stroke.setColor(LIGHT);
        stroke.setStrokeWidth(3);
        rect.set(x + 1.5f, y + 1.5f, x + w - 1.5f, y + h - 1.5f);
        c.drawRoundRect(rect, 9, 9, stroke);
        stroke.setColor(EDGE);
        stroke.setStrokeWidth(2);
        rect.set(x + 4.5f, y + 4.5f, x + w - 4.5f, y + h - 4.5f);
        c.drawRoundRect(rect, 6, 6, stroke);
        notch(c, x + 4, y + 4, 1);
        notch(c, x + w - 4, y + h - 4, -1);
    }

    private void notch(Canvas c, float x, float y, int dir) {
        path.rewind();
        path.moveTo(x, y);
        path.lineTo(x + 34 * dir, y);
        path.lineTo(x, y + 34 * dir);
        path.close();
        fill.setColor(alpha(LIGHT, 0.85f));
        c.drawPath(path, fill);
        stroke.setColor(alpha(EDGE, 0.7f));
        stroke.setStrokeWidth(2);
        c.drawLine(x + 8 * dir, y + 20 * dir, x + 20 * dir, y + 8 * dir, stroke);
    }

    // A black ribbon with pointed ends and a lattice fading in from each
    private void plate(Canvas c, float x, float y, float w, float h) {
        float p = Math.min(16, h / 2);
        path.rewind();
        path.moveTo(x, y + h / 2);
        path.lineTo(x + p, y);
        path.lineTo(x + w - p, y);
        path.lineTo(x + w, y + h / 2);
        path.lineTo(x + w - p, y + h);
        path.lineTo(x + p, y + h);
        path.close();
        fill.setColor(PLATE);
        c.drawPath(path, fill);
        c.save();
        c.clipPath(path);
        stroke.setStrokeWidth(2);
        for (int side = 0; side < 2; side++)
            for (int i = 0; i < 7; i++) {
                float lx = side == 0 ? x + i * 9 : x + w - i * 9 - h;
                stroke.setColor(alpha(LIGHT, 0.5f * (1 - i / 7f)));
                c.drawLine(lx, y, lx + h, y + h, stroke);
                c.drawLine(lx + h, y, lx, y + h, stroke);
            }
        c.restore();
    }

    // The framed gauge with its trough: `value` of it filled, and `behind` (the HP's red part) before the value
    private void bar(Canvas c, float x, float y, float w, float h, double value, double behind, int behindColor, int top, int bottom) {
        fill.setColor(FRAME);
        c.drawRect(x - 4, y - 4, x + w + 4, y + h + 4, fill);
        fill.setColor(TROUGH);
        c.drawRect(x - 2, y - 2, x + w + 2, y + h + 2, fill);
        if (behind > 0) {
            fill.setColor(behindColor);
            c.drawRect(x, y, x + (float) (w * Math.min(1, behind)), y + h, fill);
        }
        if (value > 0) {
            shader(y, y + h, top, bottom);
            c.drawRect(x, y, x + (float) (w * Math.min(1, value)), y + h, fill);
            fill.setShader(null);
        }
    }

    private void sharpBar(Canvas c, float x, float y, float w, float h, double value) {
        fill.setColor(FRAME);
        c.drawRect(x - 4, y - 4, x + w + 4, y + h + 4, fill);
        fill.setColor(TROUGH);
        c.drawRect(x - 2, y - 2, x + w + 2, y + h + 2, fill);
        float total = 0, at = x;
        for (float p : SHARP_PARTS) total += p;
        for (int i = 0; i < SHARP_PARTS.length; i++) {
            float to = at + w * SHARP_PARTS[i] / total;
            fill.setColor(SHARP_COLORS[i]);
            c.drawRect(at, y, to, y + h, fill);
            at = to;
        }
        float edge = x + (float) (w * Math.min(1, value));
        fill.setColor(0xd10a0805);
        c.drawRect(edge, y, x + w, y + h, fill);
        fill.setColor(0xffffffff);
        c.drawRect(edge - 2, y - 6, edge + 2, y + h + 6, fill);
    }

    // ---- icons, drawn in a box of 48 units at (x, y) with the side `size`

    private float gx(float x, float u, float size) {
        return x + u * size / 48;
    }

    private void line(Canvas c, float x, float y, float size, float x0, float y0, float x1, float y1) {
        c.drawLine(gx(x, x0, size), gx(y, y0, size), gx(x, x1, size), gx(y, y1, size), stroke);
    }

    private void poly(Canvas c, float x, float y, float size, boolean closed, float... p) {
        for (int i = 0; i + 3 < p.length; i += 2) line(c, x, y, size, p[i], p[i + 1], p[i + 2], p[i + 3]);
        if (closed) line(c, x, y, size, p[p.length - 2], p[p.length - 1], p[0], p[1]);
    }

    private void ring(Canvas c, float x, float y, float size, float cx, float cy, float r) {
        c.drawCircle(gx(x, cx, size), gx(y, cy, size), r * size / 48, stroke);
    }

    // kind: 0 bottle, 1 drink, 2 whetstone, 3 bomb, 4 trap, 5 paintball
    private void glyph(Canvas c, int kind, float x, float y, float size, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(Math.max(2, 3.4f * size / 48));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        switch (kind) {
            case 0:
                poly(c, x, y, size, false, 19, 5, 29, 5);
                poly(c, x, y, size, false, 21, 5, 21, 17);
                poly(c, x, y, size, false, 27, 5, 27, 17);
                ring(c, x, y, size, 24, 31, 13);
                poly(c, x, y, size, false, 13, 30, 35, 30);
                break;
            case 1:
                poly(c, x, y, size, true, 13, 6, 35, 6, 32, 42, 16, 42);
                poly(c, x, y, size, false, 15, 20, 33, 20);
                poly(c, x, y, size, false, 28, 6, 34, 2);
                break;
            case 2:
                poly(c, x, y, size, true, 5, 30, 30, 5, 43, 18, 18, 43);
                poly(c, x, y, size, false, 14, 22, 26, 34);
                break;
            case 3:
                ring(c, x, y, size, 22, 28, 15);
                poly(c, x, y, size, false, 31, 17, 37, 11);
                poly(c, x, y, size, false, 36, 5, 36, 11, 42, 11);
                break;
            case 4:
                poly(c, x, y, size, false, 6, 38, 42, 38);
                poly(c, x, y, size, false, 10, 38, 16, 22, 24, 32, 32, 22, 38, 38);
                poly(c, x, y, size, false, 24, 8, 24, 22);
                poly(c, x, y, size, false, 18, 14, 24, 8, 30, 14);
                break;
            default:
                ring(c, x, y, size, 24, 24, 17);
                fill.setColor(color);
                c.drawCircle(gx(x, 18, size), gx(y, 19, size), 3 * size / 48, fill);
                c.drawCircle(gx(x, 29, size), gx(y, 28, size), 4 * size / 48, fill);
                break;
        }
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    // An item's icon and colour from its name (the pouch gives the number and the name, no kinds)
    private static int[] kindOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("trap")) return new int[] {4, 4};
        if (n.contains("paintball")) return new int[] {5, 2};
        if (n.contains("bomb") || n.contains("barrel") || n.contains("grenade")) return new int[] {3, 3};
        if (n.contains("whetstone") || n.contains("stone") || n.contains("flute") || n.contains("net")) return new int[] {2, 2};
        if (n.contains("potion") || n.contains("herb") || n.contains("nutrient") || n.contains("antidote") || n.contains("lifepowder")
            || n.contains("ration") || n.contains("antidote") || n.contains("cure")) return new int[] {0, 0};
        if (n.contains("drink") || n.contains("juice") || n.contains("dust") || n.contains("seed") || n.contains("armorskin")
            || n.contains("demondrug") || n.contains("might") || n.contains("dash")) return new int[] {1, 1};
        return new int[] {2, 2};
    }

    private void heart(Canvas c, float cx, float cy, float r, int color) {
        fill.setColor(color);
        c.drawCircle(cx - r * 0.5f, cy - r * 0.2f, r * 0.58f, fill);
        c.drawCircle(cx + r * 0.5f, cy - r * 0.2f, r * 0.58f, fill);
        path.rewind();
        path.moveTo(cx - r * 1.03f, cy + r * 0.05f);
        path.lineTo(cx + r * 1.03f, cy + r * 0.05f);
        path.lineTo(cx, cy + r * 1.05f);
        path.close();
        c.drawPath(path, fill);
    }

    private void bolt(Canvas c, float cx, float cy, float r, int color) {
        fill.setColor(color);
        path.rewind();
        path.moveTo(cx + r * 0.3f, cy - r);
        path.lineTo(cx - r * 0.6f, cy + r * 0.15f);
        path.lineTo(cx - r * 0.05f, cy + r * 0.15f);
        path.lineTo(cx - r * 0.3f, cy + r);
        path.lineTo(cx + r * 0.6f, cy - r * 0.2f);
        path.lineTo(cx + r * 0.05f, cy - r * 0.2f);
        path.close();
        c.drawPath(path, fill);
    }

    private void blade(Canvas c, float cx, float cy, float r, int color) {
        stroke.setColor(color);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(4);
        c.drawLine(cx + r, cy - r, cx - r * 0.5f, cy + r * 0.5f, stroke);
        stroke.setStrokeWidth(5);
        c.drawLine(cx - r * 0.7f, cy + r * 0.1f, cx - r * 0.1f, cy + r * 0.7f, stroke);
        c.drawLine(cx - r * 0.5f, cy + r * 0.5f, cx - r, cy + r, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    // ---- the frame

    @Override
    protected void onDraw(Canvas c) {
        float scale = Math.min(getWidth() / W, getHeight() / H);
        if (scale <= 0) return;
        sheetScale = scale;
        sheetX = (getWidth() - W * scale) / 2;
        sheetY = (getHeight() - H * scale) / 2;
        cellCount = 0;
        c.drawColor(0xff17150f);
        c.translate(sheetX, sheetY);
        c.scale(scale, scale);
        fill.clearShadowLayer();
        fill.setShader(new RadialGradient(W * 0.4f, 0, 1300, new int[] {0xff3a342a, 0xff26231d, 0xff17150f},
                                          new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(-W, -H, 2 * W, 2 * H, fill);  // (past the sheet's edges too, where the display's shape differs)
        fill.setShader(null);
        JSONObject s = state;
        String place = s != null ? s.optString("place") : "";
        if ("quest".equals(place)) quest(c, s);
        else if ("town".equals(place)) town(c, s);
        else {
            title(c, "MHGU Recomp", null, -1);
        }
    }

    // The green bar: where (left), and the area (right)
    private void title(Canvas c, String where, String right, int zone) {
        float x = M, y = M, w = W - 2 * M, h = 76;
        rect.set(x, y, x + w, y + h);
        shader(y, y + h, GREEN_TOP, GREEN_BOT);
        c.drawRoundRect(rect, 10, 10, fill);
        fill.setShader(null);
        stroke.setColor(LIGHT);
        stroke.setStrokeWidth(3);
        rect.set(x + 1.5f, y + 1.5f, x + w - 1.5f, y + h - 1.5f);
        c.drawRoundRect(rect, 9, 9, stroke);
        stroke.setColor(GREEN_EDGE);
        stroke.setStrokeWidth(2);
        rect.set(x + 4.5f, y + 4.5f, x + w - 4.5f, y + h - 4.5f);
        c.drawRoundRect(rect, 6, 6, stroke);
        // a house for the place
        stroke.setColor(0xffffffff);
        stroke.setStrokeWidth(4);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x + 30, y + 40, x + 51, y + 22, stroke);
        c.drawLine(x + 51, y + 22, x + 72, y + 40, stroke);
        c.drawLine(x + 37, y + 38, x + 37, y + 58, stroke);
        c.drawLine(x + 65, y + 38, x + 65, y + 58, stroke);
        c.drawLine(x + 37, y + 58, x + 65, y + 58, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        text.setShadowLayer(3, 0, 2, GREEN_SHADOW);
        text.setTextSize(44);
        text.setColor(0xffffffff);
        text.setTypeface(semi);
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0.03f);
        c.drawText(TextUtils.ellipsize(where, text, 700, TextUtils.TruncateAt.END).toString(), x + 92, y + 53, text);
        text.clearShadowLayer();
        if (right == null) return;
        float rx = x + w - 28;
        if (zone > 0) {  // "AREA" and the number in a pill
            String n = String.valueOf(zone);
            float pw = Math.max(56, measure(n, 36, semi, 0) + 30);
            rect.set(rx - pw, y + 14, rx, y + h - 14);
            fill.setColor(0x73284608);
            c.drawRoundRect(rect, 8, 8, fill);
            stroke.setColor(0xb3ffffff);
            stroke.setStrokeWidth(2);
            c.drawRoundRect(rect, 8, 8, stroke);
            label(c, n, rx - pw / 2, y + 50, 36, 0xffffffff, semi, Paint.Align.CENTER, 0, 0);
            text.setShadowLayer(3, 0, 2, GREEN_SHADOW);
            label(c, right, rx - pw - 16, y + 49, 32, 0xffffffff, semi, Paint.Align.RIGHT, 0.08f, 0);
            text.clearShadowLayer();
        } else {
            label(c, right, rx, y + 49, 32, 0xffffffff, semi, Paint.Align.RIGHT, 0.08f, 0);
        }
    }

    private void town(Canvas c, JSONObject s) {
        String town = at(TOWNS, s.optInt("town", -1));
        title(c, town != null ? town : "The village", null, -1);
        JSONObject h = s.optJSONObject("hunter");
        stone(c, M, 118, W - 2 * M, 250);
        if (h == null) return;
        String weapon = at(WEAPONS, h.optInt("weapon", -1));
        label(c, "Hunter", M + 40, 178, 28, INK_SOFT, medium, Paint.Align.LEFT, 0.2f, 0);
        label(c, h.optString("name"), M + 40, 262, 92, INK, semi, Paint.Align.LEFT, 0.02f, 780);
        plate(c, M + 40, 296, 520, 44);
        if (weapon != null) label(c, weapon.toUpperCase(Locale.ROOT), M + 300, 328, 25, GOLD, semi, Paint.Align.CENTER, 0.14f, 480);
    }

    private void quest(Canvas c, JSONObject s) {
        int stageNo = s.optInt("stage") % 100, zone = s.optInt("zone", -1);
        String stage = at(STAGES, stageNo);
        title(c, stage != null ? stage : "Quest " + s.optInt("quest"), zone == 0 ? "BASE CAMP" : "AREA", zone);
        JSONObject h = s.optJSONObject("hunter");
        JSONArray monsters = s.optJSONArray("monsters");
        hunter(c, s, h);
        map(c, stageNo, zone, h, monsters);
        monsterPanels(c, monsters, zone);
        pouch(c, s);
    }

    // ---- the hunter

    private void hunter(Canvas c, JSONObject s, JSONObject h) {
        stone(c, HUNTER_X, HUNTER_Y, HUNTER_W, HUNTER_H);
        // the clock: the time left as a wedge, in a frame of rays
        JSONObject time = s.optJSONObject("time");
        float cx = HUNTER_X + 97, cy = HUNTER_Y + 80, r = 74;
        boolean late = time != null && time.optInt("left") < 300;
        fill.setColor(0xffd9532a);
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            path.rewind();
            path.moveTo(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
            path.lineTo(cx + (float) Math.cos(a + 0.2) * 58, cy + (float) Math.sin(a + 0.2) * 58);
            path.lineTo(cx + (float) Math.cos(a - 0.2) * 58, cy + (float) Math.sin(a - 0.2) * 58);
            path.close();
            c.drawPath(path, fill);
        }
        fill.setColor(0xff7a4a22);
        c.drawCircle(cx, cy, 60, fill);
        fill.setColor(0xfff1e3b8);
        c.drawCircle(cx, cy, 54, fill);
        if (time != null) {
            rect.set(cx - 46, cy - 46, cx + 46, cy + 46);
            fill.setColor(late ? 0x99e0402c : 0x8cf2c22c);
            c.drawArc(rect, -90, 360 * (float) part(time.optInt("left"), time.optInt("limit")), true, fill);
        }
        stroke.setColor(0xff3a2410);
        stroke.setStrokeWidth(3);
        c.drawCircle(cx, cy, 54, stroke);
        int left = time != null ? time.optInt("left") : 0;
        label(c, time != null ? String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60) : "--:--", cx, cy + 12, 33,
              late ? 0xff8a1d10 : 0xff3a2410, semi, Paint.Align.CENTER, 0, 0);
        // the bars
        float bx = HUNTER_X + 228, bw = 266, vx = HUNTER_X + HUNTER_W - 20;
        float y1 = HUNTER_Y + 52, y2 = y1 + 38, y3 = y2 + 39;
        if (h != null && h.has("hp")) {
            int hp = h.optInt("hp"), hpMax = h.optInt("hpMax");
            heart(c, HUNTER_X + 204, y1 + 8, 12, INK);
            bar(c, bx, y1, bw, 16, part(hp, hpMax), part(h.optInt("hpRed"), hpMax), 0xffd9422a, 0xffe9f58a, 0xff5f9d17);
            value(c, String.valueOf(hp), "/" + hpMax, vx, y1 + 20);
            double st = part(h.optDouble("st", 0), h.optDouble("stMax", 0));
            bolt(c, HUNTER_X + 204, y2 + 8, 12, INK);
            bar(c, bx, y2, bw, 16, st, 0, 0, 0xfffff39a, 0xffc99a12);
            value(c, String.valueOf(Math.round(st * 100)), "%", vx, y2 + 20);
            int sharp = h.optInt("sharp"), sharpMax = h.optInt("sharpMax");
            blade(c, HUNTER_X + 204, y3 + 8, 11, INK);
            if (sharpMax > 0) {
                sharpBar(c, bx, y3, bw, 16, part(sharp, sharpMax));
                value(c, String.valueOf(sharp), "/" + sharpMax, vx, y3 + 20);
            }
        }
        // the ribbon: the name, and the weapon
        float px = HUNTER_X + 18, pw = HUNTER_W - 36, py = HUNTER_Y + HUNTER_H - 56;
        plate(c, px, py, pw, 40);
        String name = h != null ? h.optString("name") : "";
        String weapon = h != null ? at(WEAPONS, h.optInt("weapon", -1)) : null;
        weapon = weapon != null ? weapon.toUpperCase(Locale.ROOT) : "";
        float w1 = measure(name, 27, semi, 0.04f), w2 = weapon.isEmpty() ? 0 : measure(weapon, 21, semi, 0.14f) + 18;
        float x0 = px + pw / 2 - (w1 + w2) / 2;
        label(c, name, x0, py + 29, 27, CREAM, semi, Paint.Align.LEFT, 0.04f, 300);
        label(c, weapon, x0 + w1 + 18, py + 28, 21, GOLD, semi, Paint.Align.LEFT, 0.14f, 260);
    }

    // "132 /150": the value large, what follows small, ending at x
    private void value(Canvas c, String big, String small, float x, float y) {
        float w = label(c, small, x, y, 20, INK_SOFT, medium, Paint.Align.RIGHT, 0, 0);
        label(c, big, x - w - 2, y + 3, 31, INK, semi, Paint.Align.RIGHT, 0, 0);
    }

    // ---- the map

    private void mark(AreaMap a, double x, double y, double z) {
        int p = a.ground.pieceAt((float) x, (float) y, (float) z);
        if (p >= 0 && p < seen.length && !seen[p]) {
            seen[p] = true;
            pathsDirty = true;
        }
    }

    private void triangles(Path p, AreaMap.Mesh m, boolean[] which, boolean wanted) {
        p.rewind();
        for (int i = 0; i < m.count(); i++) {
            if (which != null && which[m.comp[i]] != wanted) continue;
            int o = i * 6;
            p.moveTo(m.xz[o], m.xz[o + 1]);
            p.lineTo(m.xz[o + 2], m.xz[o + 3]);
            p.lineTo(m.xz[o + 4], m.xz[o + 5]);
            p.close();
        }
    }

    private void layer(Canvas c, Path p, int color, float scale) {
        mapFill.setStyle(Paint.Style.FILL);
        mapFill.setColor(color);
        c.drawPath(p, mapFill);
        mapLine.setColor(color);
        mapLine.setStrokeWidth(1.2f / scale);  // (a hairline over the edges: no seams between triangles)
        c.drawPath(p, mapLine);
    }

    private void map(Canvas c, int stageNo, int zone, JSONObject h, JSONArray mons) {
        stone(c, MAP_X, MAP_Y, MAP_W, MAP_H);
        // the area being shown: read in the background when the hunter's zone changes
        int key = zone >= 0 && zone < 100 && stageNo > 0 ? stageNo * 100 + zone : -1;
        if (key != areaKey || (key >= 0 && areaState == 2 && AreaMap.root != areaRoot)) {
            areaKey = key;
            areaRoot = AreaMap.root;
            area = null;
            seen = null;
            pathsDirty = true;
            areaState = key < 0 ? 2 : 0;
            if (key >= 0) {
                final int st = stageNo, zn = zone, k = key;
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        AreaMap a = AreaMap.load(st, zn);
                        if (areaKey != k) return;
                        area = a;
                        areaState = a != null ? 1 : 2;
                        postInvalidate();
                    }
                }, "area-map");
                t.setDaemon(true);
                t.start();
            }
        }
        float ix = MAP_X + 12, iy = MAP_Y + 12, iw = MAP_W - 24, ih = MAP_H - 24;
        c.save();
        c.clipRect(ix, iy, ix + iw, iy + ih);
        fill.setColor(0xff3b372d);
        c.drawRect(ix, iy, ix + iw, iy + ih, fill);
        double hx = h != null ? h.optDouble("x") : 0, hy = h != null ? h.optDouble("y") : 0, hz = h != null ? h.optDouble("z") : 0;
        boolean haveHunter = h != null && h.has("x");
        // the monsters of the hunter's area (their positions are in that area's own coordinates)
        int n = mons != null ? Math.min(mons.length(), 4) : 0;
        double[][] at = new double[n][];
        for (int i = 0; i < n; i++) {
            JSONObject m = mons.optJSONObject(i);
            if (m == null || !m.has("x")) continue;
            if (m.optInt("zone", zone) != zone || m.optInt("hp") <= 0) continue;
            at[i] = new double[] {m.optDouble("x"), m.optDouble("y"), m.optDouble("z")};
        }
        AreaMap a = area;
        float cx = ix + iw / 2, cy = iy + ih / 2, sc;
        double wx, wz;
        if (a != null) {
            if (seen == null || seen.length != a.ground.pieces) {
                seen = new boolean[a.ground.pieces];
                pathsDirty = true;
            }
            if (haveHunter) mark(a, hx, hy, hz);
            for (double[] p : at)
                if (p != null) mark(a, p[0], p[1], p[2]);
            if (pathsDirty) {
                triangles(floor, a.ground, seen, true);
                triangles(hills, a.ground, seen, false);
                triangles(pond, a.water, null, true);
                pathsDirty = false;
            }
            boolean any = false;
            for (boolean b : seen) any |= b;
            float[] b = any ? AreaMap.bounds(a.ground, seen) : new float[] {a.minX, a.maxX, a.minZ, a.maxZ};
            if (mapZoom && haveHunter) {
                sc = iw / ZOOM_SPAN;
                wx = hx;
                wz = hz;
            } else {
                float spanX = Math.max(1, b[1] - b[0]) * 1.1f, spanZ = Math.max(1, b[3] - b[2]) * 1.1f;
                sc = Math.min(iw / spanX, ih / spanZ);
                wx = (b[0] + b[1]) / 2;
                wz = (b[2] + b[3]) / 2;
            }
            c.save();
            c.translate(cx - (float) wx * sc, cy - (float) wz * sc);
            c.scale(sc, sc);
            layer(c, pond, 0xff7fa9c8, sc);
            layer(c, hills, 0xff5d584a, sc);
            layer(c, floor, 0xffe2d8b8, sc);
            c.restore();
        } else {
            // no map (loading, or no game files to read it from): the hunter in the middle and what is near
            double reach = 1500;
            if (haveHunter)
                for (double[] p : at)
                    if (p != null) reach = Math.max(reach, Math.hypot(p[0] - hx, p[2] - hz) * 1.15);
            sc = (float) (Math.min(iw, ih) / 2 * 0.88 / reach);
            wx = hx;
            wz = hz;
            stroke.setColor(0x33ffffff);
            stroke.setStrokeWidth(2);
            c.drawCircle(cx, cy, Math.min(iw, ih) / 2 * 0.88f, stroke);
            c.drawCircle(cx, cy, Math.min(iw, ih) / 4 * 0.88f, stroke);
            if (areaState == 0) label(c, "reading the map...", ix + iw - 14, iy + ih - 12, 20, 0x99ffffff, medium, Paint.Align.RIGHT, 0.05f, 0);
        }
        // the monsters, then the hunter on top
        for (int i = 0; i < n; i++) {
            if (at[i] == null) continue;
            float sx = cx + (float) (at[i][0] - wx) * sc, sy = cy + (float) (at[i][2] - wz) * sc;
            boolean out = sx < ix + 18 || sx > ix + iw - 18 || sy < iy + 18 || sy > iy + ih - 18;
            sx = Math.max(ix + 18, Math.min(ix + iw - 18, sx));
            sy = Math.max(iy + 18, Math.min(iy + ih - 18, sy));
            monsterMarker(c, sx, sy, i + 1, mons.optJSONObject(i), out);
        }
        if (haveHunter) {
            float sx = cx + (float) (hx - wx) * sc, sy = cy + (float) (hz - wz) * sc;
            sx = Math.max(ix + 12, Math.min(ix + iw - 12, sx));
            sy = Math.max(iy + 12, Math.min(iy + ih - 12, sy));
            fill.setColor(0xffffffff);
            c.drawCircle(sx, sy, 13, fill);
            fill.setColor(RED);
            c.drawCircle(sx, sy, 9, fill);
        }
        c.restore();
        label(c, zone == 0 ? "CAMP" : zone > 0 ? "AREA " + zone : "", MAP_X + 22, MAP_Y + MAP_H - 20, 24, 0xffffffff, semi,
              Paint.Align.LEFT, 0.1f, 0);
        label(c, mapZoom ? "TAP: WHOLE AREA" : "TAP: ZOOM", MAP_X + MAP_W - 22, MAP_Y + 34, 18, 0x99ffffff, medium, Paint.Align.RIGHT, 0.12f, 0);
    }

    private void monsterMarker(Canvas c, float x, float y, int number, JSONObject m, boolean out) {
        boolean low = m != null && part(m.optInt("hp"), m.optInt("hpMax")) <= 0.25;
        fill.setColor(0xff14120e);
        c.drawCircle(x, y, out ? 14 : 16, fill);
        fill.setColor(out ? 0xff9b8b78 : low ? RED : ORANGE);
        c.drawCircle(x, y, out ? 11 : 13, fill);
        label(c, String.valueOf(number), x, y + 8, 22, 0xffffffff, semi, Paint.Align.CENTER, 0, 0);
    }

    // ---- the large monsters

    private void monsterPanels(Canvas c, JSONArray monsters, int hunterZone) {
        int n = monsters != null ? Math.min(monsters.length(), 4) : 0;
        if (n == 0) {
            stone(c, MON_X, MON_Y, MON_W, MON_H);
            label(c, "No large monster in sight", MON_X + MON_W / 2, MON_Y + MON_H / 2 + 10, 34, INK_SOFT, medium, Paint.Align.CENTER, 0.06f, MON_W - 40);
            return;
        }
        int slots = Math.max(2, n);
        float h = (MON_H - MON_GAP * (slots - 1)) / slots;
        for (int i = 0; i < slots; i++) {
            float y = MON_Y + i * (h + MON_GAP);
            if (i < n && monsters.optJSONObject(i) != null) monster(c, monsters.optJSONObject(i), i + 1, y, h, n > 2, hunterZone);
            else stone(c, MON_X, y, MON_W, h);
        }
    }

    private void monster(Canvas c, JSONObject m, int number, float y, float h, boolean compact, int hunterZone) {
        float x = MON_X, w = MON_W;
        stone(c, x, y, w, h);
        int hp = m.optInt("hp"), max = m.optInt("hpMax"), zone = m.optInt("zone", hunterZone);
        double p = part(hp, max);
        boolean low = p <= 0.25, dead = hp <= 0;
        String name = m.optString("name").isEmpty() ? "Monster" : m.optString("name");
        float ic = compact ? 76 : 150, ix = x + (compact ? 16 : 22), iy = y + (h - ic) / 2;
        // the icon: the monster's initial on a dark tile, its number on the map, where it is
        rect.set(ix, iy, ix + ic, iy + ic);
        fill.setShader(new RadialGradient(ix + ic / 2, iy + ic * 0.4f, ic * 0.75f, new int[] {0xff5a5748, 0xff2f2d25}, new float[] {0, 1},
                                          Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, 14, 14, fill);
        fill.setShader(null);
        stroke.setColor(0xff1b1a15);
        stroke.setStrokeWidth(3);
        c.drawRoundRect(rect, 14, 14, stroke);
        label(c, name.substring(0, 1).toUpperCase(Locale.ROOT), ix + ic / 2, iy + ic * 0.68f, ic * 0.6f, dead ? 0xff8f8a78 : 0xffd9d2b8, semi,
              Paint.Align.CENTER, 0, 0);
        float br = compact ? 14 : 20;
        fill.setColor(0xffffffff);
        c.drawCircle(ix + 6, iy + 6, br + 2, fill);
        fill.setColor(low ? RED : ORANGE);
        c.drawCircle(ix + 6, iy + 6, br, fill);
        label(c, String.valueOf(number), ix + 6, iy + 6 + br * 0.38f, br * 1.2f, 0xffffffff, semi, Paint.Align.CENTER, 0, 0);
        if (!compact) {
            String where = zone == 0 ? "CAMP" : zone > 0 ? "AREA " + zone : "";
            label(c, where, ix + ic / 2, iy + ic - 10, 20, zone == hunterZone ? GOLD : 0xffa9a28c, semi, Paint.Align.CENTER, 0.12f, ic - 8);
        }
        float tx = ix + ic + (compact ? 18 : 24), tw = x + w - 22 - tx;
        float bh = compact ? 16 : 22;
        float by = y + h - (compact ? 28 : 56);
        if (!compact) {
            plate(c, tx, y + 22, tw, 44);
            label(c, name, tx + tw / 2, y + 22 + 32, 28, dead ? 0xffa9a28c : CREAM, semi, Paint.Align.CENTER, 0.07f, tw - 80);
            label(c, String.valueOf(Math.round(p * 100)), tx, y + 120, 66, INK, semi, Paint.Align.LEFT, 0, 0);
            float pw = measure(String.valueOf(Math.round(p * 100)), 66, semi, 0);
            label(c, "%", tx + pw + 4, y + 120, 30, INK_SOFT, semi, Paint.Align.LEFT, 0, 0);
            label(c, String.format(Locale.US, "%,d / %,d", hp, max), tx + tw, y + 118, 28, INK_SOFT, semi, Paint.Align.RIGHT, 0.04f, 0);
        } else {
            label(c, name.toUpperCase(Locale.ROOT), tx, y + 36, 28, INK, semi, Paint.Align.LEFT, 0.06f, tw - 120);
            label(c, Math.round(p * 100) + "%", tx + tw, y + 36, 28, INK, semi, Paint.Align.RIGHT, 0, 0);
            by = y + h - 28;
        }
        int top = low ? 0xffff9a7a : 0xffffc27a, bottom = low ? 0xffb81f12 : 0xffc2410f;
        bar(c, tx, by, tw, bh, p, 0, 0, top, bottom);
        // the point a monster can be captured from (a fifth of its health), and what that looks like
        float cxp = tx + tw * 0.2f;
        fill.setColor(0xffffffff);
        c.drawRect(cxp - 2, by - 8, cxp + 2, by + bh + 8, fill);
        if (!compact) {
            path.rewind();
            path.moveTo(cxp, by + bh + 12);
            path.lineTo(cxp - 10, by + bh + 28);
            path.lineTo(cxp + 10, by + bh + 28);
            path.close();
            fill.setColor(0xff7b1f10);
            c.drawPath(path, fill);
        }
    }

    // ---- the item pouch

    private float cellX(int col, float shift) {
        return GRID_X + col * (CELL_W + CELL_GAP) + shift;
    }

    private float cellY(int row) {
        return GRID_TOP + row * (CELL_H + CELL_GAP);
    }

    private void pouch(Canvas c, JSONObject s) {
        stone(c, POUCH_X, POUCH_Y, POUCH_W, POUCH_H);
        JSONArray items = s.optJSONArray("items");
        int count = items != null ? items.length() : 0;
        pageCount = Math.max(1, (count + PER_PAGE - 1) / PER_PAGE);
        if (page >= pageCount) page = pageCount - 1;
        if (animating) step();
        float ay = POUCH_Y + POUCH_H / 2 - 10;
        arrow(c, POUCH_X + ARROW_W / 2 + 4, ay, -1, page > 0);
        arrow(c, POUCH_X + POUCH_W - ARROW_W / 2 - 4, ay, 1, page < pageCount - 1);
        if (pageCount > 1) {
            String pg = (page + 1) + " / " + pageCount;
            label(c, pg, POUCH_X + ARROW_W / 2 + 4, ay + 92, 24, INK_SOFT, semi, Paint.Align.CENTER, 0.06f, 0);
            label(c, pg, POUCH_X + POUCH_W - ARROW_W / 2 - 4, ay + 92, 24, INK_SOFT, semi, Paint.Align.CENTER, 0.06f, 0);
        }
        if (count == 0) {
            label(c, "Nothing on the item bar", W / 2, POUCH_Y + POUCH_H / 2 + 10, 36, INK_SOFT, medium, Paint.Align.CENTER, 0.06f, 0);
            return;
        }
        int using = s.optInt("using", -1);
        String step = s.optString("step");
        c.save();
        c.clipRect(GRID_X - 6, GRID_TOP - 8, GRID_X + GRID_W + 6, GRID_BOTTOM + 8);  // (a page slides in from the arrow, not out over the rest)
        for (int p = Math.max(0, page - 1); p <= Math.min(pageCount - 1, page + 1); p++) {
            float shift = (p - page) * W + drag;
            if (shift <= -W || shift >= W) continue;
            for (int i = 0; i < PER_PAGE && p * PER_PAGE + i < count; i++) {
                JSONObject it = items.optJSONObject(p * PER_PAGE + i);
                if (it != null) button(c, it, cellX(i % COLS, shift), cellY(i / COLS), p == page ? i : -1, using, step);
            }
        }
        c.restore();
    }

    private void arrow(Canvas c, float x, float y, int dir, boolean on) {
        stroke.setColor(on ? LIGHT : alpha(LIGHT, 0.35f));
        stroke.setStrokeWidth(13);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x - 12 * dir, y - 52, x + 12 * dir, y, stroke);
        c.drawLine(x + 12 * dir, y, x - 12 * dir, y + 52, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    // The octagon of a button, inset by d
    private void frame(float x, float y, float w, float h, float cut, float d) {
        path.rewind();
        path.moveTo(x + cut + d, y + d);
        path.lineTo(x + w - cut - d, y + d);
        path.lineTo(x + w - d, y + cut + d);
        path.lineTo(x + w - d, y + h - cut - d);
        path.lineTo(x + w - cut - d, y + h - d);
        path.lineTo(x + cut + d, y + h - d);
        path.lineTo(x + d, y + h - cut - d);
        path.lineTo(x + d, y + cut + d);
        path.close();
    }

    // One button. index >= 0: of the page shown, so a touch can find it.
    private void button(Canvas c, JSONObject it, float x, float y, int index, int using, String step) {
        float w = CELL_W, h = CELL_H;
        int slot = it.optInt("slot", -1);
        boolean selected = it.optBoolean("sel"), busy = slot >= 0 && slot == using, down = slot >= 0 && slot == pressed;
        if (index >= 0 && slot >= 0) {
            cells[index].set(x, y, x + w, y + h);
            cellSlots[index] = slot;
            cellCount = Math.max(cellCount, index + 1);
        }
        boolean lit = selected || busy;
        frame(x, y, w, h, 20, 0);
        if (lit) {
            c.save();
            fill.setShader(new LinearGradient(x, y, x + w, y + h, 0xff7dfff0, 0xfff2f27a, Shader.TileMode.CLAMP));
            fill.setShadowLayer(14, 0, 0, 0xb382ffe6);
        } else {
            fill.setShader(new LinearGradient(x, y, x + w, y + h, 0xfff2c22c, 0xffa8800f, Shader.TileMode.CLAMP));
        }
        c.drawPath(path, fill);
        fill.clearShadowLayer();
        fill.setShader(null);
        if (lit) c.restore();
        frame(x, y, w, h, 20, 4);
        fill.setColor(down ? 0xff2a2a26 : 0xff0c0c0b);
        c.drawPath(path, fill);
        frame(x, y, w, h, 20, 10);
        stroke.setColor(alpha(GOLD, 0.45f));
        stroke.setStrokeWidth(2);
        c.drawPath(path, stroke);
        // the icon in its tile, in its kind's colour
        String name = it.optString("name").isEmpty() ? "Item " + it.optInt("id") : it.optString("name");
        int[] kind = kindOf(name);
        int color = KIND_COLORS[kind[1]];
        float tile = 84, tx = x + w / 2 - tile / 2, ty = y + 26;
        rect.set(tx, ty, tx + tile, ty + tile);
        shader(ty, ty + tile, 0xff17332d, 0xff0c1a17);
        c.drawRoundRect(rect, 18, 18, fill);
        fill.setShader(null);
        stroke.setColor(color);
        stroke.setStrokeWidth(3);
        c.drawRoundRect(rect, 18, 18, stroke);
        glyph(c, kind[0], tx + 16, ty + 16, 52, color);
        // the number, top right
        String n = String.valueOf(it.optInt("n"));
        float nw = label(c, n, x + w - 24, y + 56, 44, 0xffffffff, semi, Paint.Align.RIGHT, 0, 0);
        label(c, "×", x + w - 24 - nw - 3, y + 54, 24, 0xb3ffffff, medium, Paint.Align.RIGHT, 0, 0);
        // the name on a ribbon, or what the touch is doing
        float px = x + 16, pw = w - 32, py = y + h - 54;
        plate(c, px, py, pw, 36);
        if (busy)
            label(c, "sheathe".equals(step) ? "SHEATHING" : "wait".equals(step) ? "WAITING" : "USING", px + pw / 2, py + 26, 21, GOLD, semi,
                  Paint.Align.CENTER, 0.14f, pw - 50);
        else label(c, name, px + pw / 2, py + 26, 23, CREAM, semi, Paint.Align.CENTER, 0.03f, pw - 50);
    }

    // ---- turning the page

    // The slide to a page (or back), a little on each frame
    private void step() {
        float t = Math.min(1, (System.nanoTime() - animStart) / (ANIM_MS * 1e6f)), e = 1 - (1 - t) * (1 - t) * (1 - t);
        drag = animFrom + (animTo - animFrom) * e;
        if (t >= 1) finishSlide();
        else invalidate();
    }

    private void finishSlide() {
        if (animating && animDir != 0) page = Math.max(0, Math.min(pageCount - 1, page + animDir));
        animating = false;
        drag = 0;
    }

    // dir: +1 the next page, -1 the one before, 0 back to this one
    private void slideTo(int dir) {
        animating = true;
        animStart = System.nanoTime();
        animFrom = drag;
        animDir = dir;
        animTo = dir > 0 ? -W : dir < 0 ? W : 0;
        invalidate();
    }

    // ---- touch

    private float sheetXOf(float vx) {
        return (vx - sheetX) / sheetScale;
    }

    private float sheetYOf(float vy) {
        return (vy - sheetY) / sheetScale;
    }

    // The pouch slot of the button of the page shown at a point of the sheet, -1 when there is none.
    private int slotAt(float x, float y) {
        if (drag != 0 || animating) return -1;
        for (int i = 0; i < cellCount; i++)
            if (cells[i].contains(x, y)) return cellSlots[i];
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = sheetXOf(e.getX()), y = sheetYOf(e.getY());
        int was = pressed;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (animating) finishSlide();
                boolean inQuest = state != null && "quest".equals(state.optString("place"));
                tracking = y >= POUCH_TOP && inQuest;
                mapTouch = inQuest && x >= MAP_X && x < MAP_X + MAP_W && y >= MAP_Y && y < MAP_Y + MAP_H;
                swiping = false;
                downX = x;
                downY = y;
                downTime = e.getEventTime();
                pressed = tracking ? slotAt(x, y) : -1;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) break;
                float dx = x - downX, dy = y - downY;
                if (!swiping && Math.abs(dx) > SWIPE_SLOP && Math.abs(dx) > Math.abs(dy)) swiping = true;
                if (swiping || Math.abs(dy) > TAP_SLOP) pressed = -1;  // (a finger that has set off is not tapping)
                if (swiping) {
                    boolean end = (dx < 0 && page >= pageCount - 1) || (dx > 0 && page <= 0);
                    drag = Math.max(-W, Math.min(W, end ? dx * 0.3f : dx));  // (stretches, but not far, past the last page)
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mapTouch) {  // a tap on the map: zoom in on the hunter, or back to the whole area
                    mapTouch = false;
                    if (Math.abs(x - downX) < TAP_SLOP && Math.abs(y - downY) < TAP_SLOP) {
                        mapZoom = !mapZoom;
                        invalidate();
                    }
                    break;
                }
                if (!tracking) break;
                if (swiping) {
                    float moved = x - downX;
                    boolean quick = e.getEventTime() - downTime < FLING_MS && Math.abs(moved) >= FLING_MIN;
                    int dir = moved < 0 ? 1 : -1;
                    boolean go = (Math.abs(moved) >= SWIPE_COMMIT || quick) && page + dir >= 0 && page + dir < pageCount;
                    slideTo(go ? dir : 0);
                } else if (pressed >= 0 && slotAt(x, y) == pressed && Math.abs(x - downX) < TAP_SLOP && Math.abs(y - downY) < TAP_SLOP) {
                    Companion.useItem(pressed);
                } else if (pressed < 0 && Math.abs(x - downX) < TAP_SLOP && Math.abs(y - downY) < TAP_SLOP && !animating) {
                    if (x < GRID_X && page > 0) slideTo(-1);  // (a tap on an arrow)
                    else if (x > GRID_X + GRID_W && page < pageCount - 1) slideTo(1);
                }
                pressed = -1;
                tracking = swiping = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                mapTouch = false;
                pressed = -1;
                if (swiping) slideTo(0);
                tracking = swiping = false;
                break;
            default:
                break;
        }
        if (pressed != was) invalidate();
        return true;
    }
}
