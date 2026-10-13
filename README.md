# APK Uploader

Installs APK files and game caches on **Samsung Galaxy XR** over ADB, from a Windows PC.
Maintained by **4uva4ek**. [Russian guide](README-RU.md).

Download the ZIP from [Releases](https://github.com/4uva4ek/apk-uploader/releases), extract it
and run `ApkUploader.exe`. Java and adb are included; nothing else needs to be installed.

## Use

1. Enable developer options and USB debugging on the headset, connect it with a USB cable and
   accept the debugging prompt. The headset appears in the top bar. For Wi-Fi, open **Connect**
   and enter the address (`ip:port`), pair with a wireless-debugging code, or switch the
   USB-connected headset to Wi-Fi.
2. Drop APK files and cache folders into the window (or use **Add APK files** / **Add cache
   folder**). Several items can be dropped at once; they are processed one after another.
3. Watch the queue: upload progress and speed, installation, and the result. Failed items show
   the package manager's reason and can be retried. When the installed app has a different
   signature or a newer version, **Uninstall and retry** removes it first (its data is lost).

Cache folders are recognised by their layout ([details](docs/CACHE-LAYOUT.md)):
`*.obb` files go to `/sdcard/Android/obb/<package>/`, a folder named like a package goes to
`/sdcard/Android/data/<package>/`, an unpacked `Android/` folder is mirrored, and unknown
layouts ask for the target. APKs dropped together with their cache are installed first.

**Updates** checks GitHub releases on start; a newer version is downloaded, verified by size
and SHA-256, and installed in place with a backup of the previous version kept beside the app.

Settings and logs: `%LOCALAPPDATA%\ApkUploader`.

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
