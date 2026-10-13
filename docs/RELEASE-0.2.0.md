# APK Uploader 0.2.0

Installed 0.1.0 updates itself through **Updates**; otherwise extract the ZIP and run `ApkUploader.exe`.

- **Split packages.** Drop XAPK, APKS or APKM files: the base APK and its splits are installed in
  one package-manager session, splits for other CPU architectures are skipped, and OBB files inside
  an XAPK are copied to `/sdcard/Android/obb/<package>`.
- **Uninstall** on APK items and **Remove cache** on cache items (and on XAPK items with OBB):
  both ask for confirmation, run in the queue and report the result.
- **install.txt** from Rookie-style packages: `adb push` lines decide where files go; `install`,
  `shell` and other commands are listed on the item and never executed.
- **MD5 verification** (Settings → Cache): copied files are checked with the headset's `md5sum`
  against hashes computed during the upload. Size verification stays the default.
- **Log panel**: the document icon in the top bar shows the session log inside the window, with
  Copy and Open log folder.
- **Smaller download**: the bundled Java runtime contains only the modules the app needs.

Validated on Samsung SM-I610 (Android 14): a generated XAPK (base + `config.en` split + OBB)
installed through a session, `pm path` listed both APKs, the OBB was MD5-verified, and a plain
APK with an OBB cache installed and verified as in 0.1.0; everything was removed afterwards.

Still not included: uninstalling apps that are not in the queue, APK downloads.
The headset is changed only through standard user-space installs and copies to `/sdcard`.
