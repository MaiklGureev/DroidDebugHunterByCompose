"""Release archive/layout/checksum tests using small synthetic application images."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import tarfile
import zipfile

SCRIPT = Path(__file__).with_name('package_release.py')


class ReleasePackagingTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('release_packaging', SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory(prefix='ddhc-release-test-')
        self.root = Path(self.temp.name)
        self.module.ROOT = self.root
        (self.root / 'build.gradle.kts').write_text('version = "1.1.0"\npackageVersion = "1.1.0"\n')
        (self.root / 'docs').mkdir()
        (self.root / 'docs/README-FIRST.md').write_text('First run')
        (self.root / 'docs/README-FIRST.ru.md').write_text('Russian first run')
        (self.root / 'THIRD_PARTY_NOTICES.md').write_text('Notices')
        (self.root / 'THIRD_PARTY_NOTICES.ru.md').write_text('Russian notices')
        (self.root / 'LICENSE').write_text('MIT License')

    def tearDown(self):
        assert self.root.resolve().is_relative_to(Path(tempfile.gettempdir()).resolve())
        self.temp.cleanup()

    def image(self, system):
        root = self.module.application(system)
        files = [self.module.launcher(root, system), self.module.java_home(root, system) / 'lib/modules', self.module.java_home(root, system) / {'windows': 'bin/server/jvm.dll', 'linux': 'lib/server/libjvm.so', 'macos': 'lib/server/libjvm.dylib'}[system]]
        for file in files:
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(b'synthetic binary')
        self.module.launcher(root, system).chmod(0o755)
        return root

    def test_native_images_require_their_own_runtime(self):
        for system in ('windows', 'macos', 'linux'):
            root = self.image(system)
            self.module.validate_image(root, system)
            (self.module.java_home(root, system) / 'lib/modules').unlink()
            with self.assertRaisesRegex(ValueError, 'incomplete'):
                self.module.validate_image(root, system)

    def test_windows_zip_contains_exe_runtime_and_instructions(self):
        root = self.image('windows')
        archive = self.module.portable(root, self.root / 'output', 'windows', 'x64', '1.1.0')
        with zipfile.ZipFile(archive) as package:
            prefix = 'DroidDebugHunter-1.1.0-windows-x64/'
            self.assertIn(prefix + 'DroidDebugHunterByCompose.exe', package.namelist())
            self.assertIn(prefix + 'runtime/lib/modules', package.namelist())
            self.assertEqual(package.read(prefix + 'README-FIRST.md'), b'First run')
            self.assertEqual(package.read(prefix + 'README-FIRST.ru.md'), b'Russian first run')
            self.assertEqual(package.read(prefix + 'LICENSE'), b'MIT License')
            self.assertEqual(package.read(prefix + 'THIRD_PARTY_NOTICES.ru.md'), b'Russian notices')

    def test_mac_tar_keeps_app_bundle_and_unix_permissions(self):
        root = self.image('macos')
        archive = self.module.portable(root, self.root / 'output', 'macos', 'arm64', '1.1.0')
        with tarfile.open(archive) as package:
            member = package.getmember('DroidDebugHunter-1.1.0-macos-arm64/DroidDebugHunterByCompose.app/Contents/MacOS/DroidDebugHunterByCompose')
            self.assertEqual(package.extractfile(member).read(), b'synthetic binary')
            prefix = 'DroidDebugHunter-1.1.0-macos-arm64/'
            self.assertEqual(package.extractfile(prefix + 'LICENSE').read(), b'MIT License')
            self.assertEqual(package.extractfile(prefix + 'README-FIRST.ru.md').read(), b'Russian first run')
            if os.name != 'nt':
                self.assertTrue(member.mode & 0o111)

    def downloads(self):
        downloads = self.root / 'downloads'
        for system, arch in self.module.TARGETS:
            folder = downloads / f'release-{system}-{arch}'
            folder.mkdir(parents=True)
            filenames = [f'DroidDebugHunter-1.1.0-{system}-{arch}{self.module.SUFFIX[system]}', f'DroidDebugHunter-1.1.0-{system}-{arch}' + ('.zip' if system == 'windows' else '.tar.gz')]
            assets = []
            for name in filenames:
                path = folder / name
                path.write_bytes(b'synthetic package')
                assets.append({'name': name, 'size': path.stat().st_size, 'sha256': self.module.digest(path)})
            data = dict(version='1.1.0', platform=system, arch=arch, source_commit='a' * 40, assets=assets)
            (folder / f'manifest-{system}-{arch}.json').write_text(json.dumps(data))
        return downloads

    def test_complete_release_has_eight_downloads_and_checksums(self):
        with patch.dict(os.environ, GITHUB_SHA='a' * 40):
            self.module.collect(self.downloads())
        files = list((self.root / 'build/release-assets').iterdir())
        self.assertEqual(len(files), 18)
        self.assertEqual(len((self.root / 'build/release-assets/SHA256SUMS.txt').read_text().splitlines()), 17)
        self.assertEqual((self.root / 'build/release-assets/LICENSE').read_text(), 'MIT License')

    def test_corrupted_asset_prevents_publication(self):
        downloads = self.downloads()
        next(downloads.rglob('*.zip')).write_bytes(b'corrupted')
        with patch.dict(os.environ, GITHUB_SHA='a' * 40), self.assertRaisesRegex(ValueError, 'checksum'):
            self.module.collect(downloads)

    def test_foreign_commit_prevents_publication(self):
        with patch.dict(os.environ, GITHUB_SHA='b' * 40), self.assertRaisesRegex(ValueError, 'mismatch'):
            self.module.collect(self.downloads())

    def test_missing_platform_prevents_publication(self):
        downloads = self.downloads()
        (downloads / 'release-macos-x64/manifest-macos-x64.json').unlink()
        with patch.dict(os.environ, GITHUB_SHA='a' * 40), self.assertRaises(FileNotFoundError):
            self.module.collect(downloads)


if __name__ == '__main__':
    unittest.main()
