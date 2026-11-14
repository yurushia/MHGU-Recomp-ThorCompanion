// What the companion panel draws (Companion.java): the quest's time left and area (small, in one band), the hunter's HP,
// stamina and sharpness, the large monsters' HP, and below them most of the screen for the item pouch; in a town the
// hunter and the town. Laid out on a 1240 x 1080 sheet (the AYN Thor's lower screen) scaled to fit whatever the display is.
//
// The item pouch is a fixed grid of two rows of four buttons, eight to a page, every button the same size on every page.
// A tap on a button uses that item at once (Companion.useItem: the reader puts a drawn weapon away first where the
// weapon needs it, runtime/hle/companion.c). A swipe sideways anywhere in the pouch area turns the page and never uses
// anything: a touch is a tap only if the finger stays on its button, and a swipe once it has moved sideways past a
// small distance.
//
// The look: a dark slate sheet, thin condensed lettering (Barlow Condensed, android/assets/fonts; the system's
// condensed face without it), slim slanted gauges with a glow.
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
    private static final float W = 1240, H = 1080, L = 52, R = W - 52, TOP = 36, BOTTOM = H - 44;
    // The item pouch: two rows of four buttons, eight to a page
    static final int COLS = 4, ROWS = 2, PER_PAGE = COLS * ROWS;
    private static final float GRID_TOP = 566, CELL_GAP = 16, POUCH_TOP = 514;  // (POUCH_TOP: where the swipe area starts)
    private static final float CELL_W = (R - L - CELL_GAP * (COLS - 1)) / COLS;
    private static final float CELL_H = (BOTTOM - GRID_TOP - CELL_GAP * (ROWS - 1)) / ROWS;
    // Touch: how far a finger may wander and still be a tap, from where a swipe starts, how far (or how fast) it has to go
    private static final float TAP_SLOP = 30, SWIPE_SLOP = 36, SWIPE_COMMIT = 110, FLING_MIN = 55;
    private static final long FLING_MS = 350, ANIM_MS = 170;

    private static final int TEXT = 0xffeef3ef, SOFT = 0xffc9d6d2, DIM = 0xff8fa6a3, GOLD = 0xfff4c95d, RED = 0xffff3b30,
            ORANGE = 0xffe0603c, TROUGH = 0x66000000, LINE = 0x2effffff;
    private static final float SLANT = 0.2226f;  // a gauge's ends lean by this much of its height to each side (24 degrees)
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

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Typeface light, medium, semi;
    private String last = "";
    private JSONObject state;

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
    private boolean tracking, swiping;
    private float downX, downY;
    private long downTime;
    private int pressed = -1;
    private float sheetScale = 1, sheetX, sheetY;

    CompanionView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
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
        text.setTextSize(size);
        text.setColor(color);
        text.setTypeface(face);
        text.setTextAlign(align);
        text.setLetterSpacing(spacing);
        if (width > 0) s = TextUtils.ellipsize(s, text, width, TextUtils.TruncateAt.END).toString();
        c.drawText(s, x, y, text);
        return text.measureText(s);
    }

    // A small caption in capitals
    private void caption(Canvas c, String s, float x, float y) {
        label(c, s.toUpperCase(Locale.ROOT), x, y, 22, DIM, medium, Paint.Align.LEFT, 0.22f, 0);
    }

    // "132 / 150": the value large, what follows small and dim, ending at x
    private void value(Canvas c, String big, String small, float x, float y) {
        float w = label(c, small, x, y, 22, DIM, medium, Paint.Align.RIGHT, 0, 0);
        label(c, big, x - w, y, 32, TEXT, medium, Paint.Align.RIGHT, 0, 0);
    }

    private void slanted(float x, float y, float w, float h) {
        float d = h * SLANT;
        path.rewind();
        path.moveTo(x + d, y);
        path.lineTo(x + w + d, y);
        path.lineTo(x + w - d, y + h);
        path.lineTo(x - d, y + h);
        path.close();
    }

    // A slanted gauge: its trough, an optional part behind the value (the HP's red part), the value with a glow.
    private void gauge(Canvas c, float x, float y, float w, float h, double value, double behind, int behindColor, int top,
                       int bottom) {
        slanted(x, y, w, h);
        fill.setShader(null);
        fill.clearShadowLayer();
        fill.setColor(TROUGH);
        c.drawPath(path, fill);
        stroke.setColor(LINE);
        stroke.setStrokeWidth(1);
        c.drawPath(path, stroke);
        if (behind > 0) {
            slanted(x, y, (float) (w * Math.min(1, behind)), h);
            fill.setColor(behindColor);
            c.drawPath(path, fill);
        }
        if (value > 0) {
            slanted(x, y, (float) (w * Math.min(1, value)), h);
            fill.setShader(new LinearGradient(0, y, 0, y + h, top, bottom, Shader.TileMode.CLAMP));
            fill.setShadowLayer(16, 0, 0, alpha(bottom, 0.55f));
            fill.setColor(bottom);
            c.drawPath(path, fill);
            fill.setShader(null);
            fill.clearShadowLayer();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float scale = Math.min(getWidth() / W, getHeight() / H);
        if (scale <= 0) return;
        sheetScale = scale;
        sheetX = (getWidth() - W * scale) / 2;
        sheetY = (getHeight() - H * scale) / 2;
        cellCount = 0;
        c.drawColor(0xff0a1012);
        c.translate(sheetX, sheetY);
        c.scale(scale, scale);
        fill.clearShadowLayer();
        fill.setShader(new RadialGradient(W * 0.3f, 0, 1150, new int[] {0xff1d2f33, 0xff111a1d, 0xff0a1012},
                                          new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(-W, -H, 2 * W, 2 * H, fill);  // (past the sheet's edges too, where the display's shape differs)
        fill.setShader(null);
        JSONObject s = state;
        String place = s != null ? s.optString("place") : "";
        if ("quest".equals(place)) quest(c, s);
        else if ("town".equals(place)) town(c, s);
        else label(c, "MHGU RECOMP", W / 2, H / 2, 40, DIM, medium, Paint.Align.CENTER, 0.3f, 0);
    }

    private void hairline(Canvas c, float y) {
        fill.setShader(new LinearGradient(L, 0, R, 0, new int[] {0x55ffffff, 0x10ffffff, 0x00ffffff}, new float[] {0, 0.7f, 1},
                                          Shader.TileMode.CLAMP));
        c.drawRect(L, y, R, y + 1.5f, fill);
        fill.setShader(null);
    }

    private void town(Canvas c, JSONObject s) {
        JSONObject h = s.optJSONObject("hunter");
        String town = at(TOWNS, s.optInt("town", -1));
        caption(c, "Resting at", L, H / 2 - 110);
        label(c, (town != null ? town : "The village").toUpperCase(Locale.ROOT), L, H / 2 - 20, 96, GOLD, semi, Paint.Align.LEFT,
              0.04f, R - L);
        hairline(c, H / 2 + 14);
        if (h == null) return;
        String weapon = at(WEAPONS, h.optInt("weapon", -1));
        label(c, h.optString("name"), L, H / 2 + 84, 46, TEXT, semi, Paint.Align.LEFT, 0.03f, 640);
        if (weapon != null) label(c, weapon.toUpperCase(Locale.ROOT), R, H / 2 + 82, 26, DIM, medium, Paint.Align.RIGHT, 0.2f, 0);
    }

    private void quest(Canvas c, JSONObject s) {
        // One compact band: the time left (a small dial and the minutes and seconds) and, on the right, where
        JSONObject time = s.optJSONObject("time");
        if (time != null) {
            int left = time.optInt("left"), limit = time.optInt("limit");
            float frac = (float) part(left, limit), cx = L + 28, cy = TOP + 42, r = 26;
            boolean late = left < 300;
            stroke.setStrokeWidth(4);
            stroke.setColor(0x22ffffff);
            c.drawCircle(cx, cy, r, stroke);
            stroke.setColor(late ? RED : GOLD);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            rect.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(rect, -90, 360 * frac, false, stroke);
            double a = Math.toRadians(360 * frac);
            stroke.setStrokeWidth(3);
            stroke.setColor(TEXT);
            c.drawLine(cx, cy, cx + (float) (17 * Math.sin(a)), cy - (float) (17 * Math.cos(a)), stroke);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            fill.setColor(TEXT);
            c.drawCircle(cx, cy, 3, fill);
            label(c, String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60), L + 76, TOP + 68, 78, late ? RED : TEXT, light,
                  Paint.Align.LEFT, -0.01f, 0);
        }
        String stage = at(STAGES, s.optInt("stage") % 100);
        int zone = s.optInt("zone", -1);
        label(c, (stage != null ? stage : "Quest " + s.optInt("quest")).toUpperCase(Locale.ROOT), R, TOP + 32, 24, SOFT, medium,
              Paint.Align.RIGHT, 0.2f, 560);
        label(c, zone == 0 ? "CAMP" : zone > 0 ? "AREA " + zone : "", R, TOP + 74, 44, GOLD, semi, Paint.Align.RIGHT, 0.04f, 0);
        hairline(c, 124);

        // The hunter
        JSONObject h = s.optJSONObject("hunter");
        if (h != null) {
            String weapon = at(WEAPONS, h.optInt("weapon", -1));
            label(c, h.optString("name"), L, 168, 36, TEXT, semi, Paint.Align.LEFT, 0.03f, 640);
            if (weapon != null) label(c, weapon.toUpperCase(Locale.ROOT), R, 166, 24, DIM, medium, Paint.Align.RIGHT, 0.2f, 0);
            float x = 204, w = 812;
            int hp = h.optInt("hp"), hpMax = h.optInt("hpMax");
            caption(c, "Health", L, 202);
            gauge(c, x, 184, w, 18, part(hp, hpMax), part(h.optInt("hpRed"), hpMax), 0xffc8452f, 0xffb9f58a, 0xff55c35a);
            value(c, String.valueOf(hp), " / " + hpMax, R, 205);
            double st = part(h.optDouble("st", 0), h.optDouble("stMax", 0));
            caption(c, "Stamina", L, 236);
            gauge(c, x, 224, w, 11, st, 0, 0, 0xffffe58a, 0xffe9b63a);
            value(c, String.valueOf(Math.round(st * 100)), " %", R, 239);
            int sharp = h.optInt("sharp"), sharpMax = h.optInt("sharpMax");
            if (sharpMax > 0) {
                caption(c, "Sharp", L, 268);
                gauge(c, x, 256, w, 11, part(sharp, sharpMax), 0, 0, 0xffd6f1ff, 0xff62b6e6);
                value(c, String.valueOf(sharp), " / " + sharpMax, R, 271);
            }
        }

        // The large monsters: a row each, shorter when there are more than two
        JSONArray monsters = s.optJSONArray("monsters");
        int n = monsters != null ? Math.min(monsters.length(), 4) : 0;
        // (room for two monsters; with more the rows get shorter, so the pouch below never moves)
        float top = 294, gap = 10, rowH = n > 0 ? Math.max(48, Math.min(92, (200 - gap * (n - 1)) / n)) : 0, k = rowH / 96;
        if (n == 0) caption(c, "No large monster in sight", L, top + 40);
        for (int i = 0; i < n; i++) {
            JSONObject m = monsters.optJSONObject(i);
            if (m == null) continue;
            float y = top + i * (rowH + gap);
            int hp = m.optInt("hp"), max = m.optInt("hpMax");
            double p = part(hp, max);
            boolean low = p <= 0.25;
            int color = low ? RED : ORANGE;
            fill.setShader(new LinearGradient(L, 0, R, 0, new int[] {alpha(color, low ? 0.2f : 0.13f), 0x08ffffff, 0x00ffffff},
                                              new float[] {0, 0.4f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(L, y, R, y + rowH, fill);
            fill.setShader(null);
            fill.setColor(color);
            c.drawRect(L, y, L + 5, y + rowH, fill);
            label(c, String.valueOf(Math.round(p * 100)), 196, y + 78 * k, 84 * k, hp > 0 ? TEXT : DIM, light, Paint.Align.RIGHT, 0, 0);
            label(c, "%", 200, y + 78 * k, 38 * k, DIM, light, Paint.Align.LEFT, 0, 0);
            String name = m.optString("name");
            label(c, (name.isEmpty() ? "Monster" : name).toUpperCase(Locale.ROOT), 253, y + 50 * k, 40 * k, hp > 0 ? TEXT : DIM, semi,
                  Paint.Align.LEFT, 0.05f, 620);
            label(c, String.format(Locale.US, "%,d / %,d", hp, max), R - 26, y + 50 * k, 26 * k, DIM, medium, Paint.Align.RIGHT, 0.06f, 0);
            gauge(c, 256, y + 70 * k, R - 26 - 256, 14 * k, p, 0, 0, low ? 0xffff8a7a : 0xffffb08a, low ? 0xffe02b20 : ORANGE);
        }

        pouch(c, s);
    }

    // ---- the item pouch

    private float cellX(int col, float shift) {
        return L + col * (CELL_W + CELL_GAP) + shift;
    }

    private float cellY(int row) {
        return GRID_TOP + row * (CELL_H + CELL_GAP);
    }

    private void pouch(Canvas c, JSONObject s) {
        JSONArray items = s.optJSONArray("items");
        int count = items != null ? items.length() : 0;
        pageCount = Math.max(1, (count + PER_PAGE - 1) / PER_PAGE);
        if (page >= pageCount) page = pageCount - 1;
        if (animating) step();
        caption(c, count == 0 ? "Item pouch  ·  nothing on the bar" : "Item pouch  ·  tap to use", L, 548);
        if (count == 0) return;
        if (pageCount > 1) {  // where we are: the page's number, and a dot for each page
            float x = R;
            x -= label(c, (page + 1) + " / " + pageCount, x, 548, 24, DIM, medium, Paint.Align.RIGHT, 0.1f, 0) + 22;
            for (int p = pageCount - 1; p >= 0; p--) {
                fill.setColor(p == page ? GOLD : 0x44ffffff);
                c.drawCircle(x - 5, 540, p == page ? 6 : 5, fill);
                x -= 20;
            }
        }
        int using = s.optInt("using", -1);
        String step = s.optString("step");
        c.save();
        c.clipRect(0, GRID_TOP - 8, W, BOTTOM + 8);  // (a page slides in from the screen's edge, not out over the rest)
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

    // One button. index >= 0: of the page shown, so a touch can find it.
    private void button(Canvas c, JSONObject it, float x, float y, int index, int using, String step) {
        float w = CELL_W, h = CELL_H, cut = 26;
        int slot = it.optInt("slot", -1);
        boolean selected = it.optBoolean("sel"), busy = slot >= 0 && slot == using, down = slot >= 0 && slot == pressed;
        if (index >= 0 && slot >= 0) {
            cells[index].set(x, y, x + w, y + h);
            cellSlots[index] = slot;
            cellCount = Math.max(cellCount, index + 1);
        }
        path.rewind();
        path.moveTo(x, y);
        path.lineTo(x + w - cut, y);
        path.lineTo(x + w, y + cut);
        path.lineTo(x + w, y + h);
        path.lineTo(x + cut, y + h);
        path.lineTo(x, y + h - cut);
        path.close();
        int tint = selected || busy ? GOLD : 0xffffffff;
        float a0 = busy ? 0.5f : selected ? 0.22f : down ? 0.22f : 0.08f, a1 = busy ? 0.3f : selected ? 0.07f : down ? 0.12f : 0.028f;
        fill.setShader(new LinearGradient(0, y, 0, y + h, alpha(tint, a0), alpha(tint, a1), Shader.TileMode.CLAMP));
        c.drawPath(path, fill);
        fill.setShader(null);
        stroke.setStrokeWidth(selected || busy || down ? 2 : 1);
        stroke.setColor(selected || busy ? GOLD : down ? 0x66ffffff : 0x26ffffff);
        c.drawPath(path, stroke);
        if (selected) {
            fill.setColor(GOLD);
            c.drawRect(x, y, x + 7, y + h - cut, fill);
        }
        name(c, it.optString("name").isEmpty() ? "Item " + it.optInt("id") : it.optString("name"), x + 24, y + 46,
             selected ? 0xffffffff : SOFT, w - 24 - 28);
        String n = String.valueOf(it.optInt("n"));
        float nw = label(c, n, x + w - 24, y + h - 22, 108, selected || busy ? GOLD : TEXT, light, Paint.Align.RIGHT, 0, 0);
        label(c, "×", x + w - 24 - nw - 6, y + h - 26, 40, DIM, light, Paint.Align.RIGHT, 0, 0);
        if (busy)  // what the one touch is doing now
            label(c, "sheathe".equals(step) ? "SHEATHING" : "wait".equals(step) ? "WAITING" : "USING", x + 24, y + h - 24, 22, GOLD,
                  medium, Paint.Align.LEFT, 0.2f, 0);
    }

    // An item's name: on one line, or on two when it does not fit (the second cut short if it still doesn't)
    private void name(Canvas c, String name, float x, float y, int color, float width) {
        float size = 34;
        text.setTextSize(size);
        text.setTypeface(medium);
        text.setLetterSpacing(0.04f);
        if (text.measureText(name) <= width) {
            label(c, name, x, y, size, color, medium, Paint.Align.LEFT, 0.04f, width);
            return;
        }
        int cut = -1;
        for (int i = 1; i < name.length(); i++) {
            if (name.charAt(i) != ' ') continue;
            text.setTextSize(size);
            text.setTypeface(medium);
            text.setLetterSpacing(0.04f);
            if (text.measureText(name.substring(0, i)) <= width) cut = i;
        }
        if (cut < 0) {
            label(c, name, x, y, size, color, medium, Paint.Align.LEFT, 0.04f, width);
            return;
        }
        label(c, name.substring(0, cut), x, y, size, color, medium, Paint.Align.LEFT, 0.04f, width);
        label(c, name.substring(cut + 1), x, y + size * 1.08f, size, color, medium, Paint.Align.LEFT, 0.04f, width);
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
                tracking = y >= POUCH_TOP && state != null && "quest".equals(state.optString("place"));
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
                if (!tracking) break;
                if (swiping) {
                    float moved = x - downX;
                    boolean quick = e.getEventTime() - downTime < FLING_MS && Math.abs(moved) >= FLING_MIN;
                    int dir = moved < 0 ? 1 : -1;
                    boolean go = (Math.abs(moved) >= SWIPE_COMMIT || quick) && page + dir >= 0 && page + dir < pageCount;
                    slideTo(go ? dir : 0);
                } else if (pressed >= 0 && slotAt(x, y) == pressed && Math.abs(x - downX) < TAP_SLOP && Math.abs(y - downY) < TAP_SLOP) {
                    Companion.useItem(pressed);
                }
                pressed = -1;
                tracking = swiping = false;
                break;
            case MotionEvent.ACTION_CANCEL:
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
