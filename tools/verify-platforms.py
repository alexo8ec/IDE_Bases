"""Validate the native release archives without executing target OS binaries."""
from pathlib import Path, PurePosixPath
import hashlib, io, plistlib, posixpath, stat, struct, tarfile, zipfile

ROOT = Path(__file__).resolve().parent.parent
VERSION = (ROOT/'VERSION').read_text().strip()
EXPECTED_JAR = (ROOT/'build/nexodb-studio.jar').read_bytes()

def check(condition, message):
    if not condition: raise AssertionError(message)

def safe(name):
    check(not name.startswith('/') and '..' not in PurePosixPath(name).parts, 'Unsafe path: '+name)

def binary(header, platform):
    if platform.startswith('linux'):
        check(header[:4] == b'\x7fELF', 'Not an ELF executable')
        check(struct.unpack('<H',header[18:20])[0] == (62 if platform.endswith('x64') else 183), 'Incorrect ELF CPU')
    else:
        check(header[:4] == b'\xcf\xfa\xed\xfe', 'Not a Mach-O executable')
        check(struct.unpack('<I',header[4:8])[0] == (0x1000007 if platform.endswith('x64') else 0x100000C), 'Incorrect Mach-O CPU')

def deb(path, platform):
    entries={}
    with path.open('rb') as stream:
        check(stream.read(8)==b'!<arch>\n', 'Invalid DEB signature')
        while header:=stream.read(60):
            check(len(header)==60 and header[58:]==b'`\n','Invalid ar header')
            name=header[:16].decode().strip().rstrip('/')
            size=int(header[48:58]); entries[name]=stream.read(size)
            if size%2: stream.read(1)
    check(set(entries)=={'debian-binary','control.tar.gz','data.tar.gz'},'Invalid DEB members')
    check(entries['debian-binary']==b'2.0\n','Unsupported DEB version')
    with tarfile.open(fileobj=io.BytesIO(entries['control.tar.gz']),mode='r:gz') as archive:
        control=archive.extractfile('control').read().decode()
        check('Version: '+VERSION+'\n' in control,'Incorrect DEB version')
        check('Architecture: '+('amd64' if platform.endswith('x64') else 'arm64')+'\n' in control,'Incorrect DEB architecture')
    with tarfile.open(fileobj=io.BytesIO(entries['data.tar.gz']),mode='r:gz') as archive:
        for member in archive.getmembers():
            safe(member.name)
            if member.issym():
                resolved=posixpath.normpath(posixpath.join(posixpath.dirname(member.name),member.linkname))
                safe(resolved); check(resolved.startswith('opt/nexodb-studio/runtime/'),'Runtime link leaves bundle')
        base='opt/nexodb-studio/'
        java=archive.getmember(base+'runtime/bin/java')
        check(java.mode&0o111,'Java is not executable')
        binary(archive.extractfile(java).read(20),platform)
        launcher=archive.getmember(base+'bin/nexodb-studio')
        check(launcher.mode&0o111,'Launcher is not executable')
        script=archive.extractfile(launcher).read()
        check(b'\r' not in script and b'"$root/runtime/bin/java"' in script,'Invalid Linux launcher')
        check(archive.extractfile(base+'app/nexodb-studio.jar').read()==EXPECTED_JAR,'Incorrect app JAR')
        check(archive.extractfile('usr/share/applications/com.nexodb.studio.desktop').read().startswith(b'[Desktop Entry]'),'Missing desktop entry')
        check(any('/runtime/legal/' in name for name in archive.getnames()),'Missing runtime licenses')

def mac(path, platform):
    with zipfile.ZipFile(path) as archive:
        check(archive.testzip() is None,'ZIP CRC failure')
        base='NexoDB Studio.app/Contents/'
        metadata=plistlib.loads(archive.read(base+'Info.plist'))
        check(metadata['CFBundleShortVersionString']==VERSION,'Incorrect macOS app version')
        check(metadata['CFBundleIdentifier']=='com.nexodb.studio','Incorrect bundle ID')
        for name in archive.namelist():
            safe(name)
            mode=archive.getinfo(name).external_attr>>16
            if stat.S_ISLNK(mode):
                target=archive.read(name).decode()
                resolved=posixpath.normpath(posixpath.join(posixpath.dirname(name),target))
                safe(resolved); check(resolved.startswith(base+'runtime/'),'Runtime link leaves bundle')
        for name in (base+'MacOS/'+metadata['CFBundleExecutable'],'Instalar NexoDB Studio.command',base+'runtime/Contents/Home/bin/java'):
            check((archive.getinfo(name).external_attr>>16)&0o111,'Not executable: '+name)
        launcher=archive.read(base+'MacOS/'+metadata['CFBundleExecutable'])
        check(b'\r' not in launcher and b'"$JAVA_HOME/bin/java"' in launcher,'Invalid macOS launcher')
        binary(archive.read(base+'runtime/Contents/Home/bin/java')[:20],platform)
        check(archive.read(base+'Resources/app/nexodb-studio.jar')==EXPECTED_JAR,'Incorrect app JAR')
        check(any('/Home/legal/' in name for name in archive.namelist()),'Missing runtime licenses')

def main():
    for platform in ('linux-x64','linux-arm64','macos-x64','macos-arm64'):
        path=ROOT/'dist'/f'NexoDB-Studio-{VERSION}-{platform}.{ "deb" if platform.startswith("linux") else "zip"}'
        expected=path.with_suffix(path.suffix+'.sha256').read_text().split()[0]
        check(hashlib.sha256(path.read_bytes()).hexdigest()==expected,'Package SHA-256 mismatch')
        (deb if platform.startswith('linux') else mac)(path,platform)
        print(path.name+': OK (SHA-256, native CPU, permissions, runtime, application and licenses)',flush=True)

if __name__=='__main__': main()
