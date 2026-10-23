<p align="center">
  <img src="docs/branding/banner.png" alt="Sidekick" width="480">
</p>

<p align="center">
  <b>A free, open-source Kick app for Android TV, Google TV and Fire TV.</b><br>
  Live streams with chat, past broadcasts, clips, multi-view and more, built for the remote.
</p>

<p align="center">
  <a href="https://github.com/DIRIKtv/sidekick/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/DIRIKtv/sidekick?color=2cff05&label=download"></a>
  <img alt="Android 6.0+" src="https://img.shields.io/badge/Android%20TV-6.0%2B-2cff05">
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-lightgrey"></a>
</p>

> **Unofficial.** Sidekick is not made, endorsed or supported by Kick. "Kick" is a trademark of its
> owner. See [Disclaimer](#disclaimer).

<p align="center">
  <img src="docs/screenshots/home.png" alt="Home screen" width="49%">
  <img src="docs/screenshots/player.png" alt="Live stream with chat" width="49%">
  <img src="docs/screenshots/multiview.png" alt="Multi-view" width="49%">
  <img src="docs/screenshots/following.png" alt="Following" width="49%">
</p>

## Features

- **Home**: live channels you follow, Continue watching, Recently watched, top streams in your
  language, today's top clips and popular categories
- **Player**: rewind live streams back to the start, quality and speed, captions, sleep timer,
  loudness equalization, picture-in-picture
- **Chat** beside or over the video, with 7TV emotes and filters (hide bots and !commands, calm busy chats)
- **Multi-view**: watch up to 4 live channels at once; the highlighted one plays sound
- **Following**: your Kick account's follows, plus favorites saved on the TV, with go-live alerts
- **Channel pages** with past broadcasts (resume where you left off) and popular clips
- **TV home screen** (Android TV / Google TV): a "Live on Kick" row and Play Next for past broadcasts
- **Sign in** by scanning a QR code with your phone, or with the remote's on-screen pointer
- **Automatic updates** straight from this page

## Install

**Download link (always the newest version):**

```
https://github.com/DIRIKtv/sidekick/releases/latest/download/Sidekick.apk
```

### Fire TV (no computer needed)
1. Install **Downloader** (by AFTVnews) from the Amazon Appstore.
2. Settings → My Fire TV → Developer Options → **Install unknown apps** → turn on for Downloader.
   (No Developer Options? Settings → My Fire TV → About → click your device name 7 times.)
3. Open Downloader, enter the download link above, then choose **Install** and **Open**.

### Google TV / Android TV
1. Settings → System → About → click **Android TV OS build** 7 times to enable developer mode.
2. Settings → Apps → Security & restrictions → **Unknown sources** → allow the app you'll install with
   (Downloader, a file manager, or *Send Files to TV*).
3. Download or send the APK to the TV and open it to install.

### From a computer
```sh
adb connect <tv-ip>:5555        # turn on network debugging on the TV first
adb install -r Sidekick.apk
```

### Updates
Sidekick checks this page for a new version each time it opens and offers to install it
(Settings → Automatic updates: every open, once a day, or off). The first time, Android asks you to
allow Sidekick to install apps. Your settings and sign-in are kept.

## Using it

| Remote | Does |
| --- | --- |
| **OK** on a card | Watch |
| **☰** (or hold OK) on a card | Open the channel page |
| **◀ ▶** while watching | Seek (hold to go faster) |
| **▲ ▼** while watching | Show the controls |
| **☰** while watching | Player settings: quality, chat, multi-view, sleep timer… |
| **Home** while watching | Keep watching in a corner (picture-in-picture, where supported) |

## Privacy

No ads, no analytics, no accounts of our own. Your Kick sign-in is stored encrypted on the TV and only
sent to Kick. Phone sign-in sends your login to the TV over your home network, so use it on a network
you trust. Full details: [PRIVACY.md](PRIVACY.md).

## Disclaimer

Sidekick is an independent, unofficial project. It is not affiliated with, endorsed by or connected to
Kick or its owners. "Kick" and the Kick logo are trademarks of their respective owner.

Kick doesn't offer an official API for TV apps, so Sidekick uses the same public endpoints as the
kick.com website. Kick can change these at any time, which may break features until an update is
released. Streams, chat and other content belong to their creators and are shown as Kick serves them.

## Contributing

Bug reports and ideas are welcome, so please [open an issue](https://github.com/DIRIKtv/sidekick/issues/new/choose).
Pull requests are welcome too; for anything large, open an issue first so we can talk it through.

### Building

Requirements: JDK 21, Android SDK 36.

```sh
./gradlew assembleDebug        # debug build (installs alongside as com.diriktv.sidekick.debug)
./gradlew testDebugUnitTest    # unit tests
./gradlew lintRelease          # release lint (must be error-free)
```

<details>
<summary>Releasing (maintainer)</summary>

Release builds are signed with `keystore/sidekick-release.jks`, configured by `keystore.properties`
(both git-ignored; keep backups — without them, future versions can't install as updates). Without
`keystore.properties`, release builds fall back to the debug key.

1. Bump `appVersionName` and `appVersionCode` at the top of `app/build.gradle.kts`, add the changes
   to [CHANGELOG.md](CHANGELOG.md), and commit.
2. `scripts/release.sh "One change per line"` lints, builds the signed APK, tags and publishes the
   GitHub release. Installed apps offer it the next time they're opened.
</details>

## License

[MIT](LICENSE) © DIRIKtv
