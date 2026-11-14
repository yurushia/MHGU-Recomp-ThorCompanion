package io.github.morerokk.mhgurecomp;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws CompanionView on a PC (through stand-ins for Android's drawing classes) and drives it with touches: what a tap, a swipe
 * and the rest do to the item pouch. Writes the pictures too. args: the fonts' folder (android/assets), the output folder.
 */
public class Check {
    static int failed;
    static CompanionView view;
    static long now = 1000;

    static void check(boolean ok, String what) {
        if (!ok) { failed++; System.err.println("FAIL: " + what); }
    }

    static String items(int n, int firstSlot) {
        String[] names = {"Potion", "Mega Potion", "Whetstone", "Cool Drink", "Paintball", "Flash Bomb", "Shock Trap", "Tranq Bomb",
            "Max Potion", "Ancient Potion", "Dash Juice", "Demondrug", "Armorskin", "Lifepowder", "Dung Bomb", "Large Barrel Bomb+"};
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < n; i++)
            b.append(i > 0 ? "," : "").append("{\"slot\":").append(firstSlot + i).append(",\"id\":1,\"n\":").append(i + 1)
             .append(",\"name\":\"").append(names[i % names.length]).append("\",\"sel\":").append(i == 0).append("}");
        return b.append("]").toString();
    }

    static String quest(int itemCount, String extra) {
        return "{\"v\":1,\"place\":\"quest\",\"quest\":101,\"stage\":1,\"zone\":4,\"time\":{\"left\":2537,\"limit\":3000},"
            + "\"hunter\":{\"name\":\"Theo\",\"weapon\":7,\"hp\":132,\"hpMax\":150,\"hpRed\":140,\"st\":720.0,\"stMax\":900.0,\"sharp\":310,\"sharpMax\":450,\"x\":0.0,\"y\":0.0,\"z\":0.0},"
            + "\"monsters\":[{\"id\":2,\"name\":\"Rathalos\",\"hp\":3120,\"hpMax\":4480,\"x\":0.0,\"y\":0.0,\"z\":0.0},{\"id\":73,\"name\":\"Great Maccao\",\"hp\":310,\"hpMax\":1392,\"x\":0.0,\"y\":0.0,\"z\":0.0}],"
            + "\"items\":" + items(itemCount, 10) + extra + "}";
    }

    static BufferedImage draw() {
        BufferedImage img = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_ARGB);
        view.draw(new Canvas(img.createGraphics()));
        return img;
    }

    static void save(BufferedImage img, String dir, String name) throws Exception {
        javax.imageio.ImageIO.write(img, "png", new File(dir, name));
    }

    static void touch(int action, float x, float y, long dt) {
        now += dt;
        view.onTouchEvent(MotionEvent.obtain(0, now, action, x, y, 0));
    }

    static void tap(float x, float y) { touch(MotionEvent.ACTION_DOWN, x, y, 0); touch(MotionEvent.ACTION_UP, x, y, 60); }

    /** A drag from (x0, y) to (x1, y) in steps, taking ms in all; releases when done. */
    static void swipe(float x0, float x1, float y, long ms, boolean release) {
        touch(MotionEvent.ACTION_DOWN, x0, y, 0);
        for (int i = 1; i <= 8; i++) { touch(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / 8, y + (i % 2), ms / 8); draw(); }
        if (release) touch(MotionEvent.ACTION_UP, x1, y, 10);
    }

    /** Lets the slide to a page run out. */
    static void settle() throws Exception {
        for (int i = 0; i < 30; i++) { Thread.sleep(20); draw(); }
    }

    // The sheet is drawn 1:1 in a 1240 x 1080 view: view and sheet coordinates are the same. Button i of a page:
    static float cx(int i) { return 52 + (i % 4) * (272 + 16) + 136; }
    static float cy(int i) { return 566 + (i / 4) * (227 + 16) + 110; }

    static void fresh(String json) {
        Companion.used.clear();
        view.update(json);
        draw();
    }

    public static void main(String[] a) throws Exception {
        android.content.res.AssetManager.root = a[0];
        view = new CompanionView(new Context());
        view.w = 1240; view.h = 1080;
        String out = a[1];
        new File(out).mkdirs();

        // ---- 16 items: eight to a page
        fresh(quest(16, ""));
        check(view.page() == 0, "starts on page 1");
        save(draw(), out, "quest-page1.png");

        // a tap uses the item under the finger: row 2, column 3 of page 1 is the 7th item (slot 16)
        tap(cx(6), cy(6));
        check(Companion.used.size() == 1 && Companion.used.get(0) == 16, "a tap on button 7 uses slot 16, got " + Companion.used);
        tap(cx(0) + 9, cy(0) - 7);  // not exactly in the middle
        check(Companion.used.size() == 2 && Companion.used.get(1) == 10, "a tap near the middle of button 1 uses slot 10");
        // a tap that wanders a little (a finger's jitter) is still a tap
        touch(MotionEvent.ACTION_DOWN, cx(2), cy(2), 0); touch(MotionEvent.ACTION_MOVE, cx(2) + 9, cy(2) + 6, 20); touch(MotionEvent.ACTION_UP, cx(2) + 9, cy(2) + 6, 20);
        check(Companion.used.size() == 3 && Companion.used.get(2) == 12, "a tap with a little jitter still uses the item");
        // taps outside the pouch (the monsters, the header) and in the gaps between buttons use nothing
        int before = Companion.used.size();
        tap(600, 330); tap(600, 60); tap(52 + 272 + 8, cy(0)); tap(cx(0), 566 + 227 + 8);
        check(Companion.used.size() == before, "taps outside the buttons use nothing");

        // ---- swiping
        Companion.used.clear();
        swipe(900, 560, 780, 220, true);  // starts on a button, goes left
        settle();
        check(view.page() == 1, "a swipe left turns to page 2, page is " + view.page());
        check(Companion.used.isEmpty(), "a swipe uses nothing, used " + Companion.used);
        save(draw(), out, "quest-page2.png");
        tap(cx(3), cy(3));  // page 2, button 4 is the 12th item: slot 21
        check(Companion.used.size() == 1 && Companion.used.get(0) == 21, "a tap on page 2 button 4 uses slot 21, got " + Companion.used);
        Companion.used.clear();
        swipe(300, 700, 700, 200, true);  // right, from the gap above the buttons
        settle();
        check(view.page() == 0 && Companion.used.isEmpty(), "a swipe right returns to page 1 and uses nothing");

        // not far and not fast: stays; and a swipe past the last page stays too
        swipe(700, 650, 800, 600, true);
        settle();
        check(view.page() == 0 && Companion.used.isEmpty(), "a short slow swipe turns no page and uses nothing");
        swipe(300, 800, 800, 200, true);  // right on the first page: nothing before it
        settle();
        check(view.page() == 0 && Companion.used.isEmpty(), "no page before the first");
        swipe(900, 500, 800, 200, true);
        settle();
        swipe(900, 300, 800, 200, true);
        settle();
        check(view.page() == 1 && Companion.used.isEmpty(), "no page after the last");
        // a quick flick
        swipe(700, 800, 800, 120, true);  // right, 100 px in 120 ms
        settle();
        check(view.page() == 0 && swipeUsedNothing(), "a quick flick right turns the page");
        // a swipe that ends where it started is not a tap either
        swipe(cx(5), cx(5) + 120, cy(5), 300, false);
        touch(MotionEvent.ACTION_MOVE, cx(5), cy(5), 40);
        touch(MotionEvent.ACTION_UP, cx(5), cy(5), 10);
        settle();
        check(Companion.used.isEmpty(), "a drag that returns to its button uses nothing");
        // a finger that slides down over a button: no use
        touch(MotionEvent.ACTION_DOWN, cx(1), cy(1), 0);
        touch(MotionEvent.ACTION_MOVE, cx(1) + 5, cy(1) + 60, 50);
        touch(MotionEvent.ACTION_UP, cx(1) + 5, cy(1) + 60, 20);
        check(Companion.used.isEmpty(), "a vertical drag uses nothing");
        // a cancelled touch uses nothing
        touch(MotionEvent.ACTION_DOWN, cx(1), cy(1), 0); touch(MotionEvent.ACTION_CANCEL, cx(1), cy(1), 30);
        check(Companion.used.isEmpty(), "a cancelled touch uses nothing");

        // ---- page sizes: every button the same size on every page
        view.update(quest(11, ""));
        draw();
        check(view.page() == 0, "page stays valid");
        swipe(900, 500, 800, 200, true);
        settle();
        check(view.page() == 1, "11 items are two pages");
        save(draw(), out, "quest-page2-partial.png");
        tap(cx(2), cy(2));  // page 2 has three buttons (items 9-11): the third is item 11, slot 20
        check(Companion.used.size() == 1 && Companion.used.get(0) == 20, "a tap on the partial page uses slot 20, got " + Companion.used);
        Companion.used.clear();
        tap(cx(3), cy(3));  // no fourth button on that page
        check(Companion.used.isEmpty(), "an empty place uses nothing");

        // ---- the list shrinks to one page while on page 2: back to page 1
        view.update(quest(5, ""));
        draw();
        check(view.page() == 0, "the page is clamped when there are fewer items");

        // ---- eight items are one page, with nothing to swipe to
        fresh(quest(8, ""));
        swipe(900, 400, 800, 200, true);
        settle();
        check(view.page() == 0 && Companion.used.isEmpty(), "8 items are one page");
        save(draw(), out, "quest-8.png");

        // ---- an item being used: its button says what the touch is doing
        fresh(quest(8, ",\"using\":13,\"step\":\"sheathe\""));
        save(draw(), out, "quest-sheathing.png");
        fresh(quest(16, ""));
        fresh("{\"v\":1,\"place\":\"town\",\"town\":1,\"hunter\":{\"name\":\"Theo\",\"weapon\":7,\"x\":0.0,\"y\":0.0,\"z\":0.0}}");
        tap(cx(0), cy(0));
        check(Companion.used.isEmpty(), "no item can be used in a town");
        save(draw(), out, "town.png");

        // ---- four large monsters: the rows get shorter, the pouch stays where it is
        String q8 = quest(16, ""), mons = "\"monsters\":[";
        String[] who = {"Rathalos", "Great Maccao", "Bloodbath Diablos", "Silver Rathalos"};
        for (int i = 0; i < 4; i++)
            mons += (i > 0 ? "," : "") + "{\"id\":2,\"name\":\"" + who[i] + "\",\"hp\":" + (3000 - i * 900) + ",\"hpMax\":4480,\"x\":0.0,\"y\":0.0,\"z\":0.0}";
        fresh(q8.substring(0, q8.indexOf("\"monsters\":")) + mons + "]," + q8.substring(q8.indexOf("\"items\":")));
        save(draw(), out, "quest-4-monsters.png");
        tap(cx(0), cy(0));
        check(Companion.used.size() == 1 && Companion.used.get(0) == 10, "the pouch is where it was with four monsters");

        // ---- the display's own size: the same sheet scaled
        view.w = 620; view.h = 540;
        fresh(quest(16, ""));
        tap(52 / 2f + 136 / 2f, (566 + 110) / 2f);  // button 1 at half size
        check(Companion.used.size() == 1 && Companion.used.get(0) == 10, "touch coordinates follow the scale, got " + Companion.used);
        view.w = 1240; view.h = 1080;

        if (failed > 0) { System.err.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("test-companion-view: all passed");
    }

    static boolean swipeUsedNothing() { return Companion.used.isEmpty(); }
}
