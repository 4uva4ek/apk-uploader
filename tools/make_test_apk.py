"""Builds a harmless test APK for DeviceIntegrationTest: package dev.apkuploader.testapp,
no code, one random asset so that upload progress is visible.

Usage: python -I tools/make_test_apk.py <output-dir> [payload-mb]
Needs ANDROID_HOME (or ANDROID_SDK_ROOT) with build-tools and a platform, plus keytool on PATH
or in JAVA_HOME. The signing key is throwaway and is created next to the APK.
"""
import os
import pathlib
import shutil
import subprocess
import sys

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

work = out / 'work'
shutil.rmtree(work, ignore_errors=True)
(work / 'assets').mkdir(parents=True)
(work / 'AndroidManifest.xml').write_text(
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
    'package="dev.apkuploader.testapp" android:versionCode="1" android:versionName="1.0">\n'
    '  <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34"/>\n'
    '  <application android:label="APK Uploader Test" android:hasCode="false"/>\n'
    '</manifest>\n', encoding='utf-8')
with open(work / 'assets' / 'payload.bin', 'wb') as payload:
    for _ in range(payload_mb):
        payload.write(os.urandom(1024 * 1024))

keystore = out / 'test-signing.p12'
if not keystore.exists():
    subprocess.run([keytool, '-genkeypair', '-keystore', str(keystore), '-storetype', 'PKCS12', '-alias', 'test',
                    '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-storepass', 'testtest',
                    '-dname', 'CN=APK Uploader test'], check=True)

unsigned = work / 'unsigned.apk'
aligned = work / 'aligned.apk'
final = out / 'apk-uploader-testapp.apk'
subprocess.run([str(build_tools / f'aapt2{exe}'), 'link', '--manifest', str(work / 'AndroidManifest.xml'),
                '-I', str(platform / 'android.jar'), '-A', str(work / 'assets'), '-o', str(unsigned)], check=True)
subprocess.run([str(build_tools / f'zipalign{exe}'), '-f', '-p', '4', str(unsigned), str(aligned)], check=True)
subprocess.run([str(build_tools / f'apksigner{bat}'), 'sign', '--ks', str(keystore), '--ks-pass', 'pass:testtest',
                '--out', str(final), str(aligned)], check=True)
shutil.rmtree(work, ignore_errors=True)
print(f'{final} ({final.stat().st_size} bytes), build-tools {build_tools.name}, platform {platform.name}')
