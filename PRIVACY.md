# Privacy

Sidekick has no servers, accounts, ads or analytics of its own. This is everything it stores and sends.

## Stored on your TV
- **Your Kick sign-in** (if you sign in): a session token, encrypted with a key kept in the Android
  Keystore. Signing out deletes it.
- **Settings, favorites and watch history** (what you watched and where you stopped in past
  broadcasts). Clear the history under Settings → Clear watch history.
- None of this is backed up to the cloud by the app or sent anywhere else.

## Network connections
| Service | Why | What it receives |
| --- | --- | --- |
| **kick.com** | Streams, chat, channels, clips; your follows if signed in | Your sign-in token, only for account features (follows, follow/unfollow) |
| **7tv.io / cdn.7tv.app** | 7TV chat emotes (can be turned off) | Which channel's emotes to load |
| **github.com** | Checking for and downloading updates (can be turned off) | Nothing beyond a normal download |

As with any app, these services can see your IP address when Sidekick connects to them.

## Signing in with your phone
While the sign-in screen is open, Sidekick runs a small web page on your **home network** that your
phone opens from the QR code. It only accepts requests carrying the random one-time key in that QR,
and stops when you leave the sign-in screen.

The email and password you type on your phone travel to the TV **over your local network without
encryption** (plain HTTP, since it never leaves your home network). The TV enters them into Kick's own
sign-in page and keeps them in memory only for that, never saving them. Only use phone sign-in on a
network you trust; otherwise sign in with the remote's on-screen pointer instead.

## Optional TV features
On Android TV / Google TV, Sidekick adds a "Live on Kick" row and Play Next entries to the system home
screen. These live in Android's TV database on the device and are removed if you uninstall the app.

## Contact
Questions or concerns: [open an issue](https://github.com/DIRIKtv/sidekick/issues).
