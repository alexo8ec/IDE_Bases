"""Verify the real AdisDB distributables and write the public release inventory."""
from pathlib import Path
from urllib.parse import quote, unquote, urlparse
import argparse, hashlib, io, json, zipfile

root=Path(__file__).resolve().parent.parent
dist=root/'dist'
version=(root/'VERSION').read_text().strip()
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-url',default='https://raw.githubusercontent.com/alexo8ec/IDE_Bases/main/dist')
args=parser.parse_args()
base=args.base_url.rstrip('/')
if urlparse(base).scheme!='https':raise ValueError('HTTPS required')
def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        while block:=stream.read(1024*1024):digest.update(block)
    return digest.hexdigest()
manifest=json.loads((dist/'update-manifest.json').read_text(encoding='utf-8-sig'))
assert manifest['version']==version and len(manifest['platforms'])==5
installer=dist/'installer'/f'AdisDB-{version}.exe'
portable=dist/f'AdisDB-{version}-win64.zip'
files=[installer]
for platform,entry in manifest['platforms'].items():
    name=Path(unquote(urlparse(entry['downloadUrl']).path)).name
    assert name.startswith(f'AdisDB-{version}-')
    path=dist/name
    assert path.is_file() and sha(path)==entry['sha256'],platform
    files.append(path)
assert manifest['sha256']==manifest['platforms']['windows-x64']['sha256']
with zipfile.ZipFile(portable) as archive:
    assert archive.read('AdisDB.exe')==archive.read('NexoDB Studio.exe')
    assert archive.read('app/AdisDB.cfg')==archive.read('app/NexoDB Studio.cfg')
    with zipfile.ZipFile(io.BytesIO(archive.read('app/nexodb-studio.jar'))) as app:
        assert version.encode() in app.read('com/nexodb/update/UpdateService.class')
        assert b'AdisDB' in app.read('com/nexodb/util/ProductBrand.class')
        assert app.read('com/nexodb/licensing/public-key.der')==(root/'resources/com/nexodb/licensing/public-key.der').read_bytes()
        assert not any(name.startswith(('com/nexodb/issuer/','.signing/')) or name.endswith(('.nxvault','.nexolicense')) for name in app.namelist())
def item(path):return {'file':path.name,'url':base+'/'+quote(path.relative_to(dist).as_posix()),'sha256':sha(path)}
release={'product':'AdisDB','suite':'AdisOne','version':version,'publisher':'Alex Mite Salazar','contact':'alexo8ec@hotmail.com','notes':manifest['notes'],'installer':item(installer),'portable':item(portable),'platforms':manifest['platforms']}
universal=dist/f'AdisDB-{version}-universal.zip'
if universal.is_file():release['universal']=item(universal);files.append(universal)
mobile=json.loads((dist/'android-update-manifest.json').read_text(encoding='utf-8-sig'))
apk=dist/Path(unquote(urlparse(mobile['downloadUrl']).path)).name
assert mobile['version']==(root/'android/VERSION').read_text().strip() and sha(apk)==mobile['sha256']
release['android']=mobile
files.append(apk)
(dist/'release-manifest.json').write_text(json.dumps(release,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(dist/'SHA256SUMS.txt').write_text(''.join(f'{sha(path)}  {path.relative_to(dist).as_posix()}\n' for path in files),encoding='ascii')
print(f'AdisDB {version}: {len(files)} real distributables, legacy launcher aliases, public key, version and SHA-256 verified.')
