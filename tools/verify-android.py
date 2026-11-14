"""Verify the distributable APK, public license key and Android update manifest."""
from pathlib import Path
from urllib.parse import urlparse
import argparse
import hashlib
import json
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', required=True, type=Path)
parser.add_argument('--previous',type=Path,help='Previously distributed APK whose package/signing identity must be retained')
args = parser.parse_args()
version = (ROOT/'android/VERSION').read_text(encoding='utf-8').strip()
major, minor, patch = map(int, version.split('.'))
code = major * 10000 + minor * 100 + patch
apk = ROOT/f'dist/AdisDB-{version}-android.apk'
manifest = json.loads((ROOT/'dist/android-update-manifest.json').read_text(encoding='utf-8-sig'))
assert manifest['version'] == version and manifest['versionCode'] == code, 'Android manifest version mismatch'
assert manifest['sha256'] == hashlib.sha256(apk.read_bytes()).hexdigest(), 'APK hash mismatch'
url = urlparse(manifest['downloadUrl'])
assert url.scheme == 'https' and url.hostname and url.username is None and url.password is None
assert Path(url.path).name == apk.name, 'Incorrect APK download URL'
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None, 'Corrupt APK'
    assert archive.read('com/nexodb/licensing/public-key.der') == (ROOT/'resources/com/nexodb/licensing/public-key.der').read_bytes()
    for name in archive.namelist():
        assert not name.startswith(('.signing/', 'com/nexodb/issuer/'))
        assert not name.endswith(('.jks', '.keystore', '.nxvault', '.nexolicense', 'android-signing.properties', 'contraseña-inicial.txt'))
        if re.fullmatch(r'classes\d*\.dex', name):
            assert b'Lcom/nexodb/issuer/' not in archive.read(name), 'License issuer code bundled in client'
            assert b'Lcom/nexodb/android/AndroidResultUiTest;' not in archive.read(name), 'Instrumentation code bundled in client'
    assert 'assets/query-results-test.js' not in archive.namelist(), 'UI test harness bundled in client'
    assert all('assets/'+name in archive.namelist() for name in ('index.html','app.js','app.css','tools.js'))
    assert b'AdisDB' in archive.read('assets/index.html') and 'assets/adisdb-icon.svg' in archive.namelist()
tools = args.sdk/'build-tools/35.0.0'
badging = subprocess.check_output([str(tools/'aapt.exe'), 'dump', 'badging', str(apk)], text=True, encoding='utf-8')
assert "name='com.nexodb.android'" in badging
assert f"versionName='{version}'" in badging and f"versionCode='{code}'" in badging
assert "sdkVersion:'26'" in badging
assert 'application-debuggable' not in badging, 'Distributable APK must not be debuggable'
assert "application-label:'AdisDB'" in badging,'Incorrect Android product name'
subprocess.run([str(tools/'apksigner.bat'), 'verify', '--min-sdk-version', '26', str(apk)], check=True)
if args.previous:
    def identity(file):
        text=subprocess.check_output([str(tools/'apksigner.bat'),'verify','--print-certs',str(file)],text=True,encoding='utf-8')
        return re.findall(r'certificate SHA-256 digest: (\w+)',text)
    assert identity(apk) and identity(apk)==identity(args.previous),'Android signing identity changed'
    old=subprocess.check_output([str(tools/'aapt.exe'),'dump','badging',str(args.previous)],text=True,encoding='utf-8')
    assert "name='com.nexodb.android'" in old,'Previous APK is a different application'
    assert int(re.search(r"versionCode='(\d+)'",old)[1])<code,'Android versionCode must increase'
    print('Android rebrand: same applicationId and signing certificate, higher versionCode; existing installation can update.')
print(f'Android {version}: release APK, Android 8+ signature, public license key, files, versionCode and update SHA-256 verified.')
