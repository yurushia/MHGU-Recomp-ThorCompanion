// What the companion panel draws (Companion.java): the quest's time and area, the hunter's HP, stamina and sharpness,
// the large monsters' HP and the item bar; in a town the hunter and the town. Laid out on a 1240 x 1080 sheet (the
// AYN Thor's lower screen) that is scaled to fit whatever the display is. Display only: touches do nothing yet.
package io.github.morerokk.mhgurecomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class CompanionView extends View {
    private static final float W = 1240, H = 1080, M = 40;
    private static final int BG = 0xff14161a, CARD = 0xff1f2228, TRACK = 0xff2c3038, TEXT = 0xfff2efe6, DIM = 0xff9a9ca3,
            ACCENT = 0xffc58a2e, GREEN = 0xff58b368, YELLOW = 0xffe0b93b, RED = 0xffd2553f, HP_RED = 0xff9c3a2e,
            STAMINA = 0xffe6c84a, SHARP = 0xff6fb7d9;
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
    private final RectF r = new RectF();
    private String last = "";
    private JSONObject state;

    CompanionView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
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

    private void label(Canvas c, String s, float x, float y, float size, int color, boolean bold, Paint.Align align, float width) {
        if (s == null) return;
        text.setTextSize(size);
        text.setColor(color);
        text.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        text.setTextAlign(align);
        if (width > 0) s = TextUtils.ellipsize(s, text, width, TextUtils.TruncateAt.END).toString();
        c.drawText(s, x, y, text);
    }

    private void box(Canvas c, float x, float y, float w, float h, float radius, int color) {
        fill.setColor(color);
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, radius, radius, fill);
    }

    // A bar: its track, an optional part behind the value (the HP's red part), the value.
    private void bar(Canvas c, float x, float y, float w, float h, double value, double behind, int color, int behindColor) {
        box(c, x, y, w, h, h / 2, TRACK);
        c.save();
        r.set(x, y, x + w, y + h);
        c.clipRect(r);
        if (behind > 0) box(c, x, y, (float) (w * Math.min(1, behind)), h, h / 2, behindColor);
        if (value > 0) box(c, x, y, (float) (w * Math.min(1, value)), h, h / 2, color);
        c.restore();
    }

    private static double part(double value, double max) {
        return max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(BG);
        float scale = Math.min(getWidth() / W, getHeight() / H);
        if (scale <= 0) return;
        c.translate((getWidth() - W * scale) / 2, (getHeight() - H * scale) / 2);
        c.scale(scale, scale);
        JSONObject s = state;
        String place = s != null ? s.optString("place") : "";
        if ("quest".equals(place)) quest(c, s);
        else if ("town".equals(place)) town(c, s);
        else label(c, "MHGU Recomp", W / 2, H / 2, 48, DIM, true, Paint.Align.CENTER, 0);
    }

    private void town(Canvas c, JSONObject s) {
        JSONObject h = s.optJSONObject("hunter");
        String town = at(TOWNS, s.optInt("town", -1));
        label(c, town != null ? town : "In a village", W / 2, H / 2 - 30, 72, TEXT, true, Paint.Align.CENTER, W - 2 * M);
        if (h == null) return;
        String weapon = at(WEAPONS, h.optInt("weapon", -1));
        label(c, h.optString("name") + (weapon != null ? "  -  " + weapon : ""), W / 2, H / 2 + 50, 40, DIM, false,
              Paint.Align.CENTER, W - 2 * M);
    }

    private void quest(Canvas c, JSONObject s) {
        // The time left and where
        JSONObject time = s.optJSONObject("time");
        if (time != null) {
            int left = time.optInt("left");
            label(c, String.format("%d:%02d", left / 60, left % 60), M, 132, 110, left < 300 ? RED : TEXT, true, Paint.Align.LEFT, 0);
            label(c, "TIME LEFT", M + 4, 172, 26, DIM, true, Paint.Align.LEFT, 0);
        }
        String stage = at(STAGES, s.optInt("stage") % 100);
        int zone = s.optInt("zone", -1);
        label(c, stage != null ? stage : "Quest " + s.optInt("quest"), W - M, 96, 46, TEXT, true, Paint.Align.RIGHT, 640);
        label(c, zone == 0 ? "Camp" : zone > 0 ? "Area " + zone : "", W - M, 148, 38, ACCENT, true, Paint.Align.RIGHT, 0);

        // The hunter
        JSONObject h = s.optJSONObject("hunter");
        if (h != null) {
            float y = 210;
            box(c, M, y, W - 2 * M, 210, 24, CARD);
            String weapon = at(WEAPONS, h.optInt("weapon", -1));
            label(c, h.optString("name"), M + 28, y + 48, 34, TEXT, true, Paint.Align.LEFT, 560);
            label(c, weapon, W - M - 28, y + 48, 30, DIM, false, Paint.Align.RIGHT, 500);
            float x = M + 150, w = W - 2 * M - 150 - 190;
            int hp = h.optInt("hp"), hpMax = h.optInt("hpMax");
            label(c, "HP", M + 28, y + 100, 28, DIM, true, Paint.Align.LEFT, 0);
            bar(c, x, y + 74, w, 34, part(hp, hpMax), part(h.optInt("hpRed"), hpMax), GREEN, HP_RED);
            label(c, hp + " / " + hpMax, W - M - 28, y + 101, 30, TEXT, true, Paint.Align.RIGHT, 0);
            double st = h.optDouble("st", 0), stMax = h.optDouble("stMax", 0);
            label(c, "Stamina", M + 28, y + 146, 24, DIM, true, Paint.Align.LEFT, 0);
            bar(c, x, y + 126, w, 24, part(st, stMax), 0, STAMINA, 0);
            label(c, Math.round(part(st, stMax) * 100) + "%", W - M - 28, y + 147, 26, DIM, false, Paint.Align.RIGHT, 0);
            int sharp = h.optInt("sharp"), sharpMax = h.optInt("sharpMax");
            if (sharpMax > 0) {
                label(c, "Sharp", M + 28, y + 186, 24, DIM, true, Paint.Align.LEFT, 0);
                bar(c, x, y + 168, w, 20, part(sharp, sharpMax), 0, SHARP, 0);
                label(c, sharp + " / " + sharpMax, W - M - 28, y + 187, 26, DIM, false, Paint.Align.RIGHT, 0);
            }
        }

        // The large monsters
        JSONArray monsters = s.optJSONArray("monsters");
        int n = monsters != null ? Math.min(monsters.length(), 4) : 0;
        float top = 448, rows = 290;
        if (n == 0) label(c, "No large monster in sight", M + 4, top + 60, 30, DIM, false, Paint.Align.LEFT, 0);
        float rowH = n > 0 ? Math.min(100, rows / n) : 0;
        for (int i = 0; i < n; i++) {
            JSONObject m = monsters.optJSONObject(i);
            if (m == null) continue;
            float y = top + i * rowH;
            int hp = m.optInt("hp"), max = m.optInt("hpMax");
            double p = part(hp, max);
            String name = m.optString("name");
            label(c, name.isEmpty() ? "Monster" : name, M + 4, y + 34, 34, hp > 0 ? TEXT : DIM, true, Paint.Align.LEFT, 620);
            label(c, String.format("%,d / %,d", hp, max), W - M - 110, y + 34, 30, hp > 0 ? TEXT : DIM, false, Paint.Align.RIGHT, 0);
            label(c, Math.round(p * 100) + "%", W - M - 4, y + 34, 30, DIM, true, Paint.Align.RIGHT, 0);
            bar(c, M, y + 48, W - 2 * M, Math.min(28, rowH - 62), p, 0, p > 0.5 ? GREEN : p > 0.25 ? YELLOW : RED, 0);
        }

        // The item bar
        JSONArray items = s.optJSONArray("items");
        int count = items != null ? items.length() : 0;
        if (count == 0) return;
        int cols = count <= 8 ? 4 : count <= 15 ? 5 : 6, lines = (count + cols - 1) / cols;
        float gap = 16, gridTop = 760, gridH = H - M - gridTop;
        float cw = (W - 2 * M - gap * (cols - 1)) / cols, ch = Math.min(150, (gridH - gap * (lines - 1)) / lines);
        for (int i = 0; i < count; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            float x = M + (i % cols) * (cw + gap), y = gridTop + (i / cols) * (ch + gap);
            boolean selected = it.optBoolean("sel");
            box(c, x, y, cw, ch, 18, CARD);
            if (selected) {
                stroke.setColor(ACCENT);
                stroke.setStrokeWidth(5);
                r.set(x + 2.5f, y + 2.5f, x + cw - 2.5f, y + ch - 2.5f);
                c.drawRoundRect(r, 16, 16, stroke);
            }
            String name = it.optString("name");
            float small = Math.min(26, ch * 0.2f), big = Math.min(46, ch * 0.36f);
            label(c, name.isEmpty() ? "Item " + it.optInt("id") : name, x + 18, y + ch * 0.34f, small, selected ? TEXT : DIM, true,
                  Paint.Align.LEFT, cw - 36);
            label(c, "x" + it.optInt("n"), x + cw - 18, y + ch - ch * 0.16f, big, TEXT, true, Paint.Align.RIGHT, 0);
        }
    }
}
