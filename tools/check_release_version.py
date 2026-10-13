"""Fail before building if app.properties, BuildInfo.VERSION, the tag and the release notes disagree."""
import os
import pathlib
import re

root = pathlib.Path(__file__).resolve().parents[1]
props = dict(line.split('=', 1) for line in (root / 'app.properties').read_text().splitlines() if '=' in line)
version = props['version'].strip()
assert re.fullmatch(r'\d+\.\d+\.\d+', version), version
source = (root / 'app/src/main/kotlin/apkuploader/BuildInfo.kt').read_text()
assert f'const val VERSION = "{version}"' in source, 'BuildInfo.VERSION differs from app.properties'
assert (root / f'docs/RELEASE-{version}.md').is_file(), f'docs/RELEASE-{version}.md is missing'
ref = os.getenv('GITHUB_REF', '')
if ref.startswith('refs/tags/'):
    assert ref == f'refs/tags/v{version}', (ref, version)
print(f'APK Uploader {version}: consistent')
