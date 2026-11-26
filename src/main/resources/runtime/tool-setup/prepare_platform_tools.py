"""Prepare the ADB files used by the app, preserving Google's notices."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
URL = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
FILES = ['adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt', 'source.properties']


def prepare(output, archive=None, offline=False, cache=None):
    output = output.resolve()
    if all((output / name).is_file() for name in FILES):
        print('ADB already prepared: ' + str(output))
        return
    if archive is None:
        archive = cache or ROOT / 'tools' / 'platform-tools-cache' / 'platform-tools-windows.zip'
        if not archive.exists():
            if offline:
                raise FileNotFoundError('No cached Platform Tools ZIP. Pass --archive PATH or prepare online first.')
            archive.parent.mkdir(parents=True, exist_ok=True)
            partial = archive.with_suffix('.part')
            with urllib.request.urlopen(URL, timeout=60) as source, partial.open('wb') as target:
                shutil.copyfileobj(source, target)
            partial.replace(archive)
    with zipfile.ZipFile(archive) as source:
        # Read only known files, never extract arbitrary ZIP paths.
        contents = {}
        for name in FILES:
            info = source.getinfo('platform-tools/' + name)
            if info.file_size > 32 * 1024 * 1024:
                raise ValueError('Unexpected file size: ' + name)
            contents[name] = source.read(info)
        for name in FILES[:3]:
            if not contents[name].startswith(b'MZ'):
                raise ValueError('Not a Windows executable: ' + name)
    output.mkdir(parents=True, exist_ok=True)
    for name, data in contents.items():
        (output / name).write_bytes(data)
    with archive.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    manifest = {'source': URL if archive.name == 'platform-tools-windows.zip' else 'user-supplied archive',
                'archive_sha256': digest, 'revision': contents['source.properties'].decode().strip(),
                'note': 'Digest recorded locally; not compared against a vendor checksum.'}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print('ADB ready: ' + str(output))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'DroidDebugHunter' / 'platform-tools')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--cache', type=Path, help='Path to the local ZIP cache.')
    args = parser.parse_args()
    prepare(args.output, args.archive, args.offline, args.cache)
