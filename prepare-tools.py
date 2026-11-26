"""Prepare tools for this operating system and CPU. Uses the selected Python interpreter."""
from pathlib import Path
import runpy
import sys

ROOT = Path(__file__).resolve().parent
SCRIPT = ROOT / 'src/main/resources/runtime/tool-setup/setup_tools.py'
module = runpy.run_path(str(SCRIPT))
host, arch = module['host_key']()
if not any(arg == '--root' or arg.startswith('--root=') for arg in sys.argv[1:]):
    sys.argv += ['--root', str(ROOT / 'desktop-resources' / f'{host}-{arch}' / 'tools')]
raise SystemExit(module['main']())
