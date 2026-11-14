// The "Update now" button (Settings > Android): looks up the newest release of this fork on GitHub (UpdateInfo.java), and if it
// is newer than the installed app, downloads its APK straight into a PackageInstaller session and commits it. Android
// replaces the app in place, so the game files, saves and settings in its folder stay (it only does that for an APK signed with
// the same key as the installed one: the releases are, make PLATFORM=android thor-release).
//
// Runs inside ImportActivity (its mode UPDATE), which the native side starts like the file imports and waits for: this shows
// the dialogs, and tells it when it is done.
// What Android asks of the player, and no more:
// - "Install unknown apps" for this app, once (a settings page, opened from here, with the switch to flip; the download
//   starts when the player comes back).
// - From Android 12 on, an update of an app this app installed itself needs no confirmation (USER_ACTION_NOT_REQUIRED). The
//   first update of an app installed some other way (the APK opened from a file manager, adb) shows Android's own "Install?"
//   prompt, which UpdateReceiver brings up; after that one it is Android's own routine to ask once more at most.
// - The app is closed by Android when the update goes in: the player starts it again.
package io.github.morerokk.mhgurecomp;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.Locale;

final class Updater {
    private static final int SETTINGS = 100;  // (ImportActivity's request code for Android's settings page)
    private static volatile Updater current;

    private final ImportActivity act;
    private AlertDialog dialog;
    private volatile boolean cancelled;
    private UpdateInfo info;
    private volatile int sessionId = -1;

    Updater(ImportActivity act) {
        this.act = act;
    }

    void start() {
        current = this;
        check();
    }

    // ---- the dialogs (no lambdas: the SDK's android.jar has no LambdaMetafactory for javac)

    private AlertDialog.Builder box(String title) {
        return new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setCancelable(false).setTitle(title);
    }

    private void show(AlertDialog d) {
        if (act.isFinishing()) return;
        if (dialog != null) dialog.dismiss();
        dialog = d;
        d.show();
    }

    private void ui(Runnable r) {
        act.runOnUiThread(r);
    }

    private void say(final String text) {
        ui(new Runnable() {
            @Override public void run() { if (dialog != null) dialog.setMessage(text); }
        });
    }

    private void finish(int how) {
        current = null;
        if (dialog != null) dialog.dismiss();
        act.end(how);
    }

