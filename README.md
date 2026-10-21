# Kick TV

An unofficial Kick.com app for Android TV and Fire TV. Not affiliated with or endorsed by Kick.

Android 6.0+ (TV) · ~1.7 MB · [Latest release](https://github.com/DIRIKtv/kick-tv/releases/latest)

**Download link (always the newest version):**
`https://github.com/DIRIKtv/kick-tv/releases/latest/download/KickTV.apk`

## Installing on a TV

### Fire TV (Downloader app, no computer needed)
1. On the Fire TV, install **Downloader** (by AFTVnews) from the Amazon Appstore.
2. Settings → My Fire TV → Developer Options → **Install unknown apps** → turn on for Downloader.
   (No Developer Options? Settings → My Fire TV → About → click your device name 7 times.)
3. Open Downloader and enter the download link above.
4. Choose **Install**, then **Open**. Delete the downloaded file when it asks.

### Google TV / Android TV
1. Settings → System → About → click **Android TV OS build** 7 times to enable developer mode.
2. Settings → Apps → Security & restrictions → **Unknown sources** → allow the app you'll install with
   (Downloader, a file manager, or *Send Files to TV*).
3. Download or send the APK to the TV and open it to install.

### From a computer (adb)
```sh
adb connect <tv-ip>:5555        # enable ADB/network debugging on the TV first
adb install -r KickTV.apk
```

The app appears on the home screen as **Kick TV**.

### Updating
The app checks for new releases on its own (and under Settings → Check for updates), downloads them,
and opens Android's installer. The first time, Android asks you to allow Kick TV to install apps
(on Fire TV: Settings → My Fire TV → Developer options → Install unknown apps → Kick TV).
You can also install a newer APK over the old one by hand; settings and sign-in are kept.
If Android says the app "conflicts with an existing package", the old copy was signed with a
different key (e.g. a development build) — uninstall it once, then install.

## Features
- **Home**: hero carousel of live followed channels (◀ ▶ to browse), top streams by language, top categories
- **Following**: your Kick account's follows (after signing in) plus favorites saved on the TV
- **Browse / Search**: categories and channels
- **Channel pages**: live status, follow, past broadcasts with resume
- **Player**: play/pause, ◀ ▶ seek (hold to speed up), ⏪ ⏩, rewind live streams back to the start (DVR),
  quality (Auto/Source/…), speed, captions, live chat (beside or over the video), stats
- **Sign in**: scan a QR code and log in on your phone, or use the remote's on-screen pointer
- Remote: ☰ opens player settings / on a card opens the channel page

## Building

Requirements: JDK 21, Android SDK 36.

```sh
./gradlew dist              # signed release -> dist/KickTV-<version>.apk
./gradlew assembleDebug     # debug build (installs alongside as com.kicktv.app.debug)
./gradlew lintRelease       # release lint (must be error-free)
```

### Release signing — back this up
Release builds are signed with `keystore/kicktv-release.jks`, configured by `keystore.properties`
(both git-ignored). **Keep copies of both somewhere safe.** If they're lost, future versions can't be
installed as updates — every user would have to uninstall (losing their settings) and reinstall.
Without `keystore.properties`, release builds fall back to the debug key.

### Making a new release
1. Bump `appVersionName` and `appVersionCode` (must increase) at the top of `app/build.gradle.kts`, and commit.
2. `scripts/release.sh "One change per line"` — lints, builds the signed APK, tags, and publishes a GitHub Release.

Installed apps see the new release within 12 hours (or immediately via Settings → Check for updates).

## Notes
Kick has no official API for third-party TV apps; this app uses the same endpoints as kick.com,
which may change. Phone sign-in works over the local network only, protected by a one-time code in
the QR, and only while the sign-in screen is open.
