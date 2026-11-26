"""Prepare tools locally, online or from local archives; no global pip installs or elevation."""
import argparse
import importlib.util
import os
import platform
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import uuid

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
from tool_support import POLICY, version


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


def install_packages(root, name, versions, offline, cache):
    folder = 'frida-python' if name == 'capture' else 'proxy-python'
    target = root / folder
    requirements = HERE / ('capture-requirements.txt' if name == 'capture' else 'proxy-requirements.txt')
    keys = ['frida', 'fridaTools'] if name == 'capture' else ['mitmproxy', 'mitmproxyRs']
    pinned = all(versions[k] == POLICY[k] for k in keys) and sys.version_info[:2] == (3, 14)
    if name == 'proxy' and versions['mitmproxy'] == POLICY['mitmproxy'] and sys.version_info[:2] < (3, 12):
        raise ValueError('mitmproxy 12.3.1 requires Python 3.12+. Choose another Python or a compatible older mitmproxy version.')
    requested = ['-r', str(requirements)] if pinned else ([f'frida=={versions["frida"]}', f'frida-tools=={versions["fridaTools"]}', 'h11>=0.14,<1', 'hpack>=4,<5'] if name == 'capture' else [f'mitmproxy=={versions["mitmproxy"]}', f'mitmproxy_rs=={versions["mitmproxyRs"]}'])
    print('Using frozen target requirements.' if pinned else 'Custom interpreter/versions: resolving compatible dependencies from package metadata.', flush=True)
    root.mkdir(parents=True, exist_ok=True)
    # A failed resolver/import never replaces the previously working component.
    with tempfile.TemporaryDirectory(prefix='.ddhc-pip-', dir=root) as directory:
        stage = Path(directory) / folder
        command = [sys.executable, '-m', 'pip', '--isolated', 'install', '--disable-pip-version-check', '--no-input', '--ignore-installed', '--no-warn-conflicts', '--progress-bar', 'off', '--only-binary=:all:', '--target', str(stage)] + requested
        command += ['--cache-dir', str(cache / 'pip')]
        if offline:
            command += ['--no-index', '--find-links', str(cache / 'wheelhouse' / name)]
        subprocess.run(command, env=dict(os.environ, PYTHONPATH='', PYTHONNOUSERSITE='1'), check=True)
        environment = dict(os.environ, PYTHONPATH=str(stage) + os.pathsep + str(HERE.parent / 'agents'), PYTHONNOUSERSITE='1')
        probe = 'import frida, frida_tools.repl, tls_decoder; print(frida.__version__)' if name == 'capture' else 'import mitmproxy.tools.dump, mitmproxy_rs, h11, h2, hpack; print("Proxy modules imported")'
        subprocess.run([sys.executable, '-s', '-c', probe], env=environment, check=True)
        backup = root / ('.ddhc-package-backup-' + uuid.uuid4().hex)
        previous = target.exists()
        if previous:
            target.rename(backup)
        try:
            stage.rename(target)
        except BaseException:
            if previous:
                backup.rename(target)
            raise
        if previous:
            assert backup.parent.resolve() == root.resolve() and backup.name.startswith('.ddhc-package-backup-')
            shutil.rmtree(backup)
    print('Validated local package installation: ' + folder, flush=True)


