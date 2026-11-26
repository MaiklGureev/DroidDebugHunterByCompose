"""Validate native images, prepare archives and assemble a complete release before publishing."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
APP = 'DroidDebugHunterByCompose'
TARGETS = {('windows', 'x64'), ('linux', 'x64'), ('macos', 'arm64'), ('macos', 'x64')}
SUFFIX = {'windows': '.msi', 'linux': '.deb', 'macos': '.dmg'}


def version():
    text = (ROOT / 'build.gradle.kts').read_text(encoding='utf-8-sig')
    value = re.search(r'^version = "([0-9]+\.[0-9]+\.[0-9]+)"', text, re.M).group(1)
    if f'packageVersion = "{value}"' not in text:
        raise ValueError('Gradle and package versions differ')
    return value


def base():
    return ROOT / f'build/compose/binaries-{version()}/main'


def host():
    system = {'Windows': 'windows', 'Darwin': 'macos', 'Linux': 'linux'}[platform.system()]
    arch = {'amd64': 'x64', 'x86_64': 'x64', 'aarch64': 'arm64', 'arm64': 'arm64'}[platform.machine().lower()]
    return system, arch


def application(system):
    return base() / 'app' / (APP + '.app' if system == 'macos' else APP)


def launcher(root, system):
    return root / {'windows': f'{APP}.exe', 'linux': f'bin/{APP}', 'macos': f'Contents/MacOS/{APP}'}[system]


def java_home(root, system):
    return root / {'windows': 'runtime', 'linux': 'lib/runtime', 'macos': 'Contents/runtime/Contents/Home'}[system]


def validate_image(root, system):
    if not launcher(root, system).is_file():
        raise ValueError('Native launcher is missing: ' + str(launcher(root, system)))
    home = java_home(root, system)
    vm = home / {'windows': 'bin/server/jvm.dll', 'linux': 'lib/server/libjvm.so', 'macos': 'lib/server/libjvm.dylib'}[system]
    if not (home / 'lib/modules').is_file() or not vm.is_file():
        raise ValueError('Private Java runtime is incomplete: ' + str(home))
    resources = root / ('Contents/app/resources' if system == 'macos' else 'lib/app/resources' if system == 'linux' else 'app/resources')
    if (resources / 'tools').is_dir() and any(p.is_file() for p in (resources / 'tools').rglob('*')):
        raise ValueError('Public release expects a clean image without unreviewed external tooling')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def distribution_documents():
    return [ROOT / path for path in ('LICENSE', 'docs/README-FIRST.md', 'docs/README-FIRST.ru.md',
                                    'THIRD_PARTY_NOTICES.md', 'THIRD_PARTY_NOTICES.ru.md')]


def portable(root, output, system, arch, ver):
    name = f'DroidDebugHunter-{ver}-{system}-{arch}'
    output.mkdir(parents=True, exist_ok=True)
    if system == 'windows':
        archive = output / (name + '.zip')
        with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as target:
            for path in sorted(root.rglob('*')):
                if path.is_file():
                    target.write(path, f'{name}/{path.relative_to(root).as_posix()}')
            for document in distribution_documents():
                target.write(document, f'{name}/{document.name}')
    else:
        archive = output / (name + '.tar.gz')
        with tarfile.open(archive, 'w:gz') as target:
            target.add(root, arcname=f'{name}/{root.name}' if system == 'macos' else name)
            for document in distribution_documents():
                target.add(document, arcname=f'{name}/{document.name}')
    return archive


def package(system, arch):
    if (system, arch) != host() or (system, arch) not in TARGETS:
        raise ValueError('Release target does not match this runner')
    root = application(system)
    validate_image(root, system)
    output = ROOT / 'build/release-assets'
    archive = portable(root, output, system, arch, version())
    suffix = SUFFIX[system]
    installers = list((base() / suffix[1:]).glob('*' + suffix))
    if len(installers) != 1 or version() not in installers[0].name:
        raise ValueError('Expected one native installer for this release: ' + str(installers))
    installer = output / f'DroidDebugHunter-{version()}-{system}-{arch}{suffix}'
    shutil.copy2(installers[0], installer)
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    manifest = {'version': version(), 'platform': system, 'arch': arch, 'source_commit': commit,
                'java_runtime_bundled': True, 'external_tooling_bundled': False,
                'assets': [{'name': path.name, 'size': path.stat().st_size, 'sha256': digest(path)} for path in [archive, installer]]}
    (output / f'manifest-{system}-{arch}.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print('Prepared: ' + ', '.join(item['name'] for item in manifest['assets']))


def runtime_check():
    system, arch = host()
    root = application(system)
    validate_image(root, system)
    with tempfile.TemporaryDirectory(prefix='ddhc-runtime-release-') as directory:
        report = Path(directory) / 'runtime.json'
        environment = dict(os.environ, PATH='/usr/bin:/bin', JAVA_HOME='/nonexistent-java', JRE_HOME='/nonexistent-java', JDK_HOME='/nonexistent-java')
        subprocess.run([str(launcher(root, system)), '--runtime-check', str(report)], env=environment, check=True, timeout=30)
        data = json.loads(report.read_text(encoding='utf-8'))
        if Path(data['javaHome']).resolve() != java_home(root, system).resolve() or data['platform'] != f'{system}-{arch}':
            raise ValueError('Native launcher used an unexpected runtime or platform')
        if data.get('deviceSelection') is not True or data.get('shutdown') is not True:
            raise ValueError('Packaged application selection/shutdown check failed')
        print('Bundled runtime startup verified: ' + data['javaVersion'] + ' / ' + data['platform'])


def collect(downloads):
    output = ROOT / 'build/release-assets'
    output.mkdir(parents=True, exist_ok=True)
    manifests = []
    commit = os.environ.get('GITHUB_SHA') or subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    for system, arch in sorted(TARGETS):
        folder = downloads / f'release-{system}-{arch}'
        path = folder / f'manifest-{system}-{arch}.json'
        data = json.loads(path.read_text(encoding='utf-8'))
        expected = {f'DroidDebugHunter-{version()}-{system}-{arch}{SUFFIX[system]}', f'DroidDebugHunter-{version()}-{system}-{arch}' + ('.zip' if system == 'windows' else '.tar.gz')}
        if data['source_commit'] != commit or data['version'] != version() or (data['platform'], data['arch']) != (system, arch) or {a['name'] for a in data['assets']} != expected:
            raise ValueError('Artifact source/target/version mismatch: ' + str(path))
        for asset in data['assets']:
            filename = asset['name']
            if Path(filename).name != filename:
                raise ValueError('Asset path is not a filename')
            source = folder / filename
            if source.stat().st_size != asset['size'] or digest(source) != asset['sha256']:
                raise ValueError('Asset checksum mismatch: ' + filename)
            shutil.copy2(source, output / filename)
        shutil.copy2(path, output / path.name)
        manifests.append(data)
    for document in distribution_documents():
        shutil.copy2(document, output / document.name)
    paths = sorted(path for path in output.iterdir() if path.is_file() and path.name != 'SHA256SUMS.txt')
    (output / 'SHA256SUMS.txt').write_text(''.join(f'{digest(path)}  {path.name}\n' for path in paths), encoding='utf-8')
    print(f'Complete release verified: {len(manifests)} targets, 8 binary downloads, {len(paths) + 1} total assets')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-tag')
    parser.add_argument('--platform', choices=['windows', 'macos', 'linux'])
    parser.add_argument('--arch', choices=['x64', 'arm64'])
    parser.add_argument('--runtime-check', action='store_true')
    parser.add_argument('--collect', type=Path)
    args = parser.parse_args()
    if args.verify_tag:
        if args.verify_tag != 'v' + version():
            raise ValueError('Release tag does not match the application version')
        print('Release version verified: ' + args.verify_tag)
    elif args.runtime_check:
        runtime_check()
    elif args.collect:
        collect(args.collect)
    else:
        if not args.platform or not args.arch:
            parser.error('--platform and --arch are required')
        package(args.platform, args.arch)


if __name__ == '__main__':
    main()
