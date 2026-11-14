"""Create the Java 17+ distribution from an existing build, on any host."""
from pathlib import Path
import zipfile
import hashlib

root = Path(__file__).resolve().parent.parent
version = (root / 'VERSION').read_text(encoding='utf-8').strip()
archive = root / 'dist' / f'AdisDB-{version}-universal.zip'
archive.parent.mkdir(parents=True, exist_ok=True)
jar = root / 'build/nexodb-studio.jar'
if not jar.is_file():
    raise SystemExit('Build the application first using build.ps1 or build.sh.')

with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as output:
    def add(name, content, executable=False):
        entry = zipfile.ZipInfo('AdisDB/' + name)
        entry.create_system = 3
        entry.external_attr = ((0o100755 if executable else 0o100644) << 16)
        entry.compress_type = zipfile.ZIP_DEFLATED
        output.writestr(entry, content)
    add('app/nexodb-studio.jar', jar.read_bytes())
    for directory in ('lib', 'drivers'):
        for file in sorted((root / 'build' / directory).glob('*.jar')):
            add(f'app/{directory}/{file.name}', file.read_bytes())
    for file in sorted((root / 'packaging/universal').iterdir()):
        content = file.read_text(encoding='utf-8').replace('@VERSION@', version)
        add(file.name, content.encode('utf-8'), file.suffix == '.sh')
    add('adisdb-icon.png', (root / 'resources/com/nexodb/assets/adisdb-icon.png').read_bytes())
checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
archive.with_suffix('.zip.sha256').write_text(f'{checksum}  {archive.name}\n', encoding='ascii')
print(archive)
