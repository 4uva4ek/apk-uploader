# Cache folder detection

A dropped folder is analysed with these rules, in order. The first match wins. APK files
found directly inside a dropped folder are queued for installation before its cache, so the
app exists on the headset by the time its files arrive.

| # | Folder looks like | Kind | Destination on the headset |
| --- | --- | --- | --- |
| 1 | `Android/obb/...` and/or `Android/data/...` inside (an unpacked archive), or the folder is `Android` itself | Mirror | `/sdcard/Android/...` (same relative paths) |
| 2 | Folder named `obb` or `data` containing `com.example.game/` folders | OBB / Data | `/sdcard/Android/obb/<pkg>/` or `/sdcard/Android/data/<pkg>/` per package folder |
| 3 | `*.obb` files directly inside or one level down (Rookie/SideQuest: `Game/com.example.game/main.1.com.example.game.obb`) | OBB | `/sdcard/Android/obb/<pkg>/`. Package from `main.<ver>.<pkg>.obb`, else from the folder name, else from the APK in the same drop |
| 4 | Folder named like a package (`com.example.game`) | Data | `/sdcard/Android/data/com.example.game/` (contents mirrored, usually `files/`) |
| 5 | Package-named subfolders | Data | one item per subfolder |
| 6 | Anything else | ask | a dialog asks for the package name and OBB or Data |

Skipped files: `Thumbs.db`, `desktop.ini`, `.DS_Store`, `install.txt`, `*.apk` (APKs are
installed, not copied). `install.txt` from Rookie packages is only noted; its commands are
never executed.

Copying uses the adb sync service with per-byte progress. Files that already exist on the
headset with the same size are skipped. Before uploading, a one-byte probe checks that adb
may write to the destination; if the headset refuses (Permission denied), files are uploaded
to `/sdcard/Download/.apk-uploader-*` and moved into place with `mv`, then the temporary
folder is removed. After copying, file sizes are compared (can be disabled in Settings).
On Samsung SM-I610 (Android 14) direct writes to `/sdcard/Android/obb/<package>` work; the
fallback was not needed there.
