"""Shared version policy and bounded download progress for explicit preparation commands."""
import hashlib
import json
from pathlib import Path
import re
import time

POLICY = json.loads((Path(__file__).with_name('tool-versions.json')).read_text(encoding='utf-8'))


def version(value):
    if not isinstance(value, str) or len(value) > 40 or not re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,3}(?:(?:a|b|rc)[0-9]+)?', value):
        raise ValueError('Expected a numeric version, not a path, URL or pip expression: ' + str(value))
    return value


def copy_download(source, destination, label, limit=512 * 1024 * 1024):
    total = int(source.headers.get('Content-Length', '-1')) if hasattr(source, 'headers') else -1
    if total > limit:
        raise ValueError('Download exceeds size limit: ' + label)
    count, last = 0, 0
    while True:
        block = source.read(128 * 1024)
        if not block:
            break
        count += len(block)
        if count > limit:
            raise ValueError('Download exceeds size limit: ' + label)
        destination.write(block)
        now = time.monotonic()
        if now - last >= .2:
            print('DDHC_SETUP_PROGRESS:' + json.dumps(dict(label=label, bytes=count, total=total)), flush=True)
            last = now
    if total >= 0 and count != total:
        raise ValueError('Incomplete download: ' + label)
    print('DDHC_SETUP_PROGRESS:' + json.dumps(dict(label=label, bytes=count, total=total)), flush=True)


def file_digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def copy_unpacked(source, destination, limit=256 * 1024 * 1024):
    count = 0
    while True:
        block = source.read(128 * 1024)
        if not block:
            return
        count += len(block)
        if count > limit:
            raise ValueError('Unpacked file exceeds size limit')
        destination.write(block)
