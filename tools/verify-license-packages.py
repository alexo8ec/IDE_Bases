"""Check public/private separation without printing or exporting any administrative secret."""
from pathlib import Path
import io
import zipfile

root=Path(__file__).resolve().parent.parent
public=(root/'resources/com/nexodb/licensing/public-key.der').read_bytes()
assert len(public)==44, 'Unexpected Ed25519 public key'
private=root/'.signing'
secrets=[]
for name in ('issuer.nxvault','contraseña-inicial.txt','licencia-administrador.nexolicense'):
    path=private/name
    if path.is_file(): secrets.append(path.read_bytes().strip())

def jar_check(data,issuer):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        assert jar.testzip() is None
        names=jar.namelist()
        assert jar.read('com/nexodb/licensing/public-key.der')==public
        assert any(n.startswith('com/nexodb/issuer/') for n in names)==issuer, 'Issuer code in wrong distribution'
        for name in names:
            assert not name.endswith(('.nxvault','.nexolicense')) and not name.startswith('.signing/')
            if name.endswith('/'):
                continue
            content=jar.read(name)
            assert all(secret not in content for secret in secrets if secret), 'An administrative secret was bundled'

jar_check((root/'build/nexodb-studio.jar').read_bytes(),False)
issuer=root/'license-issuer/private-dist/NexoDB-License-Manager-1.0.1-win64.zip'
with zipfile.ZipFile(issuer) as archive:
    assert archive.testzip() is None
    cfg=archive.read('NexoDB License Manager/app/NexoDB License Manager.cfg').decode('utf-8')
    options=[line for line in cfg.splitlines() if line.startswith('java-options=')]
    assert options==['java-options=-Djpackage.app-version=1.0.1','java-options=-Dfile.encoding=UTF-8'], 'Malformed native launcher options'
    settings=archive.read('NexoDB License Manager/app/issuer.properties').decode('utf-8')
    assert settings.strip()=='vault.path='+str(private/'issuer.nxvault').replace('\\','/'), 'Incorrect authority location'
    for name in archive.namelist():
        assert not name.endswith(('.nxvault','.nexolicense','contraseña-inicial.txt'))
        if name.endswith('/app/nexodb-license-issuer.jar'):
            jar_check(archive.read(name),True)
print('License distributions: OK (matching public key, issuer separated, encrypted authority/recovery/admin license excluded)')
