package io.github.morerokk.mhgurecomp;

import org.json.JSONException;

/** What the "Update now" button makes of GitHub's "latest release" answer (UpdateInfo.java). */
public class Check {
    static int failed;

    static void check(boolean ok, String what) {
        if (!ok) { failed++; System.err.println("FAIL: " + what); }
    }

    static String release(String tag, String body, String... assets) {
        StringBuilder a = new StringBuilder();
        for (String s : assets) a.append(a.length() > 0 ? "," : "").append(s);
        return "{\"tag_name\":\"" + tag + "\",\"name\":\"Build notes\",\"body\":\"" + body + "\",\"assets\":[" + a + "]}";
    }

    static String asset(String name, long size, String digest) {
        return "{\"name\":\"" + name + "\",\"size\":" + size + ",\"browser_download_url\":\"https://github.com/x/y/releases/download/t/" + name
            + "\"" + (digest != null ? ",\"digest\":\"" + digest + "\"" : "") + "}";
    }

    static boolean rejects(String json) {
        try { UpdateInfo.parse(json); return false; } catch (JSONException e) { return true; }
    }

    public static void main(String[] a) throws Exception {
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        UpdateInfo u = UpdateInfo.parse(release("build-412", "Faster map", asset("notes.txt", 10, null), asset("MHGURecomp-Thor-412.apk", 150000000L, "sha256:" + sha)));
        check(u.build == 412, "the build number comes from the APK's name, got " + u.build);
        check(u.url.endsWith("MHGURecomp-Thor-412.apk"), "the APK asset is the one chosen, not the text file");
        check(u.size == 150000000L, "size " + u.size);
        check(u.sha256.equals(sha), "the checksum is read from the digest");
        check(u.title.equals("Build notes") && u.notes.equals("Faster map"), "title and notes");
        check(u.newerThan(411) && !u.newerThan(412) && !u.newerThan(500), "newer only when the build number is bigger");

        check(UpdateInfo.parse(release("build-9", "", asset("app.apk", 5, null))).build == 9, "no number in the APK's name: the tag's");
        check(UpdateInfo.parse(release("v0.13.0", "", asset("MHGURecomp-Thor-77.apk", 5, null))).build == 77, "the APK's number wins over a version-like tag");
        check(UpdateInfo.parse(release("build-5", "", asset("Thor.APK", 5, null))).url.endsWith("Thor.APK"), "the extension is matched in any case");
        check(UpdateInfo.parse(release("build-5", "", asset("a.apk", 5, "md5:abc"))).sha256.isEmpty(), "a digest that is not a sha256 is ignored");
        check(UpdateInfo.parse(release("build-5", "", asset("a.apk", 5, "sha256:short"))).sha256.isEmpty(), "a sha256 of the wrong length is ignored");
        check(UpdateInfo.parse(release("build-5", "", "{\"name\":\"a.apk\",\"browser_download_url\":\"https://x/a.apk\"}")).size == 0, "a missing size is 0");

        check(rejects(release("build-5", "", asset("notes.txt", 5, null))), "a release with no APK is rejected");
        check(rejects(release("latest", "", asset("app.apk", 5, null))), "a release with no number anywhere is rejected");
        check(rejects("{\"message\":\"Not Found\"}"), "GitHub's error answer is not a release");
        check(rejects("{\"tag_name\":\"build-1\",\"assets\":[{\"name\":\"a.apk\"}]}"), "an APK without a download address is not usable");

        check(UpdateInfo.numberIn("MHGURecomp-Thor-412") == 412 && UpdateInfo.numberIn("build-7") == 7 && UpdateInfo.numberIn("x") == -1
              && UpdateInfo.numberIn("a1b22") == 22 && UpdateInfo.numberIn("n1234567890123") == -1, "numberIn takes the last number, and not a huge one");

        if (failed > 0) { System.err.println(failed + " check(s) failed"); System.exit(1); }
        System.out.println("test-update: all passed");
    }
}
