// What the companion panel draws (Companion.java): the quest's time and area, the hunter's HP, stamina and sharpness,
// the large monsters' HP and the item bar; in a town the hunter and the town. Laid out on a 1240 x 1080 sheet (the
// AYN Thor's lower screen) that is scaled to fit whatever the display is. A touch on an item of the item bar uses it
// (Companion.useItem).
// The look: a dark slate sheet, thin condensed lettering (Barlow Condensed, android/assets/fonts; the system's
// condensed face without it), slim slanted gauges with a glow, the time and the area largest.
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
    private static final float W = 1240, H = 1080, L = 52, R = W - 52, TOP = 44, BOTTOM = H - 44;
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
    // The item bar's cells as last drawn (sheet coordinates) and their pouch slots; the cell a finger is on (-1: none)
    private final RectF[] cells = new RectF[24];
    private final int[] cellSlots = new int[24];
    private int cellCount, pressed = -1;
    private float sheetScale = 1, sheetX, sheetY;

    CompanionView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        light = face(context, "Light", "sans-serif-condensed-light", Typeface.NORMAL);
        medium = face(context, "Medium", "sans-serif-condensed-medium", Typeface.NORMAL);
        semi = face(context, "SemiBold", "sans-serif-condensed", Typeface.BOLD);
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
        // The time left: a dial whose arc shrinks, and the minutes and seconds
        JSONObject time = s.optJSONObject("time");
        if (time != null) {
            int left = time.optInt("left"), limit = time.optInt("limit");
            float frac = (float) part(left, limit), cx = L + 56, cy = TOP + 88, r = 50;
            boolean late = left < 300;
            stroke.setStrokeWidth(5);
            stroke.setColor(0x22ffffff);
            c.drawCircle(cx, cy, r, stroke);
            stroke.setColor(late ? RED : GOLD);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            rect.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(rect, -90, 360 * frac, false, stroke);
            double a = Math.toRadians(360 * frac);
            stroke.setStrokeWidth(4);
            stroke.setColor(TEXT);
            c.drawLine(cx, cy, cx + (float) (34 * Math.sin(a)), cy - (float) (34 * Math.cos(a)), stroke);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            fill.setColor(TEXT);
            c.drawCircle(cx, cy, 5, fill);
            label(c, String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60), L + 138, TOP + 148, 150, late ? RED : TEXT, light,
                  Paint.Align.LEFT, -0.01f, 0);
        }
        // Where
        String stage = at(STAGES, s.optInt("stage") % 100);
        int zone = s.optInt("zone", -1);
        label(c, (stage != null ? stage : "Quest " + s.optInt("quest")).toUpperCase(Locale.ROOT), R, TOP + 62, 34, SOFT, medium,
              Paint.Align.RIGHT, 0.2f, 600);
        label(c, zone == 0 ? "CAMP" : zone > 0 ? "AREA " + zone : "", R, TOP + 141, 76, GOLD, semi, Paint.Align.RIGHT, 0.04f, 0);
        hairline(c, 220);

        // The hunter
        JSONObject h = s.optJSONObject("hunter");
        if (h != null) {
            String weapon = at(WEAPONS, h.optInt("weapon", -1));
            label(c, h.optString("name"), L, 294, 46, TEXT, semi, Paint.Align.LEFT, 0.03f, 640);
            if (weapon != null) label(c, weapon.toUpperCase(Locale.ROOT), R, 292, 26, DIM, medium, Paint.Align.RIGHT, 0.2f, 0);
            float x = 204, w = 812;
            int hp = h.optInt("hp"), hpMax = h.optInt("hpMax");
            caption(c, "Health", L, 342);
            gauge(c, x, 324, w, 20, part(hp, hpMax), part(h.optInt("hpRed"), hpMax), 0xffc8452f, 0xffb9f58a, 0xff55c35a);
            value(c, String.valueOf(hp), " / " + hpMax, R, 346);
            double st = part(h.optDouble("st", 0), h.optDouble("stMax", 0));
            caption(c, "Stamina", L, 392);
            gauge(c, x, 378, w, 12, st, 0, 0, 0xffffe58a, 0xffe9b63a);
            value(c, String.valueOf(Math.round(st * 100)), " %", R, 396);
            int sharp = h.optInt("sharp"), sharpMax = h.optInt("sharpMax");
            if (sharpMax > 0) {
                caption(c, "Sharp", L, 442);
                gauge(c, x, 428, w, 12, part(sharp, sharpMax), 0, 0, 0xffd6f1ff, 0xff62b6e6);
                value(c, String.valueOf(sharp), " / " + sharpMax, R, 446);
            }
        }

        // The large monsters: a row each, smaller when there are more than two
        JSONArray monsters = s.optJSONArray("monsters");
        int n = monsters != null ? Math.min(monsters.length(), 4) : 0;
        float top = 478, gap = 12, rowH = n > 0 ? Math.min(96, (262 - gap * (n - 1)) / n) : 0, k = rowH / 96;
        if (n == 0) caption(c, "No large monster in sight", L, top + 50);
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

        // The item bar: cells with two corners cut; the one in hand in gold
        JSONArray items = s.optJSONArray("items");
        int count = items != null ? Math.min(items.length(), cells.length) : 0;
        if (count == 0) return;
        caption(c, "Item pouch  ·  tap to use", L, 776);
        int cols = count <= 8 ? 4 : count <= 15 ? 5 : 6, lines = (count + cols - 1) / cols;
        float cellGap = 16, gridTop = 796, cw = (R - L - cellGap * (cols - 1)) / cols;
        float ch = Math.min(112, (BOTTOM - gridTop - cellGap * (lines - 1)) / lines), ks = Math.min(1, Math.min(ch / 112, cw / 272));
        int using = s.optInt("using", -1);
        for (int i = 0; i < count; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            float x = L + (i % cols) * (cw + cellGap), y = gridTop + (i / cols) * (ch + cellGap), cut = 22 * ks;
            int slot = it.optInt("slot", -1);
            boolean selected = it.optBoolean("sel"), busy = slot >= 0 && slot == using, down = slot >= 0 && slot == pressed;
            if (slot >= 0) {
                if (cells[cellCount] == null) cells[cellCount] = new RectF();
                cells[cellCount].set(x, y, x + cw, y + ch);
                cellSlots[cellCount++] = slot;
            }
            path.rewind();
            path.moveTo(x, y);
            path.lineTo(x + cw - cut, y);
            path.lineTo(x + cw, y + cut);
            path.lineTo(x + cw, y + ch);
            path.lineTo(x + cut, y + ch);
            path.lineTo(x, y + ch - cut);
            path.close();
            int tint = selected || busy ? GOLD : 0xffffffff;
            float a0 = busy ? 0.5f : selected ? 0.25f : down ? 0.2f : 0.07f, a1 = busy ? 0.3f : selected ? 0.08f : down ? 0.1f : 0.024f;
            fill.setShader(new LinearGradient(0, y, 0, y + ch, alpha(tint, a0), alpha(tint, a1), Shader.TileMode.CLAMP));
            c.drawPath(path, fill);
            fill.setShader(null);
            stroke.setStrokeWidth(selected ? 1.5f : 1);
            stroke.setColor(selected || busy ? GOLD : 0x22ffffff);
            c.drawPath(path, stroke);
            if (selected) {
                fill.setColor(GOLD);
                c.drawRect(x, y, x + 6, y + ch - cut, fill);
            }
            // (the count first: in a low cell the name stops short of it)
            float kt = Math.max(ks, 0.64f), numSize = Math.min(66 * kt, ch * 0.62f);
            float nw = label(c, String.valueOf(it.optInt("n")), x + cw - 22 * ks, y + ch - 14 * ks, numSize, selected ? GOLD : TEXT, light,
                             Paint.Align.RIGHT, 0, 0);
            label(c, "\u00d7", x + cw - 22 * ks - nw - 4, y + ch - 16 * ks, 30 * kt, DIM, light, Paint.Align.RIGHT, 0, 0);
            String name = it.optString("name");
            float nameWidth = cw - 20 * ks - (ch < 80 ? nw + 30 * kt + 22 * ks : 20 * ks);
            label(c, name.isEmpty() ? "Item " + it.optInt("id") : name, x + 20 * ks, y + Math.max(40 * ks, 30 * kt * 0.95f), 30 * kt,
                  selected ? 0xffffffff : SOFT, medium, Paint.Align.LEFT, 0.04f, nameWidth);
        }
    }

    // The pouch slot of the item bar's cell at a point of the view, -1 when there is none.
    private int slotAt(float vx, float vy) {
        float x = (vx - sheetX) / sheetScale, y = (vy - sheetY) / sheetScale;
        for (int i = 0; i < cellCount; i++)
            if (cells[i].contains(x, y)) return cellSlots[i];
        return -1;
    }

    // A tap on an item (down and up on the same one) uses it.
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int was = pressed;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = slotAt(e.getX(), e.getY());
                break;
            case MotionEvent.ACTION_MOVE:
                if (pressed >= 0 && slotAt(e.getX(), e.getY()) != pressed) pressed = -1;
                break;
            case MotionEvent.ACTION_UP:
                if (pressed >= 0 && slotAt(e.getX(), e.getY()) == pressed) Companion.useItem(pressed);
                pressed = -1;
                break;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                break;
            default:
                break;
        }
        if (pressed != was) invalidate();
        return true;
    }
}
