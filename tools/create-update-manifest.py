"""Combine real release files into a manifest. Missing platforms are never invented."""
from pathlib import Path
from urllib.parse import quote, urlparse
import argparse
import hashlib
import json

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-url', required=True, help='Public HTTPS directory containing the release files')
parser.add_argument('--windows-x64', type=Path)
parser.add_argument('--windows-arm64', type=Path)
parser.add_argument('--linux-x64', type=Path)
parser.add_argument('--linux-arm64', type=Path)
parser.add_argument('--macos-x64', type=Path)
parser.add_argument('--macos-arm64', type=Path)
parser.add_argument('--output', type=Path, default=root/'dist/update-manifest.json')
parser.add_argument('--notes', default='NexoDB Studio: idiomas, temas y mejoras del actualizador HTTPS.')
args = parser.parse_args()
url = urlparse(args.base_url)
if url.scheme != 'https' or not url.hostname or url.username or url.password:
    parser.error('--base-url must be a public HTTPS directory without credentials')
platforms = {}
for key in ('windows-x64','windows-arm64','linux-x64','linux-arm64','macos-x64','macos-arm64'):
    file = getattr(args, key.replace('-', '_'))
    if file is None: continue
    if not file.is_file(): parser.error(f'File does not exist: {file}')
    platforms[key] = {'downloadUrl': args.base_url.rstrip('/') + '/' + quote(file.name),
                      'sha256': hashlib.sha256(file.read_bytes()).hexdigest()}
if not platforms: parser.error('Supply at least one real platform package')
manifest = {'version': (root/'VERSION').read_text().strip()}
# Keep root Windows fields for installed clients older than 0.11.
if 'windows-x64' in platforms: manifest.update(platforms['windows-x64'])
manifest.update(notes=args.notes, platforms=platforms)
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(args.output)
