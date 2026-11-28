# Overhypedcat Twitch Patches

Patches for the Twitch Android app, applied with [Morphe](https://morphe.software). They block ads, take the
music a streamer plays out of the sound, unlock qualities Twitch offers only in some countries, and add a screen
lock and a few player options.

## Features

- **Ad blocker.** Live streams play without the ads Twitch stitches into them, and the main page shows no banner
  or feed ads. While an ad is being skipped, a small label on the video says so ("Blocking ads (480p)" while the
  stream comes from an ad-free source, "Ad blocked" when an ad the app plays itself is stopped).
- **Song removal.** Switched on per channel, it takes recorded songs out of the stream's sound while the
  streamer's voice and the game stay. A pill beside the viewer count shows what it is doing ("Listening",
  "Removing song", "Resting 52 s") or what went wrong. Songs heard before are recognised again without a new
  download.
- **Quality unlock.** Plays streams in the higher qualities (such as 1440p) that Twitch offers only in some
  countries, through your own WireGuard VPN server. Only the streams that gain a quality go through it.
- **Highest quality.** Every stream starts at its highest quality instead of Auto; a quality picked by hand
  afterwards stays.
- **Screen lock.** In full screen, a padlock in the player's controls hides them at once and ignores touches until
  the screen is tapped three times. The screen stays horizontal while locked.
- **Switches and settings.** Each feature is switched in the video player's Options, under **Patches**, in every
  layout; a line under each switch says what it is doing right now, or what went wrong. The app's Settings get a **Patch settings** group with
  the VPN servers and Debug mode.
- **Debug mode.** Shows what the patches do in a movable, foldable window and writes everything, including the
  video player's connections, the video's state and the phone's load, to
  `Downloads/TwitchMorphed-log-<date>.txt` for problem reports. Access tokens are removed from the log.

