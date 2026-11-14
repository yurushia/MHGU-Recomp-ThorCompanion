# Updating the Android app from inside the app

**Settings > Android > Update now.** It looks at the newest release of this fork
(`github.com/yurushia/MHGU-Recomp-ThorCompanion`, nothing else), and if its build is newer than the installed app, it downloads
the APK and has Android install it over the installed app. The game files, settings and saves in the app's folder stay: it is
an update of the same app, not a reinstall.

## What the player sees

1. **Update now.** "Checking for a new version..."
2. Up to date: "You have the latest version" with the installed and the latest build. Otherwise "Update available" with the
   build, its size and the release notes, and **Update now** / **Later**.
3. Once ever: Android's "Install unknown apps" switch for this app (the dialog opens that settings page; press Back and the
   update carries on).
4. "Downloading update" with the megabytes and the percent, and **Cancel**. The APK goes straight into Android's installer as
   it arrives (no copy is kept on the device), and its SHA-256 is checked against the release's when GitHub gives one.
5. "Installing update". Android 12 and newer install an update of an app this app installed itself without asking; the
   first update of an app that was installed some other way (a file manager, adb) shows Android's own **Install** prompt, which
   the app brings up. Android then closes the app: start it again. (Save the game first: the dialog says so.)
6. Errors say what went wrong and offer **Try again**: no internet, GitHub limiting requests, no release published, a
   download that stopped early or doesn't match its checksum, not enough storage, or Android refusing the APK.

## Publishing a release (what the button finds)

The app compares its own `versionCode` (the number of commits when it was built, `android/apk.mk`) with the number in the
release's APK name.

```
make PLATFORM=android ABI=arm64-v8a thor-release
```

makes `dist/MHGURecomp-Thor-<build>.apk` and prints the command to publish it:

```
gh release create build-<build> dist/MHGURecomp-Thor-<build>.apk --repo yurushia/MHGU-Recomp-ThorCompanion \
    --title 'Build <build>' --notes '<what changed>' --latest
```

The release page's text becomes the "what changed" the player reads. The release must be the newest ("latest") one, and not a
draft or a pre-release (GitHub's "latest release" skips those).

## The signing key (the one thing that can go wrong)

Android only updates an app in place with an APK signed with **the same key** as the installed one. Otherwise it refuses
("signed with a different key"), and the only way on is uninstalling first, which deletes the app's game files and saves.
So every release must be signed with the key the installed apps were signed with:

* `THOR_KEY=release` (the default): the release key from `make PLATFORM=android release-key`. Make it once, back up the
  keystore and its `.pass` file, and sign every release with it.
* `THOR_KEY=debug`: the SDK's debug key (`~/.android/debug.keystore`), which a plain `make apk` signs with. Use this for the
  first releases if the app on the device was installed from such a build, so it can update itself; this is only as stable as
  that keystore file, so back it up too.

An app installed from a debug-signed build can't switch to the release key without an uninstall: pick one and keep it. The
button's first run on such a device is the way to find out: the error names the signature when they differ, and nothing is
changed.

## Files

| file | what |
|---|---|
| `android/java/.../Updater.java` | the dialogs and the work: check, permission, streamed download into a `PackageInstaller` session, install status |
| `android/java/.../UpdateInfo.java` | reads GitHub's "latest release" answer (no Android classes; `make test-update` checks it) |
| `android/java/.../UpdateReceiver.java` | gets the installer's replies (the Install prompt, the result) |
| `android/java/.../ImportActivity.java` | runs the updater as its mode `UPDATE` (the native side starts it like a file import and waits) |
| `runtime/ui.c`, `runtime/platform/android.c`, `runtime/settings_panel.cpp` | the button (request 13, `android_import(10)`) |
| `android/AndroidManifest.xml` | `REQUEST_INSTALL_PACKAGES` and the receiver |

**Status: not tested on a device.** What is checked: the release parsing (`make test-update`), and that the Java compiles
against the Android 15 framework (and as Java 8, as the APK build does). Not checked: the dialogs, the permission page, the
installer session, and the silent update on Android 12+ (it needs the installed app to have been installed by this app; the
first update after a file-manager or adb install shows Android's prompt).
