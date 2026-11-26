"""Check tracked files for accidental local artifacts; never prints matching secret values."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
FORBIDDEN_DIRS = (
    '.idea/', '.gradle/', '.kotlin/', 'build/', 'capture/', 'artifacts/',
    'desktop-resources/common/tools/', 'downloads/', '.venv/',
)
PRIVATE_EXTENSIONS = {'.pem', '.key', '.cer', '.p12', '.pfx', '.jks', '.keystore', '.conf', '.jsonl', '.log'}
PATTERNS = (
    re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    re.compile(rb'gh[pousr]_[A-Za-z0-9]{30,}'),
    re.compile(rb'sk-(?:proj-)?[A-Za-z0-9_-]{40,}'),
    re.compile(rb'AKIA[0-9A-Z]{16}'),
)


def main():
    tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode('utf-8').split('\0')
    problems = []
    total = 0
    for name in filter(None, tracked):
        path = ROOT / name
        if name.startswith(FORBIDDEN_DIRS) or re.match(r'desktop-resources/[^/]+/tools/', name) or '/__pycache__/' in name:
            problems.append((name, 'local artifact'))
        if path.suffix.lower() in PRIVATE_EXTENSIONS or path.name.startswith('.env') and path.name != '.env.example':
            problems.append((name, 'local or private file type'))
        if path.suffix.lower() == '.har' and not name.startswith('examples/'):
            problems.append((name, 'traffic outside synthetic examples'))
        if not path.is_file():
            continue
        size = path.stat().st_size
        total += size
        if size > 2 * 1024 * 1024:
            problems.append((name, 'file exceeds source repository size limit'))
        if path.suffix.lower() not in {'.jar', '.dex', '.png', '.ico'}:
            content = path.read_bytes()
            if any(pattern.search(content) for pattern in PATTERNS):
                problems.append((name, 'possible credential or private key'))
    for name, reason in problems:
        print(f'{name}: {reason}')
    print(f'Tracked source bytes: {total}; problems: {len(problems)}')
    return int(bool(problems))


if __name__ == '__main__':
    sys.exit(main())
