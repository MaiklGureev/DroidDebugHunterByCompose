"""Version flexibility, offline provisioning and rollback tests; no downloads or Android."""
import contextlib
import importlib.util
import io
import json
import lzma
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

TOOLS = Path(__file__).resolve().parent.parent / 'src/main/resources/runtime/tool-setup'
sys.path.insert(0, str(TOOLS))


def load(name):
    spec = importlib.util.spec_from_file_location(name, TOOLS / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ToolSetupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ddhc-setup-test-')
        self.root = Path(self.temp.name)
        self.setup = load('setup_tools')

    def tearDown(self):
        self.temp.cleanup()

    def test_versions_cannot_inject_pip_options_or_paths(self):
        support = load('tool_support')
        for value in ('17.2.0', '3.14.10', '18.0.0rc1'):
            self.assertEqual(support.version(value), value)
        for value in ('--upgrade', '../escape', '1.2;echo', '1.0 --index-url x'):
            with self.assertRaises(ValueError):
                support.version(value)

    def test_non_target_python_does_not_block_adb_or_frida_preparation(self):
        fake = unittest.mock.Mock()
        with patch.object(self.setup, 'load', return_value=fake), patch.object(self.setup.sys, 'version_info', (3, 12)), contextlib.redirect_stdout(io.StringIO()):
            self.setup.prepare(self.root, 'download', 'server', offline=True, versions={'frida': '16.5.9'})
        self.assertEqual(fake.prepare.call_args.kwargs['version'], '16.5.9')
        self.assertTrue(fake.prepare.call_args.kwargs['unpack_only'])

    def test_offline_custom_frida_set_uses_selected_version_and_checks_abi(self):
        module = load('prepare_frida')
        version = '16.5.9'
        archives = self.root / 'archives'
        archives.mkdir()
        for arch, (machine, elf_class) in module.ARCHITECTURES.items():
            header = bytearray(20)
            header[:4] = b'\x7fELF'
            header[4:6] = bytes([elf_class, 1])
            header[18:20] = machine.to_bytes(2, 'little')
            (archives / (module.filename(arch, version) + '.xz')).write_bytes(lzma.compress(header))
        with patch.object(module, 'request', side_effect=AssertionError('network')), contextlib.redirect_stdout(io.StringIO()):
            module.prepare(self.root / 'servers', archives, unpack_only=True, version=version)
        manifest = json.loads((self.root / 'servers/manifest.json').read_text())
        self.assertEqual(manifest['version'], version)
        self.assertEqual(len(manifest['servers']), 4)
        self.assertTrue(all(version in e['file'] and not e['verified_against_github'] for e in manifest['servers']))

    def test_custom_proxy_uses_resolver_and_offline_wheelhouse(self):
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            if '--target' in args:
                Path(args[args.index('--target') + 1]).mkdir()
        versions = dict(self.setup.POLICY, mitmproxy='11.0.2', mitmproxyRs='0.11.5')
        with patch.object(self.setup.subprocess, 'run', side_effect=run), patch.object(self.setup.sys, 'version_info', (3, 12)), contextlib.redirect_stdout(io.StringIO()):
            self.setup.install_packages(self.root, 'proxy', versions, True, self.root / 'cache')
        self.assertIn('mitmproxy==11.0.2', calls[0])
        self.assertNotIn('-r', calls[0])
        self.assertIn('--no-index', calls[0])
        self.assertIn(str(self.root / 'cache/wheelhouse/proxy'), calls[0])
        self.assertTrue((self.root / 'proxy-python').is_dir())

    def test_failed_package_validation_keeps_previous_installation(self):
        previous = self.root / 'frida-python/working'
        previous.parent.mkdir()
        previous.write_text('keep')
        def run(args, **kwargs):
            if '--target' in args:
                Path(args[args.index('--target') + 1]).mkdir()
            else:
                raise RuntimeError('import failed')
        with patch.object(self.setup.subprocess, 'run', side_effect=run), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, 'import failed'):
                self.setup.install_packages(self.root, 'capture', self.setup.POLICY, False, self.root / 'cache')
        self.assertEqual(previous.read_text(), 'keep')
        self.assertFalse(any(p.name.startswith('.ddhc-pip-') for p in self.root.iterdir()))

    def test_intel_mac_proxy_can_mix_local_wheels_and_pypi(self):
        cache = self.root / 'cache'
        wheelhouse = cache / 'wheelhouse/proxy'
        wheelhouse.mkdir(parents=True)

        def run(args, **kwargs):
            if '--target' in args:
                Path(args[args.index('--target') + 1]).mkdir()

        with patch.object(self.setup, 'host_key', return_value=('macos', 'x64')), \
                patch.object(self.setup.subprocess, 'run', side_effect=run) as execute, \
                patch.object(self.setup.sys, 'version_info', (3, 14)), contextlib.redirect_stdout(io.StringIO()):
            self.setup.install_packages(self.root, 'proxy', self.setup.POLICY, False, cache)

        install = execute.call_args_list[0].args[0]
        self.assertEqual(install[install.index('--find-links') + 1], str(wheelhouse))
        self.assertNotIn('--no-index', install)
        self.assertIn('--only-binary=:all:', install)

    def test_local_wireguard_version_is_selectable_offline(self):
        module = load('prepare_extra_tools')
        source = self.root / 'local.apk'
        with zipfile.ZipFile(source, 'w') as archive:
            archive.writestr('AndroidManifest.xml', b'manifest')
            archive.writestr('classes.dex', b'dex')
        with patch.object(module, 'get', side_effect=AssertionError('network')), contextlib.redirect_stdout(io.StringIO()):
            module.prepare_wireguard(self.root, apk=source, offline=True, version='1.0.20250531')
        self.assertTrue((self.root / 'android-tools/com.wireguard.android-1.0.20250531.apk').is_file())

    def test_download_progress_has_known_and_unknown_size(self):
        module = load('tool_support')
        for length in (300000, None):
            source = io.BytesIO(b'x' * 300000)
            source.headers = {'Content-Length': str(length)} if length else {}
            log = io.StringIO()
            output = io.BytesIO()
            with contextlib.redirect_stdout(log):
                module.copy_download(source, output, 'fixture')
            events = [json.loads(line.split(':', 1)[1]) for line in log.getvalue().splitlines()]
            self.assertEqual(events[-1]['bytes'], 300000)
            self.assertEqual(events[-1]['total'], length or -1)
            self.assertEqual(len(output.getvalue()), 300000)

    def test_truncated_or_oversized_download_fails(self):
        module = load('tool_support')
        source = io.BytesIO(b'x')
        source.headers = {'Content-Length': '100'}
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(ValueError, 'Incomplete'):
            module.copy_download(source, io.BytesIO(), 'fixture')
        with self.assertRaisesRegex(ValueError, 'size limit'):
            module.copy_download(source, io.BytesIO(), 'fixture', limit=5)


if __name__ == '__main__':
    unittest.main()
