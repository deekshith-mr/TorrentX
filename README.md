# TorrentX – a lightweight BitTorrent client for Android

A uTorrent-style torrent app built with Kotlin, Jetpack Compose and
[libtorrent4j](https://github.com/aldenml/libtorrent4j) (Android bindings for libtorrent).

## Features
- Add torrents from **magnet links** (paste, or tap a magnet link in your browser)
- Open **.torrent files** (file picker, or "Open with" from a file manager)
- Live progress, download/upload speed, peers/seeds, ETA, DHT node count
- Pause / resume each torrent, pause all / resume all
- Remove a torrent, optionally deleting its files
- Tabs: All / Downloading / Completed
- Keeps downloading in the background (foreground service with a live notification and an Exit button)
- Remembers your torrents across app restarts
- Light and dark theme

Downloads are saved to the app's own folder, so no storage permission is needed:
`Android/data/com.torrentx.app/files/Download/` (open it with any file manager or via USB).

## Get the APK – option A: GitHub (no install needed)
1. Create a new GitHub repository and upload this whole folder (including `.github/`).
2. Open the **Actions** tab. The "Build APK" workflow runs automatically on every push
   (or start it with **Run workflow**).
3. When it finishes, download the **TorrentX-apk** artifact (a zip containing the APKs).
4. Copy `app-release.apk` to your phone and install it (allow "Install unknown apps").

## Get the APK – option B: Android Studio
1. Open this folder in Android Studio (Ladybug or newer).
2. Let Gradle sync (it downloads the Android SDK pieces and libraries).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, or press Run with your phone connected.

Command line (needs the Android SDK installed and `ANDROID_HOME` set):
```
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

## Notes
- The release build is signed with the debug key so it installs right away.
  Create your own keystore before you share it publicly or upload it to a store.
- If `libtorrent4j` 2.1.0-31 can't be found, change `libtorrent4jVersion` in `app/build.gradle.kts`
  to the latest version listed on Maven Central (`org.libtorrent4j`).
- On Android 15, the system may stop a "dataSync" foreground service after about 6 hours a day.
  Reopen the app to continue downloading.
- Download only content you have the right to share.

## Project layout
```
app/src/main/java/com/torrentx/app/
  TorrentEngine.kt   – libtorrent session: add, pause, resume, remove, status polling, persistence
  TorrentService.kt  – foreground service that keeps downloads running in the background
  MainActivity.kt    – Compose UI, magnet and .torrent intent handling
  Format.kt          – size, speed and ETA formatting
```

## Ideas for next steps
- Settings screen: speed limits (`SettingsPack.downloadRateLimit/uploadRateLimit`), Wi-Fi only, custom download folder (SAF)
- Per-torrent detail screen: file list with selective download (`TorrentHandle.prioritizeFiles`), trackers, peers
- Built-in torrent search, RSS feeds
