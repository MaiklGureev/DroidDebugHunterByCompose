"""Prepare host-specific Android Platform Tools, including shared libraries and notices."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import stat
import tempfile
import urllib.request
import zipfile
from tool_support import POLICY, version as validate_version, copy_download


def host_os():
    return {'Windows': 'windows', 'Darwin': 'macos', 'Linux': 'linux'}[platform.system()]


def binary_matches(data, host):
    if host == 'windows':
        return data.startswith(b'MZ')
    if host == 'linux':
        return data.startswith(b'\x7fELF')
    return data[:4] in (b'\xca\xfe\xba\xbe', b'\xbe\xba\xfe\xca', b'\xcf\xfa\xed\xfe', b'\xfe\xed\xfa\xcf', b'\xca\xfe\xba\xbf', b'\xbf\xba\xfe\xca')


def prepare(output, archive=None, offline=False, cache=None, host=None, version=None):
    if version is not None:
        validate_version(version)
    host = host or host_os()
    if host not in {'windows', 'macos', 'linux'}:
        raise ValueError('Unsupported host: ' + host)
    output = Path(output).resolve()
    executable = 'adb.exe' if host == 'windows' else 'adb'
    adb = output / executable
    if adb.is_file() and (output / 'NOTICE.txt').is_file() and (output / 'source.properties').is_file():
        with adb.open('rb') as source:
            valid = binary_matches(source.read(20), host)
        if valid and (version is None or ("Pkg.Revision=" + version) in (output / "source.properties").read_text().replace(" ", "")):
            if host != 'windows':
                adb.chmod(adb.stat().st_mode | 0o111)
            print('ADB already prepared: ' + str(output))
            return
    suffix = {'windows': 'windows', 'macos': 'darwin', 'linux': 'linux'}[host]
    url = (f'https://dl.google.com/android/repository/platform-tools_r{version}-' + ('win' if host == 'windows' else suffix) + '.zip') if version else f'https://dl.google.com/android/repository/platform-tools-latest-{suffix}.zip'
    supplied = archive is not None
    archive = Path(archive or cache or output.parent / 'downloads' / f'platform-tools-{host}.zip')
    if not archive.is_file():
        if offline or supplied:
            raise FileNotFoundError('No local Platform Tools ZIP: ' + str(archive))
        archive.parent.mkdir(parents=True, exist_ok=True)
        partial = archive.with_suffix('.part')
        try:
            with urllib.request.urlopen(url, timeout=60) as source, partial.open('wb') as target:
                copy_download(source, target, archive.name)
            partial.replace(archive)
        finally:
            partial.unlink(missing_ok=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='ddhc-adb-', dir=output.parent) as folder:
        stage = Path(folder)
        count = 0
        with zipfile.ZipFile(archive) as source:
            for info in source.infolist():
                parts = PurePosixPath(info.filename)
                if parts.is_absolute() or '..' in parts.parts or '\\' in info.filename:
                    raise ValueError('Unsafe ZIP path')
                if not parts.parts or parts.parts[0] != 'platform-tools':
                    continue
                if stat.S_ISLNK(info.external_attr >> 16):
                    raise ValueError('Symlinks are not accepted in Platform Tools ZIP')
                if info.is_dir():
                    continue
                count += info.file_size
                if info.file_size > 64 * 1024 * 1024 or count > 256 * 1024 * 1024:
                    raise ValueError('Platform Tools ZIP exceeds size limit')
                target = stage.joinpath(*parts.parts[1:])
                if not target.resolve().is_relative_to(stage.resolve()):
                    raise ValueError('ZIP path escaped staging directory')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read(info))
                if host != 'windows':
                    mode = (info.external_attr >> 16) & 0o777
                    target.chmod(mode or (0o755 if target.name in ('adb', 'fastboot') else 0o644))
        required = [executable, 'NOTICE.txt', 'source.properties']
        if host == 'windows':
            required += ['AdbWinApi.dll', 'AdbWinUsbApi.dll']
        for name in required:
            if not (stage / name).is_file():
                raise ValueError('Missing required Platform Tools file: ' + name)
        with (stage / executable).open('rb') as source:
            header = source.read(20)
        if not binary_matches(header, host):
            raise ValueError('ADB binary does not match ' + host)
        if host == 'linux':
            machine = int.from_bytes(header[18:20], 'little')
            expected = 183 if platform.machine().lower() in ('arm64', 'aarch64') else 62
            if machine != expected:
                raise ValueError('Google ADB archive does not match CPU; install native adb and configure its path')
        if version is not None:
            import re
            revision = re.search(r'Pkg.Revision\s*=\s*(\S+)', (stage / 'source.properties').read_text())
            if not revision or revision.group(1) != version:
                raise ValueError('Archive version differs from selected version: ' + version)
        output.mkdir(parents=True, exist_ok=True)
        for path in stage.rglob('*'):
            if path.is_file():
                target = output / path.relative_to(stage)
                target.parent.mkdir(parents=True, exist_ok=True)
                os.replace(path, target)
    if host != 'windows':
        adb.chmod(adb.stat().st_mode | 0o111)
    with archive.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    (output / 'manifest.json').write_text(json.dumps({'source': 'user-supplied archive' if supplied else url, 'host': host, 'archive_sha256': digest, 'note': 'Local fingerprint; not a vendor signature.'}, indent=2), encoding='utf-8')
    print('ADB ready: ' + str(output))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--cache', type=Path)
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    prepare(args.output, args.archive, args.offline, args.cache)
