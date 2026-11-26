"""Prepare Gadget and WireGuard from official downloads or explicit local archives."""
import argparse
import hashlib
import json
import lzma
import os
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent / "DroidDebugHunter"
VERSION = "17.26.2"
ARCHITECTURES = [("arm64", 183, 2), ("arm", 40, 1), ("x86_64", 62, 2), ("x86", 3, 1)]
APK = "com.wireguard.android-1.0.20260315.apk"
RELEASE = f"https://github.com/frida/frida/releases/download/{VERSION}/"


def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "DroidDebugHunter-prepare"}), timeout=60)


def hash_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare_gadget(root, archives=None, offline=False):
    output = (root / "frida-gadget").resolve()
    output.mkdir(parents=True, exist_ok=True)
    archives = (archives or root / "downloads" / "frida").resolve()
    archives.mkdir(parents=True, exist_ok=True)
    assets = {}
    if not offline:
        with get(f"https://api.github.com/repos/frida/frida/releases/tags/{VERSION}") as stream:
            release = json.load(stream)
        if release.get("tag_name") != VERSION:
            raise ValueError("Unexpected Frida release version")
        assets = {asset["name"]: asset for asset in release["assets"]}
    staging = Path(tempfile.mkdtemp(prefix="gadget-prepare-", dir=output)).resolve()
    assert staging.is_relative_to(output)
    try:
        entries = []
        for arch, machine, elf_class in ARCHITECTURES:
            name = f"frida-gadget-{VERSION}-android-{arch}.so"
            archive = archives / (name + ".xz")
            expected = None
            if not offline:
                asset = assets[name + ".xz"]
                expected = asset.get("digest", "")
                if not expected.startswith("sha256:") or len(expected) != 71 or asset["browser_download_url"] != RELEASE + name + ".xz":
                    raise ValueError("Invalid official asset metadata: " + name)
                if not archive.exists() or hash_file(archive) != expected[7:]:
                    partial = archive.with_suffix(".part")
                    try:
                        print("Downloading " + name, flush=True)
                        with get(asset["browser_download_url"]) as source, partial.open("wb") as destination:
                            shutil.copyfileobj(source, destination)
                        if hash_file(partial) != expected[7:]:
                            raise ValueError("Digest mismatch: " + name)
                        os.replace(partial, archive)
                    finally:
                        partial.unlink(missing_ok=True)
            if not archive.is_file():
                raise FileNotFoundError("Missing local archive: " + str(archive))
            target = staging / name
            with lzma.open(archive, "rb") as source, target.open("wb") as destination:
                shutil.copyfileobj(source, destination)
            with target.open("rb") as stream:
                header = stream.read(20)
            if len(header) < 20 or header[:4] != b"\x7fELF" or header[4] != elf_class or header[5] != 1 or int.from_bytes(header[18:20], "little") != machine:
                raise ValueError("Wrong ELF architecture: " + name)
            entries.append({"file": name, "sha256": hash_file(target), "archive_sha256": hash_file(archive), "verified_against_github": not offline})
        (staging / "manifest.json").write_text(json.dumps({"version": VERSION, "files": entries}, indent=2), encoding="utf-8")
        for path in staging.iterdir():
            os.replace(path, output / path.name)
        print("Prepared Gadget for all four Android ABIs.", flush=True)
    finally:
        if not staging.is_relative_to(output):
            raise ValueError("Unsafe cleanup path")
        shutil.rmtree(staging)


def prepare_wireguard(root, apk=None, offline=False):
    output = (root / "android-tools").resolve()
    output.mkdir(parents=True, exist_ok=True)
    target = output / APK
    url = "https://download.wireguard.com/android-client/" + APK
    source = apk.resolve() if apk else target
    temporary = output / (APK + ".part")
    try:
        if not source.exists():
            if offline or apk is not None:
                raise FileNotFoundError("Missing local WireGuard APK. Pass --apk PATH.")
            print("Downloading official WireGuard Android client", flush=True)
            with get(url) as response, temporary.open("wb") as destination:
                shutil.copyfileobj(response, destination)
            source = temporary
        with zipfile.ZipFile(source) as package:
            if "AndroidManifest.xml" not in package.namelist() or "classes.dex" not in package.namelist() or package.testzip():
                raise ValueError("Invalid WireGuard APK")
        if source != target:
            if source != temporary:
                shutil.copyfile(source, temporary)
            os.replace(temporary, target)
        (output / "manifest.json").write_text(json.dumps({"file": APK, "source": "user-supplied APK" if apk else url,
            "sha256": hash_file(target), "note": "Local fingerprint; not a publisher signature verification."}, indent=2), encoding="utf-8")
        print("Ready WireGuard APK. Installation and Android VPN consent are separate steps.", flush=True)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--component", choices=["all", "gadget", "wireguard"], default="all")
    parser.add_argument("--archives", type=Path, help="Directory containing Gadget .xz archives.")
    parser.add_argument("--apk", type=Path, help="Previously downloaded official WireGuard APK.")
    parser.add_argument("--offline", action="store_true", help="Never access the network; validate and unpack local files.")
    args = parser.parse_args()
    if args.component in {"all", "gadget"}:
        prepare_gadget(args.root.resolve(), args.archives, args.offline)
    if args.component in {"all", "wireguard"}:
        prepare_wireguard(args.root.resolve(), args.apk, args.offline)


if __name__ == "__main__":
    main()
