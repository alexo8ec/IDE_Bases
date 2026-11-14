"""Build Linux DEB and macOS app installers with verified platform-specific Temurin JREs.

Runs on any build host. It does not cross-compile jpackage launchers: each package
contains the actual native Java runtime for its target OS and CPU.
"""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import argparse, copy, gzip, hashlib, io, json, os, plistlib, stat, struct, tarfile
from dataclasses import dataclass
import urllib.request, urllib.parse, zipfile

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT/'build/runtimes'
PLATFORMS = {'linux-x64':('linux','x64'), 'linux-arm64':('linux','aarch64'),
             'macos-x64':('mac','x64'), 'macos-arm64':('mac','aarch64')}
RELEASE = None

@dataclass
class RuntimeEntry:
    archive: tarfile.TarFile
    member: tarfile.TarInfo
    relative: str

def read_json(url):
    with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'AdisDB-packager','Accept':'application/vnd.github+json'}),timeout=40) as response:
        return json.load(response)

def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        while block:=stream.read(1024*1024): digest.update(block)
    return digest.hexdigest()

def download(url, path):
    if not url.startswith('https://'): raise ValueError('HTTPS required')
    partial=path.with_suffix(path.suffix+'.part')
    try:
        with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'NexoDB-packager'}),timeout=60) as response, partial.open('wb') as output:
            while block:=response.read(1024*1024): output.write(block)
        partial.replace(path)
    except Exception:
        partial.unlink(missing_ok=True)
        raise

def runtime(platform):
    CACHE.mkdir(parents=True,exist_ok=True)
    os_name,cpu=PLATFORMS[platform]
    prefix=f'OpenJDK17U-jre_{cpu}_{os_name}_hotspot_'
    candidates=[asset for asset in RELEASE['assets'] if asset['name'].startswith(prefix) and asset['name'].endswith('.tar.gz')]
    if len(candidates)!=1: raise ValueError('Cannot identify official Temurin runtime: '+platform)
    package=dict(candidates[0]); name=package['name']; package['link']=package['browser_download_url']
    digest=package.get('digest') or ''
    if digest.startswith('sha256:'): package['checksum']=digest.split(':',1)[1]
    else:
        with urllib.request.urlopen(package['link']+'.sha256.txt',timeout=30) as response: package['checksum']=response.read().decode().split()[0]
    if Path(name).name!=name or not name.endswith('.tar.gz'): raise ValueError('Unexpected runtime archive name')
    archive=CACHE/name
    if not archive.is_file() or sha(archive)!=package['checksum']:
        print(f'Downloading verified Temurin JRE: {platform} ({package["size"]//1024//1024} MiB)',flush=True)
        download(package['link'],archive)
    if sha(archive)!=package['checksum']:
        archive.unlink(missing_ok=True); raise ValueError('JRE SHA-256 mismatch')
    result={'platform':platform,'archive':str(archive),'sha256':package['checksum'],'version':RELEASE['tag_name'],'source':package['link']}
    print(f'JRE {platform}: SHA-256 verified.',flush=True)
    return result

def application_files():
    yield 'app/nexodb-studio.jar', ROOT/'build/nexodb-studio.jar'
    for folder in ('lib','drivers'):
        for file in sorted((ROOT/'build'/folder).glob('*.jar')): yield f'app/{folder}/{file.name}', file

def notices():
    parts=['AdisDB third-party notices\nRuntime: Eclipse Temurin / OpenJDK 17. See runtime/legal and runtime NOTICE/LICENSE.\n']
    for notice in sorted((ROOT/'third-party').glob('*/NOTICE.md')):
        parts.append('\n=== '+str(notice.relative_to(ROOT))+' ===\n'+notice.read_text(encoding='utf-8'))
    return '\n'.join(parts).encode('utf-8')

def tar_bytes(entries):
    buffer=io.BytesIO()
    with gzip.GzipFile(fileobj=buffer,mode='wb',mtime=0) as compressed:
        with tarfile.open(fileobj=compressed,mode='w',format=tarfile.PAX_FORMAT) as archive:
            for name, source, mode in entries:
                if isinstance(source,RuntimeEntry):
                    original=source.member
                    info=copy.copy(original); info.name=name; info.uid=info.gid=0; info.uname=info.gname='root'; info.mtime=0; info.mode &= 0o777; info.pax_headers={}
                    if original.islnk():
                        linked=source.archive.getmember(original.linkname); info.type=tarfile.REGTYPE; info.linkname=''; info.size=linked.size
                    if info.isfile():
                        with source.archive.extractfile(original) as stream: archive.addfile(info,stream)
                    else: archive.addfile(info)
                elif isinstance(source,Path):
                    info=archive.gettarinfo(str(source),arcname=name)
                    info.uid=info.gid=0; info.uname=info.gname='root'; info.mtime=0; info.mode=mode
                    if info.isfile():
                        with source.open('rb') as stream: archive.addfile(info,stream)
                    else: archive.addfile(info)
                else:
                    info=tarfile.TarInfo(name); info.size=len(source); info.mode=mode; info.mtime=0
                    info.uid=info.gid=0; info.uname=info.gname='root'; archive.addfile(info,io.BytesIO(source))
    return buffer.getvalue()