<!-- PATCHES_START EXPANDED -->
> **[v4.4.0](https://github.com/Overhypedcat/overhypedcat-twitch-patches/releases/tag/v4.4.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;8 patches total
<details open>
<summary>Twitch&nbsp;&nbsp;•&nbsp;&nbsp;8 patches</summary>
<br>

**Package name:** `tv.twitch.android.app`

**Supported versions:**

| 31.8.0 |
| :---: |

| Patch | Description | Options |
|----------|----------------|-----------|
| [Block ad servers](#block-ad-servers) | Blocks Twitch's banner, display and feed ads (edge.ads.twitch.tv) and its tracking servers inside the app. |  |
| [Block client-side ads](#block-client-side-ads) | Makes the app treat every ad eligibility check like a Turbo viewer, so it never starts the ads it plays itself (a black screen while their ad server is blocked). |  |
| [Block stream ads](#block-stream-ads) | Replaces ad-laden live playlists with ad-free ones from other player types, and strips ad segments when no clean playlist exists. |  |
| [Highest quality](#highest-quality) | Adds a Highest quality switch to the video player's Options: with it on, every stream starts at its highest quality instead of Auto. A quality chosen afterwards stays as chosen. |  |
| [Patch settings](#patch-settings) | Adds a Patch settings group to the app's Settings (VPN servers for the quality unlock, and Debug mode: a live log window and a log file in Downloads), and the patches' switches to the video player's Options. |  |
| [Patch switches in the vertical view](#patch-switches-in-the-vertical-view) | Adds a Patches row to the video player's Options in the vertical view, which opens the patches' switches (the vertical view's Options can't hold them itself). |  |
| [Quality unlock](#quality-unlock) | Unlocks the higher qualities (1440p) Twitch offers only in some countries: a stream's playlist is also asked for through your WireGuard VPN server, and when it offers more, that stream plays through the VPN. Nothing else in the app uses it. Servers are added in Settings, Patch settings. |  |
| [Song removal](#song-removal) | Takes the songs a streamer plays out of the stream's sound (Shazam names the song, its clean copy comes from YouTube, and it is subtracted live). Switched on and off in the video player's Options. |  |

</details>

<!-- PATCHES_END -->

## How it works

The patches are Morphe bytecode patches written in Kotlin (`patches/`). They insert calls into Twitch's own code;
the work those calls do is a Java extension (`extensions/twitch/`) that is bundled into the patched app. Twitch's
video player is Amazon IVS, whose classes the extension compiles against through stubs (`extensions/twitch/stub/`).

- **Ad blocker.** The IVS player's HTTP client is wrapped, so every HLS playlist passes through the extension.
  Playlists with stitched ads are replaced with the same stream requested as another player type that has no ad,
  stitched together with continuous segment numbering; if every player type has the ad, its segments are cut out.
  Playlists without ads are handed on as Twitch sent them. Ad and tracking servers are refused in the app's DNS
  lookup, and the app's own ad eligibility check is made to answer as for a Turbo viewer.
- **Song removal.** The audio renderer is hooked. Shazam identifies the song, a clean copy is downloaded from
  YouTube and decoded, aligned with the stream by cross-correlation of reduced spectra, and subtracted live by an
  adaptive per-frequency filter (2048-point FFT frames). The sound is delayed a fraction of a second for the
  filter, and the picture is kept in step.
- **Quality unlock.** A user-space WireGuard client (Curve25519, ChaCha20-Poly1305, BLAKE2s) with its own TCP/IP
  and TLS stack runs inside the app, so no Android VPN permission is used. A stream's master playlist (its list of
  qualities) is also asked for through the tunnel; when it offers more, that stream's requests go through it.
- **Player UI.** The screen lock, song pill and switches are added as views into Twitch's React Native and native
  player controls, found by their test ids and resource ids. They follow the controls on every frame.

## Requirements

To use:

1. Morphe 2.6.0 or newer, from [morphe.software](https://morphe.software).
2. This patch source: https://morphe.software/add-source?github=Overhypedcat/overhypedcat-twitch-patches
3. Twitch 31.8.0. Uninstall the Play Store version, patch Twitch in Morphe and install the result.
4. For the quality unlock: a WireGuard config (`.conf`) from a VPN provider in a country that gets the higher
   qualities, added under Settings, Patch settings, VPN servers.
5. Song removal needs a connection to Shazam and YouTube.

To build from a clone: JDK 21, the Android SDK, and `GITHUB_ACTOR`/`GITHUB_TOKEN` with `read:packages`.

- Tests: `gradlew :extensions:twitch:testDebugUnitTest :patches:test`
- Patch bundle: `gradlew buildAndroid` (run it last: the test task rebuilds the bundle without the Android code
  Morphe needs). The result is `patches/build/libs/patches-<version>.mpp`.

## Notes

- Supported Twitch version: 31.8.0.
- Song removal works on streams with 48 kHz sound, and only removes recorded versions of songs (a live version or
  cover has no matching copy on YouTube).
- Failures are shown on screen with their reason. The ad blocker's are shown as they happen on the line under its
  switch in the player's Options, with a code, until another stream is opened:

  | Code | Meaning |
  | --- | --- |
  | E1 | Ad markers the ad blocker does not know: ads may play (needs an update) |
  | E2 | No ad-free stream could be requested (Twitch refused or the network failed): ads are cut out instead |
  | E3 | Playlist processing failed: ads may play |
  | E4 | A live playlist address the ad blocker does not know: ads may play (needs an update) |
  | E5 | The app's own ad check changed: those ads may play (needs an update) |
  | E7 | The ad label could not be shown: ads are still blocked |

- To test the ad blocker when Twitch serves no ads, `adb shell setprop debug.twitchmorphed.fake_ad 3` makes the
  newest 3 segments of the watched stream look like a stitched ad; `0` ends it.
- Commits to `dev` publish pre-releases and commits to `main` publish releases
  ([conventional commits](https://www.conventionalcommits.org)).
