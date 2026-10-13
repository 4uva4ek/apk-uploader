"""Builds harmless test packages for DeviceIntegrationTest: package dev.apkuploader.testapp,
no code, one random asset so that upload progress is visible.

Produces <output-dir>/apk-uploader-testapp.apk and <output-dir>/apk-uploader-testapp.xapk
(the same base APK plus a `config.en` split and a 2 MB OBB expansion).

Usage: python -I tools/make_test_apk.py <output-dir> [payload-mb]
Needs ANDROID_HOME (or ANDROID_SDK_ROOT) with build-tools and a platform, plus keytool on PATH
or in JAVA_HOME. The signing key is throwaway and is created next to the APK.
"""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import zipfile

PACKAGE = 'dev.apkuploader.testapp'
out = pathlib.Path(sys.argv[1]).resolve()
payload_mb = int(sys.argv[2]) if len(sys.argv) > 2 else 24
sdk = pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or
                   pathlib.Path(os.environ['LOCALAPPDATA']) / 'Android' / 'Sdk')
exe = '.exe' if os.name == 'nt' else ''
bat = '.bat' if os.name == 'nt' else ''
build_tools = [d for d in sorted((sdk / 'build-tools').iterdir()) if (d / f'aapt2{exe}').exists() and (d / f'apksigner{bat}').exists()][-1]
platform = [d for d in sorted((sdk / 'platforms').iterdir()) if (d / 'android.jar').exists()][-1]
java_bin = pathlib.Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
keytool = str(java_bin / f'keytool{exe}') if java_bin else 'keytool'

out.mkdir(parents=True, exist_ok=True)
keystore = out / 'test-signing.p12'
if not keystore.exists():
    subprocess.run([keytool, '-genkeypair', '-keystore', str(keystore), '-storetype', 'PKCS12', '-alias', 'test',
                    '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-storepass', 'testtest',
                    '-dname', 'CN=APK Uploader test'], check=True)


def build_apk(name: str, manifest: str, assets: dict) -> pathlib.Path:
    work = out / f'work-{name}'
    shutil.rmtree(work, ignore_errors=True)
    (work / 'assets').mkdir(parents=True)
    (work / 'AndroidManifest.xml').write_text(manifest, encoding='utf-8')
    for asset_name, size in assets.items():
        with open(work / 'assets' / asset_name, 'wb') as payload:
            remaining = size
            while remaining > 0:
                chunk = min(remaining, 1024 * 1024)
                payload.write(os.urandom(chunk))
                remaining -= chunk
    unsigned = work / 'unsigned.apk'
    aligned = work / 'aligned.apk'
    final = out / name
    subprocess.run([str(build_tools / f'aapt2{exe}'), 'link', '--manifest', str(work / 'AndroidManifest.xml'),
                    '-I', str(platform / 'android.jar'), '-A', str(work / 'assets'), '-o', str(unsigned)], check=True)
    subprocess.run([str(build_tools / f'zipalign{exe}'), '-f', '-p', '4', str(unsigned), str(aligned)], check=True)
    subprocess.run([str(build_tools / f'apksigner{bat}'), 'sign', '--ks', str(keystore), '--ks-pass', 'pass:testtest',
                    '--out', str(final), str(aligned)], check=True)
    shutil.rmtree(work, ignore_errors=True)
    return final


header = ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
          f'package="{PACKAGE}" android:versionCode="1" android:versionName="1.0"')
body = ('>\n  <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34"/>\n'
        '  <application android:label="APK Uploader Test" android:hasCode="false"/>\n</manifest>\n')
base = build_apk('apk-uploader-testapp.apk', header + body, {'payload.bin': payload_mb * 1024 * 1024})
split = build_apk('config.en.apk', header + ' split="config.en"' + body, {'split.bin': 256 * 1024})

obb_name = f'main.1.{PACKAGE}.obb'
obb_entry = f'Android/obb/{PACKAGE}/{obb_name}'
xapk = out / 'apk-uploader-testapp.xapk'
manifest = {
    'xapk_version': 2, 'package_name': PACKAGE, 'name': 'APK Uploader Test', 'version_code': '1', 'version_name': '1.0',
    'split_apks': [{'file': f'{PACKAGE}.apk', 'id': 'base'}, {'file': 'config.en.apk', 'id': 'config.en'}],
    'expansions': [{'file': obb_entry, 'install_location': 'EXTERNAL_STORAGE', 'install_path': obb_entry}],
}
with zipfile.ZipFile(xapk, 'w', zipfile.ZIP_STORED) as archive:
    archive.writestr('manifest.json', json.dumps(manifest, indent=2))
    archive.write(base, f'{PACKAGE}.apk')
    archive.write(split, 'config.en.apk')
    archive.writestr(obb_entry, os.urandom(2 * 1024 * 1024))
split.unlink()
print(f'{base} ({base.stat().st_size} bytes)')
print(f'{xapk} ({xapk.stat().st_size} bytes), build-tools {build_tools.name}, platform {platform.name}')