def ar_member(output,name,payload):
    header=f'{name+"/":<16}{0:<12}{0:<6}{0:<6}{"100644":<8}{len(payload):<10}`\n'.encode('ascii')
    if len(header)!=60: raise ValueError('Invalid Debian ar header')
    output.write(header); output.write(payload)
    if len(payload)%2: output.write(b'\n')

def linux(platform,files,version):
    architecture='amd64' if platform.endswith('x64') else 'arm64'
    entries=[]
    for file in files: entries.append(('opt/nexodb-studio/runtime/'+file.relative,file,file.member.mode))
    for name,file in application_files(): entries.append(('opt/nexodb-studio/'+name,file,0o644))
    launcher=b'''#!/bin/sh
set -eu
root=/opt/nexodb-studio
exec "$root/runtime/bin/java" -Dfile.encoding=UTF-8 -Djava.net.useSystemProxies=true -Xms16m -Xmx256m -jar "$root/app/nexodb-studio.jar" "$@"
'''
    entries += [('opt/nexodb-studio/bin/nexodb-studio',launcher,0o755),
                ('usr/share/applications/com.nexodb.studio.desktop',b'''[Desktop Entry]
Type=Application
Name=AdisDB
Comment=Universal JDBC database client
Exec=/opt/nexodb-studio/bin/nexodb-studio
Icon=/opt/nexodb-studio/adisdb-icon.png
Terminal=false
Categories=Development;Database;
''',0o644), ('opt/nexodb-studio/adisdb-icon.png',ROOT/'resources/com/nexodb/assets/adisdb-icon.png',0o644),
                ('opt/nexodb-studio/THIRD-PARTY-NOTICES.txt',notices(),0o644)]
    installed=sum(file.member.size if isinstance(file,RuntimeEntry) else file.stat().st_size if isinstance(file,Path) and file.is_file() else len(file) if isinstance(file,bytes) else 0 for _,file,_ in entries)//1024
    control=f'''Package: nexodb-studio
Version: {version}
Architecture: {architecture}
Maintainer: AdisDB <alexo8ec@hotmail.com>
Section: database
Priority: optional
Installed-Size: {installed}
Depends: libc6 (>= 2.17), libx11-6, libxext6, libxi6, libxrender1, libxtst6, libfreetype6, fontconfig, zlib1g, libasound2 | libasound2t64
Description: AdisDB universal database client
 Includes a native OpenJDK 17 runtime. No separate Java installation required.
'''.encode()
    output=ROOT/'dist'/f'AdisDB-{version}-{platform}.deb'
    with output.open('wb') as stream:
        stream.write(b'!<arch>\n'); ar_member(stream,'debian-binary',b'2.0\n')
        ar_member(stream,'control.tar.gz',tar_bytes([('control',control,0o644)]))
        ar_member(stream,'data.tar.gz',tar_bytes(entries))
    return output

def zip_add(archive,name,source,mode=0o644):
    info=zipfile.ZipInfo(name); info.create_system=3; info.compress_type=zipfile.ZIP_DEFLATED
    if isinstance(source,RuntimeEntry):
        member=source.member
        if member.issym():
            info.external_attr=(stat.S_IFLNK|0o777)<<16; archive.writestr(info,member.linkname.encode()); return
        if member.isdir(): return
        info.external_attr=(stat.S_IFREG|(member.mode & 0o777))<<16
        with source.archive.extractfile(member) as input, archive.open(info,'w') as output:
            while block:=input.read(1024*1024): output.write(block)
        return
    if isinstance(source,Path):
        if source.is_symlink():
            info.external_attr=(stat.S_IFLNK|0o777)<<16; archive.writestr(info,os.readlink(source).encode()); return
        if source.is_dir(): return
        payload=source.read_bytes()
    else: payload=source
    info.external_attr=(stat.S_IFREG|mode)<<16; archive.writestr(info,payload)

