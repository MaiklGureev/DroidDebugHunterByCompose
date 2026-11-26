"""Host-native tool preparation; no PowerShell, global pip installs, or runtime JDK required."""
import argparse
import importlib.util
import platform
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def host_key():
    host = {'Windows': 'windows', 'Darwin': 'macos', 'Linux': 'linux'}[platform.system()]
    arch = {'amd64': 'x64', 'x86_64': 'x64', 'arm64': 'arm64', 'aarch64': 'arm64'}.get(platform.machine().lower())
    if not arch:
        raise ValueError('Unsupported CPU: ' + platform.machine())
    return host, arch


def prepare(root, action='check', component='all', offline=False):
    host, arch = host_key()
    root = Path(root).resolve()
    print(f'Host: {host}-{arch}\nTools root: {root}', flush=True)
    folders = dict(adb='platform-tools', server='frida-server', gadget='frida-gadget', capture='frida-python', proxy='proxy-python', wireguard='android-tools')
    selected = folders if component == 'all' else {component: folders[component]}
    if action == 'init':
        for folder in folders.values():
            (root / folder).mkdir(parents=True, exist_ok=True)
        print('Local directories created. No downloads performed.')
        return
    if action == 'check':
        files = dict(adb=['adb.exe' if host == 'windows' else 'adb', 'NOTICE.txt'], server=['frida-server-17.26.2-android-' + abi for abi in ['arm64', 'arm', 'x86_64', 'x86']], gadget=['frida-gadget-17.26.2-android-' + abi + '.so' for abi in ['arm64', 'arm', 'x86_64', 'x86']], capture=['frida/__init__.py', 'frida_tools/__init__.py'], proxy=['mitmproxy/tools/dump.py', 'h2/__init__.py'], wireguard=['com.wireguard.android-1.0.20260315.apk'])
        missing_any = False
        for name, folder in selected.items():
            missing = [file for file in files[name] if not (root / folder / file).is_file()]
            missing_any |= bool(missing)
            print(f'{name}: ' + ('missing: ' + ', '.join(missing) if missing else 'files present'), flush=True)
        print(f'Python: {sys.executable}\nPython version: {platform.python_version()}')
        if missing_any:
            raise ValueError('Some selected components are missing; configure paths or prepare tools')
        return
    if sys.version_info[:2] != (3, 14):
        raise ValueError('Pinned capture bundle requires Python 3.14; select a matching interpreter or venv')
    for name, folder in selected.items():
        print('Preparing ' + name, flush=True)
        if name == 'adb':
            load('prepare_platform_tools').prepare(root / folder, offline=offline, host=host)
        elif name == 'server':
            load('prepare_frida').prepare(root / folder, root / 'downloads/frida', unpack_only=offline)
        elif name in ('gadget', 'wireguard'):
            module = load('prepare_extra_tools')
            if name == 'gadget':
                module.prepare_gadget(root, offline=offline)
            else:
                module.prepare_wireguard(root, offline=offline)
        else:
            target = root / folder
            target.mkdir(parents=True, exist_ok=True)
            requirements = HERE / ('capture-requirements.txt' if name == 'capture' else 'proxy-requirements.txt')
            command = [sys.executable, '-m', 'pip', '--isolated', 'install', '--disable-pip-version-check', '--only-binary=:all:', '--upgrade', '--target', str(target), '-r', str(requirements)]
            if offline:
                command += ['--no-index', '--find-links', str(root / 'downloads/wheelhouse' / name)]
            subprocess.run(command, check=True)
    print('Preparation completed for ' + host + '-' + arch, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--action', choices=['init', 'check', 'download'], default='check')
    parser.add_argument('--component', choices=['all', 'adb', 'server', 'gadget', 'capture', 'proxy', 'wireguard'], default='all')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    try:
        prepare(args.root, args.action, args.component, args.offline)
    except Exception as error:
        print('Preparation failed: ' + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