    private DialogInterface.OnClickListener closeWith(final int how) {
        return new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { finish(how); }
        };
    }

    // "Cancel" while checking or downloading
    private DialogInterface.OnClickListener cancel() {
        return new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                cancelled = true;
                abandon();
                finish(ImportActivity.CANCELLED);
            }
        };
    }

    private void abandon() {
        int id = sessionId;
        if (id < 0) return;
        sessionId = -1;
        try {
            act.getPackageManager().getPackageInstaller().abandonSession(id);
        } catch (RuntimeException e) {
            // (already finished)
        }
    }

    private void fail(String text, boolean retry) {
        AlertDialog.Builder b = box("Update failed").setMessage(text);
        if (retry) {
            b.setPositiveButton("Try again", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { check(); }
            }).setNegativeButton("Close", closeWith(ImportActivity.FAILED));
        } else {
            b.setPositiveButton("OK", closeWith(ImportActivity.FAILED));
        }
        show(b.create());
    }

    // ---- what is installed, and what is the newest

    private long installedBuild() {
        try {
            PackageInfo p = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String installedName() {
        try {
            String n = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
            return n != null ? n : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private void check() {
        cancelled = false;
        show(box("Update").setMessage("Checking for a new version...").setNegativeButton("Cancel", cancel()).create());
        new Thread(new Runnable() {
            @Override public void run() {
                UpdateInfo found = null;
                String error = null;
                try {
                    found = UpdateInfo.parse(get(UpdateInfo.LATEST));
                } catch (Exception e) {
                    error = explain(e);
                }
                final UpdateInfo release = found;
                final String err = error;
                ui(new Runnable() {
                    @Override public void run() {
                        if (cancelled) return;
                        if (err != null) fail(err, true);
                        else found(release);
                    }
                });
            }
        }, "update-check").start();
    }

    private static String explain(Exception e) {
        if (e instanceof UnknownHostException || e instanceof java.net.ConnectException || e instanceof SocketTimeoutException)
            return "Couldn't reach GitHub. Check this device's internet connection and try again.";
        if (e instanceof FileNotFoundException) return "No release has been published for this app yet.";
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "MHGURecompThor-Updater");
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        return c;
    }

    // A small text from GitHub
    private String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new FileNotFoundException(url);
            if (code == 403 || code == 429) throw new IOException("GitHub is limiting requests from this network right now. Try again in a few minutes.");
            if (code != 200) throw new IOException("GitHub answered " + code + ".");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try (InputStream in = c.getInputStream()) {
                for (int n; (n = in.read(buf)) > 0;) {
                    out.write(buf, 0, n);
                    if (out.size() > (2 << 20)) throw new IOException("GitHub's answer was too big.");
                }
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void found(UpdateInfo release) {
        info = release;
        long mine = installedBuild();
        String installed = "Installed: build " + mine + (installedName().isEmpty() ? "" : " (" + installedName() + ")");
        if (!release.newerThan(mine)) {
            show(box("Up to date").setMessage("You have the latest version.\n\n" + installed + "\nLatest release: build " + release.build
                                              + " (" + release.tag + ")")
                 .setPositiveButton("OK", closeWith(ImportActivity.DONE)).create());
            return;
        }
        String notes = release.notes.trim();
        if (notes.length() > 600) notes = notes.substring(0, 600) + "...";
        String text = "A new version is available.\n\n" + installed + "\nNew: build " + release.build + " (" + release.title + ")"
            + (release.size > 0 ? "\nDownload: " + mb(release.size) : "") + (notes.isEmpty() ? "" : "\n\n" + notes)
            + "\n\nYour game files, settings and saves are kept. The app closes when the update goes in: save your game first, "
            + "then start the app again.";
        show(box("Update available").setMessage(text)
             .setPositiveButton("Update now", new DialogInterface.OnClickListener() {
                 @Override public void onClick(DialogInterface d, int w) { permission(); }
             }).setNegativeButton("Later", closeWith(ImportActivity.CANCELLED)).create());
    }

    // ---- the permission to install

    private void permission() {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            show(box("Allow installing updates")
                 .setMessage("Android needs your permission for this app to install updates. Tap Open settings, switch on \"Allow from this "
                             + "source\", then press Back to return here: the update carries on by itself.")
                 .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                     @Override public void onClick(DialogInterface d, int w) {
                         try {
                             act.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                                   Uri.parse("package:" + act.getPackageName())), SETTINGS);
                         } catch (Exception e) {
                             fail("This device has no settings page for that (" + e + "). Allow installing apps from this app in Android's "
                                  + "settings (Apps > this app > Install unknown apps), then try again.", false);
                         }
                     }
                 }).setNegativeButton("Cancel", closeWith(ImportActivity.CANCELLED)).create());
            return;
        }
        download();
    }

    /** ImportActivity's onActivityResult: true when the request was this one's. */
    boolean onResult(int request) {
        if (request != SETTINGS) return false;
        if (Build.VERSION.SDK_INT < 26 || act.getPackageManager().canRequestPackageInstalls()) download();
        else fail("Without that permission the update can't be installed. Nothing was changed.", false);
        return true;
    }

    // ---- the download, into the installer's session as it arrives (no copy of the APK is kept on the device)

    private void download() {
        cancelled = false;
        show(box("Downloading update").setMessage("Starting...").setNegativeButton("Cancel", cancel()).create());
        new Thread(new Runnable() {
            @Override public void run() {
                String error = null;
                try {
                    transfer();
                } catch (Exception e) {
                    if (!cancelled) error = e instanceof IOException || e instanceof RuntimeException ? explain(e) : e.toString();
                }
                abandonIfFailed(error != null || cancelled);
                final String err = error;
                if (err != null) ui(new Runnable() {
                    @Override public void run() { fail("The download didn't finish: " + err, true); }
                });
            }
        }, "update-download").start();
    }

    private void abandonIfFailed(boolean failed) {
        if (failed) abandon();
    }

    private void transfer() throws Exception {
        UpdateInfo u = info;
        HttpURLConnection c = open(u.url);
        PackageInstaller installer = act.getPackageManager().getPackageInstaller();
        try {
            int code = c.getResponseCode();  // (GitHub's download address redirects to its file storage: followed)
            if (code != 200) throw new IOException("The download address answered " + code + ".");
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : u.size;
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(act.getPackageName());
            if (total > 0) p.setSize(total);
            if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            sessionId = installer.createSession(p);
            PackageInstaller.Session session = installer.openSession(sessionId);
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("mhgu-update.apk", 0, total > 0 ? total : -1)) {
                    byte[] buf = new byte[65536];
                    long got = 0, last = 0;
                    for (int n; (n = in.read(buf)) > 0;) {
                        if (cancelled) throw new IOException("cancelled");
                        out.write(buf, 0, n);
                        sha.update(buf, 0, n);
                        got += n;
                        long now = System.nanoTime();
                        if (now - last > 250000000L) {
                            last = now;
                            say(total > 0 ? "Downloaded " + mb(got) + " of " + mb(total) + " (" + (got * 100 / total) + "%)" : "Downloaded " + mb(got));
                        }
                    }
                    if (total > 0 && got != total) throw new IOException("It stopped early (" + mb(got) + " of " + mb(total) + ").");
                    session.fsync(out);
                }
                if (!u.sha256.isEmpty() && !hex(sha.digest()).equals(u.sha256))
                    throw new IOException("The file is damaged (its checksum doesn't match the release's).");
                installing();
                session.commit(PendingIntent.getBroadcast(act, 0, new Intent(act, UpdateReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0)).getIntentSender());
            } finally {
                session.close();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }

    private void installing() {
        ui(new Runnable() {
            @Override public void run() {
                show(box("Installing update")
                     .setMessage("The download is done and Android is installing it. If Android asks, tap Install.\n\nThe app closes by "
                                 + "itself when the update is in: start it again afterwards. Your game files, settings and saves are kept.")
                     .setNegativeButton("Cancel update", cancel()).create());
            }
        });
    }

    // ---- what the installer says (UpdateReceiver)

    static void status(final Context context, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        final String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        final Updater u = current;
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {  // Android wants the player's yes: its own prompt
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                Toast.makeText(context, "Android's install prompt couldn't be opened: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            if (u != null) u.say("Android is asking to install the update: tap Install.\n\nThe app closes by itself when the update is in: "
                                 + "start it again afterwards.");
            return;
        }
        if (u == null) {
            if (st != PackageInstaller.STATUS_SUCCESS) Toast.makeText(context, "The update didn't install: " + (msg != null ? msg : "status " + st), Toast.LENGTH_LONG).show();
            return;
        }
        u.outcome(st, msg);
    }

    private void outcome(final int st, final String msg) {
        ui(new Runnable() {
            @Override public void run() {
                if (st == PackageInstaller.STATUS_SUCCESS) {
                    show(box("Updated").setMessage("The update is installed. Start the app again.")
                         .setPositiveButton("OK", closeWith(ImportActivity.DONE)).create());
                } else if (st == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    show(box("Update cancelled").setMessage("The update wasn't installed. Nothing was changed.")
                         .setPositiveButton("OK", closeWith(ImportActivity.CANCELLED)).create());
                } else if (st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                    fail("Android won't update this app with that APK: it was signed with a different key than the installed app. "
                         + "Nothing was changed, and your files are untouched. (Installing it anyway would mean uninstalling this "
                         + "app first, which deletes its game files and saves.)", false);
                } else if (st == PackageInstaller.STATUS_FAILURE_STORAGE) {
                    fail("There isn't enough free storage on this device for the update. Free some space and try again.", true);
                } else {
                    fail("Android couldn't install the update" + (msg != null && !msg.isEmpty() ? ": " + msg : " (status " + st + ")") + ".", true);
                }
            }
        });
    }
}
