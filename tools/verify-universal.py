"""Verify the universal distribution's contents, Java version and Unix launchers."""
from pathlib import Path
from io import BytesIO
import hashlib
import plistlib
import struct
import zipfile

root = Path(__file__).resolve().parent.parent
version = (root/'VERSION').read_text().strip()
archive = root/'dist'/f'AdisDB-{version}-universal.zip'
checksum = archive.with_suffix('.zip.sha256').read_text().split()[0]
assert hashlib.sha256(archive.read_bytes()).hexdigest() == checksum
prefix = 'AdisDB/'
with zipfile.ZipFile(archive) as package:
    for name in ('run.sh','run.cmd','install.sh','Info.plist','README.txt','adisdb-icon.png','app/nexodb-studio.jar'):
        assert prefix+name in package.namelist(), name
    for name in ('run.sh','install.sh'):
        entry = package.getinfo(prefix+name)
        assert entry.external_attr >> 16 & 0o111, f'{name} is not executable'
        assert b'\r' not in package.read(entry), f'{name} has Windows line endings'
    info = plistlib.loads(package.read(prefix+'Info.plist'))
    assert info['CFBundleShortVersionString'] == version
    assert not any(name.endswith(('.exe','.dll')) for name in package.namelist()), 'Windows-only runtime in universal distribution'
    with zipfile.ZipFile(BytesIO(package.read(prefix+'app/nexodb-studio.jar'))) as jar:
        application = jar.read('com/nexodb/App.class')
        assert struct.unpack('>H', application[6:8])[0] == 61, 'Requires a Java version newer than 17'
        assert version.encode() in jar.read('com/nexodb/update/UpdateService.class'), 'Incorrect version'
        assert 'com/nexodb/i18n/messages_en.properties' in jar.namelist()
        assert 'com/nexodb/ui/ThemeManager.class' in jar.namelist()
    for folder in ('lib','drivers'):
        for file in (root/'build'/folder).glob('*.jar'):
            assert package.read(prefix+f'app/{folder}/{file.name}') == file.read_bytes(), f'Missing/changed dependency: {file.name}'
print(f'Universal {version}: SHA-256, Java 17 classes, dependencies, macOS metadata and Unix permissions verified.')
