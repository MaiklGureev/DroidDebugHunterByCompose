"""Prepare a portable Android Frida server set. Python is needed only for preparation."""
from __future__ import annotations

import argparse
import hashlib
import json
import lzma
import os
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.error
import urllib.request

from tool_support import POLICY, version as validate_version, copy_download, copy_unpacked

VERSION = POLICY["frida"]
ARCHITECTURES = {"arm64": (183, 2), "arm": (40, 1), "x86_64": (62, 2), "x86": (3, 1)}
ROOT = Path(__file__).resolve().parent.parent
API = f"https://api.github.com/repos/frida/frida/releases/tags/{VERSION}"
RELEASE = f"https://github.com/frida/frida/releases/download/{VERSION}/"


def filename(arch: str, version: str = VERSION) -> str:
    return f"frida-server-{version}-android-{arch}"


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def request(url: str):
    return urllib.request.urlopen(urllib.request.Request(url, headers={
        "User-Agent": "DroidDebugHunter-offline-prepare", "Accept": "application/vnd.github+json"
    }), timeout=30)


def download(asset: dict, archives: Path, version: str = VERSION) -> tuple[Path, str]:
    release_url = f"https://github.com/frida/frida/releases/download/{version}/"
    name = asset["name"]
    if name not in {filename(a, version) + ".xz" for a in ARCHITECTURES}:
        raise ValueError(f"Unexpected asset name: {name}")
    if asset["browser_download_url"] != release_url + name:
        raise ValueError(f"Unexpected download URL for {name}")
    digest = asset.get("digest", "") or ""
    if not digest.startswith("sha256:") or len(digest) != 71:
        raise ValueError(f"GitHub did not provide a SHA-256 digest for {name}. Download it manually and use --unpack-only.")
    expected = digest[7:].lower()
    target = archives / name
    if target.is_file() and sha256(target) == expected and target.stat().st_size == asset["size"]:
        print(f"Cached and verified: {name}", flush=True)
        return target, expected
    partial = archives / (name + ".part")
    try:
        print(f"Downloading: {name}", flush=True)
        with request(asset["browser_download_url"]) as source, partial.open("wb") as destination:
            copy_download(source, destination, name)
        if partial.stat().st_size != asset["size"] or sha256(partial) != expected:
            raise ValueError(f"SHA-256 or size mismatch: {name}. Existing files were preserved.")
        os.replace(partial, target)
    finally:
        partial.unlink(missing_ok=True)
    return target, expected


def unpack(archive: Path, destination: Path, arch: str) -> None:
    with lzma.open(archive, "rb") as source, destination.open("wb") as output:
        copy_unpacked(source, output)
    with destination.open("rb") as output:
        header = output.read(20)
    machine, elf_class = ARCHITECTURES[arch]
    if (len(header) != 20 or header[:4] != b"\x7fELF" or header[4] != elf_class
            or header[5] != 1 or int.from_bytes(header[18:20], "little") != machine):
        raise ValueError(f"Invalid Android ELF or wrong architecture: {archive.name}")


def prepare(output: Path, archives: Path, unpack_only: bool = False, version: str = VERSION) -> None:
    validate_version(version)
    release_url = f"https://github.com/frida/frida/releases/download/{version}/"
    output = output.resolve()
    archives = archives.resolve()
    output.mkdir(parents=True, exist_ok=True)
    archives.mkdir(parents=True, exist_ok=True)
    assets = {}
    if not unpack_only:
        print(f"Reading official release {version}...", flush=True)
        with request(f"https://api.github.com/repos/frida/frida/releases/tags/{version}") as response:
            release = json.load(response)
        if release.get("tag_name") != version:
            raise ValueError("Unexpected release version")
        assets = {asset["name"]: asset for asset in release["assets"]}

    # Validate the whole set in a unique staging directory before replacing any binaries.
    staging_path = Path(tempfile.mkdtemp(prefix="frida-prepare-", dir=output)).resolve()
    if not staging_path.is_relative_to(output):
        raise ValueError("Staging directory escaped the output directory")
    try:
        entries = []
        for arch in ARCHITECTURES:
            name = filename(arch, version)
            if unpack_only:
                archive = archives / (name + ".xz")
                if not archive.is_file():
                    raise FileNotFoundError(f"Missing archive: {archive}")
                archive_hash = sha256(archive)
            else:
                asset = assets.get(name + ".xz")
                if asset is None:
                    raise ValueError(f"Official release is missing {name}.xz")
                archive, archive_hash = download(asset, archives, version)
            unpack(archive, staging_path / name, arch)
            entries.append({"architecture": arch, "file": name, "sha256": sha256(staging_path / name),
                            "archive_sha256": archive_hash, "verified_against_github": not unpack_only,
                            "source": release_url + name + ".xz"})
            print(f"Prepared: {arch}", flush=True)
        manifest = {"version": version, "servers": entries}
        (staging_path / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        for entry in entries:
            os.replace(staging_path / entry["file"], output / entry["file"])
        os.replace(staging_path / "manifest.json", output / "manifest.json")
        print(f"Offline set ready: {output}\nCopy the whole application output folder after rebuilding.", flush=True)
        if unpack_only:
            print("Manual archives: ELF architectures checked; GitHub checksums were not verified.", flush=True)
    finally:
        shutil.rmtree(staging_path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "DroidDebugHunter" / "frida-server")
    parser.add_argument("--archives", type=Path, default=ROOT / "tools" / "frida-cache" / VERSION)
    parser.add_argument("--unpack-only", action="store_true", help="Use four manually downloaded .xz archives; never access the network.")
    args = parser.parse_args()
    try:
        prepare(args.output, args.archives, args.unpack_only)
    except (OSError, ValueError, lzma.LZMAError, urllib.error.URLError) as error:
        print(f"Preparation failed: {error}\nNo complete offline set was prepared. Retry with internet access, or use --unpack-only --archives PATH.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
