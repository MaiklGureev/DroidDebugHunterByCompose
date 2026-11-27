"""Sync shared documentation/license and regenerate the source-only runtime manifest."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / 'src/main/resources'
HELP = RESOURCES / 'runtime/help'
LEGAL = HELP / 'legal'
LEGAL.mkdir(parents=True, exist_ok=True)
(LEGAL / 'LICENSE').write_bytes((ROOT / 'LICENSE').read_bytes())
help_link = '(../src/main/resources/runtime/help/'
for source, group in (
    (ROOT / 'docs/PLATFORMS.md', 'setup'),
    (ROOT / 'THIRD_PARTY_NOTICES.md', 'legal'),
):
    name = source.stem
    english = source.read_text(encoding='utf-8').replace(f'({name}.ru.md)', f'({name}.md)')
    russian = source.with_suffix('.ru.md').read_text(encoding='utf-8').replace(f'({name}.md)', f'({name}.en.md)')
    destination = HELP / group
    destination.mkdir(parents=True, exist_ok=True)
    for filename, document in ((f'{name}.en.md', english), (f'{name}.md', russian)):
        document = document.replace(f'{help_link}{group}/', '(').replace(help_link, '(../')
        (destination / filename).write_text(document, encoding='utf-8', newline='\n')
files = sorted(path.relative_to(RESOURCES).as_posix() for path in (RESOURCES / 'runtime').rglob('*')
               if path.is_file() and '__pycache__' not in path.parts and path.suffix not in {'.pyc', '.pyo'})
(RESOURCES / 'runtime-manifest.txt').write_text('\n'.join(files) + '\n', encoding='utf-8')
print(f'Runtime manifest: {len(files)} source assets')
