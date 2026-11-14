"""Validate the native release archives without executing target OS binaries."""
from pathlib import Path, PurePosixPath
import hashlib, io, plistlib, posixpath, shutil, stat, struct, subprocess, tarfile, zipfile

ROOT = Path(__file__).resolve().parent.parent
VERSION = (ROOT/'VERSION').read_text().strip()
EXPECTED_JAR = (ROOT/'build/nexodb-studio.jar').read_bytes()

def check(condition, message):
    if not condition: raise AssertionError(message)

def safe(name):
    check(not name.startswith('/') and '..' not in PurePosixPath(name).parts, 'Unsafe path: '+name)

def shell_syntax(payload, name):
    check(b'\r' not in payload, 'Windows line endings in Unix launcher')
    bash=shutil.which('bash') or ('C:/Program Files/Git/bin/bash.exe' if Path('C:/Program Files/Git/bin/bash.exe').is_file() else None)
    if bash:
        folder=ROOT/'build/platform-verification'; folder.mkdir(parents=True,exist_ok=True)
        script=folder/name;script.write_bytes(payload)
        subprocess.run([bash,'-n',str(script)],check=True)

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
        shell_syntax(script,platform+'.sh')
        check(archive.extractfile(base+'app/nexodb-studio.jar').read()==EXPECTED_JAR,'Incorrect app JAR')
        desktop=archive.extractfile('usr/share/applications/com.nexodb.studio.desktop').read()
        check(desktop.startswith(b'[Desktop Entry]') and b'Name=AdisDB\n' in desktop,'Missing AdisDB desktop entry')
        check(any('/runtime/legal/' in name for name in archive.getnames()),'Missing runtime licenses')

def mac(path, platform):
    with zipfile.ZipFile(path) as archive:
        check(archive.testzip() is None,'ZIP CRC failure')
        base='AdisDB.app/Contents/'
        metadata=plistlib.loads(archive.read(base+'Info.plist'))
        check(metadata['CFBundleName']=='AdisDB','Incorrect product name')
        check(metadata['CFBundleShortVersionString']==VERSION,'Incorrect macOS app version')
        check(metadata['CFBundleIdentifier']=='com.nexodb.studio','Incorrect bundle ID')
        for name in archive.namelist():
            safe(name)
            mode=archive.getinfo(name).external_attr>>16
            if stat.S_ISLNK(mode):
                target=archive.read(name).decode()
                resolved=posixpath.normpath(posixpath.join(posixpath.dirname(name),target))
                safe(resolved); check(resolved.startswith(base+'runtime/'),'Runtime link leaves bundle')
        for name in (base+'MacOS/'+metadata['CFBundleExecutable'],'Instalar AdisDB.command',base+'runtime/Contents/Home/bin/java'):
            check((archive.getinfo(name).external_attr>>16)&0o111,'Not executable: '+name)
        launcher=archive.read(base+'MacOS/'+metadata['CFBundleExecutable'])
        check(b'\r' not in launcher and b'"$JAVA_HOME/bin/java"' in launcher,'Invalid macOS launcher')
        shell_syntax(launcher,platform+'.sh')
        shell_syntax(archive.read('Instalar AdisDB.command'),platform+'-install.sh')
        binary(archive.read(base+'runtime/Contents/Home/bin/java')[:20],platform)
        check(archive.read(base+'Resources/app/nexodb-studio.jar')==EXPECTED_JAR,'Incorrect app JAR')
        check(any('/Home/legal/' in name for name in archive.namelist()),'Missing runtime licenses')

def main():
    with zipfile.ZipFile(ROOT/'dist'/f'AdisDB-{VERSION}-win64.zip') as archive:
        check(archive.testzip() is None,'Windows ZIP CRC failure')
        check(archive.read('app/nexodb-studio.jar')==EXPECTED_JAR,'Windows app JAR differs')
        check(archive.read('AdisDB.exe')==archive.read('NexoDB Studio.exe'),'Legacy updater launcher differs')
        check(archive.read('app/AdisDB.cfg')==archive.read('app/NexoDB Studio.cfg'),'Legacy updater configuration differs')
        check('jdk.crypto.ec' in archive.read('runtime/release').decode(),'Windows TLS module missing')
    print('Windows update ZIP: OK (CRC, application and TLS module)',flush=True)
    for platform in ('linux-x64','linux-arm64','macos-x64','macos-arm64'):
        path=ROOT/'dist'/f'AdisDB-{VERSION}-{platform}.{ "deb" if platform.startswith("linux") else "zip"}'
        expected=path.with_suffix(path.suffix+'.sha256').read_text().split()[0]
        check(hashlib.sha256(path.read_bytes()).hexdigest()==expected,'Package SHA-256 mismatch')
        (deb if platform.startswith('linux') else mac)(path,platform)
        print(path.name+': OK (SHA-256, native CPU, permissions, runtime, application and licenses)',flush=True)

if __name__=='__main__': main()
