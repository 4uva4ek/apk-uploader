# Releasing

Workflow: `.github/workflows/release.yml` (**Release**). It builds the portable application on
`windows-latest`, `ubuntu-22.04` and `macos-latest` (Apple Silicon), runs the unit tests (and the
Windows updater helper tests), checks each package's contents (launcher, bundled adb) and uploads
`apk-uploader-<version>-windows-x64.zip`, `-linux-x64.tar.gz` and `-macos-arm64.zip`. A `v*` tag
publishes a GitHub release with those files and `SHA256SUMS.txt`; manual runs only produce artifacts.

## Version and validation

1. Update `version` in `app.properties` and `BuildInfo.VERSION` to the same value.
2. Add `docs/RELEASE-<version>.md` (used as the release notes) and update `CHANGELOG.md`.
3. Run `python tools/check_release_version.py` and the focused checks for the changed behaviour:
   `./gradlew :app:test`, `tools/test_windows_updater.ps1` when the updater changed.
4. Push `main`, then run **Release** manually and check the artifact: extract the ZIP, run
   `ApkUploader.exe`, connect the headset, install a test APK and a cache folder.
5. Create and push the tag `v<version>` at the validated commit. The tag run publishes the three
   packages and `SHA256SUMS.txt`.

Release assets must keep these names: the in-app updater downloads the package for its platform
from the latest stable release and verifies its size and SHA-256 digest from the GitHub API. Do
not move published tags or rename assets; publish a new version instead. Windows packages are not
Authenticode signed; macOS packages are ad-hoc signed by jpackage and not notarized.

## Bundled adb

`app/build.gradle.kts` pins the Android SDK Platform-Tools release: one version and an archive
SHA-256 for each of Windows, Linux and macOS. The `fetchPlatformTools` task downloads the archive
for the build host, verifies it and stages the adb client files (`adb.exe` with its DLLs on
Windows, `adb` elsewhere, plus `NOTICE.txt` and `source.properties`) into
`app/build/platform-tools/<windows|linux|macos>`, which Compose packages as the app resources.
To update adb, change the version and all three hashes together.

## Icon

`app/icons/app-icon.png` (512 px, window, Linux and README), `app-icon.ico` (Windows) and
`app-icon.icns` (macOS) are generated from one drawing; keep the three in sync.

## Runtime modules

The bundled Java runtime is built by jlink from an explicit module list in `app/build.gradle.kts`
(`java.instrument`, `jdk.unsupported`, `jdk.crypto.ec` on top of the Compose defaults). After
adding a dependency, run `./gradlew :app:suggestRuntimeModules` and add what it reports; a
missing module shows up as a `ClassNotFoundException` only in the packaged app, not in `run`.

## Device check

`DeviceIntegrationTest` installs an APK and a synthetic OBB cache on a connected device, verifies
them and removes both. It runs only when `APK_UPLOADER_TEST_APK` points to an APK whose package
is not installed yet:

```sh
python -I tools/make_test_apk.py build/testapk 24   # harmless test APK and XAPK (needs the Android SDK)
APK_UPLOADER_TEST_APK=build/testapk/apk-uploader-testapp.apk \
APK_UPLOADER_TEST_XAPK=build/testapk/apk-uploader-testapp.xapk \
./gradlew :app:test --tests apkuploader.DeviceIntegrationTest -i
```

## Local build

Requires JDK 21 (set `JAVA_HOME`), network access for Gradle dependencies and the pinned
platform-tools download.

```sh
./gradlew :app:test
./gradlew :app:createReleaseDistributable   # app/build/compose/binaries/main-release/app/ApkUploader
./gradlew :app:run                           # development run
```
