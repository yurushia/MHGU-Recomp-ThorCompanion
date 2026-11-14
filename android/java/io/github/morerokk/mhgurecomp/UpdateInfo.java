// What the "Update now" button learns about the newest release (Updater.java): read from GitHub's "latest release" answer
// for this fork's repository. No Android classes here, so tests/update runs it on a PC.
//
// How a release is made (make PLATFORM=android ABI=arm64-v8a thor-release, android/apk.mk): an APK named
// MHGURecomp-Thor-<build>.apk, where <build> is the APK's versionCode (the number of commits so far), attached to a release
// whose tag is build-<build>. The app compares that number with its own versionCode: a bigger one is an update. (A release
// with no number in its APK's name falls back to the one in its tag.)
package io.github.morerokk.mhgurecomp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

final class UpdateInfo {
    /** Where releases are looked up: this fork. */
    static final String LATEST = "https://api.github.com/repos/yurushia/MHGU-Recomp-ThorCompanion/releases/latest";

    final String tag, title, notes, url, sha256;
    /** The APK's size in bytes (0: not given), and its build number (its versionCode). */
    final long size, build;

    private UpdateInfo(String tag, String title, String notes, String url, String sha256, long size, long build) {
        this.tag = tag;
        this.title = title;
        this.notes = notes;
        this.url = url;
        this.sha256 = sha256;
        this.size = size;
        this.build = build;
    }

    /** Whether this release is newer than the installed app, by versionCode. */
    boolean newerThan(long installed) {
        return build > installed;
    }

    /** The release in GitHub's JSON. JSONException when it has no APK, no build number, or isn't a release. */
    static UpdateInfo parse(String json) throws JSONException {
        JSONObject release = new JSONObject(json);
        String tag = release.optString("tag_name");
        if (tag.isEmpty()) throw new JSONException("not a release");
        JSONArray assets = release.optJSONArray("assets");
        JSONObject apk = null;
        for (int i = 0; assets != null && i < assets.length() && apk == null; i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a != null && a.optString("name").toLowerCase(Locale.ROOT).endsWith(".apk")
                && !a.optString("browser_download_url").isEmpty()) apk = a;
        }
        if (apk == null) throw new JSONException("The latest release (" + tag + ") has no APK attached.");
        String name = apk.optString("name");
        long build = numberIn(name.substring(0, name.length() - 4));
        if (build < 0) build = numberIn(tag);
        if (build < 0) throw new JSONException("The latest release (" + tag + ") has no build number in its name.");
        String digest = apk.optString("digest").toLowerCase(Locale.ROOT);  // ("sha256:<hex>", when GitHub gives one)
        String sha = digest.startsWith("sha256:") && digest.length() == 7 + 64 ? digest.substring(7) : "";
        String title = release.optString("name");
        return new UpdateInfo(tag, title.isEmpty() ? tag : title, release.optString("body"), apk.optString("browser_download_url"), sha,
                              (long) apk.optDouble("size", 0), build);
    }

    // The last number in a text ("MHGURecomp-Thor-412" -> 412), -1 when there is none
    static long numberIn(String s) {
        int end = s.length();
        while (end > 0 && !Character.isDigit(s.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && Character.isDigit(s.charAt(start - 1))) start--;
        if (start == end || end - start > 9) return -1;
        return Long.parseLong(s.substring(start, end));
    }
}
