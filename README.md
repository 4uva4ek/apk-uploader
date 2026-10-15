<img src="app/icons/app-icon.png" width="96" alt="">

# APK Uploader

Installs APK files and game caches on **Samsung Galaxy XR** over ADB, from a Windows, Linux or
macOS PC. Maintained by **4uva4ek**.

Download the package for your system from [Releases](https://github.com/4uva4ek/apk-uploader/releases).
Java and adb are included; nothing else needs to be installed.

| System | Package | Run |
|---|---|---|
| Windows x64 | `apk-uploader-<version>-windows-x64.zip` | extract, run `ApkUploader.exe` |
| Linux x64 | `apk-uploader-<version>-linux-x64.tar.gz` | extract, run `ApkUploader/bin/ApkUploader` |
| macOS (Apple Silicon) | `apk-uploader-<version>-macos-arm64.zip` | extract, move `ApkUploader.app` to Applications |

- **Linux**: on first start the app adds itself to the application menu (`~/.local/share/applications`).
  USB access to the headset needs a udev rule for Samsung devices (vendor `04e8`), for example
  `android-udev-rules` from your distribution, and membership in its group (`adbusers` or `plugdev`).
- **macOS**: the app is not notarized. After extracting, remove the quarantine flag once:
  `xattr -dr com.apple.quarantine /Applications/ApkUploader.app`.

## Use

1. Enable developer options and USB debugging on the headset, connect it with a USB cable and
   accept the debugging prompt. The headset appears in the top bar. For Wi-Fi, open **Connect**
   and enter the address (`ip:port`), pair with a wireless-debugging code, or switch the
   USB-connected headset to Wi-Fi.
2. Drop APK, XAPK, APKS or APKM files and cache folders into the window (or use **Add APK
   files** / **Add cache folder**). Several items can be dropped at once; they are processed one
   after another. Split packages are installed in one package-manager session; splits for other
   CPU architectures are skipped and OBB files inside an XAPK are copied as well.
3. Watch the queue: upload progress and speed, installation, and the result. Failed items show
   the package manager's reason and can be retried. When the installed app has a different
   signature or a newer version, **Uninstall and retry** removes it first (its data is lost).
   **Uninstall** and **Remove cache** on an item remove the app or its copied files from the
   headset after confirmation.
4. **Installed apps** (grid icon in the top bar) lists the third-party apps on the headset with
   their OBB/data sizes; each can be uninstalled or have its cache removed.

Cache folders are recognised by their layout ([details](docs/CACHE-LAYOUT.md)):
`*.obb` files go to `/sdcard/Android/obb/<package>/`, a folder named like a package goes to
`/sdcard/Android/data/<package>/`, an unpacked `Android/` folder is mirrored, a Rookie-style
`install.txt` decides through its `adb push` lines (nothing else in it is executed), and unknown
layouts ask for the target. APKs dropped together with their cache are installed first.
Copied files are size-checked; MD5 verification can be enabled in Settings. The log panel
(document icon in the top bar) shows what happened.

**Updates** checks GitHub releases on start; a newer version is downloaded and verified by size
and SHA-256. On Windows it is installed in place with a backup of the previous version kept beside
the app; on Linux and macOS the download folder opens and you replace the app yourself.

Settings and logs: `%LOCALAPPDATA%\ApkUploader` (Windows), `~/.local/share/ApkUploader` (Linux),
`~/Library/Application Support/ApkUploader` (macOS).

## What it changes on the headset

Only standard user-space operations: `pm install` and file copies to `/sdcard/Android`.
No system files, root, firmware or persistent settings are touched. Installed apps and
copied caches stay after a reboot; remove them from the headset's app settings or with adb.
Switching to Wi-Fi uses `adb tcpip 5555`, which the headset forgets on reboot.

## Build

JDK 21 is required. Gradle downloads the pinned Android platform-tools (adb) and verifies its
SHA-256 before packaging.

```sh
./gradlew :app:test
./gradlew :app:createReleaseDistributable   # app/build/compose/binaries/main-release/app/ApkUploader
```

See [docs/RELEASING.md](docs/RELEASING.md) for the release workflow.

## License

GPL-3.0, see [LICENSE](LICENSE). Bundled Android SDK Platform-Tools are distributed under
their own notices (`app/resources/NOTICE.txt`). APK parsing uses
[ARSCLib](https://github.com/REAndroid/ARSCLib) (Apache-2.0). Built with Compose Multiplatform.
