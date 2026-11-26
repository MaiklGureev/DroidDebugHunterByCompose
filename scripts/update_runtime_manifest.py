"""Regenerate the source-only runtime manifest, excluding interpreter caches."""
from pathlib import Path

RESOURCES = Path(__file__).resolve().parent.parent / 'src/main/resources'
files = sorted(path.relative_to(RESOURCES).as_posix() for path in (RESOURCES / 'runtime').rglob('*')
               if path.is_file() and '__pycache__' not in path.parts and path.suffix not in {'.pyc', '.pyo'})
(RESOURCES / 'runtime-manifest.txt').write_text('\n'.join(files) + '\n', encoding='utf-8')
print(f'Runtime manifest: {len(files)} source assets')
