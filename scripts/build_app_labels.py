"""Rebuild our small embedded Android helper; no downloads and no Gradle required."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME'))
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    args = parser.parse_args()
    if not args.sdk:
        parser.error('Pass --sdk PATH or set ANDROID_SDK_ROOT')
    jars = sorted(args.sdk.glob('platforms/android-*/android.jar'), key=lambda p: p.parent.name)
    dexers = sorted(args.sdk.glob('build-tools/*/lib/d8.jar'), key=lambda p: p.parent.parent.name)
    if not jars or not dexers:
        parser.error('The SDK must contain an Android platform and Build Tools with d8.jar')
    java = str(args.java_home / 'bin/java.exe') if args.java_home else 'java'
    javac = str(args.java_home / 'bin/javac.exe') if args.java_home else 'javac'
    helper = ROOT / 'src/main/resources/runtime/AndroidHelper'
    with tempfile.TemporaryDirectory(prefix='ddh-label-build-') as temp:
        subprocess.run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', str(jars[-1]), '-d', temp, str(helper / 'AppLabels.java')], check=True)
        subprocess.run([java, '-cp', str(dexers[-1]), 'com.android.tools.r8.D8', '--release', '--min-api', '23', '--lib', str(jars[-1]), '--output', temp, str(Path(temp) / 'droiddebughunter/AppLabels.class')], check=True)
        (helper / 'app-labels.dex').write_bytes((Path(temp) / 'classes.dex').read_bytes())
    print(helper / 'app-labels.dex')


if __name__ == '__main__':
    main()