def prepare(root, action='check', component='all', offline=False, versions=None, cache=None, adb_archive=None):
    host, arch = host_key()
    root = Path(root).resolve()
    v = {k: version((versions or {}).get(k, POLICY[k])) for k in ['python', 'frida', 'fridaTools', 'mitmproxy', 'mitmproxyRs', 'wireguard', 'adb']}
    cache = Path(cache).resolve() if cache else root / 'downloads'
    print(f'Host: {host}-{arch}\nTools root: {root}', flush=True)
    if platform.python_version() != POLICY['python']:
        print(f"WARNING: interpreter {platform.python_version()}; target {POLICY['python']}. Continuing with the selected interpreter.", flush=True)
    for key, value in v.items():
        if value != POLICY[key]:
            print(f'WARNING: {key} {value}; target {POLICY[key]}. Alternative versions may work but are not guaranteed.', flush=True)
    folders = dict(adb='platform-tools', server='frida-server', gadget='frida-gadget', capture='frida-python', proxy='proxy-python', wireguard='android-tools')
    selected = folders if component == 'all' else ({k: folders[k] for k in ('capture', 'server', 'gadget')} if component == 'frida' else {component: folders[component]})
    if action == 'init':
        for folder in folders.values():
            (root / folder).mkdir(parents=True, exist_ok=True)
        print('Local directories created. No downloads performed.')
        return
    if action == 'check':
        files = dict(adb=['adb.exe' if host == 'windows' else 'adb', 'NOTICE.txt'], server=[f'frida-server-{v["frida"]}-android-' + abi for abi in ['arm64', 'arm', 'x86_64', 'x86']], gadget=[f'frida-gadget-{v["frida"]}-android-' + abi + '.so' for abi in ['arm64', 'arm', 'x86_64', 'x86']], capture=['frida/__init__.py', 'frida_tools/__init__.py'], proxy=['mitmproxy/tools/dump.py', 'h2/__init__.py'], wireguard=[f'com.wireguard.android-{v["wireguard"]}.apk'])
        missing_any = False
        for name, folder in selected.items():
            missing = [file for file in files[name] if not (root / folder / file).is_file()]
            missing_any |= bool(missing)
            print(f'{name}: ' + ('missing: ' + ', '.join(missing) if missing else 'files present'), flush=True)
        print(f'Python: {sys.executable}\nPython version: {platform.python_version()}')
        if missing_any:
            raise ValueError('Some selected components are missing; configure paths or prepare tools')
        return
    root.mkdir(parents=True, exist_ok=True)
    suffix = {'windows': 'win', 'macos': 'darwin', 'linux': 'linux'}[host]
    for name, folder in selected.items():
        print('Preparing ' + name, flush=True)
        if name == 'adb':
            load('prepare_platform_tools').prepare(root / folder, archive=adb_archive, offline=offline, cache=cache / 'adb' / f'platform-tools_r{v["adb"]}-{suffix}.zip', host=host, version=v['adb'])
        elif name == 'server':
            load('prepare_frida').prepare(root / folder, cache / 'frida', unpack_only=offline, version=v['frida'])
        elif name in ('gadget', 'wireguard'):
            module = load('prepare_extra_tools')
            if name == 'gadget':
                module.prepare_gadget(root, archives=cache / 'frida', offline=offline, version=v['frida'])
            else:
                module.prepare_wireguard(root, offline=offline, version=v['wireguard'], cache=cache)
        else:
            install_packages(root, name, v, offline, cache)
    print('Preparation completed for ' + host + '-' + arch, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--action', choices=['init', 'check', 'download'], default='check')
    parser.add_argument('--component', choices=['all', 'frida', 'adb', 'server', 'gadget', 'capture', 'proxy', 'wireguard'], default='all')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--cache', type=Path)
    parser.add_argument('--adb-archive', type=Path)
    for key in ['python', 'frida', 'fridaTools', 'mitmproxy', 'mitmproxyRs', 'wireguard', 'adb']:
        parser.add_argument('--version-' + key, default=POLICY[key], type=version)
    args = parser.parse_args()
    try:
        versions = {key: getattr(args, 'version_' + key) for key in ['python', 'frida', 'fridaTools', 'mitmproxy', 'mitmproxyRs', 'wireguard', 'adb']}
        prepare(args.root, args.action, args.component, args.offline, versions, args.cache, args.adb_archive)
    except Exception as error:
        print('Preparation failed: ' + str(error), file=sys.stderr)
        print('Try a writable user folder, a local venv, offline archives/wheelhouse or manually configured executables. No administrator privileges were requested.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
