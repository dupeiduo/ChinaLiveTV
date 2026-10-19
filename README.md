# ChinaLiveTV — Android TV

Android TV APK starter using Kotlin + ExoPlayer 2.x. Includes remote M3U playlist loading and GitHub Actions cloud build workflow.

## Device compatibility

- **Android 4.4 (API 19) and above.** This is the minimum supported version.
- Android 5.0+ uses the same player engine; the app theme switches to Material automatically on API 21+.
- Android 4.0–4.3 (API 14–18) is **not** supported: those releases lack reliable TLS 1.2/HLS support and the MediaCodec APIs the player relies on.
- Media3 was replaced by ExoPlayer 2.19.1 because Media3 requires Android 5.0 (API 21). HLS playback is still supported.
- On Android 4.4 the app explicitly enables TLS 1.2 for HTTPS playlists and streams, which KitKat leaves off by default. Very old devices may still fail on servers that require modern TLS/SNI, and some H.265/DRM streams cannot be decoded by the device hardware.

## Default online playlist

The app attempts to load this community-maintained playlist at startup:

`https://iptv-org.github.io/iptv/countries/cn.m3u`

This is a public third-party aggregation, not an official broadcaster feed. **It is not guaranteed that every stream is authorized, available, playable, or accessible in every country.** The playlist may omit channels or contain dead links. Confirm usage rights and use only streams you are entitled to access. This project does not bypass login, DRM, or geographic restrictions.

Official services may offer their own apps or web players, but their web URLs are not necessarily direct stream URLs compatible with ExoPlayer.

## Cloud build APK with GitHub Actions

1. Create a GitHub account and a new **public or private** repository, e.g. `ChinaLiveTV`.
2. Unzip this project locally.
3. Upload the **contents** of the `ChinaLiveTV` folder to the repository root (the `.github/workflows/android.yml` file must be included). You can use GitHub's web interface's **Add file → Upload files**, or Git from a computer.
4. Open the repository's **Actions** tab and enable Actions if prompted.
5. Select **Build Android TV APK** → **Run workflow**. Or push/commit to the `main` branch to trigger a build.
6. Wait for the run to finish with a green check.
7. Open that workflow run; under **Artifacts**, download `ChinaLiveTV-debug-apk`.
8. Unzip the artifact to get `app-debug.apk`.
9. Copy it to a USB drive or transfer it to the TV and install it. On the TV, allow installation from the file manager/source used to open the APK.

The workflow installs JDK 17, Gradle 8.13, and Android SDK platform/build tools for API 36, then runs `gradle assembleDebug`. The first build needs internet access to download dependencies.

## Local build

Open the root folder in Android Studio and wait for Gradle sync, then choose **Build → Build APK(s)**. Debug APK path:

`app/build/outputs/apk/debug/app-debug.apk`

## Controls

- D-pad up/down: navigate channels
- D-pad right: move focus to player
- D-pad left: return to channel list
- Channel Up/Down or Page Up/Down: change channel

## Important limitations

- The app loads a community playlist over the internet; the channel list requires internet access.
- Live-stream endpoints can change or be geo-restricted, and the playlist is not an availability or legal guarantee.
- Some TV streams may use codecs, DRM, headers, or protocols not supported by ExoPlayer 2 on Android 4.4.
- No stream proxy/server is included.
