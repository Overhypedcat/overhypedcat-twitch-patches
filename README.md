# Overhypedcat Twitch Patches

Patches for the Twitch Android app, for use with [Morphe](https://morphe.software).

Blocks stream ads the way [vaft](https://github.com/ryanbr/TwitchAdSolutions) does on desktop: when Twitch
stitches an ad into a live stream, the player gets the same stream requested as another player type
(popout, embed, ...) that has no ad. If every player type has the ad, the ad segments are cut out
and the player waits for live content.

## Install on the phone

1. Install Morphe from [morphe.software](https://morphe.software).
2. Add this patch source:
   https://morphe.software/add-source?github=Overhypedcat/overhypedcat-twitch-patches
3. Patch Twitch in Morphe and install the result. Uninstall the Play Store Twitch first.

Morphe shows a banner when a new version of these patches is released.

## Error codes

When Twitch changes something and ads may get through, the app shows a short message once per launch:

| Code | Meaning |
| --- | --- |
| E1 | Playlist has ad markers the patch does not know |
| E2 | No ad-free backup stream could be requested (token or usher API changed) |
| E3 | Playlist processing crashed, the original playlist was used |
| E4 | A live playlist URL the patch does not recognise |

Details are in logcat under the tag `TwitchMorphed`.

## Development

- `extensions/twitch` - the Java code that runs inside Twitch, with unit tests
- `extensions/twitch/stub` - compile-time copies of the Amazon IVS classes it hooks
- `patches` - the Morphe bytecode patch
- `Build.cmd` - builds, tests and patches a local copy: put the Twitch `.apkm` from APKMirror in `apk\`,
  the patched APK lands in `dist\` and is installed if a phone is connected with USB debugging

Commits to `dev` publish pre-releases, merges to `main` publish releases
([semantic commits](https://www.conventionalcommits.org): `fix:`, `feat:`, `chore:`).

## Patches

<!-- PATCHES_START EXPANDED -->
<!-- PATCHES_END -->
