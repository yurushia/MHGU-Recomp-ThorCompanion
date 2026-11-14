// The companion panel on a second screen (AYN Thor: the lower one): a Presentation of this activity on the other
// display, showing what runtime/hle/companion.c reads from the game (the quest's time, the hunter's bars, the large
// monsters' HP and positions, the item bar); a touch on an item uses it. With one display nothing happens. The panel's window never
// takes the focus, so the controller's and the keyboard's events stay with the game.
package io.github.morerokk.mhgurecomp;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import java.nio.charset.StandardCharsets;

final class Companion implements DisplayManager.DisplayListener {
    // runtime/hle/companion.c: the state as JSON (UTF-8), null before the first one
    private static native byte[] snapshot();
    // Use the item in a pouch slot as soon as the hunter may (a touch on it: CompanionView)
    private static native void use(int slot);

    static void useItem(int slot) {
        try {
            use(slot);
        } catch (UnsatisfiedLinkError e) {  // (the runtime isn't loaded)
        }
    }

    private static final long POLL_MS = 100;

    private final Activity activity;
    private final DisplayManager displays;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Presentation shown;
    private CompanionView view;
    private boolean started;

    Companion(Activity activity) {
        this.activity = activity;
        displays = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    // The activity's onStart / onStop
    void start() {
        if (started || displays == null) return;
        started = true;
        // where the game's files are (the map of an area is read from them: AreaMap)
        java.io.File files = FilesFolder.get(activity);
        AreaMap.root = files != null ? new java.io.File(files, "data/romfs/nativeNX") : null;
        displays.registerDisplayListener(this, handler);
        show();
        handler.postDelayed(poll, POLL_MS);
    }

    void stop() {
        if (!started) return;
        started = false;
        handler.removeCallbacks(poll);
        displays.unregisterDisplayListener(this);
        hide();
    }

    // The display the game isn't on: one meant for presentations, else any other one.
    private Display other() {
        Display own = activity.getWindowManager().getDefaultDisplay();
        int ownId = own != null ? own.getDisplayId() : Display.DEFAULT_DISPLAY;
        for (Display d : displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION))
            if (d.getDisplayId() != ownId) return d;
        for (Display d : displays.getDisplays())
            if (d.getDisplayId() != ownId && d.getState() != Display.STATE_OFF) return d;
        return null;
    }

    private void show() {
        Display d = other();
        if (shown != null && (d == null || shown.getDisplay().getDisplayId() != d.getDisplayId())) hide();
        if (d == null || shown != null || activity.isFinishing()) return;
        final CompanionView v = new CompanionView(activity);
        Presentation p = new Presentation(activity, d) {
            @Override
            protected void onCreate(Bundle saved) {
                super.onCreate(saved);
                Window w = getWindow();
                if (w != null) w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                setContentView(v);
            }
        };
        try {
            p.show();
        } catch (WindowManager.InvalidDisplayException e) {  // (the display went away meanwhile)
            return;
        }
        shown = p;
        view = v;
    }

    private void hide() {
        if (shown != null) shown.dismiss();
        shown = null;
        view = null;
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            if (view != null) {
                byte[] json = null;
                try {
                    json = snapshot();
                } catch (UnsatisfiedLinkError e) {  // (the runtime isn't loaded yet: SDL loads it when the game starts)
                }
                if (json != null) view.update(new String(json, StandardCharsets.UTF_8));
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    public void onDisplayAdded(int id) {
        if (started) show();
    }

    @Override
    public void onDisplayRemoved(int id) {
        if (started) show();
    }

    @Override
    public void onDisplayChanged(int id) {
    }
}
