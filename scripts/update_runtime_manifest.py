"""Sync shared documentation/license and regenerate the source-only runtime manifest."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / 'src/main/resources'
HELP = RESOURCES / 'runtime/help'
(HELP / 'LICENSE').write_bytes((ROOT / 'LICENSE').read_bytes())
for source in (ROOT / 'docs/PLATFORMS.md', ROOT / 'THIRD_PARTY_NOTICES.md'):
    name = source.stem
    english = source.read_text(encoding='utf-8').replace(f'({name}.ru.md)', f'({name}.md)')
    russian = source.with_suffix('.ru.md').read_text(encoding='utf-8').replace(f'({name}.md)', f'({name}.en.md)')
    (HELP / f'{name}.en.md').write_text(english.replace('(../LICENSE)', '(LICENSE)'), encoding='utf-8', newline='\n')
    (HELP / f'{name}.md').write_text(russian.replace('(../LICENSE)', '(LICENSE)'), encoding='utf-8', newline='\n')
files = sorted(path.relative_to(RESOURCES).as_posix() for path in (RESOURCES / 'runtime').rglob('*')
               if path.is_file() and '__pycache__' not in path.parts and path.suffix not in {'.pyc', '.pyo'})
(RESOURCES / 'runtime-manifest.txt').write_text('\n'.join(files) + '\n', encoding='utf-8')
print(f'Runtime manifest: {len(files)} source assets')
