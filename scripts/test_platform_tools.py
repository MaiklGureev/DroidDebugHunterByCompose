"""Offline host preparation tests. Synthetic ZIPs only; no downloads or Android operations."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import struct
import tempfile
import sys
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


class PlatformToolsTest(unittest.TestCase):
    def setUp(self):
        self.module = load('prepare_platform_tools')
        self.temp = tempfile.TemporaryDirectory(prefix='ddhc-tool-test-')
        self.root = Path(self.temp.name)

    def tearDown(self):
        assert self.root.resolve().is_relative_to(Path(tempfile.gettempdir()).resolve())
        self.temp.cleanup()

    def archive(self, host, extra=None):
        header = {'windows': b'MZ' + bytes(18), 'macos': struct.pack('<II12x', 0xfeedfacf, 0x01000007),
                  'linux': b'\x7fELF\x02\x01' + bytes(12) + b'\x3e\x00'}[host]
        entries = {'platform-tools/' + ('adb.exe' if host == 'windows' else 'adb'): header, 'platform-tools/NOTICE.txt': b'notice', 'platform-tools/source.properties': b'Pkg.Revision=1'}
        if host == 'windows':
            entries.update({'platform-tools/AdbWinApi.dll': b'MZ', 'platform-tools/AdbWinUsbApi.dll': b'MZ'})
        entries.update(extra or {})
        archive = self.root / (host + '.zip')
        with zipfile.ZipFile(archive, 'w') as package:
            for name, value in entries.items():
                package.writestr(name, value)
        return archive

    def test_all_hosts_preserve_notices_and_binary_names(self):
        for host in ('windows', 'macos', 'linux'):
            with self.subTest(host=host), patch.object(self.module.platform, 'machine', return_value='x86_64'):
                output = self.root / host
                self.module.prepare(output, self.archive(host), offline=True, host=host)
                self.assertTrue((output / ('adb.exe' if host == 'windows' else 'adb')).is_file())
                self.assertEqual((output / 'NOTICE.txt').read_text(), 'notice')
                self.assertTrue((output / 'manifest.json').is_file())

    def test_unix_shared_libraries_are_preserved(self):
        output = self.root / 'linux'
        with patch.object(self.module.platform, 'machine', return_value='x86_64'):
            self.module.prepare(output, self.archive('linux', {'platform-tools/lib64/libadbusb.so': b'library'}), host='linux')
        self.assertEqual((output / 'lib64/libadbusb.so').read_bytes(), b'library')
        if os.name != 'nt':
            self.assertTrue((output / 'adb').stat().st_mode & 0o111)

    def test_wrong_host_binary_is_rejected(self):
        with self.assertRaises(ValueError):
            self.module.prepare(self.root / 'output', self.archive('windows'), host='macos')

    def test_linux_cpu_mismatch_is_explained(self):
        with patch.object(self.module.platform, 'machine', return_value='aarch64'), self.assertRaisesRegex(ValueError, 'CPU'):
            self.module.prepare(self.root / 'output', self.archive('linux'), host='linux')

    def test_linux_arm64_skips_google_download_and_accepts_local_native_archive(self):
        output = self.root / 'output'
        with patch.object(self.module.platform, 'machine', return_value='aarch64'), \
                patch.object(self.module.urllib.request, 'urlopen', side_effect=AssertionError('network')):
            with self.assertRaisesRegex(ValueError, 'x86-64 only'):
                self.module.prepare(output, host='linux')
            header = b'\x7fELF\x02\x01' + bytes(12) + struct.pack('<H', 183)
            self.module.prepare(output, self.archive('linux', {'platform-tools/adb': header}), host='linux')
        self.assertEqual((output / 'adb').read_bytes(), header)

    def test_macos_arm64_rejects_thin_intel_and_accepts_universal(self):
        output = self.root / 'output'
        with patch.object(self.module.platform, 'machine', return_value='arm64'):
            with self.assertRaisesRegex(ValueError, 'OS/CPU'):
                self.module.prepare(output, self.archive('macos'), host='macos')
            header = struct.pack('>III16xI16x', 0xcafebabe, 2, 0x01000007, 0x0100000c)
            self.module.prepare(output, self.archive('macos', {'platform-tools/adb': header}), host='macos')
        self.assertEqual((output / 'adb').read_bytes(), header)

    def test_traversal_rejected_before_copying_to_destination(self):
        with self.assertRaisesRegex(ValueError, 'Unsafe ZIP path'):
            self.module.prepare(self.root / 'output', self.archive('windows', {'platform-tools/../../escape.txt': b'bad'}), host='windows')
        self.assertFalse((self.root / 'escape.txt').exists())
        self.assertFalse((self.root / 'output').exists())

    def test_symlinks_in_zip_are_rejected(self):
        archive = self.archive('macos')
        with zipfile.ZipFile(archive, 'a') as package:
            info = zipfile.ZipInfo('platform-tools/link')
            info.external_attr = 0o120777 << 16
            package.writestr(info, '/etc/passwd')
        with self.assertRaisesRegex(ValueError, 'Symlinks'):
            self.module.prepare(self.root / 'output', archive, host='macos')

    def test_offline_without_archive_never_downloads(self):
        with patch.object(self.module.urllib.request, 'urlopen', side_effect=AssertionError('network')):
            with self.assertRaises(FileNotFoundError):
                self.module.prepare(self.root / 'output', offline=True, host='macos')

    def test_init_does_not_install_or_download(self):
        setup = load('setup_tools')
        with patch.object(setup.subprocess, 'run', side_effect=AssertionError('subprocess')):
            setup.prepare(self.root / 'bundle', action='init')
        self.assertTrue((self.root / 'bundle/frida-python').is_dir())

    def test_check_reports_missing_components(self):
        setup = load('setup_tools')
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(ValueError, 'missing'):
            setup.prepare(self.root / 'empty', action='check', component='adb')

    def test_unknown_host_is_rejected(self):
        with self.assertRaises(ValueError):
            self.module.prepare(self.root / 'output', host='other')


if __name__ == '__main__':
    unittest.main()