def macos(platform,files,version):
    output=ROOT/'dist'/f'AdisDB-{version}-{platform}.zip'
    app='AdisDB.app/Contents/'
    launcher=b'''#!/bin/sh
set -eu
contents=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
export JAVA_HOME="$contents/runtime/Contents/Home"
exec "$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Djava.net.useSystemProxies=true -Xms16m -Xmx256m -jar "$contents/Resources/app/nexodb-studio.jar" "$@"
'''
    installer='''#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
printf 'Cierra AdisDB antes de instalar. Presiona Enter para continuar. / Close AdisDB, then press Enter.\\n'
read -r confirmation
mkdir -p "$HOME/Applications"
/usr/bin/ditto "$root/AdisDB.app" "$HOME/Applications/AdisDB.app"
printf 'Instalado en ~/Applications/AdisDB.app / Installed.\\n'
/usr/bin/open "$HOME/Applications/AdisDB.app"
'''.encode()
    icon=(ROOT/'resources/com/nexodb/assets/adisdb-icon.png').read_bytes()
    width,height=struct.unpack('>II',icon[16:24]); codes={128:b'ic07',256:b'ic08',512:b'ic09',1024:b'ic10'}
    code=codes.get(width) if width==height else None
    metadata={'CFBundleName':'AdisDB','CFBundleIdentifier':'com.nexodb.studio','CFBundleExecutable':'AdisDB',
              'CFBundlePackageType':'APPL','CFBundleShortVersionString':version,'CFBundleVersion':version,'NSHighResolutionCapable':True}
    if code: metadata['CFBundleIconFile']='adisdb.icns'
    with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED) as archive:
        zip_add(archive,app+'Info.plist',plistlib.dumps(metadata))
        zip_add(archive,app+'MacOS/AdisDB',launcher,0o755)
        zip_add(archive,'Instalar AdisDB.command',installer,0o755)
        zip_add(archive,'LEEME.txt',('AdisDB '+version+' / '+platform+'\n\nAbre Instalar AdisDB.command, o arrastra AdisDB.app a Aplicaciones.\nIncluye Java 17 para esta arquitectura; no requiere instalar Java.\nAplicacion sin firma Developer ID ni notarizacion. Usa la autorizacion de apertura normal de macOS si la solicita.\n\nOpen the installer command, or drag the app to Applications. Native Java 17 is included.\nThis app is not Developer ID signed or notarized.\n').encode())
        for file in files: zip_add(archive,app+'runtime/Contents/'+file.relative,file)
        for name,file in application_files(): zip_add(archive,app+'Resources/'+name,file)
        zip_add(archive,app+'Resources/THIRD-PARTY-NOTICES.txt',notices())
        if code:
            chunk=code+struct.pack('>I',len(icon)+8)+icon
            zip_add(archive,app+'Resources/adisdb.icns',b'icns'+struct.pack('>I',len(chunk)+8)+chunk)
    return output

def build(asset,version):
    platform=asset['platform']; archive=Path(asset['archive'])
    if sha(archive)!=asset['sha256']: raise ValueError('Runtime cache changed')
    # Repackage members directly: Windows cannot preserve Unix execute bits/symlinks on extraction.
    with tarfile.open(archive,'r:gz') as runtime_archive:
        members=runtime_archive.getmembers()
        candidates=[member for member in members if member.name.endswith('/bin/java') and member.isfile()]
        if len(candidates)!=1: raise ValueError('Cannot locate native Java runtime')
        java=candidates[0]
        prefix=java.name.rsplit('bin/java',1)[0]
        if platform.startswith('macos'): prefix=prefix.removesuffix('Home/')
        files=[]
        for member in members:
            if not member.name.startswith(prefix) or member.name==prefix: continue
            relative=member.name[len(prefix):]
            if relative.startswith('/') or '..' in Path(relative).parts: raise ValueError('Unsafe runtime path')
            if not (member.isfile() or member.isdir() or member.issym() or member.islnk()): raise ValueError('Unsupported runtime entry')
            files.append(RuntimeEntry(runtime_archive,member,relative))
        with runtime_archive.extractfile(java) as stream: header=stream.read(20)
        if platform.startswith('linux') and (header[:4]!=b'\x7fELF' or struct.unpack('<H',header[18:20])[0]!=(62 if platform.endswith('x64') else 183)): raise ValueError('Incorrect Linux runtime architecture')
        if platform.startswith('macos') and (header[:4]!=b'\xcf\xfa\xed\xfe' or struct.unpack('<I',header[4:8])[0]!=(0x1000007 if platform.endswith('x64') else 0x100000C)): raise ValueError('Incorrect macOS runtime architecture')
        output=linux(platform,files,version) if platform.startswith('linux') else macos(platform,files,version)
    if output.stat().st_size>=100*1024*1024: raise ValueError('Package exceeds GitHub Git file limit')
    output.with_suffix(output.suffix+'.sha256').write_text(sha(output)+'  '+output.name+'\n',encoding='ascii')
    print(f'Installer ready: {output.name} ({output.stat().st_size//1024//1024} MiB)',flush=True)
    return {'platform':platform,'file':str(output),'sha256':sha(output),'runtime':asset}

def main():
    global RELEASE
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download-only',action='store_true'); parser.add_argument('--offline',action='store_true')
    args=parser.parse_args(); CACHE.mkdir(parents=True,exist_ok=True)
    catalog=CACHE/'catalog.json'
    if args.offline: assets=json.loads(catalog.read_text())
    else:
        RELEASE=read_json('https://api.github.com/repos/adoptium/temurin17-binaries/releases/latest')
        with ThreadPoolExecutor(max_workers=4) as pool: assets=list(pool.map(runtime,PLATFORMS))
        catalog.write_text(json.dumps(assets,indent=2)+'\n')
    if args.download_only: return
    version=(ROOT/'VERSION').read_text().strip()
    if not (ROOT/'build/nexodb-studio.jar').is_file(): raise ValueError('Compile the application first')
    results=[build(asset,version) for asset in assets]
    (ROOT/'dist/platform-packages.json').write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__': main()
